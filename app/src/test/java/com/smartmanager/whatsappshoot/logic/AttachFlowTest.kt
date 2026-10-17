package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachFlowTest {
    private val clock = FakeClock()
    private fun engine(baseline: Int? = 3) = AttachFlowEngine(baseline, clock)
    private fun tick() = clock.advance(AttachFlowEngine.CLICK_GAP_MS + 100)
    private val chat = AttachScreenView(chatVisible = true, attachButton = true, outgoingMediaCount = 3)
    private val menu = AttachScreenView(chatVisible = false, galleryEntry = true)
    private val galleryRecents = AttachScreenView(chatVisible = false, pickerOpen = true, albumsSwitcher = true, thumbnail = true)
    private val dropdown = galleryRecents.copy(albumLabel = true)
    private val galleryScreenshots = galleryRecents.copy(albumSelected = true)
    private val withSend = galleryScreenshots.copy(sendButton = true)

    /** Drives the engine up to the thumbnail click. */
    private fun AttachFlowEngine.toThumbnail() {
        assertEquals(AttachAction.ClickAttach, step(chat)); tick()
        assertEquals(AttachAction.ClickGallery, step(menu)); tick()
        assertEquals(AttachAction.ClickAlbumsSwitcher, step(galleryRecents)); tick()
        assertEquals(AttachAction.ClickAlbum, step(dropdown)); tick()
        assertEquals(AttachAction.Wait, step(galleryScreenshots))
        clock.advance(AttachFlowEngine.ALBUM_SETTLE_MS + 100)
        assertEquals(AttachAction.ClickThumbnail, step(galleryScreenshots)); tick()
    }

    @Test fun happyPath_attachGalleryDropdownAlbumThumbnailSend_thenConfirmedByNewBubble() {
        val e = engine(); e.toThumbnail()
        assertEquals(AttachAction.ClickSend, e.step(withSend)); tick()
        assertEquals(AttachAction.Done(ShareOutcome.SENT_CONFIRMED), e.step(chat.copy(outgoingMediaCount = 4)))
    }

    @Test fun neverClicksTwiceWithinTheClickGap() {
        val e = engine()
        assertEquals(AttachAction.ClickAttach, e.step(chat))
        assertEquals(AttachAction.Wait, e.step(menu))
        tick()
        assertEquals(AttachAction.ClickGallery, e.step(menu))
    }

    @Test fun doesNotStartUntilTheChatIsClearOfOverlays() {
        val e = engine()
        // chat title/message box visible BEHIND an open gallery is not "chat in front"
        assertEquals(AttachAction.Wait, e.step(AttachScreenView(chatVisible = false, attachButton = true, pickerOpen = true)))
        clock.advance(AttachFlowEngine.OPEN_PATIENCE_MS + 100)
        assertEquals(AttachAction.Done(ShareOutcome.ABORT_NO_GALLERY), e.step(AttachScreenView(chatVisible = false, attachButton = true)))
    }

    @Test fun neverPicksAThumbnailBeforeTheAlbumIsVerified() {
        val e = engine()
        e.step(chat); tick(); e.step(menu); tick()
        // tiles are there, but the gallery shows Recents (some other album): no tile may be tapped
        repeat(30) { clock.advance(400); val a = e.step(galleryRecents.copy(albumsSwitcher = false)); assertTrue(a !is AttachAction.ClickThumbnail && a !is AttachAction.ClickSend); if (e.finished) return@repeat }
        assertTrue(e.finished)
        clock.advance(10); assertEquals(AttachAction.Wait, e.step(galleryRecents))
    }

    @Test fun albumMissingEverywhere_abortsNoAlbum() {
        val e = engine()
        e.step(chat); tick(); e.step(menu); tick()
        var done: AttachAction? = null
        repeat(40) { clock.advance(500); val a = e.step(galleryRecents); if (a is AttachAction.Done) done = a; assertTrue(a !is AttachAction.ClickThumbnail) }
        assertEquals(AttachAction.Done(ShareOutcome.ABORT_NO_ALBUM), done)
    }

    @Test fun albumAlreadySelected_skipsTheDropdown() {
        val e = engine()
        e.step(chat); tick(); e.step(menu); tick()
        assertEquals(AttachAction.Wait, e.step(galleryScreenshots))
        clock.advance(AttachFlowEngine.ALBUM_SETTLE_MS + 100)
        assertEquals(AttachAction.ClickThumbnail, e.step(galleryScreenshots))
    }

    @Test fun gridIsGivenTimeToReloadAfterTheAlbumChanges() {
        val e = engine()
        e.step(chat); tick(); e.step(menu); tick(); e.step(galleryRecents); tick(); e.step(dropdown); tick()
        assertEquals(AttachAction.Wait, e.step(galleryScreenshots))
        clock.advance(AttachFlowEngine.ALBUM_SETTLE_MS / 2)
        assertEquals(AttachAction.Wait, e.step(galleryScreenshots))
    }

    @Test fun sendIsNeverClickedBeforeAPictureWasChosen_orWhileTheChatIsInFront() {
        val e = engine()
        e.step(chat); tick(); e.step(menu); tick(); e.step(galleryRecents); tick(); e.step(dropdown); tick()
        e.step(galleryScreenshots); clock.advance(AttachFlowEngine.ALBUM_SETTLE_MS + 100)
        // album verified, send button visible, but no picture chosen yet: the next action is the tile, never Send
        assertEquals(AttachAction.ClickThumbnail, e.step(withSend)); tick()
        // chat in front: its corner buttons are not Send
        assertEquals(AttachAction.Wait, e.step(chat.copy(sendButton = true)))
    }

    @Test fun albumListOpenButAlbumFurtherDown_isScrolled() {
        val e = engine()
        e.step(chat); tick(); e.step(menu); tick()
        assertEquals(AttachAction.ClickAlbumsSwitcher, e.step(galleryRecents)); tick()
        val open = galleryRecents.copy(canScroll = true, albumsSwitcher = false)
        clock.advance(AttachFlowEngine.ALBUM_LOOK_MS + 100)
        assertEquals(AttachAction.ScrollPicker, e.step(open)); tick()
        assertEquals(AttachAction.ClickAlbum, e.step(open.copy(albumLabel = true)))
    }

    @Test fun secondSendOnCaptionScreenIsAllowed_butLimited() {
        val e = engine(); e.toThumbnail()
        val send = AttachScreenView(chatVisible = false, sendButton = true)
        assertEquals(AttachAction.ClickSend, e.step(send))
        clock.advance(AttachFlowEngine.SECOND_SEND_GAP_MS + 100)
        assertEquals(AttachAction.ClickSend, e.step(send))
        clock.advance(AttachFlowEngine.SECOND_SEND_GAP_MS + 100)
        assertEquals(AttachAction.ClickSend, e.step(send))
        clock.advance(AttachFlowEngine.SECOND_SEND_GAP_MS + 100)
        assertEquals("at most three Send taps", AttachAction.Wait, e.step(send))
    }

    @Test fun backInChatWithoutNewBubble_isUnconfirmed_afterGrace() {
        val e = engine(); e.toThumbnail()
        e.step(withSend); tick()
        assertEquals(AttachAction.Wait, e.step(chat))
        clock.advance(AttachFlowEngine.RESULT_GRACE_MS + 100)
        assertEquals(AttachAction.Done(ShareOutcome.SENT_UNCONFIRMED), e.step(chat))
    }

    @Test fun newBubbleAppearingLate_stillConfirms() {
        val e = engine(); e.toThumbnail()
        e.step(withSend); tick()
        assertEquals(AttachAction.Wait, e.step(chat))
        clock.advance(1_000)
        assertEquals(AttachAction.Done(ShareOutcome.SENT_CONFIRMED), e.step(chat.copy(outgoingMediaCount = 4)))
    }

    @Test fun stopEndsTheFlow_andNothingMoreIsClicked() {
        val e = engine()
        e.step(chat)
        assertEquals(AttachAction.Done(ShareOutcome.ABORT_STOPPED), e.stop())
        assertEquals(AttachAction.Wait, e.step(chat))
    }
}
