package com.smartmanager.whatsappshoot.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.smartmanager.whatsappshoot.logic.AutomationController
import com.smartmanager.whatsappshoot.logic.AutomationState
import com.smartmanager.whatsappshoot.logic.CaptureEngine
import com.smartmanager.whatsappshoot.logic.CaptureMethod
import com.smartmanager.whatsappshoot.logic.CaptureResult
import com.smartmanager.whatsappshoot.logic.Cancellable
import com.smartmanager.whatsappshoot.logic.Clock
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.ControllerState
import com.smartmanager.whatsappshoot.logic.ScreenClassification
import com.smartmanager.whatsappshoot.logic.ScreenClassifier
import com.smartmanager.whatsappshoot.logic.ScreenObserver
import com.smartmanager.whatsappshoot.logic.ScreenshotRepository
import com.smartmanager.whatsappshoot.logic.Scheduler
import com.smartmanager.whatsappshoot.logic.MediaAccess
import com.smartmanager.whatsappshoot.logic.PersistentLedger
import com.smartmanager.whatsappshoot.logic.WhatsAppProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors

/** Manual dependency graph (no DI framework: fewer moving parts, no extra dependencies). */
class AppGraph private constructor(app: Application) {
    private val appContext: Context = app
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()

    val profile = WhatsAppProfile()
    val classifier = ScreenClassifier(profile, app.packageName)
    val settings = SettingsStore(app)
    val clock = Clock { System.currentTimeMillis() }
    val repository = ScreenshotRepository(File(app.cacheDir, "screenshots"), clock)
    val notifier = Notifier(app)
    val license = LicenseGate(app, app.packageName)
    private val shareLauncher = ShareLauncher(app) { this }

    @Volatile var lastWhatsAppPackage: String? = null

    private val projectionEngine = ProjectionCaptureEngine(app, repository)

    private val engine = object : CaptureEngine {
        override fun capture(settings: AutomationSettings, callback: (CaptureResult) -> Unit) {
            val svc = WhatsAppAccessibilityService.instance
            val useAccessibility = settings.captureMethod == CaptureMethod.AUTO &&
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && svc != null
            val wrapped: (CaptureResult) -> Unit = { r ->
                if (r is CaptureResult.PermissionRequired) requestProjectionConsent()
                callback(r)
            }
            if (useAccessibility) svc!!.captureViaAccessibility(settings, wrapped) else projectionEngine.capture(settings, wrapped)
        }
    }

    private val observer = ScreenObserver {
        WhatsAppAccessibilityService.instance?.currentWhatsAppScreen()
    }

    private val scheduler = object : Scheduler {
        override fun schedule(delayMs: Long, block: () -> Unit): Cancellable {
            val r = Runnable(block)
            main.postDelayed(r, delayMs)
            return Cancellable { main.removeCallbacks(r) }
        }
    }

    private val latestProvider = MediaStoreLatestProvider(app, repository, clock)
    val ledger = PersistentLedger(PrefsLedgerStorage(app))

    val controller = AutomationController(
        classifier, { settings.current }, engine, repository, observer, shareLauncher, scheduler, clock,
        isLicensed = { license.isActive() },
        latest = latestProvider, ledger = ledger, identitySalt = IdentitySalt.get(app),
    )

    /** Watches the media library only while a session is running (and only matters for "new screenshot only"). */
    private val watcher = NewScreenshotWatcher(app) { controller.onNewScreenshotAvailable() }

    fun mediaAccess(): MediaAccess = latestProvider.access

    private val _ui = MutableStateFlow(controller.view)
    val ui: StateFlow<ControllerState> = _ui

    init {
        controller.onChange = {
            val v = controller.view
            _ui.value = v
            notifier.update(v)
            if (v.state == AutomationState.INACTIVE) watcher.stop() else watcher.start()
            WhatsAppAccessibilityService.instance?.refreshOverlay()
            if (v.state != AutomationState.CAPTURE_PERMISSION_REQUIRED) notifier.cancelPermissionNeeded()
        }
        refreshLicense()
        bg.execute { if (settings.current.autoCleanup) repository.cleanupOlderThan(settings.current.retentionMs) }
    }

    fun refreshLicense() = bg.execute { license.refresh() }

    // ---- screen-capture consent ----

    private fun requestProjectionConsent() {
        notifier.showPermissionNeeded() // reliable path: user taps the notification
        try {
            appContext.startActivity(Intent(appContext, ProjectionPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            // Background start was blocked; the notification above remains.
        }
    }

    fun onProjectionConsent(granted: Boolean) = main.post {
        notifier.cancelPermissionNeeded()
        if (granted) controller.onCapturePermissionGranted(retry = true) else controller.onCapturePermissionDenied()
    }

    // ---- accessibility lifecycle ----

    fun onServiceConnected(svc: WhatsAppAccessibilityService) {
        svc.refreshOverlay()
    }

    fun onServiceDisconnected() = main.post {
        // Without the service nothing can be observed: stop the session so the UI never shows a false "active".
        if (controller.view.state != AutomationState.INACTIVE) controller.stop()
    }

    val accessibilityEnabled: Boolean get() = WhatsAppAccessibilityService.instance != null

    fun captureReady(): Boolean = WhatsAppAccessibilityService.instance != null &&
        settings.current.captureMethod == CaptureMethod.AUTO && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ||
        ProjectionSession.isActive

    fun deleteAllScreenshots() = bg.execute { repository.deleteAll() }

    companion object {
        @Volatile private var instance: AppGraph? = null
        fun init(app: Application) { instance = AppGraph(app) }
        fun get(context: Context): AppGraph = instance ?: synchronized(this) {
            instance ?: AppGraph(context.applicationContext as Application).also { instance = it }
        }
    }
}
