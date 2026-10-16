package com.smartmanager.whatsappshoot.logic

import org.junit.Assert.*
import org.junit.Test

class ClassifierAndIdentityTest {
    private val c = ScreenClassifier(ownPackage = "com.smartmanager.whatsappshoot")
    private fun kind(s: ScreenSnapshot) = c.classify(s).kind

    @Test fun whatsAppClosedOrOtherApp() {
        assertEquals(ScreenKind.NOT_WHATSAPP, kind(otherApp()))
        assertEquals(ScreenKind.IGNORED, kind(ScreenSnapshot(null)))
    }

    @Test fun ownAppAndSystemUiAreIgnored() {
        assertEquals(ScreenKind.IGNORED, kind(ScreenSnapshot("com.smartmanager.whatsappshoot")))
        assertEquals(ScreenKind.IGNORED, kind(ScreenSnapshot("com.android.systemui")))
    }

    @Test fun chatListScreen() = assertEquals(ScreenKind.CHAT_LIST, kind(chatList()))

    @Test fun individualAndGroupConversation() {
        assertEquals(ConversationType.UNKNOWN, c.classify(conversation()).conversationType)
        val g = c.classify(conversation(group = true))
        assertEquals(ScreenKind.CONVERSATION, g.kind); assertEquals(ConversationType.GROUP, g.conversationType)
        val i = c.classify(wa("conversation_contact_name", "entry", "conversation_contact_status_individual", cls = "com.whatsapp.Conversation", header = "Bob"))
        assertEquals(ConversationType.INDIVIDUAL, i.conversationType)
    }

    @Test fun whatsAppBusinessIsSupportedExplicitly() {
        assertEquals(ScreenKind.CONVERSATION, kind(conversation(pkg = "com.whatsapp.w4b")))
        // A variant that is not configured is NOT treated as WhatsApp.
        assertEquals(ScreenKind.NOT_WHATSAPP, kind(conversation(pkg = "com.whatsapp.clone")))
        val custom = ScreenClassifier(WhatsAppProfile(packages = setOf("com.whatsapp", "com.whatsapp.clone")), "x")
        assertEquals(ScreenKind.CONVERSATION, custom.classify(conversation(pkg = "com.whatsapp.clone")).kind)
    }

    @Test fun otherWhatsAppScreens() {
        assertEquals(ScreenKind.SETTINGS, kind(wa(cls = "com.whatsapp.settings.Settings")))
        assertEquals(ScreenKind.CALLS, kind(wa("calls_row_contact_name")))
        assertEquals(ScreenKind.STATUS, kind(wa("status_item_name")))
        assertEquals(ScreenKind.MEDIA_VIEWER, kind(wa(cls = "com.whatsapp.mediaview.MediaView")))
    }

    @Test fun attachmentPickerOverConversationWins() {
        assertEquals(ScreenKind.ATTACHMENT_PICKER, kind(wa("conversation_contact_name", "entry", "pickfiletype_gallery_holder", cls = "com.whatsapp.Conversation")))
    }

    @Test fun unrecognisedWhatsAppScreenIsUnknownNotGuessed() {
        assertEquals(ScreenKind.UNKNOWN_WHATSAPP, kind(wa("something_new")))
        // One weak signal alone (only an input field, no screen identity) is not enough for a conversation.
        assertEquals(ScreenKind.UNKNOWN_WHATSAPP, kind(wa("entry")))
    }

    @Test fun snapshotToStringDoesNotLeakHeader() {
        assertFalse(conversation("Secret Name").toString().contains("Secret"))
    }

    // ---- identity ----
    @Test fun identityMatchesNormalisedTitles() {
        val h = IdentityHasher()
        assertTrue(h.identityOf("  Alice   Smith ").matches(h.identityOf("alice smith")))
        assertFalse(h.identityOf("Alice").matches(h.identityOf("Bob")))
        assertFalse(h.identityOf(null).matches(h.identityOf(null))) // unknown never "matches"
        assertFalse(h.identityOf("Alice").matches(null))
    }

    @Test fun differentSaltsGiveDifferentFingerprints() {
        assertNotEquals(IdentityHasher().fingerprintOf("Alice"), IdentityHasher().fingerprintOf("Alice"))
        assertFalse(IdentityHasher().fingerprintOf("Alice")!!.contains("alice"))
    }

    @Test fun identityToStringIsOpaque() {
        assertFalse(IdentityHasher().identityOf("Alice").toString().contains("Alice"))
    }

    @Test fun destinationVerifier() {
        val h = IdentityHasher(); val exp = h.identityOf("Alice")
        fun conv(t: String?) = ScreenClassification(ScreenKind.CONVERSATION, headerText = t)
        assertEquals(DestinationCheck.VERIFIED, DestinationVerifier.check(exp, conv("Alice"), h))
        assertEquals(DestinationCheck.CHANGED, DestinationVerifier.check(exp, conv("Bob"), h))
        assertEquals(DestinationCheck.CHANGED, DestinationVerifier.check(exp, ScreenClassification(ScreenKind.CHAT_LIST), h))
        assertEquals(DestinationCheck.UNCERTAIN, DestinationVerifier.check(exp, null, h))
        assertEquals(DestinationCheck.UNCERTAIN, DestinationVerifier.check(exp, ScreenClassification(ScreenKind.NOT_WHATSAPP), h))
        assertEquals(DestinationCheck.UNCERTAIN, DestinationVerifier.check(exp, conv(null), h))
        assertEquals(DestinationCheck.UNCERTAIN, DestinationVerifier.check(null, conv("Alice"), h))
        assertEquals(DestinationCheck.UNCERTAIN, DestinationVerifier.check(h.identityOf(null), conv("Alice"), h))
    }

    @Test fun shareTargetSelectorOnlyPicksExactlyOneMatch() {
        val h = IdentityHasher(); val exp = h.identityOf("Alice")
        assertEquals(ShareTargetSelector.Choice.Single(1), ShareTargetSelector.choose(listOf("Bob", "alice", null), exp, h))
        assertEquals(ShareTargetSelector.Choice.Ambiguous, ShareTargetSelector.choose(listOf("Alice", "ALICE"), exp, h))
        assertEquals(ShareTargetSelector.Choice.None, ShareTargetSelector.choose(listOf("Bob"), exp, h))
        assertEquals(ShareTargetSelector.Choice.None, ShareTargetSelector.choose(listOf("Alice"), h.identityOf(null), h))
        assertEquals(ShareTargetSelector.Choice.None, ShareTargetSelector.choose(listOf("Alice"), null, h))
    }
}
