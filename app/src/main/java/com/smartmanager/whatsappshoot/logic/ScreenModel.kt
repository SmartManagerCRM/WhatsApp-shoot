package com.smartmanager.whatsappshoot.logic

/**
 * Minimal, content-free description of what is on screen. Built by the accessibility service.
 * It deliberately contains no message text. [headerText] is the visible conversation title and is
 * only used transiently to derive a salted hash (see [IdentityHasher]); it is never persisted or logged.
 */
class ScreenSnapshot(
    val packageName: String?,
    /** Short resource names ("entry", not "com.whatsapp:id/entry") of views currently on screen. */
    val viewIds: Set<String> = emptySet(),
    val headerText: String? = null,
    val windowClassName: String? = null,
    /** Number of outgoing media messages currently visible (evidence that something was sent). */
    val outgoingMediaCount: Int? = null,
    /** Share-flow details, only filled by the service while a share flow is running. */
    val share: ShareScreenView? = null,
    /** True if an editable text field (the message box) is on screen. Used by the structural fallback. */
    val hasEditableInput: Boolean = false,
    /** Text of the top-most title-like view in the upper part of the screen (the conversation title), if any. */
    val topTitle: String? = null,
) {
    // No toString() override that would print headerText.
    override fun toString(): String = "ScreenSnapshot(package=$packageName, window=$windowClassName, ids=${viewIds.size})"
}

enum class ScreenKind {
    /** A package we ignore (own app, system UI): must not change automation state. */
    IGNORED,
    NOT_WHATSAPP,
    CHAT_LIST,
    CONVERSATION,
    SETTINGS,
    CALLS,
    STATUS,
    MEDIA_VIEWER,
    ATTACHMENT_PICKER,
    /** WhatsApp is in front but the screen cannot be identified reliably. */
    UNKNOWN_WHATSAPP,
}

enum class ConversationType { INDIVIDUAL, GROUP, UNKNOWN }

data class ScreenClassification(
    val kind: ScreenKind,
    val conversationType: ConversationType = ConversationType.UNKNOWN,
    val headerText: String? = null,
    val outgoingMediaCount: Int? = null,
) {
    val isWhatsApp: Boolean
        get() = kind != ScreenKind.IGNORED && kind != ScreenKind.NOT_WHATSAPP
}

/**
 * Everything the classifier knows about WhatsApp's UI, kept as configuration rather than code so that
 * a WhatsApp update can be handled by changing data. IMPORTANT: the default identifiers below are
 * best-effort and have NOT been verified against a real device in this repository's history
 * (see docs/TESTING_REPORT.md). They must be calibrated on a device with `Settings > Diagnostics`.
 * When nothing matches, the classifier returns [ScreenKind.UNKNOWN_WHATSAPP] and the workflow falls
 * back to manual confirmation rather than guessing.
 */
data class WhatsAppProfile(
    /** Explicit list of supported variants (regular + Business). Edit here to add a variant. */
    val packages: Set<String> = setOf("com.whatsapp", "com.whatsapp.w4b"),
    val ignoredPackages: Set<String> = setOf("com.android.systemui"),
    val conversationClasses: Set<String> = setOf("com.whatsapp.Conversation"),
    val chatListClasses: Set<String> = setOf("com.whatsapp.HomeActivity"),
    val settingsClasses: Set<String> = setOf("com.whatsapp.settings.Settings", "com.whatsapp.SettingsActivity"),
    val mediaViewerClasses: Set<String> = setOf("com.whatsapp.mediaview.MediaView", "com.whatsapp.MediaView"),
    val pickerClasses: Set<String> = setOf("com.whatsapp.gallerypicker.GalleryPicker", "com.whatsapp.documentpicker.DocumentPickerActivity", "com.whatsapp.ContactPicker"),
    val conversationHeaderIds: Set<String> = setOf("conversation_contact_name"),
    val inputFieldIds: Set<String> = setOf("entry"),
    val chatListIds: Set<String> = setOf("conversations_row_contact_name"),
    val callsIds: Set<String> = setOf("calls_row_contact_name"),
    val statusIds: Set<String> = setOf("status_item_name"),
    val settingsIds: Set<String> = setOf("settings_row_title"),
    val mediaViewerIds: Set<String> = setOf("photo_view_pager", "media_view_root"),
    val pickerIds: Set<String> = setOf("pickfiletype_gallery_holder", "gallery_picker_root"),
    val groupMarkerIds: Set<String> = setOf("conversation_contact_status_group"),
    val individualMarkerIds: Set<String> = setOf("conversation_contact_status_individual"),
    /** Title views of the rows in WhatsApp's share (contact picker) list; used only by experimental direct selection. */
    val shareRowTitleIds: Set<String> = setOf("contactpicker_row_name"),
    /** Window class of WhatsApp's share target list. */
    val shareListClasses: Set<String> = setOf("com.whatsapp.ContactPicker"),
    /** WhatsApp's "send to" confirmation/preview screen after a recipient was chosen. */
    val sharePreviewClasses: Set<String> = setOf("com.whatsapp.mediaview.MediaComposerActivity", "com.whatsapp.mediacomposer.MediaComposerActivity"),
    /** View showing the recipient name on that screen. */
    val sharePreviewRecipientIds: Set<String> = setOf("recipients", "send_to_text"),
    /** WhatsApp's own send button; only ever tapped when fully automatic sending is enabled AND the recipient matched. */
    val sendButtonIds: Set<String> = setOf("send"),
    /** Recognise a conversation by structure (message box + title) when the ids/classes above do not match. */
    val structuralFallback: Boolean = true,
    /** Outgoing media message bubbles in a conversation. */
    val outgoingMediaIds: Set<String> = setOf("image_message_outgoing", "main_image_outgoing"),
)

class ScreenClassifier(private val profile: WhatsAppProfile = WhatsAppProfile(), private val ownPackage: String = "") {

    fun classify(s: ScreenSnapshot): ScreenClassification {
        val pkg = s.packageName ?: return ScreenClassification(ScreenKind.IGNORED)
        if (pkg == ownPackage || pkg in profile.ignoredPackages) return ScreenClassification(ScreenKind.IGNORED)
        if (pkg !in profile.packages) return ScreenClassification(ScreenKind.NOT_WHATSAPP)

        val ids = s.viewIds
        val cls = s.windowClassName
        fun idHit(set: Set<String>) = ids.any { it in set }
        fun clsHit(set: Set<String>) = cls != null && cls in set

        // Overlays that sit on top of a conversation take precedence: never auto-capture over them.
        if (clsHit(profile.pickerClasses) || idHit(profile.pickerIds)) return ScreenClassification(ScreenKind.ATTACHMENT_PICKER)
        if (clsHit(profile.mediaViewerClasses) || idHit(profile.mediaViewerIds)) return ScreenClassification(ScreenKind.MEDIA_VIEWER)

        val hasHeader = idHit(profile.conversationHeaderIds)
        val hasInput = idHit(profile.inputFieldIds)
        // Require two independent signals (screen identity + a structural view) to call it a conversation.
        val convSignals = listOf(clsHit(profile.conversationClasses), hasHeader, hasInput).count { it }
        if (convSignals >= 2 && (hasHeader || hasInput)) {
            val type = when {
                idHit(profile.groupMarkerIds) -> ConversationType.GROUP
                idHit(profile.individualMarkerIds) -> ConversationType.INDIVIDUAL
                else -> ConversationType.UNKNOWN
            }
            return ScreenClassification(ScreenKind.CONVERSATION, type, s.headerText, s.outgoingMediaCount)
        }
        if (clsHit(profile.settingsClasses) || idHit(profile.settingsIds)) return ScreenClassification(ScreenKind.SETTINGS)
        if (idHit(profile.callsIds)) return ScreenClassification(ScreenKind.CALLS)
        if (idHit(profile.statusIds)) return ScreenClassification(ScreenKind.STATUS)
        if (clsHit(profile.chatListClasses) || idHit(profile.chatListIds)) return ScreenClassification(ScreenKind.CHAT_LIST)
        // Structural fallback for when WhatsApp's identifiers are not the expected ones: a screen with a message box and a title at the
        // top is treated as a conversation. Deliberately last, so every recognised non-chat screen above wins.
        if (profile.structuralFallback && s.hasEditableInput && !s.topTitle.isNullOrBlank()) {
            return ScreenClassification(ScreenKind.CONVERSATION, ConversationType.UNKNOWN, s.topTitle, s.outgoingMediaCount)
        }
        return ScreenClassification(ScreenKind.UNKNOWN_WHATSAPP)
    }
}
