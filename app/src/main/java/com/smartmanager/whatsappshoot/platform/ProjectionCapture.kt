package com.smartmanager.whatsappshoot.platform

import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureEngine
import com.smartmanager.whatsappshoot.logic.CaptureResult
import com.smartmanager.whatsappshoot.logic.FailureReason
import com.smartmanager.whatsappshoot.logic.ScreenshotRepository

/** Holds the one live MediaProjection (if the user granted consent). Process-local, never persisted. */
object ProjectionSession {
    @Volatile var projection: MediaProjection? = null
        private set
    val isActive: Boolean get() = projection != null

    /** Android 14+ allows one VirtualDisplay per consent, so each capture needs fresh consent there. */
    val singleUse: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    internal fun set(p: MediaProjection?) {
        projection = p
    }

    fun stop() {
        val p = projection
        projection = null
        try { p?.stop() } catch (e: Exception) { /* already stopped */ }
    }
}

/** Screen size including system bars, in the current rotation. */
data class DisplaySize(val width: Int, val height: Int, val dpi: Int) {
    companion object {
        fun current(context: Context): DisplaySize {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dpi = context.resources.displayMetrics.densityDpi
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val b = wm.maximumWindowMetrics.bounds
                DisplaySize(b.width(), b.height(), dpi)
            } else {
                val m = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(m)
                DisplaySize(m.widthPixels, m.heightPixels, dpi)
            }
        }
    }
}

/**
 * Single-frame MediaProjection capture: a VirtualDisplay exists only for the instant needed to receive one
 * frame and is released immediately - there is no continuous recording. Protected (FLAG_SECURE) content is
 * rendered black by the system; we make no attempt to circumvent that.
 */
class ProjectionCaptureEngine(
    private val context: Context,
    private val repo: ScreenshotRepository,
) : CaptureEngine {
    private val main = Handler(Looper.getMainLooper())

    override fun capture(settings: AutomationSettings, callback: (CaptureResult) -> Unit) {
        val projection = ProjectionSession.projection
        if (projection == null) {
            main.post { callback(CaptureResult.PermissionRequired) }
            return
        }
        val thread = HandlerThread("wss-capture").apply { start() }
        val handler = Handler(thread.looper)
        val size = DisplaySize.current(context)
        var vd: VirtualDisplay? = null
        var reader: ImageReader? = null
        var done = false

        fun complete(r: CaptureResult) {
            if (done) return
            done = true
            try { vd?.release() } catch (e: Exception) { }
            try { reader?.close() } catch (e: Exception) { }
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            if (ProjectionSession.singleUse) ProjectionSession.stop()
            main.post { callback(r) }
        }

        try {
            val rd = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
            reader = rd
            rd.setOnImageAvailableListener({ r ->
                val image = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * size.width
                    val padded = Bitmap.createBitmap(size.width + rowPadding / plane.pixelStride, size.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val bmp = if (rowPadding == 0) padded else Bitmap.createBitmap(padded, 0, 0, size.width, size.height).also { padded.recycle() }
                    complete(ScreenshotWriter.write(bmp, settings.format, repo))
                } catch (e: OutOfMemoryError) {
                    complete(CaptureResult.Failed(FailureReason.MEMORY))
                } catch (e: Exception) {
                    complete(CaptureResult.Failed(FailureReason.UNKNOWN))
                } finally {
                    image.close()
                }
            }, handler)
            vd = projection.createVirtualDisplay(
                "wss-capture", size.width, size.height, size.dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, rd.surface, null, handler,
            )
            handler.postDelayed({ complete(CaptureResult.Failed(FailureReason.UNKNOWN)) }, CAPTURE_TIMEOUT_MS)
        } catch (e: SecurityException) {
            // Consent used up or revoked (e.g. Android 14 single-use token): ask again, never loop silently.
            ProjectionSession.stop()
            done = true
            thread.quitSafely()
            main.post { callback(CaptureResult.PermissionRequired) }
        } catch (e: Exception) {
            complete(CaptureResult.Failed(FailureReason.UNKNOWN))
        }
    }

    companion object {
        const val CAPTURE_TIMEOUT_MS = 4_000L
    }
}

/** Foreground service required by Android while a MediaProjection exists. Shows a visible notification. */
class ProjectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            AppGraph.get(this).onProjectionConsent(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val n: Notification = AppGraph.get(this).notifier.projectionNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(Notifier.ID_PROJECTION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(Notifier.ID_PROJECTION, n)
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(code, data)
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // Consent ended (user stopped sharing, or system revoked it).
                    ProjectionSession.set(null)
                    stopForegroundCompat()
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
            ProjectionSession.set(projection)
            AppGraph.get(this).onProjectionConsent(true)
        } catch (e: Exception) {
            ProjectionSession.set(null)
            AppGraph.get(this).onProjectionConsent(false)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProjectionSession.stop()
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        const val EXTRA_RESULT_CODE = "rc"
        const val EXTRA_DATA = "data"
    }
}

/** Transparent activity that shows the system consent dialog. Asked only on explicit need, never in a loop. */
class ProjectionPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Whole screen only: we are not offered "single app" sharing, so the full display is captured.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                val svc = Intent(this, ProjectionService::class.java)
                    .putExtra(ProjectionService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ProjectionService.EXTRA_DATA, data)
                startForegroundService(svc)
            } else {
                AppGraph.get(this).onProjectionConsent(false)
            }
        }
        finish()
    }

    companion object {
        private const val REQUEST = 4711
    }
}
