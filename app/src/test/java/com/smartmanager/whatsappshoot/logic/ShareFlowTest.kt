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
        assertEquals(ShareOutcome.ABORT_NOT_FOUND, done(ShareFlowEngine(alice, h, true, 0).step(list("Bob"))))
        assertEquals(ShareOutcome.ABORT_NOT_FOUND, done(ShareFlowEngine(h.identityOf(null), h, true, 0).step(list("Alice"))))
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
        val e2 = ShareFlowEngine(alice, h, true, 0); e2.step(list("Alice"))
        assertEquals(ShareOutcome.ABORT_RECIPIENT_UNVERIFIED, done(e2.step(preview(null))))
        val e3 = ShareFlowEngine(alice, h, true, 0); e3.step(list("Alice"))
        assertEquals(ShareOutcome.ABORT_RECIPIENT_UNVERIFIED, done(e3.step(preview("  "))))
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
}
