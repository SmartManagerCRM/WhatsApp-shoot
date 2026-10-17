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
    private val lines = java.util.ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US) // one formatter, used under the lock (creating one per line is wasteful)

    @Synchronized
    fun add(message: String) {
        lines.addLast(format.format(Date()) + " " + message)
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized fun last(n: Int): List<String> = lines.toList().takeLast(n)
    @Synchronized fun text(): String = lines.joinToString("\n")
    @Synchronized fun clear() = lines.clear()
}
