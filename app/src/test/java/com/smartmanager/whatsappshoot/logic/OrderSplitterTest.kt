package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderSplitterTest {
    private fun box(t: String, top: Int) = OcrBox(t, 10, top, 400, top + 30)

    @Test fun oneOrderIsNotSplit() {
        assertTrue(OrderSplitter.split(listOf(box("Order #1234567", 100), box("Date: 12/11/2026", 200)), 2000).isEmpty())
    }

    @Test fun sameNumberTwiceIsOneOrder() {
        assertTrue(OrderSplitter.split(listOf(box("Order #1234567", 100), box("Ref Order #1234567", 900)), 2000).isEmpty())
    }

    @Test fun shortNumbersAreNotOrders() {
        assertTrue(OrderSplitter.split(listOf(box("Order #12", 100), box("Order #34", 900)), 2000).isEmpty())
    }

    @Test fun twoOrdersGetOneBandEach() {
        val lines = listOf(
            box("Date: 12/11/2026", 40), box("Order #1111111", 100), box("Blue watch", 160), box("Notes", 220),
            box("Date: 13/11/2026", 940), box("Order #2222222", 1000), box("Red bag", 1060),
        )
        val b = OrderSplitter.split(lines, 2000)
        assertEquals(2, b.size)
        assertEquals("1111111", b[0].orderNumber); assertEquals("2222222", b[1].orderNumber)
        assertEquals(0, b[0].top); assertEquals(b[1].top, b[0].bottom); assertEquals(2000, b[1].bottom)
        // each date lands with its own order, and positions are relative to the band
        assertTrue(b[0].text.contains("12/11/2026")); assertTrue(!b[0].text.contains("13/11/2026"))
        assertTrue(b[1].text.contains("13/11/2026")); assertTrue(b[1].text.contains("Red bag"))
        assertEquals(1000 - b[1].top, b[1].lines.first { it.text.startsWith("Order") }.top)
    }

    @Test fun neverMoreThanTheLimit() {
        val lines = (0 until 12).map { box("Order #${1000000 + it}", 100 + it * 300) }
        assertEquals(OrderSplitter.MAX_ORDERS, OrderSplitter.split(lines, 5000).size)
    }
}
