package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.*
import org.junit.Test

class ShareFlowTest {
    private val h = IdentityHasher(ByteArray(16) { 5 })
    private val alice = h.identityOf("Alice")
    private fun list(vararg titles: String?) = ShareScreenView(ShareStage.SHARE_LIST, rowTitles = titles.toList())
    private fun preview(recipient: String?, send: Boolean = true) = ShareScreenView(ShareStage.SHARE_PREVIEW, previewRecipient = recipient, sendButtonAvailable = send)
    private fun chat(title: String?, outgoing: Int?) = ShareScreenView(ShareStage.CONVERSATION, conversationHeader = title, outgoingMediaCount = outgoing)
    private fun done(a: ShareAction) = (a as ShareAction.Done).outcome

    @Test fun selectOnly_clicksOneRowAndNeverSends() {
        val e = ShareFlowEngine(alice, h, allowSend = false, baselineOutgoing = 0)
        assertEquals(ShareAction.Wait, e.step(chat("Alice", 0)))
        assertEquals(ShareAction.ClickRow(1), e.step(list("Bob", "alice")))
        assertEquals(ShareOutcome.ROW_SELECTED, done(e.afterClick()))
        assertEquals(ShareAction.Wait, e.step(preview("Alice")))      // finished: nothing more happens
    }

    @Test fun ambiguousOrMissingRowAborts() {
        assertEquals(ShareOutcome.ABORT_AMBIGUOUS, done(ShareFlowEngine(alice, h, true, 0).step(list("Alice", "ALICE"))))
        assertEquals(ShareOutcome.ABORT_NOT_FOUND, done(ShareFlowEngine(h.identityOf(null), h, true, 0).step(list("Alice"))))
        // a list that simply does not contain the chat is only given up on after it had time to load
        val clock = FakeClock(); val e = ShareFlowEngine(alice, h, true, 0, clock)
        assertEquals(ShareAction.Wait, e.step(list("Bob")))
        clock.advance(ShareFlowEngine.LOAD_MS + ShareFlowEngine.SETTLE_MS + 1)
        assertEquals(ShareOutcome.ABORT_NOT_FOUND, done(e.step(list("Bob"))))
    }

    @Test fun fullSend_onlyAfterRecipientMatches_thenConfirmedByMoreOutgoingMedia() {
        val e = ShareFlowEngine(alice, h, allowSend = true, baselineOutgoing = 2)
        assertEquals(ShareAction.ClickRow(0), e.step(list("Alice", "Bob")))
        assertEquals(ShareAction.Wait, e.step(preview("Alice", send = false)))   // button not there yet
        assertEquals(ShareAction.ClickSend, e.step(preview("Alice")))
        assertEquals(ShareAction.Wait, e.step(preview("Alice")))                  // still sending
        assertEquals(ShareOutcome.SENT_CONFIRMED, done(e.step(chat("Alice", 3))))
    }

    @Test fun sendWithoutEvidenceIsReportedUnconfirmed() {
        fun run(view: ShareScreenView, baseline: Int?): ShareOutcome {
            val e = ShareFlowEngine(alice, h, true, baseline)
            e.step(list("Alice")); e.step(preview("Alice"))
            return done(e.step(view))
        }
        assertEquals(ShareOutcome.SENT_UNCONFIRMED, run(chat("Alice", 2), 2))        // count did not grow
        assertEquals(ShareOutcome.SENT_UNCONFIRMED, run(chat("Alice", null), 2))     // cannot count
        assertEquals(ShareOutcome.SENT_UNCONFIRMED, run(chat("Alice", 5), null))     // no baseline
        assertEquals(ShareOutcome.SENT_UNCONFIRMED, run(chat("Bob", 9), 2))          // different chat
        assertEquals(ShareOutcome.SENT_UNCONFIRMED, run(ShareScreenView(ShareStage.OTHER), 2))
    }

    @Test fun recipientMismatchOrUnreadableNeverSends() {
        val e1 = ShareFlowEngine(alice, h, true, 0); e1.step(list("Alice"))
        assertEquals(ShareOutcome.ABORT_RECIPIENT_MISMATCH, done(e1.step(preview("Bob"))))
        for (blank in listOf(null, "  ")) {
            val e = ShareFlowEngine(alice, h, true, 0); e.step(list("Alice"))
            // the recipient may simply not have rendered yet: wait a little, then give up without ever sending
            repeat(ShareFlowEngine.PREVIEW_PATIENCE - 1) { assertEquals(ShareAction.Wait, e.step(preview(blank))) }
            assertEquals(ShareOutcome.ABORT_RECIPIENT_UNVERIFIED, done(e.step(preview(blank))))
        }
    }

    private fun previewWithTexts(texts: List<String?>, send: Boolean = true) =
        ShareScreenView(ShareStage.SHARE_PREVIEW, previewRecipient = null, sendButtonAvailable = send, visibleTexts = texts)

    @Test fun recipientVerifiedFromVisibleTextsWhenNoLabelIdIsKnown() {
        val e = ShareFlowEngine(alice, h, true, 1); e.step(list("Alice"))
        assertEquals(ShareAction.ClickSend, e.step(previewWithTexts(listOf("Add a caption", "alice", "Send"))))
    }

    @Test fun otherNamesOnlyNeverSend() {
        val e = ShareFlowEngine(alice, h, true, 1); e.step(list("Alice"))
        repeat(ShareFlowEngine.PREVIEW_PATIENCE - 1) { assertEquals(ShareAction.Wait, e.step(previewWithTexts(listOf("Bob", "Add a caption")))) }
        assertEquals(ShareOutcome.ABORT_RECIPIENT_UNVERIFIED, done(e.step(previewWithTexts(listOf("Bob", "Add a caption")))))
    }

    @Test fun nameRendersLate_stillSendsOnceItAppears() {
        val e = ShareFlowEngine(alice, h, true, 1); e.step(list("Alice"))
        assertEquals(ShareAction.Wait, e.step(previewWithTexts(listOf("Add a caption"))))
        assertEquals(ShareAction.ClickSend, e.step(previewWithTexts(listOf("Add a caption", "Alice"))))
    }

    @Test fun explicitLabelWinsOverVisibleTexts() {
        // if WhatsApp's own recipient label says Bob, a stray "Alice" elsewhere on the screen must not allow Send
        val e = ShareFlowEngine(alice, h, true, 1); e.step(list("Alice"))
        val v = ShareScreenView(ShareStage.SHARE_PREVIEW, previewRecipient = "Bob", sendButtonAvailable = true, visibleTexts = listOf("Alice", "Bob"))
        assertEquals(ShareOutcome.ABORT_RECIPIENT_MISMATCH, done(e.step(v)))
    }

    @Test fun noSendWhenButtonMissing_evenIfRecipientMatches() {
        val e = ShareFlowEngine(alice, h, true, 1); e.step(list("Alice"))
        assertEquals(ShareAction.Wait, e.step(previewWithTexts(listOf("Alice"), send = false)))
    }

    @Test fun timeoutAndStop() {
        val e = ShareFlowEngine(alice, h, true, 0); e.step(list("Alice"))
        assertEquals(ShareOutcome.ABORT_TIMEOUT, e.timeout().outcome)
        assertEquals(ShareAction.Wait, e.step(preview("Alice")))
        val s = ShareFlowEngine(alice, h, true, 0); s.step(list("Alice")); s.step(preview("Alice"))
        assertEquals("timeout after Send was tapped is not a failure but is unconfirmed", ShareOutcome.SENT_UNCONFIRMED, s.timeout().outcome)
        val t = ShareFlowEngine(alice, h, true, 0)
        assertEquals(ShareOutcome.ABORT_STOPPED, t.stop().outcome)
        assertEquals(ShareAction.Wait, t.step(list("Alice")))
    }

    @Test fun clickSendNeverEmittedFromAnyOtherPath() {
        val e = ShareFlowEngine(alice, h, allowSend = false, baselineOutgoing = 0)
        e.step(list("Alice")); e.afterClick()
        for (v in listOf(preview("Alice"), chat("Alice", 9), list("Alice"))) assertNotEquals(ShareAction.ClickSend, e.step(v))
    }

    // ---- behaviour observed on a real phone: the recipient list fills in after the screen opens ----
    private fun scrollable(vararg titles: String?, canScroll: Boolean = true) = ShareScreenView(ShareStage.SHARE_LIST, rowTitles = titles.toList(), canScroll = canScroll)

    @Test fun aListThatIsStillLoading_isNeverJudged_andTheChatIsFoundOnceItAppears() {
        val clock = FakeClock(); val e = ShareFlowEngine(alice, h, true, 0, clock)
        assertEquals(ShareAction.Wait, e.step(scrollable("Recent chats")))               // only a header and a placeholder so far
        clock.advance(2_000); assertEquals(ShareAction.Wait, e.step(scrollable("Recent chats")))
        clock.advance(200);   assertEquals(ShareAction.ClickRow(2), e.step(scrollable("Recent chats", "Bob", "Alice")))
    }

    @Test fun chatBelowTheVisibleRows_isFoundByScrolling() {
        val clock = FakeClock(); val e = ShareFlowEngine(alice, h, true, 0, clock)
        e.step(scrollable("Recent chats", "Bob", "Carol"))
        clock.advance(ShareFlowEngine.LOAD_MS + ShareFlowEngine.SETTLE_MS + 1)
        assertEquals(ShareAction.ScrollList, e.step(scrollable("Recent chats", "Bob", "Carol")))
        clock.advance(100)
        // once the chat is visible it is clicked right away (a click targets the row itself, not a screen position)
        assertEquals(ShareAction.ClickRow(2), e.step(scrollable("Dave", "Erin", "Alice")))
    }

    @Test fun reachingTheEndOfTheListWithoutTheChat_stopsInsteadOfScrollingForever() {
        val clock = FakeClock(); val e = ShareFlowEngine(alice, h, true, 0, clock)
        val rows = arrayOf<String?>("Bob", "Carol", "Dave")
        e.step(scrollable(*rows)); clock.advance(ShareFlowEngine.LOAD_MS + ShareFlowEngine.SETTLE_MS + 1)
        assertEquals(ShareAction.ScrollList, e.step(scrollable(*rows)))
        clock.advance(ShareFlowEngine.SETTLE_MS + 1)                                   // scrolled, but the rows did not change: end of list
        assertEquals(ShareOutcome.ABORT_NOT_FOUND, done(e.step(scrollable(*rows))))
    }

    @Test fun neverScrollsMoreThanTheLimit() {
        val clock = FakeClock(); val e = ShareFlowEngine(alice, h, true, 0, clock)
        e.step(scrollable("r0")); clock.advance(ShareFlowEngine.LOAD_MS + ShareFlowEngine.SETTLE_MS + 1)
        var scrolls = 0
        for (page in 1..20) {
            val a = e.step(scrollable("r$page", "x$page"))
            if (a == ShareAction.ScrollList) { scrolls++; clock.advance(ShareFlowEngine.SETTLE_MS + 1) } else if (a is ShareAction.Done) break else clock.advance(ShareFlowEngine.SETTLE_MS + 1)
        }
        assertTrue("scrolls=$scrolls", scrolls <= ShareFlowEngine.MAX_SCROLLS)
    }

    @Test fun recipientLabelMustNameExactlyTheVerifiedChat() {
        fun run(label: String): ShareAction {
            val e = ShareFlowEngine(alice, h, true, 1); e.step(list("Alice"))
            return e.step(ShareScreenView(ShareStage.SHARE_PREVIEW, previewRecipient = label, sendButtonAvailable = true))
        }
        assertEquals(ShareAction.ClickSend, run("Alice"))
        assertEquals(ShareAction.ClickSend, run("  alice "))
        assertEquals(ShareAction.Done(ShareOutcome.ABORT_RECIPIENT_MISMATCH), run("Alice, Bob"))   // an extra recipient is never acceptable
        assertEquals(ShareAction.Done(ShareOutcome.ABORT_RECIPIENT_MISMATCH), run("Bob"))
        assertEquals(ShareAction.Done(ShareOutcome.ABORT_RECIPIENT_MISMATCH), run("Alice\u060C Bob"))
    }

    @Test fun theWholeFlowOnOneScreenClass_listThenSendBarThenBackInTheChat() {
        // WhatsApp keeps list and send bar in the same activity; only the presence of the send bar tells the stages apart.
        val clock = FakeClock(); val e = ShareFlowEngine(alice, h, true, 2, clock)
        assertEquals(ShareAction.ClickRow(1), e.step(scrollable("Bob", "Alice", "Carol")))
        assertEquals(ShareAction.Wait, e.step(scrollable("Bob", "Alice", "Carol")))                  // click registered, bar not shown yet
        assertEquals(ShareAction.ClickSend, e.step(ShareScreenView(ShareStage.SHARE_PREVIEW, previewRecipient = "Alice", sendButtonAvailable = true)))
        assertEquals(ShareOutcome.SENT_CONFIRMED, done(e.step(ShareScreenView(ShareStage.CONVERSATION, conversationHeader = "Alice", outgoingMediaCount = 3))))
    }
}
