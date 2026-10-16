package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.content.SharedPreferences
import com.smartmanager.whatsappshoot.logic.AutomationSettings
import com.smartmanager.whatsappshoot.logic.CaptureMethod
import com.smartmanager.whatsappshoot.logic.CaptureMode
import com.smartmanager.whatsappshoot.logic.ImageFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Non-sensitive preferences only. Nothing about conversations, contacts or license lives here. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _flow = MutableStateFlow(read())
    val flow: StateFlow<AutomationSettings> = _flow

    val current: AutomationSettings get() = _flow.value

    fun update(transform: (AutomationSettings) -> AutomationSettings) {
        val next = transform(_flow.value)
        prefs.edit()
            .putString("mode", next.mode.name)
            .putLong("delayMs", next.delayMs)
            .putBoolean("auto", next.automaticCaptureEnabled)
            .putBoolean("floating", next.floatingControlEnabled)
            .putString("format", next.format.name)
            .putString("method", next.captureMethod.name)
            .putBoolean("cleanup", next.autoCleanup)
            .putLong("retention", next.retentionMs)
            .putBoolean("deleteOnEnd", next.deleteWhenWorkflowEnds)
            .putBoolean("directSelect", next.directSelectExperimental)
            .apply()
        _flow.value = next
    }

    private fun read(): AutomationSettings {
        val d = AutomationSettings()
        return AutomationSettings(
            mode = enumOf(prefs.getString("mode", null), d.mode),
            delayMs = prefs.getLong("delayMs", d.delayMs),
            automaticCaptureEnabled = prefs.getBoolean("auto", d.automaticCaptureEnabled),
            floatingControlEnabled = prefs.getBoolean("floating", d.floatingControlEnabled),
            format = enumOf(prefs.getString("format", null), d.format),
            captureMethod = enumOf(prefs.getString("method", null), d.captureMethod),
            autoCleanup = prefs.getBoolean("cleanup", d.autoCleanup),
            retentionMs = prefs.getLong("retention", d.retentionMs),
            deleteWhenWorkflowEnds = prefs.getBoolean("deleteOnEnd", d.deleteWhenWorkflowEnds),
            directSelectExperimental = prefs.getBoolean("directSelect", d.directSelectExperimental),
        )
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
