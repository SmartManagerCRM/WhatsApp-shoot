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
}
