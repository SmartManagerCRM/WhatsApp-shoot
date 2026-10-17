package com.smartmanager.whatsappshoot.logic

import com.smartmanager.whatsappshoot.logic.GridOrder.Box
import org.junit.Assert.assertEquals
import org.junit.Test

class GridOrderTest {
    // 3 columns x 2 rows, 300 px tiles; names are a1 a2 a3 / b1 b2 b3 by screen position (left to right)
    private val tiles = listOf(
        "b3" to Box(600, 310, 900, 610), "a1" to Box(0, 0, 300, 300), "b1" to Box(0, 312, 300, 612),
        "a3" to Box(600, 4, 900, 304), "a2" to Box(300, 0, 600, 300), "b2" to Box(300, 308, 600, 608),
    )
    private fun order(rtl: Boolean) = GridOrder.inReadingOrder(tiles, { it.second }, rtl).map { it.first }

    @Test fun leftToRightStartsTopLeft() = assertEquals(listOf("a1", "a2", "a3", "b1", "b2", "b3"), order(false))

    @Test fun rightToLeftStartsTopRight() {
        val o = order(true)
        assertEquals("a3", o.first())
        assertEquals(listOf("a3", "a2", "a1", "b3", "b2", "b1"), o)
    }

    @Test fun directionFromWhatsAppsOwnButtons() {
        assertEquals(false, GridOrder.isRtl(listOf(100 to 900, 80 to 800), fallbackRtl = true))
        assertEquals(true, GridOrder.isRtl(listOf(900 to 100, 800 to 80), fallbackRtl = false))
        assertEquals(true, GridOrder.isRtl(listOf(900 to 100, 800 to 80, 100 to 900), fallbackRtl = false))
    }

    @Test fun noMarksOrTieUsesPhoneDirection() {
        assertEquals(true, GridOrder.isRtl(emptyList(), true))
        assertEquals(false, GridOrder.isRtl(emptyList(), false))
        assertEquals(true, GridOrder.isRtl(listOf(100 to 900, 900 to 100), true))
    }
}
