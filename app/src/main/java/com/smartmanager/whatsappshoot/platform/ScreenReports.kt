package com.smartmanager.whatsappshoot.platform

import android.content.Context
import android.os.Build

/**
 * In-memory diagnostics of the WhatsApp screens seen while the accessibility service runs, so a screen definition can be
 * calibrated without a PC. It records only technical structure: window class, view-id names, view classes, whether a view has text
 * (yes/no, never the text), whether it is editable/clickable, and its vertical position. No message text, names or numbers.
 * Never written to disk or sent anywhere; the user copies it manually.
 */
object ScreenReports {
    private const val MAX = 10
    private val items = LinkedHashMap<String, String>()

    @Synchronized
    fun record(context: Context, pkg: String, windowClass: String?, kind: String, ids: Set<String>, nodes: List<String>) {
        val key = "$pkg|$windowClass|${ids.sorted().hashCode()}"
        items.remove(key)
        items[key] = buildString {
            append("screen: package=").append(pkg).append(" class=").append(windowClass ?: "?").append(" classified-as=").append(kind).append('\n')
            append("  view ids (").append(ids.size).append("): ").append(ids.sorted().joinToString(",")).append('\n')
            append("  nodes (id|view class|has text|editable|clickable|top%):\n")
            nodes.forEach { append("    ").append(it).append('\n') }
        }
        while (items.size > MAX) items.remove(items.keys.first())
    }

    @Synchronized
    fun text(context: Context): String {
        val wa = listOf("com.whatsapp", "com.whatsapp.w4b").mapNotNull { p ->
            try { "$p ${context.packageManager.getPackageInfo(p, 0).versionName}" } catch (e: Exception) { null }
        }
        val app = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "?" }
        return buildString {
            append("Screenshot Assistant screen report v1 (no message text, names or numbers)\n")
            append("app=").append(app).append(" android=").append(Build.VERSION.RELEASE).append(" sdk=").append(Build.VERSION.SDK_INT)
            append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("whatsapp: ").append(if (wa.isEmpty()) "not found" else wa.joinToString("; ")).append('\n')
            if (items.isEmpty()) append("(no WhatsApp screen seen yet: enable the accessibility service, press Start Automation, open WhatsApp screens, then copy again)\n")
            items.values.forEach { append('\n').append(it) }
        }
    }

    @Synchronized
    fun clear() = items.clear()
}
