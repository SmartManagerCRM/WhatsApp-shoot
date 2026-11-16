package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderItemsTest {
    private val a11 = Product("a", "A11", "10 TND", "")
    private val l6 = Product("l", "L6", "20 TND", "")
    private fun box(t: String, top: Int) = OcrBox(t, 10, top, 400, top + 30)

    @Test fun everyItemLineOfOneOrderGivesItsProduct_topToBottom() {
        val lines = listOf(box("1X L6", 760), box("Order Summary", 600), box("1X A11", 700), box("SubTotal", 900))
        assertEquals(listOf("a", "l"), OrderItems.products(lines, listOf(a11, l6)).map { it.id })
    }

    @Test fun sameProductTwiceIsOneProduct() {
        assertEquals(listOf("a"), OrderItems.products(listOf(box("1X A11", 10), box("2X A11", 90)), listOf(a11, l6)).map { it.id })
    }

    @Test fun linesWithoutAQuantityAreNotItems() {
        assertEquals(emptyList<String>(), OrderItems.products(listOf(box("A11 promo", 10), box("Total 195.00", 90)), listOf(a11, l6)).map { it.id })
    }

    @Test fun unknownItemsAreSkipped() {
        assertEquals(listOf("l"), OrderItems.products(listOf(box("1X Z99", 10), box("3X L6", 90)), listOf(a11, l6)).map { it.id })
    }
}
