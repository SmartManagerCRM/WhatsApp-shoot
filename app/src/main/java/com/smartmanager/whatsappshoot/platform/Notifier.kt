package com.smartmanager.whatsappshoot.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.logic.AutomationState
import com.smartmanager.whatsappshoot.logic.ControllerState
import com.smartmanager.whatsappshoot.ui.MainActivity

/** Visible indication that automation is running, plus the "tap to attach" action. All text is local. */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ACTION, context.getString(R.string.channel_action), NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun canPost() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channel)
        else @Suppress("DEPRECATION") Notification.Builder(context)

    private fun activity(code: Int, cls: Class<*>) =
        PendingIntent.getActivity(context, code, Intent(context, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun broadcast(action: String) = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, ActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(title: Int, pi: PendingIntent) =
        Notification.Action.Builder(null as android.graphics.drawable.Icon?, context.getString(title), pi).build()

    /** Ongoing status line while a session is active; removed when stopped. */
    fun update(v: ControllerState) {
        if (v.state == AutomationState.INACTIVE) {
            nm.cancel(ID_STATUS); nm.cancel(ID_READY); return
        }
        if (!canPost()) return
        val b = builder(CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(StatusStrings.res(v.message)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(activity(1, MainActivity::class.java))
            .addAction(if (v.paused) action(R.string.action_resume, broadcast(ActionReceiver.ACTION_RESUME)) else action(R.string.action_pause, broadcast(ActionReceiver.ACTION_PAUSE)))
            .addAction(action(R.string.action_stop, broadcast(ActionReceiver.ACTION_STOP)))
        try { nm.notify(ID_STATUS, b.build()) } catch (e: SecurityException) { }

        if (v.state == AutomationState.SCREENSHOT_READY && v.screenshot != null) {
            val ready = builder(CH_ACTION)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(context.getString(R.string.notif_ready_title))
                .setContentText(context.getString(R.string.notif_ready_text))
                .setAutoCancel(true)
                .setContentIntent(activity(1, MainActivity::class.java))
                .addAction(action(R.string.action_attach, activity(2, AttachTrampolineActivity::class.java)))
            try { nm.notify(ID_READY, ready.build()) } catch (e: SecurityException) { }
        } else if (v.state != AutomationState.SCREENSHOT_READY) {
            nm.cancel(ID_READY)
        }
    }

    /** Shown when automation needs the user to grant screen-capture consent: the reliable way to start that dialog. */
    fun showPermissionNeeded() {
        if (!canPost()) return
        val n = builder(CH_ACTION)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.notif_permission_title))
            .setContentText(context.getString(R.string.notif_permission_text))
            .setAutoCancel(true)
            .setContentIntent(activity(3, ProjectionPermissionActivity::class.java))
            .build()
        try { nm.notify(ID_PERMISSION, n) } catch (e: SecurityException) { }
    }

    fun cancelPermissionNeeded() = nm.cancel(ID_PERMISSION)

    /** Android did not let us open WhatsApp sharing from the background: one tap does it. */
    fun showShareFallback(shareIntent: Intent) {
        if (!canPost()) return
        val pi = PendingIntent.getActivity(context, 5, Intent(shareIntent), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = builder(CH_ACTION)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(context.getString(R.string.notif_share_title))
            .setContentText(context.getString(R.string.notif_share_text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { nm.notify(ID_SHARE, n) } catch (e: SecurityException) { }
    }

    fun projectionNotification(): Notification = builder(CH_STATUS)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(context.getString(R.string.notif_projection_text))
        .setOngoing(true)
        .setContentIntent(activity(1, MainActivity::class.java))
        .build()

    companion object {
        const val CH_STATUS = "status"
        const val CH_ACTION = "action"
        const val ID_STATUS = 1
        const val ID_READY = 2
        const val ID_PERMISSION = 3
        const val ID_PROJECTION = 4
        const val ID_SHARE = 5
    }
}

/** Handles Pause / Resume / Stop taps on the status notification. Not exported. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = AppGraph.get(context).controller
        when (intent.action) {
            ACTION_PAUSE -> c.pause()
            ACTION_RESUME -> c.resume()
            ACTION_STOP -> c.stop()
        }
        WhatsAppAccessibilityService.instance?.refreshOverlay()
    }

    companion object {
        const val ACTION_PAUSE = "com.smartmanager.whatsappshoot.PAUSE"
        const val ACTION_RESUME = "com.smartmanager.whatsappshoot.RESUME"
        const val ACTION_STOP = "com.smartmanager.whatsappshoot.STOP"
    }
}

/**
 * Transparent activity started from the notification action. Because WhatsApp is still on screen underneath,
 * the destination can be re-checked before the share screen opens.
 */
class AttachTrampolineActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val c = AppGraph.get(this).controller
        c.requestAttach()
        if (c.view.state == AutomationState.MANUAL_ACTION_REQUIRED) {
            // Needs the user's confirmation: bring up the app with the explanation and the manual share button.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}

object StatusStrings {
    fun res(m: com.smartmanager.whatsappshoot.logic.StatusMessage): Int = when (m) {
        com.smartmanager.whatsappshoot.logic.StatusMessage.STOPPED -> R.string.status_stopped
        com.smartmanager.whatsappshoot.logic.StatusMessage.PAUSED -> R.string.status_paused
        com.smartmanager.whatsappshoot.logic.StatusMessage.WAITING_FOR_WHATSAPP -> R.string.status_waiting
        com.smartmanager.whatsappshoot.logic.StatusMessage.WHATSAPP_DETECTED -> R.string.status_whatsapp_detected
        com.smartmanager.whatsappshoot.logic.StatusMessage.CONVERSATION_DETECTED -> R.string.status_conversation_detected
        com.smartmanager.whatsappshoot.logic.StatusMessage.CONVERSATION_NOT_RECOGNISED -> R.string.status_conversation_not_recognised
        com.smartmanager.whatsappshoot.logic.StatusMessage.CAPTURE_PERMISSION_REQUIRED -> R.string.status_capture_permission_required
        com.smartmanager.whatsappshoot.logic.StatusMessage.CAPTURE_PERMISSION_DENIED -> R.string.status_capture_permission_denied
        com.smartmanager.whatsappshoot.logic.StatusMessage.PREPARING_SCREENSHOT -> R.string.status_preparing_screenshot
        com.smartmanager.whatsappshoot.logic.StatusMessage.SCREENSHOT_CAPTURED -> R.string.status_screenshot_captured
        com.smartmanager.whatsappshoot.logic.StatusMessage.CHECKING_CONVERSATION -> R.string.status_checking_conversation
        com.smartmanager.whatsappshoot.logic.StatusMessage.PREPARING_ATTACHMENT -> R.string.status_preparing_attachment
        com.smartmanager.whatsappshoot.logic.StatusMessage.ATTACHMENT_READY -> R.string.status_attachment_ready
        com.smartmanager.whatsappshoot.logic.StatusMessage.MANUAL_CONFIRMATION_REQUIRED -> R.string.status_manual_confirmation
        com.smartmanager.whatsappshoot.logic.StatusMessage.DESTINATION_CHANGED -> R.string.status_destination_changed
        com.smartmanager.whatsappshoot.logic.StatusMessage.PROTECTED_SCREEN -> R.string.status_protected_screen
        com.smartmanager.whatsappshoot.logic.StatusMessage.CAPTURE_FAILED -> R.string.status_capture_failed
        com.smartmanager.whatsappshoot.logic.StatusMessage.STORAGE_FAILED -> R.string.status_storage_failed
        com.smartmanager.whatsappshoot.logic.StatusMessage.ATTACHMENT_FAILED -> R.string.status_attachment_failed
        com.smartmanager.whatsappshoot.logic.StatusMessage.WHATSAPP_NOT_INSTALLED -> R.string.status_whatsapp_not_installed
        com.smartmanager.whatsappshoot.logic.StatusMessage.LICENSE_REQUIRED -> R.string.status_license_required
        com.smartmanager.whatsappshoot.logic.StatusMessage.LOOKING_FOR_SCREENSHOT -> R.string.status_looking
        com.smartmanager.whatsappshoot.logic.StatusMessage.LATEST_READY -> R.string.status_latest_ready
        com.smartmanager.whatsappshoot.logic.StatusMessage.NO_SCREENSHOT -> R.string.status_no_screenshot
        com.smartmanager.whatsappshoot.logic.StatusMessage.MEDIA_PERMISSION_REQUIRED -> R.string.status_media_permission
        com.smartmanager.whatsappshoot.logic.StatusMessage.MEDIA_PERMISSION_PARTIAL -> R.string.status_media_partial
        com.smartmanager.whatsappshoot.logic.StatusMessage.SCREENSHOT_UNREADABLE -> R.string.status_unreadable
        com.smartmanager.whatsappshoot.logic.StatusMessage.ALREADY_HANDLED_CHAT -> R.string.status_already_chat
        com.smartmanager.whatsappshoot.logic.StatusMessage.SAME_IMAGE_HANDLED -> R.string.status_same_image
        com.smartmanager.whatsappshoot.logic.StatusMessage.NOT_NEWER -> R.string.status_not_newer
        com.smartmanager.whatsappshoot.logic.StatusMessage.IDENTITY_UNRELIABLE -> R.string.status_identity_unreliable
        com.smartmanager.whatsappshoot.logic.StatusMessage.NEWER_SCREENSHOT_UNREADABLE -> R.string.status_newer_unreadable
        com.smartmanager.whatsappshoot.logic.StatusMessage.SEND_CONFIRMED -> R.string.status_send_confirmed
        com.smartmanager.whatsappshoot.logic.StatusMessage.SEND_UNCONFIRMED -> R.string.status_send_unconfirmed
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_AMBIGUOUS -> R.string.status_auto_ambiguous
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_NOT_FOUND -> R.string.status_auto_not_found
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_RECIPIENT_MISMATCH -> R.string.status_auto_mismatch
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_RECIPIENT_UNVERIFIED -> R.string.status_auto_unverified
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_UNAVAILABLE -> R.string.status_auto_unavailable
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_TIMEOUT -> R.string.status_auto_timeout
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_NO_GALLERY -> R.string.status_auto_no_gallery
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_NO_ALBUM -> R.string.status_auto_no_album
        com.smartmanager.whatsappshoot.logic.StatusMessage.AUTO_SEND_NO_IMAGE -> R.string.status_auto_no_image
        com.smartmanager.whatsappshoot.logic.StatusMessage.CHAT_STATUS_RESET -> R.string.status_chat_reset
    }
}
