package com.smartmanager.whatsappshoot.platform

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureResult
import com.smartmanager.whatsappshoot.logic.ConversationIdentity
import com.smartmanager.whatsappshoot.logic.DirectSelectOutcome
import com.smartmanager.whatsappshoot.logic.FailureReason
import com.smartmanager.whatsappshoot.logic.IdentityHasher
import com.smartmanager.whatsappshoot.logic.ScreenClassification
import com.smartmanager.whatsappshoot.logic.ScreenSnapshot
import com.smartmanager.whatsappshoot.logic.ShareTargetSelector
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
    private var pendingDirect: PendingDirect? = null

    private class PendingDirect(val expected: ConversationIdentity?, val hasher: IdentityHasher, val startedAt: Long)

    private val evaluate = Runnable { evaluateNow() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlay = FloatingControl(this) { graph.controller.captureNow() }
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
        graph.controller.onScreen(snapshot)
        updateOverlay(snapshot)
        pendingDirect?.let {
            // A stale request must never act on a share list the user opened later by hand.
            if (System.currentTimeMillis() - it.startedAt > DIRECT_SELECT_TTL_MS) pendingDirect = null else tryDirectSelect(it, pkg)
        }
    }

    private fun buildSnapshot(root: AccessibilityNodeInfo?, expectedPkg: String): ScreenSnapshot {
        if (root == null || root.packageName?.toString() != expectedPkg) return ScreenSnapshot(expectedPkg, windowClassName = lastWindowClass)
        val ids = HashSet<String>()
        var header: String? = null
        val headerIds = profile.conversationHeaderIds
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst()
            visited++
            val full = n.viewIdResourceName
            if (full != null) {
                val short = full.substringAfter(":id/", full)
                ids += short
                if (header == null && short in headerIds) header = n.text?.toString()
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
            if (n !== root) recycleCompat(n)
        }
        queue.forEach(::recycleCompat)
        return ScreenSnapshot(expectedPkg, ids, header, lastWindowClass)
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

    // ---- experimental: select the matching chat in WhatsApp's share list (never presses Send) ----

    fun cancelDirectSelect() {
        pendingDirect = null
    }

    fun beginDirectSelect(expected: ConversationIdentity?, hasher: IdentityHasher) {
        pendingDirect = PendingDirect(expected, hasher, System.currentTimeMillis())
    }

    private fun tryDirectSelect(p: PendingDirect, pkg: String) {
        if (lastWindowClass == null || lastWindowClass !in profile.shareListClasses) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        pendingDirect = null // exactly one attempt per share launch
        val titles = ArrayList<String?>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val n = queue.removeFirst(); visited++
            val short = n.viewIdResourceName?.substringAfter(":id/")
            if (short != null && short in profile.shareRowTitleIds) { titles += n.text?.toString(); nodes += n }
            for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
        }
        val outcome = when (val c = ShareTargetSelector.choose(titles, p.expected, p.hasher)) {
            is ShareTargetSelector.Choice.Single -> if (clickRow(nodes[c.index])) DirectSelectOutcome.SELECTED else DirectSelectOutcome.NOT_FOUND
            ShareTargetSelector.Choice.Ambiguous -> DirectSelectOutcome.AMBIGUOUS
            ShareTargetSelector.Choice.None -> DirectSelectOutcome.NOT_FOUND
        }
        graph.controller.onDirectSelectionResult(outcome)
    }

    private fun clickRow(node: AccessibilityNodeInfo): Boolean {
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
        private const val DIRECT_SELECT_TTL_MS = 14_000L
    }
}
