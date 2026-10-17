package com.smartmanager.whatsappshoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureMethod
import com.smartmanager.whatsappshoot.logic.CaptureMode
import com.smartmanager.whatsappshoot.logic.ImageFormat
import com.smartmanager.whatsappshoot.logic.ImageSource
import com.smartmanager.whatsappshoot.logic.SendBehavior
import com.smartmanager.whatsappshoot.logic.sendsExistingPicture
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
                    "With this on, the app works inside the chat you open: it attaches your newest screenshot from WhatsApp's own gallery and presses Send, " +
                        "only when the open chat was verified. If anything does not match it stops and nothing is sent. Because it drives WhatsApp's screens, " +
                        "a WhatsApp update can change how it behaves. A \"sent\" result is reported only when the chat shows a new outgoing image. " +
                        "You can stop at any time with the Stop button.",
                )
            },
            confirmButton = { Button(onClick = { upd { it.copy(fullyAutomaticSend = true) }; confirmAuto = false }) { Text("I understand, turn on") } },
            dismissButton = { TextButton(onClick = { confirmAuto = false }) { Text("Cancel") } },
        )
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("Settings", "Choose what is sent and when")

        SectionCard("What to send", "Where the picture comes from") {
            ChoiceChips(
                listOf("Newest photo" to ImageSource.WHATSAPP_GALLERY, "Latest screenshot from a folder" to ImageSource.LATEST_SCREENSHOT, "Take a new screenshot" to ImageSource.CAPTURE_NEW),
                selected = s.imageSource,
            ) { m -> upd { it.copy(imageSource = m) } }
            if (s.imageSource == ImageSource.WHATSAPP_GALLERY) {
                Text(
                    "Sends the newest photo on your phone: the first picture WhatsApp's own gallery shows. No folder is needed. Videos and other files are never sent; " +
                        "if the app cannot be sure the picture is a photo, nothing is sent.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.imageSource == ImageSource.LATEST_SCREENSHOT) {
                OutlinedTextField(
                    value = folders,
                    onValueChange = { folders = it; upd { st -> st.copy(screenshotFolders = it.split(',').map { n -> n.trim() }.filter { n -> n.isNotEmpty() }.ifEmpty { listOf("Screenshots") }) } },
                    label = { Text("Screenshots folder (name or full path)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Text(
                    "Example: /storage/emulated/0/Pictures/Screenshots. Only images directly inside that folder are used; nothing new is captured. Its last name is the album the app looks for in WhatsApp's gallery.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (s.sendsExistingPicture) {
            SectionCard("When to send", "What happens when you open a chat") {
                ChoiceChips(
                    listOf("Every chat open" to SendBehavior.EVERY_CHAT_OPEN, "Once per chat" to SendBehavior.ONCE_PER_CHAT, "New screenshot only" to SendBehavior.NEW_SCREENSHOT_ONLY),
                    selected = s.sendBehavior,
                ) { b -> upd { it.copy(sendBehavior = b) } }
                Text(
                    when (s.sendBehavior) {
                        SendBehavior.EVERY_CHAT_OPEN -> "Sends your newest picture whenever you open a chat."
                        SendBehavior.ONCE_PER_CHAT -> "Sends once per chat until you reset that chat's status on the Home screen."
                        SendBehavior.NEW_SCREENSHOT_ONLY -> "Sends only when the newest picture has not been sent to this chat yet (also reacts to a screenshot taken while the chat is open)."
                    } + " Handled chats are remembered as one-way hashes on this phone, never as names.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard("Fully automatic sending", "No tap needed: the app presses Send for you") {
                SwitchSetting(
                    "Send automatically", "Off: the app opens WhatsApp's share screen and you press Send.",
                    s.fullyAutomaticSend,
                ) { b -> if (b) confirmAuto = true else upd { it.copy(fullyAutomaticSend = false) } }
                if (s.fullyAutomaticSend && s.imageSource == ImageSource.LATEST_SCREENSHOT) {
                    RowDivider()
                    SwitchSetting(
                        "Use the first picture of WhatsApp's gallery",
                        "Fastest. Used only when the phone confirms your newest item is the screenshot (a photo, not a video); otherwise the Screenshots album is opened.",
                        s.landingGalleryFirst,
                    ) { b -> upd { it.copy(landingGalleryFirst = b) } }
                }
            }
        }

        SectionCard("Screenshot capture", "Only used for \"Take a new screenshot\"") {
            ChoiceChips(listOf("Automatic" to CaptureMode.AUTOMATIC, "Delayed" to CaptureMode.DELAYED, "Manual" to CaptureMode.MANUAL), selected = s.mode) { m -> upd { it.copy(mode = m) } }
            Text(
                when (s.mode) {
                    CaptureMode.AUTOMATIC -> "Captures shortly after a conversation is detected."
                    CaptureMode.DELAYED -> "Captures after the delay below once a conversation is detected."
                    CaptureMode.MANUAL -> "Never captures by itself. Use the Capture button or the floating control."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (s.mode == CaptureMode.DELAYED) {
                Text("Delay: ${s.delayMs / 1000.0} s")
                Slider(
                    value = s.delayMs.toFloat(),
                    onValueChange = { v -> upd { it.copy(delayMs = (v / 500f).toInt() * 500L) } },
                    valueRange = AutomationSettings.MIN_DELAY_MS.toFloat()..AutomationSettings.MAX_DELAY_MS.toFloat(),
                )
            }
            SwitchSetting("Automatic capture", null, s.automaticCaptureEnabled) { b -> upd { it.copy(automaticCaptureEnabled = b) } }
            SwitchSetting("Floating capture button in WhatsApp", null, s.floatingControlEnabled) { b -> upd { it.copy(floatingControlEnabled = b) } }
            RowDivider()
            Text("Format", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("PNG (lossless)" to ImageFormat.PNG, "JPEG (smaller)" to ImageFormat.JPEG), selected = s.format) { f -> upd { it.copy(format = f) } }
            Text("Method", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(
                listOf("Automatic" to CaptureMethod.AUTO, "Screen-capture permission" to CaptureMethod.MEDIA_PROJECTION),
                selected = s.captureMethod,
            ) { m -> upd { it.copy(captureMethod = m) } }
            Text(
                "Automatic uses the accessibility screenshot on Android 11+ (no repeated permission dialogs) and falls back to the screen-capture permission. " +
                    "On Android 14+ that permission must be granted again for every capture.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Storage & cleanup", "Temporary copies made for sharing") {
            SwitchSetting("Delete the copy when the workflow ends", null, s.deleteWhenWorkflowEnds) { b -> upd { it.copy(deleteWhenWorkflowEnds = b) } }
            SwitchSetting("Automatically delete old copies", null, s.autoCleanup) { b -> upd { it.copy(autoCleanup = b) } }
            if (s.autoCleanup) {
                ChoiceChips(listOf("15 min" to 15L * 60_000, "1 hour" to 3_600_000L, "24 hours" to 86_400_000L), selected = s.retentionMs) { r -> upd { it.copy(retentionMs = r) } }
            }
            OutlinedButton(onClick = { graph.deleteAllScreenshots() }) { Text("Delete all stored copies now") }
        }

        SectionCard("Experimental") {
            SwitchSetting(
                "Pre-select the chat in WhatsApp's share list",
                "Only for the manual \"you press Send\" mode. Acts only when exactly one row matches the verified chat and never presses Send. Depends on WhatsApp's screen layout.",
                s.directSelectExperimental,
            ) { b -> upd { it.copy(directSelectExperimental = b) } }
        }
    }
}
