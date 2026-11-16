package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NoteIconLocatorTest {
    private val W = 720; private val H = 1000

    private fun canvas() = ByteArray(W * H) { 255.toByte() }
    private fun fill(c: ByteArray, l: Int, t: Int, r: Int, b: Int) { for (y in t..b) for (x in l..r) c[y * W + x] = 0 }
    /** A 30x36 outline with a 3 px frame and [bars] bars inside. */
    private fun icon(c: ByteArray, l: Int, t: Int, bars: Int = 3) {
        val w = 30; val h = 36
        fill(c, l, t, l + w - 1, t + 2); fill(c, l, t + h - 3, l + w - 1, t + h - 1); fill(c, l, t, l + 2, t + h - 1); fill(c, l + w - 3, t, l + w - 1, t + h - 1)
        for (i in 0 until bars) fill(c, l + 8, t + 8 + i * 8, l + 21, t + 9 + i * 8)
    }

    @Test fun findsTheIcon() {
        val c = canvas(); icon(c, 60, 500)
        val i = NoteIconLocator.find(c, W, H)
        assertNotNull(i); assertEquals(60, i!!.left); assertEquals(500, i.top); assertEquals(30, i.width); assertEquals(36, i.height)
    }

    @Test fun anOutlineWithoutBarsIsNotAnIcon() {
        val c = canvas(); icon(c, 60, 500, bars = 0)
        assertNull(NoteIconLocator.find(c, W, H))
    }

    @Test fun aSolidBlockIsNotAnIcon() {
        val c = canvas(); fill(c, 60, 500, 89, 535)
        assertNull(NoteIconLocator.find(c, W, H))
    }

    @Test fun iconOnTheRightIsIgnored() {
        val c = canvas(); icon(c, 600, 500)
        assertNull(NoteIconLocator.find(c, W, H))
    }

    @Test fun lowestIconWins() {
        val c = canvas(); icon(c, 60, 200); icon(c, 60, 700)
        assertEquals(700, NoteIconLocator.find(c, W, H)!!.top)
    }

    @Test fun regionStopsAtTheNextSection() {
        val i = NoteIconLocator.Icon(60, 500, 89, 535)
        val open = NoteIconLocator.region(i, emptyList(), W, H)!!
        assertEquals(0, open.left); assertEquals(W, open.right); assertEquals(500 - 50, open.top); assertEquals(535 + 151, open.bottom)
        val cut = NoteIconLocator.region(i, listOf(300, 640, 900), W, H)!!
        assertEquals(640 - 6, cut.bottom)
        assertEquals(560 - 6, NoteIconLocator.region(i, listOf(560), W, H)!!.bottom)
    }
}
