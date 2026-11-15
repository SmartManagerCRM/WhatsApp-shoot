package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLocatorTest {
    private fun box(text: String, top: Int, h: Int = 26, left: Int = 100, right: Int = 700) = OcrBox(text, left, top, right, top + h)

    /** The order screenshot that failed on a real phone (1080 x 2400): the note is Arabic, the next Latin line is "Order Summary". */
    private val orderScreenshot = listOf(
        box("Mrsool", 530), box("# 359682125", 560), box("Order Status", 695), box("New Order", 880), box("Accepted", 880),
        box("Notes", 960), box("Order Summary", 1165), box("Payment Method", 1170), box("1X L6", 1265),
    )

    @Test fun theStripUnderNotesEndsAtTheNextSection_andNoLatinTextIsTakenAsTheNote() {
        val scan = NoteLocator.locate(orderScreenshot, 1080, 2400)!!
        assertNull("'Order Summary' is another section, not the note", scan.latinBody)
        val r = scan.region!!
        assertTrue(r.top in 986..1000)
        assertTrue(r.bottom in 1155..1165)
        assertEquals(0, r.left); assertEquals(1080, r.right)
    }

    @Test fun aLatinNoteRightUnderTheHeadingIsTaken() {
        val scan = NoteLocator.locate(listOf(box("Order No 7", 100), box("Notes", 300), box("Call before delivery", 332), box("gate code 4412", 360), box("Total 20", 600)), 1080, 2400)!!
        assertEquals("Call before delivery gate code 4412", scan.latinBody)
    }

    @Test fun headingVariants() {
        for (h in listOf("Notes", "NOTE", "Notes:", "Remarques", "Remarque :", "Comments", "Special instructions", "Instructions")) {
            assertNotNull("heading '$h'", NoteLocator.locate(listOf(box(h, 100), box("Total", 700)), 1080, 2400))
        }
    }

    @Test fun noHeading_noScan() {
        assertNull(NoteLocator.locate(listOf(box("Order Summary", 100), box("Notebook 15 inch", 300), box("Notes: ring twice", 500)), 1080, 2400))
    }

    @Test fun noRoomUnderTheHeading_noStrip() {
        val scan = NoteLocator.locate(listOf(box("Notes", 100), box("Total", 160)), 1080, 2400)!!
        assertNull(scan.region)
    }

    @Test fun lastHeadingWithNothingBelow_stripIsBounded() {
        val r = NoteLocator.locate(listOf(box("Notes", 2000)), 1080, 2400)!!.region!!
        assertTrue(r.bottom <= 2400)
        val r2 = NoteLocator.locate(listOf(box("Notes", 500)), 1080, 2400)!!.region!!
        assertTrue(r2.bottom - r2.top <= (2400 * 0.30).toInt())
    }

    @Test fun cleanNote_dropsEmojiAndJoinsLines() {
        val raw = "كتابه كرت طباعه ( سلمت روح أنجبت )\nالف الحمدلله على سلامتك 💙\nخواتك / نوره ، سحر\n"
        val n = NoteLocator.cleanNote(raw)!!
        assertFalse(n.contains('\n')); assertFalse(n.contains("\uD83D"))
        assertTrue(n.startsWith("كتابه كرت طباعه")); assertTrue(n.contains("خواتك / نوره"))
        assertTrue(NoteLocator.hasArabic(n))
    }

    @Test fun cleanNote_rejectsNoise() {
        assertNull(NoteLocator.cleanNote("")); assertNull(NoteLocator.cleanNote(" .. -- | \n ~~ ")); assertNull(NoteLocator.cleanNote("a"))
    }

    @Test fun cleanNote_isShortened() {
        val n = NoteLocator.cleanNote("كلمة ".repeat(100))!!
        assertTrue(n.length <= 201 && n.endsWith("…"))
    }

    @Test fun hasArabic_needsAFewLetters() {
        assertFalse(NoteLocator.hasArabic("Order Summary a")); assertFalse(NoteLocator.hasArabic("ا ب")); assertTrue(NoteLocator.hasArabic("ملاحظة"))
    }
}
