package com.smartmanager.whatsappshoot.logic

enum class ShareStage { SHARE_LIST, SHARE_PREVIEW, CONVERSATION, OTHER }

/** What the accessibility layer sees of WhatsApp while the share flow runs. Content-free except titles used for hashing. */
data class ShareScreenView(
    val stage: ShareStage,
    val rowTitles: List<String?> = emptyList(),
    /** Recipient label shown on WhatsApp's send/preview screen. */
    val previewRecipient: String? = null,
    val sendButtonAvailable: Boolean = false,
    /** Conversation title when [stage] is CONVERSATION. */
    val conversationHeader: String? = null,
    /** Number of outgoing media messages visible in the conversation (evidence of sending). */
    val outgoingMediaCount: Int? = null,
)

sealed class ShareAction {
    object Wait : ShareAction()
    data class ClickRow(val index: Int) : ShareAction()

    /** Only ever produced after the recipient on WhatsApp's own screen matched the verified chat. */
    object ClickSend : ShareAction()
    data class Done(val outcome: ShareOutcome) : ShareAction()
}

enum class ShareOutcome {
    ROW_SELECTED,
    SENT_CONFIRMED,
    SENT_UNCONFIRMED,
    ABORT_AMBIGUOUS,
    ABORT_NOT_FOUND,
    ABORT_RECIPIENT_MISMATCH,
    ABORT_RECIPIENT_UNVERIFIED,
    ABORT_SEND_UNAVAILABLE,
    ABORT_TIMEOUT,
    ABORT_STOPPED,
}

/**
 * Drives WhatsApp's share screens, step by step, from observed screens. Pure decision logic: the Android layer only
 * reports what it sees and performs the returned click.
 *
 * Safety rules enforced here (and tested):
 *  - a row is chosen only when exactly one row matches the verified conversation;
 *  - Send is clicked only when [allowSend] is true AND the recipient shown by WhatsApp itself matches that conversation;
 *  - success is reported as confirmed only when the conversation shows MORE outgoing media than the baseline taken before
 *    sending; otherwise the honest outcome is [ShareOutcome.SENT_UNCONFIRMED].
 */
class ShareFlowEngine(
    private val expected: ConversationIdentity?,
    private val hasher: IdentityHasher,
    private val allowSend: Boolean,
    private val baselineOutgoing: Int?,
) {
    private enum class Phase { PICK_ROW, ROW_CLICKED, AWAIT_PREVIEW, AWAIT_RESULT, FINISHED }

    private var phase = Phase.PICK_ROW
    private var sendClicked = false
    var finished = false
        private set

    fun step(v: ShareScreenView): ShareAction {
        if (finished) return ShareAction.Wait
        return when (phase) {
            Phase.PICK_ROW -> pick(v)
            Phase.ROW_CLICKED -> ShareAction.Wait
            Phase.AWAIT_PREVIEW -> preview(v)
            Phase.AWAIT_RESULT -> result(v)
            Phase.FINISHED -> ShareAction.Wait
        }
    }

    fun stop(): ShareAction.Done = finish(ShareOutcome.ABORT_STOPPED)
    fun timeout(): ShareAction.Done = finish(if (sendClicked) ShareOutcome.SENT_UNCONFIRMED else ShareOutcome.ABORT_TIMEOUT)

    private fun pick(v: ShareScreenView): ShareAction {
        if (v.stage != ShareStage.SHARE_LIST) return ShareAction.Wait
        return when (val c = ShareTargetSelector.choose(v.rowTitles, expected, hasher)) {
            is ShareTargetSelector.Choice.Single -> {
                phase = if (allowSend) Phase.AWAIT_PREVIEW else Phase.ROW_CLICKED
                ShareAction.ClickRow(c.index)
            }
            ShareTargetSelector.Choice.Ambiguous -> finish(ShareOutcome.ABORT_AMBIGUOUS)
            ShareTargetSelector.Choice.None -> finish(ShareOutcome.ABORT_NOT_FOUND)
        }
    }

    /** Call after the row click was performed. In select-only mode this ends the flow (the user presses Send). */
    fun afterClick(): ShareAction = if (phase == Phase.ROW_CLICKED) finish(ShareOutcome.ROW_SELECTED) else ShareAction.Wait

    private fun preview(v: ShareScreenView): ShareAction {
        if (v.stage == ShareStage.SHARE_LIST) return ShareAction.Wait // list still animating away
        if (v.stage != ShareStage.SHARE_PREVIEW) return ShareAction.Wait
        val recipient = hasher.identityOf(v.previewRecipient)
        if (!recipient.isKnown) return finish(ShareOutcome.ABORT_RECIPIENT_UNVERIFIED)
        if (expected == null || !expected.matches(recipient)) return finish(ShareOutcome.ABORT_RECIPIENT_MISMATCH)
        if (!v.sendButtonAvailable) return ShareAction.Wait
        sendClicked = true
        phase = Phase.AWAIT_RESULT
        return ShareAction.ClickSend
    }

    private fun result(v: ShareScreenView): ShareAction {
        if (v.stage == ShareStage.SHARE_PREVIEW || v.stage == ShareStage.SHARE_LIST) return ShareAction.Wait
        if (v.stage == ShareStage.CONVERSATION) {
            val same = expected?.matches(hasher.identityOf(v.conversationHeader)) == true
            val confirmed = same && baselineOutgoing != null && (v.outgoingMediaCount ?: -1) > baselineOutgoing
            return finish(if (confirmed) ShareOutcome.SENT_CONFIRMED else ShareOutcome.SENT_UNCONFIRMED)
        }
        return finish(ShareOutcome.SENT_UNCONFIRMED)
    }

    private fun finish(o: ShareOutcome): ShareAction.Done {
        phase = Phase.FINISHED; finished = true
        return ShareAction.Done(o)
    }
}
