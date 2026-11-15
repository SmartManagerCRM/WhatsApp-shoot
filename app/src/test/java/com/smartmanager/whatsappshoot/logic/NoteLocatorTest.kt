package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteLocatorTest {
    private fun box(text: String, top: Int, h: Int = 26, left: Int = 100, right: Int = 700) = OcrBox(text, left, top, right, top + h)

    /** The order screenshot that failed on a real phone (1080 x 2436): the note is Arabic, the next Latin line is "Order Summary". */
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
        assertNotNull(scan.fullRegion)
    }

    @Test fun theRealPhoneGeometry_withJunkLinesThatTheLatinReaderInventsForArabicPrint() {
        // heading 36 px high; the reader returns junk for the Arabic lines ("a", "dall ale") and finds "Order Summary" below the box
        val lines = listOf(
            box("Order Details", 100, 40), box("Mrsool", 300, 40), box("Notes", 1190, 36), box("a", 1290, 28), box("dall ale", 1330, 28),
            box("Order Summary", 1595, 36), box("Payment Method", 1600, 30), box("1X L6", 1760, 40),
        )
        val scan = NoteLocator.locate(lines, 1080, 2436)!!
        // the junk line "dall ale" looks like a section heading and ends the first strip early; the generous window under the heading is the safety net
        val full = scan.fullRegion!!
        assertTrue(full.top in 1226..1240)
        assertTrue("the window covers the whole note box (it ends at about 1414)", full.bottom >= 1500)
        assertNull("junk is never taken as a Latin note", scan.latinBody)
    }

    @Test fun anIconMisreadAsAShortTokenInFrontOfTheHeadingStillFindsIt() {
        assertNotNull(NoteLocator.locate(listOf(box("E Notes", 100), box("Total amount", 700)), 1080, 2400))
        assertNotNull(NoteLocator.locate(listOf(box("= Remarques", 100), box("Total amount", 700)), 1080, 2400))
    }

    @Test fun aLatinNoteRightUnderTheHeadingIsTaken() {
        val scan = NoteLocator.locate(listOf(box("Order No 7", 100), box("Notes", 300), box("Call before delivery", 332), box("gate code 4412", 360), box("Total amount", 600)), 1080, 2400)!!
        assertEquals("Call before delivery gate code 4412", scan.latinBody)
    }

    @Test fun headingVariants() {
        for (h in listOf("Notes", "NOTE", "Notes:", "Remarques", "Remarque :", "Comments", "Special instructions", "Instructions")) {
            assertNotNull("heading '$h'", NoteLocator.locate(listOf(box(h, 100), box("Total amount", 700)), 1080, 2400))
        }
    }

    @Test fun wordsThatContainNoteAreNotHeadings() {
        assertNull(NoteLocator.locate(listOf(box("Order Summary", 100), box("Notebook 15 inch", 300), box("Notes: ring twice", 500), box("Keynotes", 700)), 1080, 2400))
    }

    @Test fun noRoomBetweenTheHeadingAndTheNextSection_firstStripIsDropped_windowStays() {
        val scan = NoteLocator.locate(listOf(box("Notes", 100), box("Order Summary", 160)), 1080, 2400)!!
        assertNull(scan.region); assertNotNull(scan.fullRegion)
    }

    @Test fun noSectionBelow_theWindowIsTheStrip_andIsBounded() {
        val scan = NoteLocator.locate(listOf(box("Notes", 500)), 1080, 2400)!!
        assertEquals(scan.fullRegion, scan.region)
        assertTrue(scan.fullRegion!!.bottom - scan.fullRegion!!.top <= (2400 * 0.18).toInt())
        val last = NoteLocator.locate(listOf(box("Notes", 2300)), 1080, 2400)!!
        assertTrue(last.fullRegion == null || last.fullRegion!!.bottom <= 2400)
    }

    // ---- what the Arabic reader returned (real output for the failing order screenshot, Tesseract 5 with the bundled Arabic data) ----

    private val realStrip = "كتابه كرت طباعه ( سلمت روح أنجبت وسلمت روخ على الدنيا أقبلت\nالف الحمدلله على سلامتك وسلامة بيبي غمر جعله الله مباركاً أينما حل\nخواتك / نوره , سحر\n"
    private val realWindow = "كتابه كرت طباعه ( سلمت روح أنجبت وسلمت روخ على الدنيا إ قبلت\nالف الحمدلله على سلامتك وسلامة بيبى غمر جعله الله مبارك أينما حل\nخواتك / نوره , سحر\n6601© ومطاعا لمعصبروم 5007737 ‎013061١‏\n"

    @Test fun arabicBlock_realStrip_keepsAllThreeLines() {
        val b = NoteLocator.arabicBlock(realStrip)!!
        assertTrue(b.startsWith("كتابه كرت طباعه")); assertTrue(b.contains("جعله الله مباركاً أينما حل")); assertTrue(b.endsWith("نوره , سحر"))
    }

    @Test fun arabicBlock_stopsAtTheNextSectionMisreadAsArabicAndDigits() {
        val b = NoteLocator.arabicBlock(realWindow)!!
        assertTrue(b.endsWith("نوره , سحر")); assertFalse(b.contains("5007737")); assertFalse(b.contains("6601"))
    }

    @Test fun arabicBlock_skipsAnEmojiLine_butStopsAtEnglishText() {
        val raw = "ملاحظة مهمة جدا للتوصيل\n💙\nالتسليم بعد الظهر\nOrder Summary\nمرحبا بكم في المتجر"
        assertEquals("ملاحظة مهمة جدا للتوصيل التسليم بعد الظهر", NoteLocator.arabicBlock(raw))
    }

    @Test fun arabicBlock_noArabic_isNull() {
        assertNull(NoteLocator.arabicBlock("Order Summary\n1X L6\n146.30")); assertNull(NoteLocator.arabicBlock("")); assertNull(NoteLocator.arabicBlock("5 ا0106 >"))
    }

    @Test fun cleanNote_realStrip() {
        val n = NoteLocator.cleanNote(NoteLocator.arabicBlock(realStrip)!!)!!
        assertFalse(n.contains('\n')); assertTrue(NoteLocator.hasArabic(n)); assertTrue(n.contains("خواتك / نوره"))
    }

    @Test fun cleanNote_dropsEmojiAndJoinsLines() {
        val raw = "كتابه كرت طباعه ( سلمت روح أنجبت )\nالف الحمدلله على سلامتك 💙\nخواتك / نوره ، سحر\n"
        val n = NoteLocator.cleanNote(raw)!!
        assertFalse(n.contains('\n')); assertFalse(n.contains("\uD83D"))
        assertTrue(n.startsWith("كتابه كرت طباعه")); assertTrue(n.contains("خواتك / نوره"))
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

    @Test fun looksLikeWords_separatesEnglishFromMisreadArabic() {
        assertTrue(NoteLocator.looksLikeWords("Call before delivery")); assertTrue(NoteLocator.looksLikeWords("ring twice"))
        assertFalse(NoteLocator.looksLikeWords("a")); assertFalse(NoteLocator.looksLikeWords("5 D")); assertFalse(NoteLocator.looksLikeWords("Mfhs Prtn"))
    }
}
