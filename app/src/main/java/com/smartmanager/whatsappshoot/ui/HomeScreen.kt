package com.smartmanager.whatsappshoot.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.license.ActivationStatus
import com.smartmanager.whatsappshoot.logic.ActivityLog
import com.smartmanager.whatsappshoot.logic.AutomationState
import com.smartmanager.whatsappshoot.logic.CaptureMethod
import com.smartmanager.whatsappshoot.logic.ImageSource
import com.smartmanager.whatsappshoot.logic.MediaAccess
import com.smartmanager.whatsappshoot.logic.StoredScreenshot
import com.smartmanager.whatsappshoot.logic.sendsExistingPicture
import com.smartmanager.whatsappshoot.platform.AppGraph
import com.smartmanager.whatsappshoot.platform.ProjectionPermissionActivity
import com.smartmanager.whatsappshoot.platform.ProjectionSession
import com.smartmanager.whatsappshoot.platform.ScreenReports
import com.smartmanager.whatsappshoot.platform.StatusStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val COUNTDOWN_SECONDS = 5

@Composable
fun HomeScreen(graph: AppGraph, openLicense: () -> Unit, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    val v by graph.ui.collectAsStateWithLifecycle()
    val license by graph.license.status.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val c = graph.controller
    val clipboard = LocalClipboardManager.current
    val lastScreen by graph.lastScreen.collectAsStateWithLifecycle()

    // Permission state is polled while the screen is visible: users toggle these in system Settings.
    var accessibilityOn by remember { mutableStateOf(graph.accessibilityEnabled) }
    var captureOn by remember { mutableStateOf(graph.captureReady()) }
    var notificationsOn by remember { mutableStateOf(notificationsAllowed(ctx)) }
    var media by remember { mutableStateOf(graph.mediaAccess()) }
    fun videoGranted() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED else true
    var videoOk by remember { mutableStateOf(videoGranted()) }
    var recent by remember { mutableStateOf(ActivityLog.last(10)) }
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = graph.accessibilityEnabled
            captureOn = graph.captureReady()
            notificationsOn = notificationsAllowed(ctx)
            media = graph.mediaAccess()
            videoOk = videoGranted()
            recent = ActivityLog.last(10)
            delay(1000)
        }
    }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { media = graph.mediaAccess(); videoOk = videoGranted() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOn = it }
    val latestMode = settings.sendsExistingPicture

    var countdown by remember { mutableIntStateOf(0) }
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown -= 1
            if (countdown == 0) c.captureNow()
        }
    }

    val licensed = license is ActivationStatus.Active
    val active = v.state != AutomationState.INACTIVE
    var showLog by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle(stringResource(R.string.app_name), stringResource(R.string.h_subtitle))

        // ---------- Hero: state + the one big control ----------
        Hero(
            active = active, paused = v.paused, message = stringResource(StatusStrings.res(v.message)),
            canStart = licensed && accessibilityOn,
            startHint = when {
                !licensed -> stringResource(R.string.h_hint_license)
                !accessibilityOn -> stringResource(R.string.h_hint_accessibility)
                else -> null
            },
            onStart = { c.start() }, onStop = { c.stop() },
            onPause = { if (v.paused) c.resume() else c.pause() },
        )

        // ---------- Needs attention ----------
        if (v.state == AutomationState.MANUAL_ACTION_REQUIRED) {
            NoticeCard(stringResource(R.string.h_needs_you), stringResource(StatusStrings.res(v.message)), Tone.WARN) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.screenshot != null) Button(onClick = { c.confirmAndShareManually() }) { Text(stringResource(R.string.h_open_sharing)) }
                    OutlinedButton(onClick = { c.dismiss() }) { Text(stringResource(R.string.h_dismiss)) }
                }
            }
        }
        if (v.state == AutomationState.ERROR) {
            NoticeCard(stringResource(R.string.h_error_title), stringResource(StatusStrings.res(v.message)), Tone.BAD) { OutlinedButton(onClick = { c.dismiss() }) { Text(stringResource(R.string.h_dismiss)) } }
        }

        // ---------- Setup checklist ----------
        val licenseTone = describeLicense(license)
        val needsStorage = latestMode
        val allReady = licensed && accessibilityOn && (!needsStorage || (media == MediaAccess.FULL && videoOk)) && (notificationsOn || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        SectionCard(
            stringResource(if (allReady) R.string.h_setup_ready else R.string.h_setup_finish),
            stringResource(if (allReady) R.string.h_setup_ready_sub else R.string.h_setup_todo_sub),
        ) {
            CheckRow(licensed, stringResource(R.string.h_license), licenseTone.first, if (!licensed || licenseTone.second) stringResource(R.string.h_open) else null, openLicense)
            RowDivider()
            CheckRow(
                accessibilityOn, stringResource(R.string.h_acc),
                stringResource(if (accessibilityOn) R.string.h_acc_on else R.string.h_acc_off),
                if (!accessibilityOn) stringResource(R.string.h_turn_on) else null,
            ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (needsStorage) {
                RowDivider()
                CheckRow(
                    media == MediaAccess.FULL, stringResource(R.string.h_photo),
                    stringResource(when (media) { MediaAccess.FULL -> R.string.h_photo_ok; MediaAccess.PARTIAL -> R.string.h_photo_partial; MediaAccess.NONE -> R.string.h_photo_none }),
                    if (media != MediaAccess.FULL) stringResource(R.string.h_allow) else null,
                ) { mediaLauncher.launch(mediaPermissions()) }
                RowDivider()
                CheckRow(
                    videoOk, stringResource(R.string.h_video),
                    stringResource(if (videoOk) R.string.h_video_ok else R.string.h_video_need),
                    if (!videoOk) stringResource(R.string.h_allow) else null,
                ) { mediaLauncher.launch(mediaPermissions()) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                RowDivider()
                CheckRow(
                    notificationsOn, stringResource(R.string.h_notif), stringResource(if (notificationsOn) R.string.h_notif_ok else R.string.h_notif_need),
                    if (!notificationsOn) stringResource(R.string.h_allow) else null,
                ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            if (!latestMode || settings.captureMethod == CaptureMethod.MEDIA_PROJECTION || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val captureReady = captureOn || ProjectionSession.isActive
                RowDivider()
                CheckRow(
                    captureReady, stringResource(R.string.h_capture), stringResource(if (captureReady) R.string.h_capture_ready else R.string.h_capture_need),
                    if (!captureReady) stringResource(R.string.h_grant) else null,
                ) { ctx.startActivity(Intent(ctx, ProjectionPermissionActivity::class.java)) }
            }
        }

        // ---------- How it sends ----------
        SectionCard(stringResource(R.string.h_how_title)) {
            if (latestMode) {
                if (settings.fullyAutomaticSend) {
                    StatusPill(stringResource(R.string.h_auto_pill), Tone.OK)
                    Text(
                        stringResource(if (settings.imageSource == ImageSource.WHATSAPP_GALLERY) R.string.h_auto_text_gallery else R.string.h_auto_text_folder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    StatusPill(stringResource(R.string.h_manual_pill), Tone.WARN)
                    Text(stringResource(R.string.h_manual_text), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = openSettings) { Text(stringResource(R.string.h_manual_open_settings)) }
                }
            } else {
                Text(stringResource(R.string.h_capture_mode_text), style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---------- Quick actions ----------
        if (active) {
            SectionCard(stringResource(R.string.h_quick_title)) {
                FilledTonalButton(
                    onClick = { if (latestMode) c.captureNow() else if (countdown > 0) countdown = 0 else countdown = COUNTDOWN_SECONDS },
                    enabled = !v.paused, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (latestMode) stringResource(R.string.h_send_now) else if (countdown > 0) stringResource(R.string.h_cancel_n, countdown) else stringResource(R.string.h_capture_btn)) }
                if (countdown > 0 && !latestMode) Text(stringResource(R.string.h_capture_countdown, countdown), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { c.resetCurrentChatStatus() }) { Text(stringResource(R.string.h_reset_chat)) }
                    TextButton(onClick = { c.resetAllChatStatus() }) { Text(stringResource(R.string.h_reset_all)) }
                }
            }
        }

        // ---------- Preview ----------
        v.screenshot?.let { shot ->
            SectionCard(stringResource(R.string.h_shot_title), stringResource(R.string.h_shot_sub, shot.widthPx, shot.heightPx, shot.format.name)) {
                Preview(shot)
                Text(
                    stringResource(if (settings.fullyAutomaticSend && latestMode) R.string.h_shot_auto_text else R.string.h_shot_manual_text),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { c.requestAttach() }, enabled = v.state != AutomationState.PREPARING_ATTACHMENT && v.state != AutomationState.CAPTURING_SCREEN) { Text(stringResource(R.string.h_attach)) }
                    OutlinedButton(onClick = { c.deleteScreenshot() }) { Text(stringResource(R.string.h_delete)) }
                    TextButton(onClick = { c.dismiss() }) { Text(stringResource(R.string.h_done)) }
                }
            }
        }

        // ---------- Activity & diagnostics (no message text, names or numbers) ----------
        SectionCard(stringResource(R.string.h_diag_title)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (lastScreen.isNotEmpty()) stringResource(R.string.h_last_screen, lastScreen) else stringResource(R.string.h_none_yet),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showLog = !showLog }) { Text(stringResource(if (showLog) R.string.h_hide_log else R.string.h_show_log)) }
            }
            if (showLog) {
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (recent.isEmpty()) Text(stringResource(R.string.h_no_activity), style = MaterialTheme.typography.bodySmall)
                    recent.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
            Text(stringResource(R.string.h_diag_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val shareTitle = stringResource(R.string.h_share_title)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diagnosticsText(ctx))) }) { Text(stringResource(R.string.h_copy_diag)) }
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, diagnosticsText(ctx)), shareTitle)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.h_share)) }
            }
        }
    }
}

/** The big status card: what the app is doing now and the one control that matters. */
@Composable
private fun Hero(
    active: Boolean, paused: Boolean, message: String, canStart: Boolean, startHint: String?,
    onStart: () -> Unit, onStop: () -> Unit, onPause: () -> Unit,
) {
    val dot = when { !active -> WssColors.Idle; paused -> WssColors.Warn; else -> Color(0xFF5BE49B) }
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.verticalGradient(listOf(WssColors.HeroTop, WssColors.HeroBottom))).padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
                Text(
                    stringResource(if (!active) R.string.h_hero_off else if (paused) R.string.h_hero_paused else R.string.h_hero_on),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = if (active) onStop else onStart, enabled = active || canStart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF053F38), disabledContainerColor = Color.White.copy(alpha = 0.3f), disabledContentColor = Color.White.copy(alpha = 0.7f)),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(if (active) R.string.h_stop else R.string.h_start), fontWeight = FontWeight.SemiBold) }
                if (active) {
                    OutlinedButton(
                        onClick = onPause, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text(stringResource(if (paused) R.string.h_resume else R.string.h_pause)) }
                }
            }
            if (startHint != null && !active) Text(startHint, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Short license description for the checklist, and whether it deserves an "Open" button (ending soon). */
@Composable
private fun describeLicense(s: ActivationStatus?): Pair<String, Boolean> = when (s) {
    null -> stringResource(R.string.h_lic_checking) to false
    is ActivationStatus.Active -> {
        val exp = s.license.expiresAtEpochSeconds
        if (exp == null) stringResource(R.string.h_lic_lifetime) to false else {
            val days = ((exp - System.currentTimeMillis() / 1000) / 86_400).toInt()
            stringResource(R.string.h_lic_until, formatDate(exp), days) to (days <= 7)
        }
    }
    is ActivationStatus.Expired -> stringResource(R.string.h_lic_expired, formatDate(s.license.expiresAtEpochSeconds ?: 0L)) to true
    is ActivationStatus.Revoked -> stringResource(R.string.h_lic_revoked) to true
    else -> stringResource(R.string.h_lic_not) to true
}

/** Date in the app's language (not the phone's), e.g. 17 Oct 2026 / ١٧ أكتوبر ٢٠٢٦ / 17 oct. 2026. */
@Composable
fun formatDate(epochSec: Long): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(java.util.Date(epochSec * 1000))
}

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

@Composable
private fun Preview(shot: StoredScreenshot) {
    var bitmap by remember(shot.file.path) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(shot.file.path) {
        bitmap = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(shot.file.path, bounds)
            var sample = 1
            while (bounds.outHeight / sample > 1400) sample *= 2
            BitmapFactory.decodeFile(shot.file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = stringResource(R.string.h_preview_cd),
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(MaterialTheme.shapes.medium),
            contentScale = ContentScale.Fit, // never cropped
        )
    }
}

private fun diagnosticsText(ctx: android.content.Context): String =
    ScreenReports.text(ctx) + "\n--- activity log (newest last) ---\n" + ActivityLog.text() + "\n"

private fun notificationsAllowed(ctx: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
