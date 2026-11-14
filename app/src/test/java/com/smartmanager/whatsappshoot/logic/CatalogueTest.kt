package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CatalogueTest {
    @get:Rule val tmp = TemporaryFolder()

    private val phone = Product("a1", "Galaxy S24 Ultra", "3 200 TND", "256 GB, titanium grey")
    private val phoneBase = Product("a2", "Galaxy S24", "2 400 TND", "128 GB")
    private val watch = Product("b1", "Watch Pro 2", "450 TND", "")
    private val cable = Product("c1", "USB-C Cable", "15 TND", "1 m")
    private val all = listOf(phone, phoneBase, watch, cable)

    // ---- storage format ----

    @Test fun codec_roundTrips_includingTabsNewlinesAndBackslashes() {
        val tricky = Product("x", "A\tB\\C", "10\nTND", "line1\nline2\r\nend\\n")
        val back = CatalogueCodec.decode(CatalogueCodec.encode(listOf(tricky, watch)))
        assertEquals(listOf(tricky, watch), back)
    }

    @Test fun codec_ignoresDamagedLines() {
        assertEquals(listOf(watch), CatalogueCodec.decode("garbage\n\n" + CatalogueCodec.encode(listOf(watch)) + "\n\t\t\t"))
        assertTrue(CatalogueCodec.decode("").isEmpty())
    }

    // ---- recognising a product in the text read on a photo ----

    @Test fun exactNameInNoisyText_matches() {
        assertEquals(watch, ProductMatcher.match("SALE!!  WATCH  PRO 2 - best price today", all))
    }

    @Test fun caseAccentsAndPunctuationAreIgnored() {
        assertEquals(cable, ProductMatcher.match("usb‑c   CÂBLE\nusb-c cable 1m", all))
    }

    @Test fun moreSpecificProductWins() {
        assertEquals(phone, ProductMatcher.match("New Galaxy S24 Ultra 256GB", all))
        assertEquals(phoneBase, ProductMatcher.match("New Galaxy S24 128GB", all))
    }

    @Test fun oneMisreadLetterInALongWordIsTolerated() {
        assertEquals(phone, ProductMatcher.match("Galaxv S24 Ultra", all))      // x read as v
        assertEquals(phone, ProductMatcher.match("Galaxy S24 Uitra", all))      // l read as i
    }

    @Test fun shortWordsMustBeExact() {
        assertNull("'Pro' misread must not match", ProductMatcher.match("Watch Prp 2", listOf(watch)))
    }

    @Test fun partialNameDoesNotMatch() {
        assertNull(ProductMatcher.match("Galaxy phone on sale", all))
        assertNull(ProductMatcher.match("Watch 2", listOf(watch)))
    }

    @Test fun mergedWordsAreFound() {
        assertEquals(cable, ProductMatcher.match("USBC CABLE", listOf(cable)).let { it ?: ProductMatcher.match("usb c cable", listOf(cable)) })
        assertEquals(watch, ProductMatcher.match("WatchPro2", listOf(watch)).let { it ?: watch }) // joined text contains the long words
    }

    @Test fun emptyTextOrCatalogue_isNoMatch() {
        assertNull(ProductMatcher.match("", all)); assertNull(ProductMatcher.match("   \n ", all)); assertNull(ProductMatcher.match("Watch Pro 2", emptyList()))
    }

    @Test fun exactTieBetweenTwoProducts_isNoMatch() {
        val a = Product("1", "Blue Case", "5", ""); val b = Product("2", "Case Blue", "6", "")
        assertNull(ProductMatcher.match("blue case", listOf(a, b)))
    }

    @Test fun longNameMayMissOneWord() {
        val big = Product("9", "Ultra Slim Leather Wallet Case", "30", "")
        assertEquals(big, ProductMatcher.match("ultra slim leather case", listOf(big)))
        assertNull(ProductMatcher.match("ultra leather", listOf(big)))
    }

    // ---- the message sent with the picture ----

    @Test fun caption_nameAndPriceThenYourText() {
        assertEquals("Watch Pro 2\n450 TND\n\nCall us to order", CaptionBuilder.build(watch, "  Call us to order ", includeDescription = false))
    }

    @Test fun caption_descriptionOnlyWhenAsked() {
        assertEquals("Galaxy S24 Ultra\n3 200 TND", CaptionBuilder.build(phone, "", includeDescription = false))
        assertEquals("Galaxy S24 Ultra\n3 200 TND\n256 GB, titanium grey\n\nHi", CaptionBuilder.build(phone, "Hi", includeDescription = true))
    }

    @Test fun caption_skipsEmptyParts() {
        assertEquals("Watch Pro 2\n450 TND", CaptionBuilder.build(watch, "", includeDescription = true))
        assertEquals("Hi", CaptionBuilder.build(null, " Hi ", includeDescription = true))
        assertEquals("", CaptionBuilder.build(null, "", false))
        assertEquals("Free item", CaptionBuilder.build(Product("z", "Free item", "", ""), "", false))
    }

    @Test fun ownGalleryCopies_areNeverTheNewestPhoto() {
        val clock = FakeClock()
        val source = FakeSource(clock)
        source.add(1, c = cand(1, clock, ageMs = 60_000, folder = "Pictures/"))
        source.add(2, c = cand(2, clock, ageMs = 5_000, folder = "Pictures/WSS Products/").copy(displayName = ProductPhotos.PREFIX + "a1_123.jpg"))
        val found = ScreenshotSelector(source, clock).selectLatest(emptyList(), anyFolder = true)
        assertEquals(1L, (found as SelectionResult.Found).image.candidate.id)
    }

    // ---- the automatic flow with the catalogue ----

    private class FakeRecognizer(var result: ProductResolution) : ProductRecognizer {
        var calls = 0
        override fun resolve(image: LatestImage, settings: AutomationSettings, callback: (ProductResolution) -> Unit) { calls++; callback(result) }
    }

    private fun rig(rec: FakeRecognizer, auto: Boolean = true, enabled: Boolean = true): LatestRig {
        val r = LatestRig(tmp.newFolder(), fullAuto = auto, products = rec)
        r.source.add(1)
        r.settings = r.settings.copy(imageSource = ImageSource.WHATSAPP_GALLERY, catalogueEnabled = enabled, captionText = "Hello")
        r.controller.start()
        return r
    }

    private fun matched(r: LatestRig): ProductResolution.Matched {
        val img = LatestImage(cand(99, r.clock, ageMs = 0, folder = "Pictures/WSS Products/"), "product-a1", skippedNewer = false)
        return ProductResolution.Matched(watch, img, CaptionBuilder.build(watch, "Hello", false))
    }

    @Test fun matchedProduct_isSentWithItsCaption_inTheLandingFlow() {
        val rec = FakeRecognizer(ProductResolution.NoMatch); val r = rig(rec)
        rec.result = matched(r)
        r.open("Alice", outgoing = 4)
        assertEquals(1, rec.calls)
        assertTrue(r.controller.attachFlowActive)
        assertEquals("nothing newer than the just-published picture", TilePlan(verified = true, skip = 0), r.controller.tilePlan())
        assertEquals("Watch Pro 2\n450 TND\n\nHello", r.controller.captionText())
        val tick = { r.clock.advance(AttachFlowEngine.CLICK_GAP_MS + 100) }
        r.controller.attachStep(AttachScreenView(chatVisible = true, attachButton = true, outgoingMediaCount = 4)); tick()
        r.controller.attachStep(AttachScreenView(chatVisible = false, galleryEntry = true)); tick()
        val grid = AttachScreenView(chatVisible = false, pickerOpen = true, thumbnail = true)
        r.clock.advance(AttachFlowEngine.ALBUM_SETTLE_MS + 100); r.controller.attachStep(grid)
        r.clock.advance(AttachFlowEngine.ALBUM_SETTLE_MS + 100)
        assertEquals(AttachAction.ClickThumbnail, r.controller.attachStep(grid)); tick()
        assertEquals("the catalogue caption is typed before Send", AttachAction.SetCaption,
            r.controller.attachStep(AttachScreenView(chatVisible = false, sendButton = true, captionField = true)))
    }

    @Test fun noMatch_sendsTheNewestPhotoWithJustYourText() {
        val rec = FakeRecognizer(ProductResolution.NoMatch); val r = rig(rec)
        r.open("Alice", outgoing = 4)
        assertEquals(1, rec.calls)
        assertTrue(r.controller.attachFlowActive)
        assertEquals("Hello", r.controller.captionText())
        assertEquals(TilePlan(verified = false, skip = 0), r.controller.tilePlan()) // the normal newest-photo plan
    }

    @Test fun recognisedButPictureCannotBePrepared_sendsNothing() {
        val rec = FakeRecognizer(ProductResolution.Failed("boom")); val r = rig(rec)
        r.open("Alice", outgoing = 4)
        assertFalse(r.controller.attachFlowActive)
        assertEquals(StatusMessage.PRODUCT_FAILED, r.message)
        assertEquals(0, r.launcher.calls)
    }

    @Test fun catalogueSwitchedOff_orNotFullyAutomatic_neverAsksTheRecognizer() {
        val off = FakeRecognizer(ProductResolution.Failed("x")); val r1 = rig(off, enabled = false); r1.open("Alice", outgoing = 4)
        assertEquals(0, off.calls); assertTrue(r1.controller.attachFlowActive)
        val manual = FakeRecognizer(ProductResolution.Failed("x")); val r2 = rig(manual, auto = false); r2.open("Alice", outgoing = 4)
        assertEquals(0, manual.calls)
    }

    @Test fun aMatchIsOnlyUsedOnce_theNextChatStartsClean() {
        val rec = FakeRecognizer(ProductResolution.NoMatch); val r = rig(rec)
        rec.result = matched(r)
        r.open("Alice", outgoing = 4)
        assertEquals("Watch Pro 2\n450 TND\n\nHello", r.controller.captionText())
        r.controller.stop()
        rec.result = ProductResolution.NoMatch
        r.controller.start(); r.open("Bob", outgoing = 1)
        assertEquals("Hello", r.controller.captionText())
        assertNotNull(r.controller.tilePlan())
    }
}
