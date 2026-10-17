package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachFlowTest {
    private val clock = FakeClock()
    private fun engine(baseline: Int? = 3) = AttachFlowEngine(baseline, clock)
    private fun tick() = clock.advance(AttachFlowEngine.CLICK_GAP_MS + 100)
    private val chat = AttachScreenView(chatVisible = true, attachButton = true, outgoingMediaCount = 3)

    @Test fun happyPath_attachGalleryAlbumThumbnailSend_thenConfirmedByNewBubble() {
        val e = engine()
        assertEquals(AttachAction.ClickAttach, e.step(chat)); tick()
        assertEquals(AttachAction.ClickGallery, e.step(AttachScreenView(chatVisible = true, galleryEntry = true))); tick()
        assertEquals(AttachAction.ClickAlbum, e.step(AttachScreenView(chatVisible = false, albumLabel = true))); tick()
        assertEquals(AttachAction.ClickThumbnail, e.step(AttachScreenView(chatVisible = false, thumbnail = true, albumLabel = true))); tick()
        assertEquals(AttachAction.ClickSend, e.step(AttachScreenView(chatVisible = false, sendButton = true))); tick()
        assertEquals(AttachAction.Done(ShareOutcome.SENT_CONFIRMED), e.step(chat.copy(outgoingMediaCount = 4)))
    }

    @Test fun neverClicksTwiceWithinTheClickGap() {
        val e = engine()
        assertEquals(AttachAction.ClickAttach, e.step(chat))
        assertEquals(AttachAction.Wait, e.step(AttachScreenView(chatVisible = true, galleryEntry = true)))
        tick()
        assertEquals(AttachAction.ClickGallery, e.step(AttachScreenView(chatVisible = true, galleryEntry = true)))
    }

    @Test fun doesNotStartUntilTheChatIsInFront() {
        val e = engine()
        assertEquals(AttachAction.Wait, e.step(AttachScreenView(chatVisible = false, attachButton = true)))
        clock.advance(AttachFlowEngine.OPEN_PATIENCE_MS + 100)
        assertEquals(AttachAction.Done(ShareOutcome.ABORT_NO_GALLERY), e.step(AttachScreenView(chatVisible = false, attachButton = true)))
    }

    @Test fun neverPicksAThumbnailBeforeTheScreenshotsAlbumIsOpen() {
        val e = engine()
        e.step(chat); tick(); e.step(AttachScreenView(chatVisible = true, galleryEntry = true)); tick()
        assertEquals(AttachAction.Wait, e.step(AttachScreenView(chatVisible = false, thumbnail = true)))
        clock.advance(AttachFlowEngine.PICKER_PATIENCE_MS + 100)
        assertEquals(AttachAction.Done(ShareOutcome.ABORT_NO_ALBUM), e.step(AttachScreenView(chatVisible = false, thumbnail = true)))
    }

    @Test fun albumsSwitcherIsUsedOnceToRevealTheAlbum() {
        val e = engine()
        e.step(chat); tick(); e.step(AttachScreenView(chatVisible = true, galleryEntry = true)); tick()
        assertEquals(AttachAction.ClickAlbumsSwitcher, e.step(AttachScreenView(chatVisible = false, albumsSwitcher = true))); tick()
        assertEquals(AttachAction.Wait, e.step(AttachScreenView(chatVisible = false, albumsSwitcher = true)))
        assertEquals(AttachAction.ClickAlbum, e.step(AttachScreenView(chatVisible = false, albumsSwitcher = true, albumLabel = true)))
    }

    @Test fun sendIsNeverClickedBeforeAPictureWasChosen_orWhileTheChatIsInFront() {
        val e = engine()
        e.step(chat); tick(); e.step(AttachScreenView(chatVisible = true, galleryEntry = true)); tick()
        e.step(AttachScreenView(chatVisible = false, albumLabel = true)); tick()
        // album opened, send button visible but no picture chosen yet
        assertEquals(AttachAction.Wait, e.step(AttachScreenView(chatVisible = false, sendButton = true)))
        e.step(AttachScreenView(chatVisible = false, thumbnail = true)); tick()
        // chat in front: its "send" is the voice-note button
        assertEquals(AttachAction.Wait, e.step(chat.copy(sendButton = true)))
    }

    @Test fun secondSendOnCaptionScreenIsAllowed_butLimited() {
        val e = engine()
        e.step(chat); tick(); e.step(AttachScreenView(chatVisible = true, galleryEntry = true)); tick()
        e.step(AttachScreenView(chatVisible = false, albumLabel = true)); tick()
        e.step(AttachScreenView(chatVisible = false, thumbnail = true)); tick()
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
        val e = engine()
        e.step(chat); tick(); e.step(AttachScreenView(chatVisible = true, galleryEntry = true)); tick()
        e.step(AttachScreenView(chatVisible = false, albumLabel = true)); tick()
        e.step(AttachScreenView(chatVisible = false, thumbnail = true)); tick()
        e.step(AttachScreenView(chatVisible = false, sendButton = true)); tick()
        assertEquals(AttachAction.Wait, e.step(chat))
        clock.advance(AttachFlowEngine.RESULT_GRACE_MS + 100)
        assertEquals(AttachAction.Done(ShareOutcome.SENT_UNCONFIRMED), e.step(chat))
    }

    @Test fun newBubbleAppearingLate_stillConfirms() {
        val e = engine()
        e.step(chat); tick(); e.step(AttachScreenView(chatVisible = true, galleryEntry = true)); tick()
        e.step(AttachScreenView(chatVisible = false, albumLabel = true)); tick()
        e.step(AttachScreenView(chatVisible = false, thumbnail = true)); tick()
        e.step(AttachScreenView(chatVisible = false, sendButton = true)); tick()
        assertEquals(AttachAction.Wait, e.step(chat))
        clock.advance(1_000)
        assertTrue(e.step(chat.copy(outgoingMediaCount = 4)) == AttachAction.Done(ShareOutcome.SENT_CONFIRMED))
    }

    @Test fun stopEndsTheFlow_andNothingMoreIsClicked() {
        val e = engine()
        e.step(chat)
        assertEquals(AttachAction.Done(ShareOutcome.ABORT_STOPPED), e.stop())
        assertEquals(AttachAction.Wait, e.step(chat))
    }
}
