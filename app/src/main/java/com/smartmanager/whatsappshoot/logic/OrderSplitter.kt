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

    private val STATUS = Regex("(?i)^\\W*order\\s+status\\b.{0,12}$")
    private val SUMMARY = Regex("(?i)^\\W*order\\s+summary\\b.{0,6}$")
    private val TOTAL = Regex("(?i)^\\W*total\\b.{0,20}$")

    /**
     * Order numbers are tried first. Many order pages show no usable number (a 4-digit code, or none), so when fewer than two different numbers are found the
     * repeated headings "Order Status", then "Order Summary", mark the orders instead; the line "Total" that closes an order tells where the next order's own header
     * (date, shop, number) begins.
     */
    fun split(lines: List<OcrBox>, imageHeight: Int): List<OrderBand> {
        val sorted = lines.sortedBy { it.top }
        val numbered = ArrayList<Pair<OcrBox, String>>()
        for (l in sorted) {
            val no = DetailExtractor.extract(l.text).orderNumber ?: continue
            if (no.count { it.isDigit() } < MIN_DIGITS) continue
            // The same number again (e.g. in a header and a footer of the same order) is not a new order.
            if (numbered.any { it.second == no }) continue
            numbered += l to no
        }
        if (numbered.size >= 2) return bands(sorted, numbered.take(MAX_ORDERS), imageHeight, marginLines = MARGIN_LINES, totalRule = false)
        for ((regex, margin) in listOf(STATUS to 9.0, SUMMARY to 18.0)) {
            val marks = sorted.filter { regex.matches(it.text.trim()) }
            // Headings that sit right next to each other belong to the same order.
            val spread = ArrayList<OcrBox>()
            for (m in marks) if (spread.isEmpty() || m.top - spread.last().bottom > Math.max(m.height, 1) * 12) spread += m
            if (spread.size >= 2) return bands(sorted, spread.take(MAX_ORDERS).mapIndexed { i, l -> l to "#${i + 1}" }, imageHeight, margin, totalRule = true)
        }
        // Last resort: each order ends with its own "Total" line.
        val totals = ArrayList<OcrBox>()
        for (m in sorted.filter { TOTAL.matches(it.text.trim()) }) if (totals.isEmpty() || m.top - totals.last().bottom > Math.max(m.height, 1) * 12) totals += m
        if (totals.size >= 2) return afterEach(sorted, totals.take(MAX_ORDERS), imageHeight)
        return emptyList()
    }

    /** Orders that each END at one of [ends]: an order starts just under the previous end line. */
    private fun afterEach(sorted: List<OcrBox>, ends: List<OcrBox>, imageHeight: Int): List<OrderBand> {
        val starts = ends.mapIndexed { i, _ -> if (i == 0) 0 else ends[i - 1].bottom + Math.max(ends[i - 1].height, 1) }
        return ends.mapIndexed { i, _ ->
            val top = starts[i]
            val bottom = if (i + 1 < ends.size) starts[i + 1] else imageHeight
            val inside = sorted.filter { val mid = (it.top + it.bottom) / 2; mid >= top && mid < bottom }
            OrderBand(top, bottom, inside.map { it.copy(top = it.top - top, bottom = it.bottom - top) }, "#${i + 1}")
        }
    }

    private fun bands(sorted: List<OcrBox>, used: List<Pair<OcrBox, String>>, imageHeight: Int, marginLines: Double, totalRule: Boolean): List<OrderBand> {
        val starts = used.mapIndexed { i, (l, _) ->
            if (i == 0) return@mapIndexed 0
            val prev = used[i - 1].first
            val byMargin = Math.max(l.top - (Math.max(l.height, 1) * marginLines).toInt(), prev.bottom)
            if (!totalRule) return@mapIndexed byMargin
            // The last "Total" line between the two headings closes the previous order.
            val total = sorted.lastOrNull { it.top >= prev.bottom && it.bottom <= l.top && TOTAL.matches(it.text.trim()) }
            if (total != null) Math.min(total.bottom + Math.max(total.height, 1), l.top) else byMargin
        }
        return used.mapIndexed { i, (_, no) ->
            val top = starts[i]
            val bottom = if (i + 1 < used.size) starts[i + 1] else imageHeight
            val inside = sorted.filter { val mid = (it.top + it.bottom) / 2; mid >= top && mid < bottom }
            OrderBand(top, bottom, inside.map { it.copy(top = it.top - top, bottom = it.bottom - top) }, no)
        }
    }
}
