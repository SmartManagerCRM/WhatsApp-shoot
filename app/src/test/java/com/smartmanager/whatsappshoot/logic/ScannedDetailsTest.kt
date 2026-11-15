package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedDetailsTest {
    private fun d(t: String) = DetailExtractor.extract(t)

    @Test fun englishReceipt() {
        val x = d("ACME STORE\nOrder No: 10458\nDate: 12/11/2026\nBranch: Sfax Centre\nTotal 320.000 TND")
        assertEquals("10458", x.orderNumber); assertEquals("12/11/2026", x.date); assertEquals("Sfax Centre", x.branch)
    }

    @Test fun frenchReceipt() {
        val x = d("Commande n° 4587-B\nLe 03.05.2026\nAgence Tunis Lac\nMerci")
        assertEquals("4587-B", x.orderNumber); assertEquals("03.05.2026", x.date); assertEquals("Tunis Lac", x.branch)
    }

    @Test fun orderVariants() {
        assertEquals("12345", d("Order #12345").orderNumber)
        assertEquals("AB-1234", d("order id: AB-1234").orderNumber)
        assertEquals("778", d("N° de commande : 778").orderNumber)
        assertEquals("55120", d("Invoice\n55120").orderNumber)
        assertEquals("9001", d("Bon de commande 9001").orderNumber)
    }

    @Test fun orderNeedsADigit_soWordsAreNeverTaken() {
        assertNull(d("Order number pending").orderNumber)
        assertNull(d("Your order is ready").orderNumber)
    }

    @Test fun dateFormats() {
        assertEquals("2026-11-12", d("created 2026-11-12 14:03").date)
        assertEquals("12 Nov 2026", d("Delivered on 12 Nov 2026").date)
        assertEquals("12 novembre 2026", d("Livré le 12 novembre 2026").date)
        assertEquals("Nov 12, 2026", d("Placed Nov 12, 2026").date)
        assertEquals("1/2/26", d("rcpt 1/2/26").date)
    }

    @Test fun impossibleDatesAreIgnored() {
        assertNull(d("ref 45/13/2026").date)
        assertNull(d("price 3.250.000").date)
    }

    @Test fun aDateOnTheDateLineWins() {
        assertEquals("05/06/2026", d("Printed 01/01/2020\nDate: 05/06/2026").date)
    }

    @Test fun branchStopsAtSeparators() {
        assertEquals("Sousse", d("Branch: Sousse | Tel 73 000 000").branch)
        assertEquals("Ariana", d("Magasin Ariana - Caisse 2").branch)
        assertEquals("12", d("Store 12").branch)
    }

    @Test fun nothingFound_isEmpty() {
        assertTrue(d("").isEmpty); assertTrue(d("Hello, the phone is on sale!").isEmpty)
    }

    @Test fun caption_orderIsProductThenDetailsThenYourText() {
        val p = Product("1", "Watch Pro 2", "450 TND", "Titanium")
        val details = ScannedDetails("10458", "12/11/2026", "Sfax")
        assertEquals(
            "Watch Pro 2\n450 TND\nTitanium\nOrder no.: 10458\nDate: 12/11/2026\nBranch: Sfax\n\nThank you",
            CaptionBuilder.build(p, "Thank you", true, details),
        )
    }

    @Test fun caption_labelsFollowTheLanguage() {
        val labels = DetailLabels("N° de commande", "Date", "Agence")
        assertEquals("N° de commande: 77\nAgence: Tunis", CaptionBuilder.build(null, "", true, ScannedDetails("77", null, "Tunis"), labels))
    }

    @Test fun caption_detailsOnly_withoutProduct() {
        assertEquals("Order no.: 5\n\nHi", CaptionBuilder.build(null, "Hi", true, ScannedDetails(orderNumber = "5")))
    }

    @Test fun arabicIndicDigitsAreUnderstood() {
        val x = d("Date: \u0661\u0662/\u0661\u0661/\u0662\u0660\u0662\u0666\nOrder No: \u0661\u0660\u0664\u0665\u0668")
        assertEquals("12/11/2026", x.date); assertEquals("10458", x.orderNumber)
    }

    @Test fun orderWithoutAnOrderLabel() {
        assertEquals("4587", d("Ref: 4587").orderNumber)
        assertEquals("778", d("N° 778").orderNumber)
        assertEquals("CMD-2026045", d("merci\nCMD-2026045\nbonne journée").orderNumber)
        assertEquals("INV 2026/045", d("INV 2026/045").orderNumber)
        assertEquals("TX9981", d("Transaction ID TX9981").orderNumber)
    }

    @Test fun orderLabelStillBeatsAGenericReference() {
        assertEquals("55120", d("Ref: 9999\nOrder no 55120").orderNumber)
    }

    @Test fun moreDateShapes() {
        assertEquals("12-Nov-26", d("Sale 12-Nov-26 14:03").date)
        assertEquals("12 Nov", d("Date: 12 Nov").date)
        assertNull(d("Total 12 Nov items").date)
    }

    @Test fun shopNameAndSellerLabels() {
        assertEquals("Mega Phone", d("Shop name: Mega Phone").branch)
        assertEquals("Mega Phone Sfax", d("Sold by Mega Phone Sfax").branch)
        assertEquals("Mega Phone", d("Nom du magasin : Mega Phone").branch)
        assertEquals("Lac 2", d("Location: Lac 2").branch)
    }

    @Test fun aWordInsideAnotherWordIsNotALabel() {
        assertNull(d("visit our website: example.com").branch)
        assertNull(d("restore the backup").orderNumber)
    }

    @Test fun notesOnTheSameLine() {
        assertEquals("deliver before 5pm", d("Order No: 77\nNotes: deliver before 5pm\nTotal 20").notes)
        assertEquals("Livrer avant 17h", d("Remarque : Livrer avant 17h").notes)
        assertEquals("fragile", d("Comment - fragile").notes)
    }

    @Test fun notesLabelThenTheNoteOnTheNextLines() {
        assertEquals("Call before delivery gate code 4412", d("Notes:\nCall before delivery\ngate code 4412\n\nThank you").notes)
    }

    @Test fun noteAtTheStartOfALine() {
        assertEquals("livrer le matin", d("Note livrer le matin").notes)
    }

    @Test fun notesStopAtAnotherLabelLine() {
        assertEquals("ring twice", d("Notes:\nring twice\nDate: 12/11/2026").notes)
    }

    @Test fun wordsThatMerelyContainNoteAreNotLabels() {
        assertNull(d("Notebook 15 inch\nCommentary track").notes)
        assertNull(d("Keynote speaker").notes)
    }

    @Test fun veryLongNotesAreShortened() {
        val n = d("Notes: " + "word ".repeat(80)).notes!!
        assertTrue(n.length <= 201 && n.endsWith("…"))
    }

    @Test fun caption_notesComeAfterTheOtherDetails() {
        val p = Product("1", "Watch Pro 2", "450 TND", "")
        assertEquals(
            "Watch Pro 2\n450 TND\nOrder no.: 7\nNotes: ring twice\n\nThanks",
            CaptionBuilder.build(p, "Thanks", true, ScannedDetails(orderNumber = "7", notes = "ring twice")),
        )
        assertEquals("Remarques: livrer", CaptionBuilder.build(null, "", true, ScannedDetails(notes = "livrer"), DetailLabels(notes = "Remarques")))
    }
}
