package com.smartmanager.whatsappshoot.logic

/** One line of text found on a picture with its position in pixels. */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** The strip of the picture under a "Notes" heading, where the note itself is written. */
data class NoteRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * [region] ends where the next section starts (null if that leaves no room); [fullRegion] is a generous fixed window under the heading, tried when the first strip
 * yields nothing (the Latin reader sometimes returns junk lines for Arabic print, which can hide where the real next section starts); [latinBody] is note text
 * written in Latin letters right under the heading (null when nothing readable stands directly under it).
 */
data class NoteScan(val region: NoteRegion?, val fullRegion: NoteRegion?, val latinBody: String?)

/**
 * Finds the heading of a notes box ("Notes", "Remarques", "Comments" ... standing alone on its line) and the part of the picture that belongs to it.
 * Text that stands directly under the heading (a line gap of at most 1.2 line heights) belongs to the note; a real section heading further below ("Order Summary")
 * ends the note's strip. Lines that are only misread Arabic print (short, or not made of English-looking words) are ignored. The strip is cropped, enlarged and read by the
 * Arabic reader, because the Latin reader cannot see Arabic script.
 */
object NoteLocator {
    // An icon next to the heading may be misread as a short token in front of it: "E Notes".
    private val HEADING = Regex(
        "(?i)^(?:\\S{1,3}\\s+)?\\W*(?:notes?|remarks?|remarques?|observations?|comments?|commentaires?|special\\s+instructions?|instructions?|memo)\\s*[:：\\-–]?\\s*$",
    )
    private const val WINDOW = 0.18 // of the picture height

    /** English-looking text: at least half of its words of 3+ characters contain a vowel, and it has 3+ letters. */
    fun looksLikeWords(text: String): Boolean {
        if (text.count { it.isLetter() } < 3) return false
        val words = text.split(Regex("\\s+")).filter { it.length >= 3 }
        if (words.isEmpty()) return false
        return words.count { w -> w.any { it in "aeiouyAEIOUYéèàùâêîôûÉÈÀ" } } * 2 >= words.size
    }

    /** A section heading such as "Order Summary" or "Payment Method": 6+ Latin letters, mostly letters, English-looking. */
    private fun isSectionHeader(text: String): Boolean {
        val t = text.trim()
        val latin = t.count { it in 'A'..'Z' || it in 'a'..'z' }
        val nonSpace = t.count { !it.isWhitespace() }
        return latin >= 6 && latin * 10 >= nonSpace * 7 && looksLikeWords(t)
    }

    fun locate(lines: List<OcrBox>, imageWidth: Int, imageHeight: Int): NoteScan? {
        val sorted = lines.sortedBy { it.top }
        val idx = sorted.indexOfFirst { HEADING.matches(it.text.trim()) }
        if (idx < 0) return null
        val heading = sorted[idx]
        val lh = Math.max(heading.height, 1)
        val maxGap = lh * 1.2
        val body = ArrayList<OcrBox>()
        var prevBottom = heading.bottom
        var boundary: OcrBox? = null
        for (l in sorted.drop(idx + 1)) {
            if (l.top - prevBottom <= maxGap) {
                if (looksLikeWords(l.text)) body += l
                prevBottom = l.bottom
            } else if (isSectionHeader(l.text)) {
                boundary = l; break
            } // else: a stray misread line far below the heading: ignored
        }
        val top = heading.bottom + (lh * 0.2).toInt()
        val fullBottom = Math.min(imageHeight, heading.bottom + (imageHeight * WINDOW).toInt())
        val minHeight = (lh * 1.5).toInt()
        val full = if (fullBottom - top >= minHeight) NoteRegion(0, top, imageWidth, fullBottom) else null
        val bounded = boundary?.let { Math.min(it.top - (lh * 0.2).toInt(), fullBottom) }
        val region = when {
            boundary == null -> full
            bounded != null && bounded - top >= minHeight -> NoteRegion(0, top, imageWidth, bounded)
            else -> null
        }
        val latin = body.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() }
        return NoteScan(region, full, latin)
    }

    private val ARABIC = '؀'..'ۿ'

    fun hasArabic(text: String): Boolean = text.count { it in ARABIC } >= 3

    /**
     * From the Arabic reader's output for a strip: the first block of lines that are mostly Arabic letters. Short lines (an emoji, a stray mark) are skipped; the block ends at
     * the first line that is not mostly Arabic or is full of digits (the next section misread as Arabic). Null if no such line exists.
     */
    fun arabicBlock(raw: String): String? {
        val kept = ArrayList<String>()
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val letters = t.count { it.isLetter() }
            if (letters < 4) continue
            val arabic = t.count { it in ARABIC && it.isLetter() }
            val digits = t.count { it.isDigit() }
            val nonSpace = t.count { !it.isWhitespace() }
            if (arabic * 2 >= letters && digits * 4 <= nonSpace) kept += t else if (kept.isNotEmpty()) break
        }
        return if (kept.isEmpty()) null else kept.joinToString(" ")
    }

    /**
     * Turns the reader's text for the notes strip into one clean line: only letters, digits and ordinary punctuation survive (emoji and stray marks are dropped), lines are
     * joined with spaces, and the result is cut at 200 characters.
     */
    fun cleanNote(raw: String): String? {
        val kept = raw.map { c ->
            when {
                c.isLetterOrDigit() || c in ARABIC || c.isWhitespace() -> c
                c in ".,،؛؟?!:;/()-–'\"%" -> c
                else -> ' '
            }
        }.joinToString("")
        val one = kept.replace(Regex("\\s+"), " ").trim().trim('-', '–', ':', ',', '،', ';').trim()
        if (one.count { it.isLetterOrDigit() } < 3) return null
        return if (one.length <= 200) one else one.take(200).trimEnd() + "…"
    }
}
