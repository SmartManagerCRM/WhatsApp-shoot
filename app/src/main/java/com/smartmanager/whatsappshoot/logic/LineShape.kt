package com.smartmanager.whatsappshoot.logic

/**
 * A privacy-safe picture of what the text reader saw on one line, for the diagnostics log: structural words (order, status, summary, total, notes ...) are kept,
 * every other Latin letter becomes "a", Arabic letters "ع" and digits "9". Names, numbers and message text never reach the log.
 */
object LineShape {
    private val KEEP = Regex("(?i)\\b(order|status|summary|details|subtotal|total|notes?|date|today|branch|delivery|payment|items?|customer|invoice|commande|pending|preparing|accepted|new)\\b")

    fun of(text: String): String {
        val keep = KEEP.findAll(text).map { it.range to it.value }.toList()
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val k = keep.firstOrNull { i in it.first }
            if (k != null) { sb.append(k.second); i = k.first.last + 1; continue }
            val c = text[i]
            sb.append(when {
                c in 'A'..'Z' || c in 'a'..'z' -> 'a'
                c in '\u0600'..'\u06FF' && c.isLetter() -> '\u0639'
                c.isDigit() -> '9'
                c.isWhitespace() -> ' '
                else -> '.'
            })
            i++
        }
        return sb.toString().replace(Regex("a{2,}"), "a+").replace(Regex("\u0639{2,}"), "\u0639+").replace(Regex("9{2,}"), "9+").replace(Regex("\\s+"), " ").trim()
    }
}
