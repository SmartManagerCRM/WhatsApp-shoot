package com.smartmanager.whatsappshoot.logic

/** One line of text found on a picture with its position in pixels. */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = bottom - top
}

/** The strip of the picture under a "Notes" heading, where the note itself is written. */
data class NoteRegion(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * [region] is the strip under the heading (null when there is no room for a note); [latinBody] is note text written in Latin letters right under the heading
 * (null when nothing stands directly under it).
 */
data class NoteScan(val region: NoteRegion?, val latinBody: String?)

/**
 * Finds the heading of a notes box ("Notes", "Remarques", "Comments" ... standing alone on its line) and the part of the picture that belongs to it.
 * Text that stands directly under the heading (a line gap of at most 1.2 line heights) belongs to the note; text further below is another section ("Order Summary")
 * and ends the note's strip. The strip is cropped and read by the Arabic reader, because the Latin reader cannot see Arabic script.
 */
object NoteLocator {
    private val HEADING = Regex(
        "(?i)^\\W*(?:notes?|remarks?|remarques?|observations?|comments?|commentaires?|special\\s+instructions?|instructions?|memo)\\s*[:：\\-–]?\\s*$",
    )

    fun locate(lines: List<OcrBox>, imageWidth: Int, imageHeight: Int): NoteScan? {
        val sorted = lines.sortedBy { it.top }
        val idx = sorted.indexOfFirst { HEADING.matches(it.text.trim()) }
        if (idx < 0) return null
        val heading = sorted[idx]
        val lh = Math.max(heading.height, 1)
        val maxGap = lh * 1.2
        val body = ArrayList<OcrBox>()
        var prevBottom = heading.bottom
        var next: OcrBox? = null
        for (l in sorted.drop(idx + 1)) {
            if (l.top - prevBottom > maxGap) { next = l; break }
            body += l
            prevBottom = l.bottom
        }
        val top = heading.bottom + (lh * 0.2).toInt()
        val bottom = Math.min(imageHeight, next?.let { it.top - (lh * 0.2).toInt() } ?: (heading.bottom + (imageHeight * 0.30).toInt()))
        val region = if (bottom - top >= lh) NoteRegion(0, top, imageWidth, bottom) else null
        val latin = body.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ").trim().takeIf { it.any(Char::isLetterOrDigit) }
        return NoteScan(region, latin)
    }

    private val ARABIC = '؀'..'ۿ'

    fun hasArabic(text: String): Boolean = text.count { it in ARABIC } >= 3

    /**
     * Turns what the Arabic reader returned for the notes strip into one clean line: only letters, digits and ordinary punctuation survive (emoji and stray marks
     * are dropped), lines are joined with spaces, and the result is cut at 200 characters.
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
