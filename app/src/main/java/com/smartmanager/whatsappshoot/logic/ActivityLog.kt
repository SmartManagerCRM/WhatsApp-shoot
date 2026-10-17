package com.smartmanager.whatsappshoot.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory trace of what the automation decided and why, so a problem can be diagnosed from the phone without a PC.
 * Only technical events are recorded (state changes, counts, yes/no results): never message text, names or numbers.
 * Never written to disk or sent anywhere; the user copies it manually.
 */
object ActivityLog {
    private const val MAX = 120
    private val lines = ArrayList<String>()

    @Synchronized
    fun add(message: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        lines += "$t $message"
        while (lines.size > MAX) lines.removeAt(0)
    }

    @Synchronized fun last(n: Int): List<String> = lines.takeLast(n)
    @Synchronized fun text(): String = lines.joinToString("\n")
    @Synchronized fun clear() = lines.clear()
}
