package com.smartmanager.whatsappshoot.logic

/** One order on a photo that shows several: the rows [top, bottom) of the picture and the text lines of the Latin reader that fall in them. */
data class OrderBand(val top: Int, val bottom: Int, val lines: List<OcrBox>, val orderNumber: String) {
    val text: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Cuts a photo that holds several orders into one band per order. An order starts at a line that carries an order number (label + 5 or more digits, or a code
 * such as CMD-12345); a photo with fewer than two different order numbers is a single order and is not split. Each band begins a little above its order-number line
 * (the date or shop name often stands just above it) and ends where the next band begins; the first band starts at the top of the picture and the last ends at
 * the bottom, so nothing printed on the photo is lost.
 */
object OrderSplitter {
    private const val MIN_DIGITS = 5
    private const val MARGIN_LINES = 1.5
    /** A photo is never split into more orders than this (a long list is read in this many pieces at most). */
    const val MAX_ORDERS = 8

    fun split(lines: List<OcrBox>, imageHeight: Int): List<OrderBand> {
        val sorted = lines.sortedBy { it.top }
        val anchors = ArrayList<Pair<OcrBox, String>>()
        for (l in sorted) {
            val no = DetailExtractor.extract(l.text).orderNumber ?: continue
            if (no.count { it.isDigit() } < MIN_DIGITS) continue
            // The same number again (e.g. in a header and a footer of the same order) is not a new order.
            if (anchors.any { it.second == no }) continue
            anchors += l to no
        }
        if (anchors.size < 2) return emptyList()
        val used = anchors.take(MAX_ORDERS)
        val starts = used.mapIndexed { i, (l, _) ->
            if (i == 0) 0 else Math.max(l.top - (Math.max(l.height, 1) * MARGIN_LINES).toInt(), used[i - 1].first.bottom)
        }
        return used.mapIndexed { i, (_, no) ->
            val top = starts[i]
            val bottom = if (i + 1 < used.size) starts[i + 1] else imageHeight
            val inside = sorted.filter { val mid = (it.top + it.bottom) / 2; mid >= top && mid < bottom }
            OrderBand(top, bottom, inside.map { it.copy(top = it.top - top, bottom = it.bottom - top) }, no)
        }
    }
}
