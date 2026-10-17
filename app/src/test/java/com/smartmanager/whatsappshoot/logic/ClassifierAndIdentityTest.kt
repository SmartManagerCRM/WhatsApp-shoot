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

    // ---- structural fallback (WhatsApp ids/classes not as configured) ----
    private fun unknownIds(edit: Boolean, title: String?, cls: String? = "com.whatsapp.SomethingNew", ids: Set<String> = setOf("x1", "x2")) =
        ScreenSnapshot("com.whatsapp", ids, null, cls, hasEditableInput = edit, topTitle = title)

    @Test fun structuralFallback_recognisesAConversationFromItsStructure() {
        val r = c.classify(unknownIds(edit = true, title = "Alice"))
        assertEquals(ScreenKind.CONVERSATION, r.kind); assertEquals("Alice", r.headerText)
    }

    @Test fun structuralFallbackNeedsBothMessageBoxAndTitle() {
        assertEquals(ScreenKind.UNKNOWN_WHATSAPP, c.classify(unknownIds(edit = true, title = null)).kind)
        assertEquals(ScreenKind.UNKNOWN_WHATSAPP, c.classify(unknownIds(edit = false, title = "Alice")).kind)
        assertEquals(ScreenKind.UNKNOWN_WHATSAPP, c.classify(unknownIds(edit = true, title = "  ")).kind)
    }

    @Test fun structuralFallbackNeverOverridesRecognisedScreens() {
        assertEquals(ScreenKind.CHAT_LIST, c.classify(unknownIds(true, "Chats", cls = "com.whatsapp.HomeActivity")).kind)
        assertEquals(ScreenKind.SETTINGS, c.classify(unknownIds(true, "Settings", cls = "com.whatsapp.settings.Settings")).kind)
        assertEquals(ScreenKind.ATTACHMENT_PICKER, c.classify(unknownIds(true, "Share", cls = "com.whatsapp.ContactPicker")).kind)
        assertEquals(ScreenKind.MEDIA_VIEWER, c.classify(unknownIds(true, "Photo", cls = "com.whatsapp.mediaview.MediaView")).kind)
    }

    @Test fun structuralFallbackCanBeSwitchedOff() {
        val strict = ScreenClassifier(WhatsAppProfile(structuralFallback = false), "x")
        assertEquals(ScreenKind.UNKNOWN_WHATSAPP, strict.classify(unknownIds(true, "Alice")).kind)
    }

    // ---- screens observed on a real phone (WhatsApp Business 2.26.34.74, Android 16) ----
    @Test fun realWhatsAppBusinessScreens() {
        val chatList = ScreenSnapshot("com.whatsapp.w4b", setOf("conversations_row_contact_name", "contact_selector", "fab"), null, "com.whatsapp.home.ui.HomeActivity")
        assertEquals(ScreenKind.CHAT_LIST, c.classify(chatList).kind)
        val chat = ScreenSnapshot("com.whatsapp.w4b", setOf("conversation_contact_name", "entry", "input_attach_button"), "Alice", "com.whatsapp.Conversation", hasEditableInput = true, topTitle = "Alice")
        assertEquals(ScreenKind.CONVERSATION, c.classify(chat).kind)
        val picker = ScreenSnapshot("com.whatsapp.w4b", setOf("contactpicker_row_name", "menuitem_search"), null, "com.whatsapp.contact.ui.picker.ExternalShareContactPicker")
        assertEquals(ScreenKind.ATTACHMENT_PICKER, c.classify(picker).kind)
        // the same screen once a chat is selected has a message box (caption) and a title: it must still be the picker, not a chat
        val pickerWithSendBar = ScreenSnapshot("com.whatsapp.w4b", setOf("recipients", "send", "appended_message"), null,
            "com.whatsapp.contact.ui.picker.ExternalShareContactPicker", hasEditableInput = true, topTitle = "Send to")
        assertEquals(ScreenKind.ATTACHMENT_PICKER, c.classify(pickerWithSendBar).kind)
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
