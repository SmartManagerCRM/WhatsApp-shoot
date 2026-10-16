package com.smartmanager.whatsappshoot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureMethod
import com.smartmanager.whatsappshoot.logic.CaptureMode
import com.smartmanager.whatsappshoot.logic.ImageFormat
import com.smartmanager.whatsappshoot.platform.AppGraph

@Composable
fun SettingsScreen(graph: AppGraph) {
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val upd = graph.settings::update

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

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
