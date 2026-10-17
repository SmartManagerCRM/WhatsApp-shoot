package com.smartmanager.whatsappshoot.logic

/**
 * WhatsApp's gallery lists pictures newest first, reading in the layout direction of the screen: left to right normally,
 * right to left when WhatsApp runs in a right-to-left language (Arabic, Hebrew, Persian, Urdu ...), where the newest picture is
 * the top-RIGHT tile. These two helpers find that direction from the screen itself and put tiles in that order.
 */
object GridOrder {
    /** A rectangle in screen pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX get() = (left + right) / 2
        val height get() = bottom - top
    }

    /**
     * Rows top to bottom; inside a row left to right ([rtl] false) or right to left ([rtl] true).
     * Tiles of one row can differ by a few pixels (animations, rounding): they are grouped when their tops are closer than half a tile.
     */
    fun <T> inReadingOrder(items: List<T>, box: (T) -> Box, rtl: Boolean): List<T> {
        val rows = ArrayList<MutableList<T>>()
        for (t in items.sortedBy { box(it).top }) {
            val row = rows.lastOrNull()
            if (row != null && Math.abs(box(t).top - box(row[0]).top) < box(t).height / 2) row += t else rows += mutableListOf(t)
        }
        return rows.flatMap { r -> if (rtl) r.sortedByDescending { box(it).centerX } else r.sortedBy { box(it).centerX } }
    }

    /**
     * Is WhatsApp's layout mirrored? Each pair is (x of an element that sits at the START of the screen in a left-to-right layout,
     * x of one that sits at the END), e.g. emoji button vs camera button, back arrow vs menu button. If WhatsApp mirrored its screen
     * the start element is on the right. The majority of pairs decides; with no pair (or a tie) [fallbackRtl] (the phone's language direction) is used.
     */
    fun isRtl(startEndPairs: List<Pair<Int, Int>>, fallbackRtl: Boolean): Boolean {
        var ltr = 0; var rtl = 0
        for ((start, end) in startEndPairs) if (start < end) ltr++ else if (start > end) rtl++
        return when {
            rtl > ltr -> true
            ltr > rtl -> false
            else -> fallbackRtl
        }
    }
}
