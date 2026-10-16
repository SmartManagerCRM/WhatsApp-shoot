package com.smartmanager.whatsappshoot.platform

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
            lastWindowClass = if (pkg in profile.packages) event.className?.toString() else null
        }
        if (pkg != lastPackage && pkg !in graph.profile.ignoredPackages && pkg != packageName) lastPackage = pkg
        // Coalesce bursts of events into one evaluation (also the first line of duplicate prevention).
        pendingPackage = pkg
        main.removeCallbacks(evaluate)
        main.postDelayed(evaluate, EVENT_DEBOUNCE_MS)
    }

    @Volatile private var pendingPackage: String? = null

    private fun evaluateNow() {
        val pkg = pendingPackage ?: return
        val snapshot = if (pkg in profile.packages) {
            graph.lastWhatsAppPackage = pkg
            buildSnapshot(rootInActiveWindow, pkg)
        } else {
            ScreenSnapshot(pkg) // other apps: package name only
        }
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
        val nodes: ArrayList<String> = ArrayList(),
        var editable: Boolean = false,
        var title: String? = null,
        var titleKey: Long = Long.MAX_VALUE,
    )

    private fun scan(root: AccessibilityNodeInfo): Scan {
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
            if (!keep) recycleCompat(n)
        }
        queue.forEach(::recycleCompat)
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

    private fun driveShareFlow(pkg: String) {
        val c = graph.controller
        if (!c.shareFlowActive) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val sc = scan(root)
        try {
            val cls = lastWindowClass
            val stage = when {
                cls != null && cls in profile.shareListClasses -> ShareStage.SHARE_LIST
                cls != null && cls in profile.sharePreviewClasses -> ShareStage.SHARE_PREVIEW
                graph.classifier.classify(ScreenSnapshot(pkg, sc.ids, sc.header, cls, hasEditableInput = sc.editable, topTitle = sc.title)).kind == ScreenKind.CONVERSATION -> ShareStage.CONVERSATION
                else -> ShareStage.OTHER
            }
            val view = ShareScreenView(
                stage = stage, rowTitles = sc.rowTitles, previewRecipient = sc.recipient, sendButtonAvailable = sc.sendNode != null,
                conversationHeader = sc.header ?: sc.title, outgoingMediaCount = if (stage == ShareStage.CONVERSATION) sc.outgoing else null,
            )
            when (val a = c.shareStep(view)) {
                is ShareAction.ClickRow -> {
                    if (clickAncestor(sc.rowNodes.getOrNull(a.index))) c.shareClickPerformed() else c.onShareOutcome(ShareOutcome.ABORT_NOT_FOUND)
                }
                // The only place WhatsApp's Send button is ever tapped: opt-in setting, verified chat, recipient matched on WhatsApp's own screen.
                is ShareAction.ClickSend -> if (!clickAncestor(sc.sendNode)) c.onShareOutcome(ShareOutcome.ABORT_SEND_UNAVAILABLE)
                else -> Unit
            }
        } finally {
            releaseScan(sc, root)
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
    }
}
