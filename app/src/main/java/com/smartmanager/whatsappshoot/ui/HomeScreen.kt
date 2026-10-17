package com.smartmanager.whatsappshoot.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.smartmanager.whatsappshoot.platform.ScreenReports
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.R
import com.smartmanager.whatsappshoot.license.ActivationStatus
import com.smartmanager.whatsappshoot.logic.ActivityLog
import com.smartmanager.whatsappshoot.logic.AutomationState
import com.smartmanager.whatsappshoot.logic.ImageSource
import com.smartmanager.whatsappshoot.logic.MediaAccess
import com.smartmanager.whatsappshoot.logic.StoredScreenshot
import com.smartmanager.whatsappshoot.platform.AppGraph
import com.smartmanager.whatsappshoot.platform.ProjectionPermissionActivity
import com.smartmanager.whatsappshoot.platform.ProjectionSession
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
    var recent by remember { mutableStateOf(ActivityLog.last(8)) }
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = graph.accessibilityEnabled
            captureOn = graph.captureReady()
            notificationsOn = notificationsAllowed(ctx)
            media = graph.mediaAccess()
            recent = ActivityLog.last(8)
            delay(1000)
        }
    }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { media = graph.mediaAccess() }
    val latestMode = settings.imageSource == ImageSource.LATEST_SCREENSHOT
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsOn = it }

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

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)

        // --- Status ---
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (active) (if (v.paused) "Paused" else "Automation active") else "Automation stopped", style = MaterialTheme.typography.titleMedium)
                Text(stringResource(StatusStrings.res(v.message)))
            }
        }

        // --- How sending works right now (the automatic mode is a separate, explicit switch) ---
        if (latestMode) {
            if (settings.fullyAutomaticSend) {
                Text("Sending mode: fully automatic (ON). The app chooses the chat in WhatsApp and presses Send itself when everything matches.", style = MaterialTheme.typography.bodySmall)
            } else {
                InfoCard(
                    "Sending mode: you press Send",
                    "The app opens WhatsApp's share screen with your latest screenshot; you choose the chat and press Send. " +
                        "To let the app choose the chat and press Send by itself, turn on \"Fully automatic sending\" in Settings.",
                ) { OutlinedButton(onClick = openSettings) { Text("Open Settings") } }
            }
        }

        // --- Setup requirements ---
        if (!licensed) {
            InfoCard("License not activated", "Automation needs an activated license.") { Button(onClick = openLicense) { Text("Open license") } }
        }
        if (!accessibilityOn) {
            InfoCard(
                "Accessibility service is off",
                "Turn on \"Screenshot Assistant\" under Accessibility > Installed apps. It lets the app notice when a WhatsApp conversation is open. It never reads message text.",
            ) { Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open accessibility settings") } }
        }
        if (latestMode && media != MediaAccess.FULL) {
            InfoCard(
                if (media == MediaAccess.PARTIAL) "Only some photos are shared" else "Photo access is needed",
                "The app reads only the newest image in your Screenshots folder (nothing is uploaded). " +
                    if (media == MediaAccess.PARTIAL) "Choose \"Allow all\" so the newest screenshot can be found reliably." else "Allow access to photos so it can find your latest screenshot.",
            ) {
                OutlinedButton(onClick = {
                    mediaLauncher.launch(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    )
                }) { Text("Allow photo access") }
            }
        }
        if (!notificationsOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InfoCard("Notifications are off", "Without notifications the app cannot show its \"automation active\" indicator or the Attach action.") {
                OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
            }
        }

        // --- Controls ---
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.start() }, enabled = !active && licensed && accessibilityOn) { Text("Start Automation") }
            OutlinedButton(onClick = { c.stop() }, enabled = active) { Text("Stop Automation") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (v.paused) OutlinedButton(onClick = { c.resume() }) { Text("Resume") }
            else OutlinedButton(onClick = { c.pause() }, enabled = active) { Text("Pause") }
            Button(
                onClick = { if (latestMode) c.captureNow() else if (countdown > 0) countdown = 0 else countdown = COUNTDOWN_SECONDS },
                enabled = active && !v.paused,
            ) { Text(if (latestMode) "Attach latest screenshot" else if (countdown > 0) "Cancel ($countdown)" else "Capture Screenshot") }
        }
        if (active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { c.resetCurrentChatStatus() }) { Text("Reset this chat's status") }
                OutlinedButton(onClick = { c.resetAllChatStatus() }) { Text("Reset all chats") }
            }
        }
        if (countdown > 0 && !latestMode) Text("Switch to the screen you want. Capturing in $countdown s.", style = MaterialTheme.typography.bodySmall)

        // --- Permission rows ---
        StatusRow("Photo access", when (media) { MediaAccess.FULL -> "Granted"; MediaAccess.PARTIAL -> "Limited (selected photos)"; MediaAccess.NONE -> "Not granted" })
        StatusRow("Accessibility permission", if (accessibilityOn) "Granted" else "Not granted")
        StatusRow(
            "Screen capture",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && accessibilityOn && settings.captureMethod == com.smartmanager.whatsappshoot.logic.CaptureMethod.AUTO)
                "Ready (accessibility screenshot, no extra permission)"
            else if (ProjectionSession.isActive) "Granted for this session" else "Permission needed when capturing",
        )
        if (settings.captureMethod == com.smartmanager.whatsappshoot.logic.CaptureMethod.MEDIA_PROJECTION || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, ProjectionPermissionActivity::class.java)) }, enabled = !captureOn) { Text("Grant screen-capture permission") }
        }
        StatusRow("License", if (licensed) "Activated (lifetime)" else "Not activated")
        if (lastScreen.isNotEmpty()) Text("Last WhatsApp screen seen: $lastScreen", style = MaterialTheme.typography.bodySmall)

        // --- Recent activity + diagnostics: helps find problems without a PC. Contains no message text, names or numbers. ---
        if (recent.isNotEmpty()) {
            Text("Recent activity", style = MaterialTheme.typography.titleSmall)
            recent.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
        Text(
            "If something does not work: reproduce it (open a chat, wait for the app to act), come back here and copy the diagnostics. " +
                "It contains technical steps and screen identifiers only, never message text, names or numbers.",
            style = MaterialTheme.typography.bodySmall,
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

        // --- Manual confirmation ---
        if (v.state == AutomationState.MANUAL_ACTION_REQUIRED) {
            InfoCard("Confirm the destination", stringResource(StatusStrings.res(v.message))) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (v.screenshot != null) Button(onClick = { c.confirmAndShareManually() }) { Text("Open WhatsApp sharing") }
                    OutlinedButton(onClick = { c.dismiss() }) { Text("Cancel") }
                }
            }
        }
        if (v.state == AutomationState.ERROR) {
            OutlinedButton(onClick = { c.dismiss() }) { Text("Dismiss") }
        }

        // --- Preview ---
        v.screenshot?.let { shot ->
            Text("Screenshot preview", style = MaterialTheme.typography.titleMedium)
            Preview(shot)
            Text("${shot.widthPx} x ${shot.heightPx} px, ${shot.format.name}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (settings.fullyAutomaticSend && latestMode) "Automatic sending is ON: the app tries to choose the chat and press Send. If anything does not match, it stops and nothing is sent."
                else "Attaching opens WhatsApp's share screen. You choose the chat and press Send yourself.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { c.requestAttach() }, enabled = v.state != AutomationState.PREPARING_ATTACHMENT && v.state != AutomationState.CAPTURING_SCREEN) { Text("Attach to WhatsApp") }
                OutlinedButton(onClick = { c.deleteScreenshot() }) { Text("Delete") }
            }
            OutlinedButton(onClick = { c.dismiss() }) { Text("Done") }
        }
    }
}

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
            contentDescription = "Captured screenshot preview",
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            contentScale = ContentScale.Fit, // never cropped
        )
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            action()
        }
    }
}

@Composable
fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

private fun diagnosticsText(ctx: android.content.Context): String =
    ScreenReports.text(ctx) + "\n--- activity log (newest last) ---\n" + ActivityLog.text() + "\n"

private fun notificationsAllowed(ctx: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
