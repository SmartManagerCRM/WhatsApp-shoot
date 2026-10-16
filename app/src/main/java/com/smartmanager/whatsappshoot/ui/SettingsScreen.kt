package com.smartmanager.whatsappshoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureMethod
import com.smartmanager.whatsappshoot.logic.CaptureMode
import com.smartmanager.whatsappshoot.logic.ImageFormat
import com.smartmanager.whatsappshoot.logic.ImageSource
import com.smartmanager.whatsappshoot.logic.SendBehavior
import com.smartmanager.whatsappshoot.platform.AppGraph

@Composable
fun SettingsScreen(graph: AppGraph) {
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val upd = graph.settings::update
    var confirmAuto by remember { mutableStateOf(false) }
    var folders by remember(s.screenshotFolders) { mutableStateOf(s.screenshotFolders.joinToString(", ")) }

    if (confirmAuto) {
        AlertDialog(
            onDismissRequest = { confirmAuto = false },
            title = { Text("Let the app press Send?") },
            text = {
                Text(
                    "With this on, the app will press WhatsApp's Send button itself, but only when the open chat was verified and WhatsApp's own screen shows the same recipient. " +
                        "If anything does not match it stops and nothing is sent. A wrong guess could still send a screenshot to the wrong chat, because chat names are not unique " +
                        "and WhatsApp screens can change. The result is reported as confirmed only when the chat shows a new outgoing image. You can stop at any time with the Stop button. " +
                        "This has not been validated on every phone and WhatsApp version.",
                )
            },
            confirmButton = { Button(onClick = { upd { it.copy(fullyAutomaticSend = true) }; confirmAuto = false }) { Text("I understand, turn on") } },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        Section("Image source")
        ChipRow(
            listOf("Latest screenshot (no new capture)" to ImageSource.LATEST_SCREENSHOT, "Take a new screenshot" to ImageSource.CAPTURE_NEW),
            selected = s.imageSource,
        ) { m -> upd { it.copy(imageSource = m) } }
        if (s.imageSource == ImageSource.LATEST_SCREENSHOT) {
            OutlinedTextField(
                value = folders, onValueChange = { folders = it; upd { st -> st.copy(screenshotFolders = it.split(',').map { n -> n.trim() }.filter { n -> n.isNotEmpty() }.ifEmpty { listOf("Screenshots") }) } },
                label = { Text("Screenshots folder name(s), comma separated") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Text("Only images directly inside a folder with one of these names are ever used.", style = MaterialTheme.typography.bodySmall)

            Section("Automatic sending behavior")
            ChipRow(
                listOf("Every chat open" to SendBehavior.EVERY_CHAT_OPEN, "Once per chat" to SendBehavior.ONCE_PER_CHAT, "New screenshot only" to SendBehavior.NEW_SCREENSHOT_ONLY),
                selected = s.sendBehavior,
            ) { b -> upd { it.copy(sendBehavior = b) } }
            Text(
                when (s.sendBehavior) {
                    SendBehavior.EVERY_CHAT_OPEN -> "Prepares the latest screenshot whenever you open a chat."
                    SendBehavior.ONCE_PER_CHAT -> "Prepares it once per identifiable chat until you reset that chat's status."
                    SendBehavior.NEW_SCREENSHOT_ONLY -> "Prepares it only when the latest screenshot has not been handled for this chat (also reacts to a screenshot taken while the chat is open)."
                } + " If a chat cannot be identified reliably, you are asked to confirm instead. Handled chats are remembered as one-way hashes on this phone, never as names.",
                style = MaterialTheme.typography.bodySmall,
            )
            Section("Fully automatic sending")
            SwitchRow("Let the app press Send (off by default)", s.fullyAutomaticSend) { b -> if (b) confirmAuto = true else upd { it.copy(fullyAutomaticSend = false) } }
            Text("Off: the app opens WhatsApp's share screen with the image and you press Send.", style = MaterialTheme.typography.bodySmall)
        }

        Section("Capture mode")
        ChipRow(
            listOf("Automatic" to CaptureMode.AUTOMATIC, "Delayed" to CaptureMode.DELAYED, "Manual" to CaptureMode.MANUAL),
            selected = s.mode,
        ) { m -> upd { it.copy(mode = m) } }
        Text(
            when (s.mode) {
                CaptureMode.AUTOMATIC -> "Captures shortly after a conversation is detected."
                CaptureMode.DELAYED -> "Captures after the delay below once a conversation is detected."
                CaptureMode.MANUAL -> "Never captures by itself. Use the Capture button or the floating control."
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (s.mode == CaptureMode.DELAYED) {
            Text("Delay: ${s.delayMs / 1000.0} s")
            Slider(
                value = s.delayMs.toFloat(),
                onValueChange = { v -> upd { it.copy(delayMs = (v / 500f).toInt() * 500L) } },
                valueRange = AutomationSettings.MIN_DELAY_MS.toFloat()..AutomationSettings.MAX_DELAY_MS.toFloat(),
            )
        }
        SwitchRow("Automatic capture enabled", s.automaticCaptureEnabled) { b -> upd { it.copy(automaticCaptureEnabled = b) } }
        SwitchRow("Floating capture button in WhatsApp", s.floatingControlEnabled) { b -> upd { it.copy(floatingControlEnabled = b) } }

        Section("Screenshot format")
        ChipRow(listOf("PNG (lossless, larger)" to ImageFormat.PNG, "JPEG (smaller)" to ImageFormat.JPEG), selected = s.format) { f -> upd { it.copy(format = f) } }

        Section("Capture method")
        ChipRow(
            listOf("Automatic (recommended)" to CaptureMethod.AUTO, "Screen-capture permission" to CaptureMethod.MEDIA_PROJECTION),
            selected = s.captureMethod,
        ) { m -> upd { it.copy(captureMethod = m) } }
        Text(
            "Automatic uses the accessibility screenshot on Android 11+ (no repeated permission dialogs) and falls back to the screen-capture permission. " +
                "On Android 14+ the screen-capture permission must be granted again for every capture.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Cleanup")
        SwitchRow("Delete screenshot when the workflow ends", s.deleteWhenWorkflowEnds) { b -> upd { it.copy(deleteWhenWorkflowEnds = b) } }
        SwitchRow("Automatically delete old screenshots", s.autoCleanup) { b -> upd { it.copy(autoCleanup = b) } }
        if (s.autoCleanup) {
            ChipRow(
                listOf("15 min" to 15L * 60_000, "1 hour" to 3_600_000L, "24 hours" to 86_400_000L),
                selected = s.retentionMs,
            ) { r -> upd { it.copy(retentionMs = r) } }
        }
        OutlinedButton(onClick = { graph.deleteAllScreenshots() }) { Text("Delete all stored screenshots now") }

        Section("Experimental")
        SwitchRow("Try to pre-select the chat in WhatsApp's share list", s.directSelectExperimental) { b -> upd { it.copy(directSelectExperimental = b) } }
        Text(
            "Only acts when the conversation was verified and exactly one row matches. It never presses Send. " +
                "Depends on WhatsApp's screen layout and has not been validated on every WhatsApp version.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChipRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}
