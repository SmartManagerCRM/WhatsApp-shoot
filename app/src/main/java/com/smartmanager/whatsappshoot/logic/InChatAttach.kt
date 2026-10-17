package com.smartmanager.whatsappshoot.logic

/**
 * What the accessibility layer sees of WhatsApp while the in-chat attach flow runs (content-free: only booleans and one count).
 * The destination is the chat that is already open, so no recipient list is involved.
 */
data class AttachScreenView(
    /** The verified chat is in front: its title at the top and a message box. */
    val chatVisible: Boolean,
    val attachButton: Boolean = false,
    /** The "Gallery" entry of the attach menu is visible. */
    val galleryEntry: Boolean = false,
    /** A "Screenshots" (or configured folder) album label is visible. */
    val albumLabel: Boolean = false,
    /** An "Albums"-style switcher is visible (used only to reveal the album label). */
    val albumsSwitcher: Boolean = false,
    /** A picture thumbnail is available to tap (camera tile excluded). */
    val thumbnail: Boolean = false,
    val sendButton: Boolean = false,
    val outgoingMediaCount: Int? = null,
)

sealed class AttachAction {
    object Wait : AttachAction()
    object ClickAttach : AttachAction()
    object ClickGallery : AttachAction()
    object ClickAlbum : AttachAction()
    object ClickAlbumsSwitcher : AttachAction()
    object ClickThumbnail : AttachAction()

    /** Only ever produced after the image was picked from the screenshots album, and never while the chat itself is in front. */
    object ClickSend : AttachAction()
    data class Done(val outcome: ShareOutcome) : AttachAction()
}

/**
 * Sends the staged screenshot from inside the already-open chat: attach (+) -> Gallery -> Screenshots album -> newest picture -> Send.
 * Pure decision logic (timing via [Clock]); the Android layer reports what it sees and performs the returned click.
 *
 * Safety rules enforced here (and tested):
 *  - the flow only starts while the verified chat is in front, so the destination is the chat itself;
 *  - a picture is chosen only after the Screenshots album was opened (otherwise a newer photo or received image could be sent);
 *  - Send is clicked only after a picture was chosen and never while the chat screen is in front (that button is the voice-note button);
 *  - success is [ShareOutcome.SENT_CONFIRMED] only if the chat shows MORE outgoing media than before; otherwise SENT_UNCONFIRMED.
 */
class AttachFlowEngine(
    private val baselineOutgoing: Int?,
    private val clock: Clock = Clock { System.currentTimeMillis() },
) {
    private enum class Phase { OPEN_ATTACH, AWAIT_MENU, AWAIT_PICKER, AWAIT_SEND, AWAIT_RESULT, FINISHED }

    private var phase = Phase.OPEN_ATTACH
    private var phaseSince = -1L
    private var lastClickAt = -1_000_000L
    private var attachClicks = 0
    private var albumClicked = false
    private var switcherClicked = false
    private var sendClicks = 0
    private var chatBackAt = -1L

    var note: String = ""
        private set
    var finished = false
        private set

    fun stop(): AttachAction.Done = finish(ShareOutcome.ABORT_STOPPED)
    fun timeout(): AttachAction.Done = finish(if (sendClicks > 0) ShareOutcome.SENT_UNCONFIRMED else ShareOutcome.ABORT_TIMEOUT)

    fun step(v: AttachScreenView): AttachAction {
        if (finished) return AttachAction.Wait
        val now = clock.nowMs()
        if (phaseSince < 0) phaseSince = now
        val a = when (phase) {
            Phase.OPEN_ATTACH -> openAttach(v, now)
            Phase.AWAIT_MENU -> awaitMenu(v, now)
            Phase.AWAIT_PICKER -> awaitPicker(v, now)
            Phase.AWAIT_SEND -> awaitSend(v, now)
            Phase.AWAIT_RESULT -> awaitResult(v, now)
            Phase.FINISHED -> AttachAction.Wait
        }
        note = "phase=$phase attach=${v.attachButton} gallery=${v.galleryEntry} album=${v.albumLabel} thumb=${v.thumbnail} send=${v.sendButton} chat=${v.chatVisible}"
        return a
    }

    private fun go(p: Phase, now: Long) { phase = p; phaseSince = now }
    private fun click(a: AttachAction, now: Long): AttachAction { lastClickAt = now; return a }
    private fun settled(now: Long) = now - lastClickAt >= CLICK_GAP_MS

    private fun openAttach(v: AttachScreenView, now: Long): AttachAction {
        if (v.chatVisible && v.attachButton && settled(now)) {
            attachClicks++
            go(Phase.AWAIT_MENU, now)
            return click(AttachAction.ClickAttach, now)
        }
        return if (now - phaseSince > OPEN_PATIENCE_MS) finish(ShareOutcome.ABORT_NO_GALLERY) else AttachAction.Wait
    }

    private fun awaitMenu(v: AttachScreenView, now: Long): AttachAction {
        if (v.galleryEntry && settled(now)) {
            go(Phase.AWAIT_PICKER, now)
            return click(AttachAction.ClickGallery, now)
        }
        if (now - phaseSince > MENU_PATIENCE_MS) {
            // The menu did not open (the first tap may have been swallowed): try the attach button once more, then give up.
            if (attachClicks < 2 && v.chatVisible && v.attachButton) { attachClicks++; phaseSince = now; return click(AttachAction.ClickAttach, now) }
            return finish(ShareOutcome.ABORT_NO_GALLERY)
        }
        return AttachAction.Wait
    }

    private fun awaitPicker(v: AttachScreenView, now: Long): AttachAction {
        if (!settled(now)) return AttachAction.Wait
        if (!albumClicked) {
            when {
                v.albumLabel -> { albumClicked = true; phaseSince = now; return click(AttachAction.ClickAlbum, now) }
                v.albumsSwitcher && !switcherClicked -> { switcherClicked = true; return click(AttachAction.ClickAlbumsSwitcher, now) }
            }
            return if (now - phaseSince > PICKER_PATIENCE_MS) finish(ShareOutcome.ABORT_NO_ALBUM) else AttachAction.Wait
        }
        if (v.thumbnail) {
            go(Phase.AWAIT_SEND, now)
            return click(AttachAction.ClickThumbnail, now)
        }
        return if (now - phaseSince > PICKER_PATIENCE_MS) finish(ShareOutcome.ABORT_NO_IMAGE) else AttachAction.Wait
    }

    private fun awaitSend(v: AttachScreenView, now: Long): AttachAction {
        if (v.sendButton && !v.chatVisible && settled(now)) {
            sendClicks++
            go(Phase.AWAIT_RESULT, now)
            return click(AttachAction.ClickSend, now)
        }
        return if (now - phaseSince > SEND_PATIENCE_MS) finish(ShareOutcome.ABORT_SEND_UNAVAILABLE) else AttachAction.Wait
    }

    private fun awaitResult(v: AttachScreenView, now: Long): AttachAction {
        if (v.chatVisible) {
            if (chatBackAt < 0) chatBackAt = now
            val confirmed = baselineOutgoing != null && (v.outgoingMediaCount ?: -1) > baselineOutgoing
            // The new bubble can appear a moment after the chat is back: give it time before reporting "unconfirmed".
            return when {
                confirmed -> finish(ShareOutcome.SENT_CONFIRMED)
                now - chatBackAt >= RESULT_GRACE_MS -> finish(ShareOutcome.SENT_UNCONFIRMED)
                else -> AttachAction.Wait
            }
        }
        chatBackAt = -1
        // Selecting a picture can lead to a second screen (caption/preview) with its own Send button.
        if (v.sendButton && sendClicks < MAX_SEND_CLICKS && now - lastClickAt >= SECOND_SEND_GAP_MS) {
            sendClicks++
            return click(AttachAction.ClickSend, now)
        }
        return AttachAction.Wait
    }

    private fun finish(o: ShareOutcome): AttachAction.Done {
        phase = Phase.FINISHED; finished = true
        return AttachAction.Done(o)
    }

    companion object {
        const val CLICK_GAP_MS = 700L
        const val OPEN_PATIENCE_MS = 6_000L
        const val MENU_PATIENCE_MS = 3_000L
        const val PICKER_PATIENCE_MS = 6_000L
        const val SEND_PATIENCE_MS = 6_000L
        const val RESULT_GRACE_MS = 3_000L
        const val SECOND_SEND_GAP_MS = 1_500L
        const val MAX_SEND_CLICKS = 3
    }
}
