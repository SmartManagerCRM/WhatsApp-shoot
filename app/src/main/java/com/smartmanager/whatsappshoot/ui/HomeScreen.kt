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
        ScreenTitle(stringResource(R.string.app_name), "Sends your newest photo to the WhatsApp chat you open")

        // ---------- Hero: state + the one big control ----------
        Hero(
            active = active, paused = v.paused, message = stringResource(StatusStrings.res(v.message)),
            canStart = licensed && accessibilityOn,
            startHint = when {
                !licensed -> "Activate your license to start"
                !accessibilityOn -> "Turn on the accessibility service to start"
                else -> null
            },
            onStart = { c.start() }, onStop = { c.stop() },
            onPause = { if (v.paused) c.resume() else c.pause() },
        )

        // ---------- Needs attention ----------
        if (v.state == AutomationState.MANUAL_ACTION_REQUIRED) {
            NoticeCard("Needs you", stringResource(StatusStrings.res(v.message)), Tone.WARN) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.screenshot != null) Button(onClick = { c.confirmAndShareManually() }) { Text("Open WhatsApp sharing") }
                    OutlinedButton(onClick = { c.dismiss() }) { Text("Dismiss") }
                }
            }
        }
        if (v.state == AutomationState.ERROR) {
            NoticeCard("Something went wrong", stringResource(StatusStrings.res(v.message)), Tone.BAD) { OutlinedButton(onClick = { c.dismiss() }) { Text("Dismiss") } }
        }

        // ---------- Setup checklist ----------
        val licenseTone = describeLicense(license)
        val needsStorage = latestMode
        val allReady = licensed && accessibilityOn && (!needsStorage || (media == MediaAccess.FULL && videoOk)) && (notificationsOn || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        SectionCard(if (allReady) "Everything is ready" else "Finish the setup", if (allReady) "All permissions are in place." else "A few things are still needed.") {
            CheckRow(licensed, "License", licenseTone.first, if (!licensed || licenseTone.second) "Open" else null, openLicense)
            RowDivider()
            CheckRow(
                accessibilityOn, "Accessibility service",
                if (accessibilityOn) "On: notices when a WhatsApp chat is open" else "Off: turn on Screenshot Assistant under Accessibility > Installed apps",
                if (!accessibilityOn) "Turn on" else null,
            ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (needsStorage) {
                RowDivider()
                CheckRow(
                    media == MediaAccess.FULL, "Photo access",
                    when (media) { MediaAccess.FULL -> "Allowed"; MediaAccess.PARTIAL -> "Only some photos shared: choose \"Allow all\""; MediaAccess.NONE -> "Needed to find your newest picture" },
                    if (media != MediaAccess.FULL) "Allow" else null,
                ) { mediaLauncher.launch(mediaPermissions()) }
                RowDivider()
                CheckRow(
                    videoOk, "Video access (dates only)",
                    if (videoOk) "Allowed: the app can make sure it sends a photo" else "Lets the app make sure the newest item is a photo, not a video",
                    if (!videoOk) "Allow" else null,
                ) { mediaLauncher.launch(mediaPermissions()) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                RowDivider()
                CheckRow(
                    notificationsOn, "Notifications", if (notificationsOn) "Allowed" else "Needed for the \"automation active\" indicator",
                    if (!notificationsOn) "Allow" else null,
                ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
            if (!latestMode || settings.captureMethod == CaptureMethod.MEDIA_PROJECTION || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                val captureReady = captureOn || ProjectionSession.isActive
                RowDivider()
                CheckRow(
                    captureReady, "Screen capture", if (captureReady) "Ready" else "Permission needed when capturing",
                    if (!captureReady) "Grant" else null,
                ) { ctx.startActivity(Intent(ctx, ProjectionPermissionActivity::class.java)) }
            }
        }

        // ---------- How it sends ----------
        SectionCard("How it sends") {
            if (latestMode) {
                if (settings.fullyAutomaticSend) {
                    StatusPill("Fully automatic", Tone.OK)
                    Text(
                        if (settings.imageSource == ImageSource.WHATSAPP_GALLERY) "Open a chat in WhatsApp and your newest photo (the first one in WhatsApp's gallery) is sent to it, with no tap needed. Videos and other files are never sent."
                        else "Open a chat in WhatsApp and the newest screenshot is sent to it, with no tap needed. If the newest item on your phone is a video, nothing is sent.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    StatusPill("You press Send", Tone.WARN)
                    Text("The app opens WhatsApp's share screen with your latest screenshot; you choose the chat and press Send.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = openSettings) { Text("Turn on fully automatic sending") }
                }
            } else {
                Text("Takes a new screenshot of the screen, then lets you attach it. Switch to \"latest screenshot\" in Settings to send an existing one.", style = MaterialTheme.typography.bodyMedium)
            }
        }

        // ---------- Quick actions ----------
        if (active) {
            SectionCard("Quick actions") {
                FilledTonalButton(
                    onClick = { if (latestMode) c.captureNow() else if (countdown > 0) countdown = 0 else countdown = COUNTDOWN_SECONDS },
                    enabled = !v.paused, modifier = Modifier.fillMaxWidth(),
                ) { Text(if (latestMode) "Send newest picture now" else if (countdown > 0) "Cancel ($countdown)" else "Capture screenshot") }
                if (countdown > 0 && !latestMode) Text("Switch to the screen you want. Capturing in $countdown s.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { c.resetCurrentChatStatus() }) { Text("Reset this chat") }
                    TextButton(onClick = { c.resetAllChatStatus() }) { Text("Reset all chats") }
                }
            }
        }

        // ---------- Preview ----------
        v.screenshot?.let { shot ->
            SectionCard("Screenshot ready", "${shot.widthPx} x ${shot.heightPx} px, ${shot.format.name}") {
                Preview(shot)
                Text(
                    if (settings.fullyAutomaticSend && latestMode) "Automatic sending is on: the app sends it inside the open chat. If anything does not match, it stops and nothing is sent."
                    else "Attaching opens WhatsApp's share screen. You choose the chat and press Send yourself.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { c.requestAttach() }, enabled = v.state != AutomationState.PREPARING_ATTACHMENT && v.state != AutomationState.CAPTURING_SCREEN) { Text("Attach to WhatsApp") }
                    OutlinedButton(onClick = { c.deleteScreenshot() }) { Text("Delete") }
                    TextButton(onClick = { c.dismiss() }) { Text("Done") }
                }
            }
        }

        // ---------- Activity & diagnostics (no message text, names or numbers) ----------
        SectionCard("Activity & diagnostics") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (lastScreen.isNotEmpty()) "Last WhatsApp screen: $lastScreen" else "Nothing seen from WhatsApp yet",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
            }
            if (showLog) {
                Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (recent.isEmpty()) Text("No activity yet.", style = MaterialTheme.typography.bodySmall)
                    recent.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                }
            }
            Text(
                "If something does not work: reproduce it (open a chat, wait for the app to act), then copy the diagnostics and send them to the seller. " +
                    "They contain technical steps and screen identifiers only, never message text, names or numbers.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diagnosticsText(ctx))) }) { Text("Copy diagnostics") }
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, diagnosticsText(ctx)), "Share diagnostics")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Share") }
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
                    if (!active) "Automation is off" else if (paused) "Paused" else "Automation is on",
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
                )
            }
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.92f))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = if (active) onStop else onStart, enabled = active || canStart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF053F38), disabledContainerColor = Color.White.copy(alpha = 0.3f), disabledContentColor = Color.White.copy(alpha = 0.7f)),
                    modifier = Modifier.weight(1f),
                ) { Text(if (active) "Stop" else "Start automation", fontWeight = FontWeight.SemiBold) }
                if (active) {
                    OutlinedButton(
                        onClick = onPause, border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text(if (paused) "Resume" else "Pause") }
                }
            }
            if (startHint != null && !active) Text(startHint, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** Short license description for the checklist, and whether it deserves an "Open" button (ending soon). */
private fun describeLicense(s: ActivationStatus?): Pair<String, Boolean> = when (s) {
    null -> "Checking..." to false
    is ActivationStatus.Active -> {
        val exp = s.license.expiresAtEpochSeconds
        if (exp == null) "Active: lifetime" to false else {
            val days = ((exp - System.currentTimeMillis() / 1000) / 86_400).toInt()
            "Active until ${formatDate(exp)} ($days day${if (days == 1) "" else "s"} left)" to (days <= 7)
        }
    }
    is ActivationStatus.Expired -> "Expired on ${formatDate(s.license.expiresAtEpochSeconds ?: 0L)}: enter a renewal code" to true
    is ActivationStatus.Revoked -> "Stopped by the seller: contact them" to true
    else -> "Not activated" to true
}

fun formatDate(epochSec: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochSec * 1000))

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
            contentDescription = "Screenshot preview",
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
