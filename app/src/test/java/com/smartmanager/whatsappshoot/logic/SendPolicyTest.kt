package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SendPolicyTest {
    private val h = IdentityHasher(ByteArray(16) { 3 })
    private val alice = h.identityOf("Alice")
    private val bob = h.identityOf("Bob")
    private val unknown = h.identityOf(null)
    private val clock = FakeClock()
    private fun img(key: String, dateMs: Long = 1000) =
        LatestImage(cand(1, clock, takenMs = dateMs), key, false)

    @Test fun everyChatOpen_alwaysProceeds_evenWhenSentBefore_evenWithoutIdentity() {
        val l = InMemoryLedger(); l.record(alice.key!!, "img1", 1000, LedgerStatus.SENT_CONFIRMED, 1)
        assertEquals(SendDecision.Proceed, SendPolicy.decide(SendBehavior.EVERY_CHAT_OPEN, alice, img("img1"), l))
        assertEquals(SendDecision.Proceed, SendPolicy.decide(SendBehavior.EVERY_CHAT_OPEN, unknown, img("img1"), l))
    }

    @Test fun oncePerChat_firstTimeThenSkipUntilReset_perChat() {
        val l = InMemoryLedger()
        assertEquals(SendDecision.Proceed, SendPolicy.decide(SendBehavior.ONCE_PER_CHAT, alice, img("a"), l))
        l.record(alice.key!!, "a", 1000, LedgerStatus.PREPARED, 1)
        assertEquals(SendDecision.Skip(SkipReason.ALREADY_HANDLED_CHAT), SendPolicy.decide(SendBehavior.ONCE_PER_CHAT, alice, img("brand-new"), l))
        assertEquals("another chat is independent", SendDecision.Proceed, SendPolicy.decide(SendBehavior.ONCE_PER_CHAT, bob, img("a"), l))
        l.resetChat(alice.key!!)
        assertEquals(SendDecision.Proceed, SendPolicy.decide(SendBehavior.ONCE_PER_CHAT, alice, img("a"), l))
    }

    @Test fun newScreenshotOnly_dedupesImagePerChat_andRequiresNewer() {
        val l = InMemoryLedger()
        assertEquals(SendDecision.Proceed, SendPolicy.decide(SendBehavior.NEW_SCREENSHOT_ONLY, alice, img("a", 1000), l))
        l.record(alice.key!!, "a", 1000, LedgerStatus.SENT_CONFIRMED, 1)
        assertEquals(SendDecision.Skip(SkipReason.SAME_IMAGE_ALREADY_HANDLED), SendPolicy.decide(SendBehavior.NEW_SCREENSHOT_ONLY, alice, img("a", 1000), l))
        assertEquals("same image to another chat is fine", SendDecision.Proceed, SendPolicy.decide(SendBehavior.NEW_SCREENSHOT_ONLY, bob, img("a", 1000), l))
        assertEquals(SendDecision.Proceed, SendPolicy.decide(SendBehavior.NEW_SCREENSHOT_ONLY, alice, img("b", 2000), l))
        assertEquals("an older image that was never sent is not 'new'", SendDecision.Skip(SkipReason.NOT_NEWER), SendPolicy.decide(SendBehavior.NEW_SCREENSHOT_ONLY, alice, img("old", 500), l))
    }

    @Test fun identicalContentDifferentFile_isTheSameImage() {
        // identity is the content hash: a copy of an already-handled image has the same key
        val l = InMemoryLedger(); l.record(alice.key!!, "samehash", 1000, LedgerStatus.PREPARED, 1)
        assertTrue(SendPolicy.decide(SendBehavior.NEW_SCREENSHOT_ONLY, alice, img("samehash", 5000), l) is SendDecision.Skip)
    }

    @Test fun unreliableIdentity_requiresManualConfirmationInBAndC_neverMergedWithAnotherChat() {
        val l = InMemoryLedger()
        for (b in listOf(SendBehavior.ONCE_PER_CHAT, SendBehavior.NEW_SCREENSHOT_ONLY)) {
            assertEquals(SendDecision.NeedManual(ManualReason.IDENTITY_UNRELIABLE), SendPolicy.decide(b, unknown, img("a"), l))
            assertEquals(SendDecision.NeedManual(ManualReason.IDENTITY_UNRELIABLE), SendPolicy.decide(b, null, img("a"), l))
        }
        assertFalse("too-short titles are not an identity", h.identityOf("A").isKnown)
    }

    @Test fun statusIsUpgradedNotDowngraded_andCapacityEvictsOldest() {
        val l = InMemoryLedger(capacity = 3)
        l.record("c", "i", 1, LedgerStatus.SENT_CONFIRMED, 1); l.record("c", "i", 1, LedgerStatus.PREPARED, 2)
        assertEquals(1, l.size())
        for (n in 1..5) l.record("c$n", "i", n.toLong(), LedgerStatus.PREPARED, n.toLong())
        assertEquals(3, l.size()); assertFalse(l.chatHasAny("c")); assertTrue(l.chatHasAny("c5"))
    }

    @Test fun persistentLedgerRoundTripAndCorruptionTolerance() {
        class Mem(var text: String? = null) : LedgerStorage { override fun read() = text; override fun write(text: String) { this.text = text } }
        val store = Mem()
        PersistentLedger(store).apply { record("chatA", "img1", 123, LedgerStatus.SENT_CONFIRMED, 9); record("chatB", "img2", 456, LedgerStatus.PREPARED, 10) }
        val back = PersistentLedger(store)
        assertTrue(back.hasHandled("chatA", "img1")); assertEquals(456L, back.newestImageDateMs("chatB"))
        assertFalse("no names are stored", store.text!!.contains("Alice"))
        store.text = "garbage\nchatA,img1,notanumber,PREPARED,1\n,,,,\nchatC,img3,5,BOGUS,1\nchatD,img4,7,PREPARED,2"
        val tolerant = PersistentLedger(store)
        assertEquals(1, tolerant.size()); assertTrue(tolerant.hasHandled("chatD", "img4"))
        tolerant.resetAll(); assertEquals("", store.text)
    }
}
