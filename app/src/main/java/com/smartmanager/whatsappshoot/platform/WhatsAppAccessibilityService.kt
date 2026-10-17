package com.smartmanager.whatsappshoot.platform

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartmanager.whatsappshoot.logic.ActivityLog
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureResult
import com.smartmanager.whatsappshoot.logic.FailureReason
import com.smartmanager.whatsappshoot.logic.ScreenClassification
import com.smartmanager.whatsappshoot.logic.ScreenKind
import com.smartmanager.whatsappshoot.logic.ScreenSnapshot
import com.smartmanager.whatsappshoot.logic.ShareAction
import com.smartmanager.whatsappshoot.logic.ShareOutcome
import com.smartmanager.whatsappshoot.logic.ShareScreenView
import com.smartmanager.whatsappshoot.logic.ShareStage
import java.util.concurrent.Executors

/**
 * Observes which app/screen is in front and (optionally) takes the screenshot.
 *
 * Privacy contract:
 *  - For apps other than the configured WhatsApp packages only the package name is read; their windows are never inspected.
 *  - For WhatsApp only view *ids* and the conversation header text are read, the latter solely to compute a salted hash.
 *  - Message bodies, contact lists and phone numbers are never read, stored or logged.
 */
class WhatsAppAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val graph get() = AppGraph.get(this)
    private val profile get() = graph.profile

    private var lastPackage: String? = null
    private var lastWindowClass: String? = null
    private lateinit var overlay: FloatingControl

    private val evaluate = Runnable { evaluateNow() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = FloatingControl(this, onTap = { graph.controller.captureNow() }, onStop = { graph.controller.stop() })
        graph.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg in profile.packages) {
                // WhatsApp often reports a generic framework class (e.g. android.widget.FrameLayout) for window events; that says nothing
                // about which screen is showing, so only a real activity class replaces the remembered one.
                val c = event.className?.toString()
                if (c != null && !c.startsWith("android.") && !c.startsWith("androidx.")) lastWindowClass = c
            } else if (pkg !in profile.ignoredPackages && pkg != packageName) {
                lastWindowClass = null
            }
        }
        if (pkg != lastPackage && pkg !in graph.profile.ignoredPackages && pkg != packageName) lastPackage = pkg
        // Coalesce bursts of events into one evaluation (also the first line of duplicate prevention).
        pendingPackage = pkg
        main.removeCallbacks(evaluate)
        main.postDelayed(evaluate, EVENT_DEBOUNCE_MS)
    }

    @Volatile private var pendingPackage: String? = null

    // While a share flow is running, look at WhatsApp every few hundred ms instead of waiting for the next accessibility event:
    // a screen that finished loading after the last event would otherwise never be noticed.
    private var emptyRetries = 0
    private var tickerRunning = false
    private val shareTick = object : Runnable {
        override fun run() {
            val c = graph.controller
            if (!c.shareFlowActive) { tickerRunning = false; return }
            val pkg = rootInActiveWindow?.packageName?.toString()
            if (pkg != null && pkg in profile.packages) driveShareFlow(pkg)
            main.postDelayed(this, SHARE_TICK_MS)
        }
    }

    fun ensureShareTicker() {
        if (graph.controller.shareFlowActive && !tickerRunning) {
            tickerRunning = true
            ActivityLog.add("share flow: watching WhatsApp screens")
            main.postDelayed(shareTick, SHARE_TICK_MS)
        }
    }

    private fun evaluateNow() {
        ensureShareTicker()
        val pkg = pendingPackage ?: return
        val snapshot = if (pkg in profile.packages) {
            graph.lastWhatsAppPackage = pkg
            buildSnapshot(rootInActiveWindow, pkg)
        } else {
            ScreenSnapshot(pkg) // other apps: package name only
        }
        if (pkg in profile.packages && snapshot.viewIds.isEmpty() && !snapshot.hasEditableInput && emptyRetries < MAX_EMPTY_RETRIES) {
            // WhatsApp's window exists but its views are not built yet: look again shortly instead of reacting to an empty screen.
            emptyRetries++
            main.postDelayed(evaluate, EMPTY_RETRY_MS)
            return
        }
        emptyRetries = 0
        if (pkg in profile.packages) {
            val kind = graph.classifier.classify(snapshot).kind.name
            graph.lastScreen.value = "$kind (${lastWindowClass ?: "?"})"
            ScreenReports.record(this, pkg, lastWindowClass, kind, snapshot.viewIds, lastNodes)
        }
        graph.controller.onScreen(snapshot)
        updateOverlay(snapshot)
        if (pkg in profile.packages) driveShareFlow(pkg)
    }

    /** Everything read from one pass over WhatsApp's accessibility tree: ids and a few specific labels only. */
    private class Scan(
        val ids: HashSet<String> = HashSet(),
        var header: String? = null,
        val rowTitles: ArrayList<String?> = ArrayList(),
        val rowNodes: ArrayList<AccessibilityNodeInfo> = ArrayList(),
        var recipient: String? = null,
        var sendNode: AccessibilityNodeInfo? = null,
        var outgoing: Int = 0,
        val texts: ArrayList<Pair<String, AccessibilityNodeInfo>> = ArrayList(),
        var scrollNode: AccessibilityNodeInfo? = null,
        var scrollArea: Long = 0,
        val clicks: ArrayList<ClickInfo> = ArrayList(),
        val nodes: ArrayList<String> = ArrayList(),
        var editable: Boolean = false,
        var title: String? = null,
        var titleKey: Long = Long.MAX_VALUE,
    )

    private class ClickInfo(val node: AccessibilityNodeInfo, val desc: String?, val cls: String?, val rect: android.graphics.Rect, val id: String? = null)

    /** @param retain keep every visited node un-recycled so the caller may click them afterwards (share flow only). */
    private fun scan(root: AccessibilityNodeInfo, retain: Boolean = false): Scan {
        val r = Scan()
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            val short = n.viewIdResourceName?.substringAfter(":id/")
            var keep = n === root
            val cls = n.className?.toString()
            val hasText = !n.text.isNullOrEmpty()
            val rect = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            if (n.isEditable) r.editable = true
            if (retain && n.isVisibleToUser) {
                if (hasText && cls != null && cls.endsWith("TextView") && r.texts.size < MAX_TEXTS) r.texts += n.text.toString() to n
                if (n.isScrollable && rect.width().toLong() * rect.height() > r.scrollArea) { r.scrollNode = n; r.scrollArea = rect.width().toLong() * rect.height() }
                if (n.isClickable && n.isEnabled && r.clicks.size < MAX_CLICKS) r.clicks += ClickInfo(n, n.contentDescription?.toString(), cls, rect, short)
            }
            if (n !== root && cls != null && cls.endsWith("TextView") && hasText && rect.top >= 0 && rect.top < screenH * 0.2) {
                val key = rect.top * 10_000L + rect.left
                if (key < r.titleKey) { r.titleKey = key; r.title = n.text.toString() }
            }
            if (r.nodes.size < MAX_REPORT_NODES && (short != null || n.isEditable || (hasText && rect.top < screenH * 0.25))) {
                r.nodes += "${short ?: "-"}|${cls?.substringAfterLast('.') ?: "?"}|text=${if (hasText) "Y" else "N"}|edit=${if (n.isEditable) "Y" else "N"}|click=${if (n.isClickable) "Y" else "N"}|top=${rect.top * 100 / screenH}%"
            }
            if (short != null) {
                r.ids += short
                if (r.header == null && short in profile.conversationHeaderIds) r.header = n.text?.toString()
                if (short in profile.shareRowTitleIds) { r.rowTitles += n.text?.toString(); r.rowNodes += n; keep = true }
                if (r.recipient == null && short in profile.sharePreviewRecipientIds) r.recipient = n.text?.toString()
                if (r.sendNode == null && short in profile.sendButtonIds && n.isEnabled) { r.sendNode = n; keep = true }
                if (short in profile.outgoingMediaIds) r.outgoing++
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            if (!keep && !retain) recycleCompat(n)
        }
        if (!retain) queue.forEach(::recycleCompat)
        return r
    }

    private fun buildSnapshot(root: AccessibilityNodeInfo?, expectedPkg: String): ScreenSnapshot {
        if (root == null || root.packageName?.toString() != expectedPkg) return ScreenSnapshot(expectedPkg, windowClassName = lastWindowClass)
        val sc = scan(root)
        releaseScan(sc, root)
        lastNodes = sc.nodes
        return ScreenSnapshot(
            expectedPkg, sc.ids, sc.header, lastWindowClass,
            outgoingMediaCount = if (sc.outgoing > 0 || sc.header != null || sc.editable) sc.outgoing else null,
            hasEditableInput = sc.editable, topTitle = sc.title,
        )
    }

    @Volatile private var lastNodes: List<String> = emptyList()

    private fun releaseScan(sc: Scan, root: AccessibilityNodeInfo) {
        sc.rowNodes.forEach { if (it !== root) recycleCompat(it) }
        sc.sendNode?.let { if (it !== root) recycleCompat(it) }
    }

    @Suppress("DEPRECATION")
    private fun recycleCompat(n: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) n.recycle()
    }

    // ---- ScreenObserver: what is WhatsApp showing right now (even if our trampoline is on top)? ----

    fun currentWhatsAppScreen(): ScreenClassification? {
        val ws = windows ?: return null
        for (w in ws) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString()
            if (pkg != null && pkg in profile.packages) {
                val snap = buildSnapshot(root, pkg)
                return graph.classifier.classify(snap)
            }
        }
        return null
    }

    // ---- screenshot via the accessibility API (Android 11+, no per-capture consent dialog) ----

    fun captureViaAccessibility(settings: AutomationSettings, callback: (CaptureResult) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(CaptureResult.Failed(FailureReason.UNSUPPORTED)); return
        }
        // Hide our own overlay so it is not part of the picture, then wait for a frame to be drawn.
        overlay.hideForCapture()
        main.postDelayed({
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val r = try {
                        val hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val soft = hw?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (soft == null) CaptureResult.Failed(FailureReason.MEMORY)
                        else ScreenshotWriter.write(soft, settings.format, graph.repository)
                    } catch (e: OutOfMemoryError) {
                        CaptureResult.Failed(FailureReason.MEMORY)
                    } finally {
                        hb.close()
                    }
                    main.post { overlay.showAfterCapture(); callback(r) }
                }

                override fun onFailure(errorCode: Int) {
                    val r = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> CaptureResult.Protected
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> CaptureResult.PermissionRequired
                        else -> CaptureResult.Failed(FailureReason.UNKNOWN)
                    }
                    main.post { overlay.showAfterCapture(); callback(r) }
                }
            })
        }, HIDE_OVERLAY_SETTLE_MS)
    }

    // ---- floating control ----

    fun refreshOverlay() = updateOverlay(null)

    private fun updateOverlay(snapshot: ScreenSnapshot?) {
        if (!::overlay.isInitialized) return
        val v = graph.controller.view
        val active = v.state != com.smartmanager.whatsappshoot.logic.AutomationState.INACTIVE && !v.paused
        val whatsappInFront = snapshot?.let { it.packageName in profile.packages } ?: overlay.isShowing
        overlay.setVisible(active && graph.settings.current.floatingControlEnabled && whatsappInFront)
    }

    // ---- share flow (select chat / optional Send), driven by the tested ShareFlowEngine ----

    private var lastDriveTrace = ""

    private fun driveShareFlow(pkg: String) {
        val c = graph.controller
        if (!c.shareFlowActive) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val sc = scan(root, retain = true)
        if (c.attachFlowActive) { driveAttachFlow(c, sc); return }
        val cls = lastWindowClass
        val send = findSendButton(sc)
        val kind = graph.classifier.classify(ScreenSnapshot(pkg, sc.ids, sc.header, cls, hasEditableInput = sc.editable, topTitle = sc.title)).kind
        // Stage detection does not rely on WhatsApp's internal names (they are not known to be stable). The verified chat's own title at
        // the top identifies the chat itself; a message box plus a send-like button without that title is the "send" preview; a screen of
        // text rows without a message box is the recipient list.
        val inPicker = cls != null && (cls in profile.shareListClasses || cls in profile.sharePreviewClasses)
        val stage = when {
            // Back in the verified chat (its own title at the top plus a message box).
            sc.editable && c.expectedTitleMatches(sc.header ?: sc.title) -> ShareStage.CONVERSATION
            kind == ScreenKind.CONVERSATION && c.expectedTitleMatches(sc.header ?: sc.title) -> ShareStage.CONVERSATION
            // WhatsApp shows its send bar (recipient label + Send button) inside the same picker screen once a chat is selected.
            inPicker && send != null -> ShareStage.SHARE_PREVIEW
            inPicker -> ShareStage.SHARE_LIST
            sc.editable && send != null -> ShareStage.SHARE_PREVIEW
            !sc.editable && sc.texts.size >= MIN_LIST_ROWS -> ShareStage.SHARE_LIST
            else -> ShareStage.OTHER
        }
        val useIdRows = sc.texts.isEmpty() && sc.rowTitles.isNotEmpty()
        val rowTitles: List<String?> = if (useIdRows) sc.rowTitles else sc.texts.map { it.first }
        val rowNodes: List<AccessibilityNodeInfo> = if (useIdRows) sc.rowNodes else sc.texts.map { it.second }
        val view = ShareScreenView(
            stage = stage, rowTitles = rowTitles, previewRecipient = sc.recipient, sendButtonAvailable = send != null,
            conversationHeader = sc.header ?: sc.title, outgoingMediaCount = if (stage == ShareStage.CONVERSATION) sc.outgoing else null,
            visibleTexts = sc.texts.map { it.first }, canScroll = sc.scrollNode != null,
        )
        val trace = "screen: stage=$stage class=${cls ?: "?"} editable=${sc.editable} send=${send?.second ?: "none"}"
        if (trace != lastDriveTrace) { ActivityLog.add(trace); lastDriveTrace = trace }
        when (val a = c.shareStep(view)) {
            is ShareAction.ClickRow -> {
                if (clickAncestor(rowNodes.getOrNull(a.index))) c.shareClickPerformed() else c.onShareOutcome(ShareOutcome.ABORT_NOT_FOUND)
            }
            ShareAction.ScrollList -> {
                val ok = sc.scrollNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
                ActivityLog.add("scrolling the recipient list ($ok)")
            }
            // The only place WhatsApp's Send button is ever tapped: opt-in setting, verified chat, recipient matched on WhatsApp's own screen.
            is ShareAction.ClickSend -> {
                ActivityLog.add("tapping Send (found by ${send?.second})")
                if (!clickAncestor(send?.first)) c.onShareOutcome(ShareOutcome.ABORT_SEND_UNAVAILABLE)
            }
            else -> Unit
        }
    }

    /**
     * Finds WhatsApp's Send button without relying on a view id: (1) known ids, (2) an accessibility label that means "send" in common
     * languages, (3) the small clickable button in the bottom-right corner of the screen. Only ever used after the recipient matched.
     */
    private fun findSendButton(sc: Scan, strict: Boolean = false): Pair<AccessibilityNodeInfo, String>? {
        sc.sendNode?.let { return it to "id" }
        sc.clicks.firstOrNull { ci -> ci.desc?.lowercase()?.let { d -> SEND_WORDS.any { w -> d.contains(w) } } == true }?.let { return it.node to "label" }
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val corner = sc.clicks.filter { ci ->
            if (strict) {
                // Picture thumbnails also sit in the lower right of a gallery grid: only a small round button may count.
                ci.rect.centerX() > w * 0.7 && ci.rect.centerY() > h * 0.6 && ci.rect.width() < w * 0.2 &&
                    (ci.cls?.let { it.endsWith("ImageButton") || it.endsWith("FloatingActionButton") } == true)
            } else {
                ci.rect.centerX() > w * 0.6 && ci.rect.centerY() > h * 0.55 && ci.rect.width() < w * 0.35 &&
                    (ci.cls?.let { it.endsWith("ImageButton") || it.endsWith("ImageView") || it.endsWith("FloatingActionButton") || it.endsWith("View") || it.endsWith("FrameLayout") } == true)
            }
        }.maxByOrNull { it.rect.centerY() * 2L + it.rect.centerX() }
        return corner?.let { it.node to "corner" }
    }

    // ---- in-chat attach flow: + -> Gallery -> Screenshots album -> newest picture -> Send ----

    private fun lc(s: String?) = s?.trim()?.lowercase().orEmpty()

    private fun driveAttachFlow(c: com.smartmanager.whatsappshoot.logic.AutomationController, sc: Scan) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val chatVisible = sc.editable && c.expectedTitleMatches(sc.header ?: sc.title)

        val attach = sc.clicks.firstOrNull { it.id in ATTACH_IDS }?.node
            ?: sc.clicks.firstOrNull { ci -> lc(ci.desc).let { d -> d.isNotEmpty() && ATTACH_WORDS.any { d.contains(it) } } }?.node
        val gallery = sc.clicks.firstOrNull { it.id in GALLERY_IDS }?.node
            ?: sc.texts.firstOrNull { t -> lc(t.first).let { it in GALLERY_WORDS } }?.second
            ?: sc.clicks.firstOrNull { ci -> lc(ci.desc).let { it in GALLERY_WORDS } }?.node
        val names = c.albumNames().map { lc(it) } + (if (c.albumNames().any { lc(it) == "screenshots" }) SCREENSHOT_WORDS else emptyList())
        val album = if (chatVisible) null else sc.texts.firstOrNull { t -> lc(t.first).let { x -> x.isNotEmpty() && names.any { n -> x == n || (x.contains(n) && x.length <= n.length + 14) } } }?.second
        val switcher = if (chatVisible) null else
            sc.texts.firstOrNull { t -> lc(t.first) in ALBUM_SWITCH_WORDS }?.second ?: sc.clicks.firstOrNull { ci -> lc(ci.desc) in ALBUM_SWITCH_WORDS }?.node
        val thumb = if (chatVisible) null else sc.clicks.filter { ci ->
            ci.rect.width() in (w * 0.15).toInt()..(w * 0.6).toInt() && ci.rect.height() in (ci.rect.width() * 0.6).toInt()..(ci.rect.width() * 1.7).toInt() &&
                ci.rect.top >= h * 0.12 && lc(ci.desc).let { d -> CAMERA_WORDS.none { d.contains(it) } } &&
                ci.cls?.let { it.endsWith("ImageView") || it.endsWith("View") || it.endsWith("FrameLayout") || it.endsWith("ImageButton") } == true
        }.minWithOrNull(compareBy<ClickInfo>({ it.rect.top }, { it.rect.left }))?.node
        val send = findSendButton(sc, strict = true)

        val view = com.smartmanager.whatsappshoot.logic.AttachScreenView(
            chatVisible = chatVisible, attachButton = attach != null, galleryEntry = gallery != null,
            albumLabel = album != null, albumsSwitcher = switcher != null, thumbnail = thumb != null,
            sendButton = send != null, outgoingMediaCount = if (chatVisible) sc.outgoing else null,
        )
        val trace = "screen: class=${lastWindowClass ?: "?"} chat=$chatVisible editable=${sc.editable} send=${send?.second ?: "none"}"
        if (trace != lastDriveTrace) { ActivityLog.add(trace); lastDriveTrace = trace }
        val a = c.attachStep(view) ?: return
        val ok: Boolean? = when (a) {
            com.smartmanager.whatsappshoot.logic.AttachAction.ClickAttach -> clickAncestor(attach)
            com.smartmanager.whatsappshoot.logic.AttachAction.ClickGallery -> clickAncestor(gallery)
            com.smartmanager.whatsappshoot.logic.AttachAction.ClickAlbum -> clickAncestor(album)
            com.smartmanager.whatsappshoot.logic.AttachAction.ClickAlbumsSwitcher -> clickAncestor(switcher)
            com.smartmanager.whatsappshoot.logic.AttachAction.ClickThumbnail -> clickAncestor(thumb)
            // The only place WhatsApp's Send button is tapped: opt-in setting, verified chat, picture chosen from the screenshots album.
            com.smartmanager.whatsappshoot.logic.AttachAction.ClickSend -> clickAncestor(send?.first)
            else -> null
        }
        if (ok != null) {
            ActivityLog.add("clicked ${a::class.simpleName}: $ok")
            if (!ok) c.attachClickFailed(a)
        }
    }

    private fun clickAncestor(node: AccessibilityNodeInfo?): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent; depth++
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) overlay.remove()
        if (instance === this) {
            instance = null
            // Permission revoked: the session cannot observe anything any more, so stop honestly.
            AppGraph.get(this).onServiceDisconnected()
        }
        worker.shutdown()
    }

    companion object {
        @Volatile var instance: WhatsAppAccessibilityService? = null
            private set
        private const val EVENT_DEBOUNCE_MS = 200L
        private const val HIDE_OVERLAY_SETTLE_MS = 150L
        private const val MAX_NODES = 600
        private const val MAX_REPORT_NODES = 160
        private const val MAX_TEXTS = 300
        private const val MAX_CLICKS = 200
        private const val MIN_LIST_ROWS = 3
        private const val MAX_EMPTY_RETRIES = 6
        private const val EMPTY_RETRY_MS = 350L
        private const val SHARE_TICK_MS = 400L
        private val ATTACH_IDS = setOf("input_attach_button", "attach_button", "attach")
        private val GALLERY_IDS = setOf("pickfiletype_gallery_holder")
        private val ATTACH_WORDS = listOf("attach", "إرفاق", "ارفاق", "adjuntar", "anexar", "joindre", "anhängen", "allega", "ekle", "lampirkan", "прикрепить", "附件", "添付", "첨부")
        private val GALLERY_WORDS = setOf("gallery", "photos", "photos & videos", "photos and videos", "معرض الصور", "المعرض", "معرض", "galería", "galeria", "galerie", "galleria", "galerij", "galeri", "галерея", "图库", "相册", "ギャラリー", "갤러리", "fotos")
        private val SCREENSHOT_WORDS = listOf("screenshots", "لقطات الشاشة", "لقطات", "capturas de pantalla", "capturas de tela", "captures d'écran", "bildschirmfotos", "screenshot", "istantanee", "ekran görüntüleri", "снимки экрана", "截图", "スクリーンショット", "스크린샷")
        private val ALBUM_SWITCH_WORDS = setOf("albums", "الألبومات", "álbumes", "álbuns", "albums ", "alben", "album")
        private val CAMERA_WORDS = listOf("camera", "الكاميرا", "cámara", "câmera", "caméra", "kamera")
        private val SEND_WORDS = listOf("send", "إرسال", "ارسال", "enviar", "envoyer", "senden", "invia", "gönder", "kirim", "отправить", "发送", "送信", "보내기")
    }
}
