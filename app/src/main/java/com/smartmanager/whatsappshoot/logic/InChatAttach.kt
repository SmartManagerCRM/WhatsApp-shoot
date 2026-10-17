package com.smartmanager.whatsappshoot.logic

/**
 * What the accessibility layer sees of WhatsApp while the in-chat attach flow runs (content-free: only booleans and one count).
 * The destination is the chat that is already open, so no recipient list is involved.
 */
data class AttachScreenView(
    /**
     * The verified chat is in front AND nothing is laid over it (no attach menu, no gallery panel). WhatsApp Business shows its gallery
     * as a panel inside the chat, so the chat's title and message box stay visible while the gallery is open.
     */
    val chatVisible: Boolean,
    val attachButton: Boolean = false,
    /** The "Gallery" entry of the attach menu is visible. */
    val galleryEntry: Boolean = false,
    /** WhatsApp's gallery (picture grid) is open. */
    val pickerOpen: Boolean = false,
    /** The gallery's album dropdown shows the wanted album (e.g. "Screenshots") as the current one. */
    val albumSelected: Boolean = false,
    /** The wanted album is listed as a choice (the dropdown is open). */
    val albumLabel: Boolean = false,
    /** The gallery's album dropdown control is available. */
    val albumsSwitcher: Boolean = false,
    /** A picture tile of the gallery grid is available to tap (camera tile excluded). */
    val thumbnail: Boolean = false,
    val sendButton: Boolean = false,
    /** The open dropdown list can be scrolled further. */
    val canScroll: Boolean = false,
    val outgoingMediaCount: Int? = null,
)

sealed class AttachAction {
    object Wait : AttachAction()
    object ClickAttach : AttachAction()
    object ClickGallery : AttachAction()
    object ClickAlbum : AttachAction()
    object ClickAlbumsSwitcher : AttachAction()
    object ClickThumbnail : AttachAction()

    /** Scroll the open album list forward one page. */
    object ScrollPicker : AttachAction()

    /** Only ever produced after the image was picked from the verified album, and never while the chat itself is in front. */
    object ClickSend : AttachAction()
    data class Done(val outcome: ShareOutcome) : AttachAction()
}

/**
 * Sends the staged screenshot from inside the already-open chat: attach (+) -> Gallery -> album dropdown -> Screenshots -> newest picture -> Send.
 * Pure decision logic (timing via [Clock]); the Android layer reports what it sees and performs the returned click.
 *
 * Safety rules enforced here (and tested):
 *  - the flow only starts while the verified chat is in front, so the destination is the chat itself;
 *  - a picture is chosen only after the gallery's album dropdown shows the Screenshots album, or (landing mode) after the phone's media library confirmed that the screenshot is the newest picture (otherwise a newer photo or a received image could be sent);
 *  - Send is clicked only after a picture was chosen and never while the chat itself is in front;
 *  - success is [ShareOutcome.SENT_CONFIRMED] only if the chat shows MORE outgoing media than before; otherwise SENT_UNCONFIRMED.
 */
class AttachFlowEngine(
    private val baselineOutgoing: Int?,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    /** The screenshot was verified to be the newest picture on the phone: the first tile of WhatsApp's landing gallery is it. */
    private val landingFirst: Boolean = false,
) {
    private enum class Phase { OPEN_ATTACH, AWAIT_MENU, AWAIT_PICKER, AWAIT_SEND, AWAIT_RESULT, FINISHED }

    private var phase = Phase.OPEN_ATTACH
    private var phaseSince = -1L
    private var lastClickAt = -1_000_000L
    private var attachClicks = 0
    private var albumClicks = 0
    private var switcherClicks = 0
    private var lastSwitcherAt = -1_000_000L
    private var albumVerifiedAt = -1L
    private var sendClicks = 0
    private var chatBackAt = -1L
    private var scrolls = 0

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
        note = "phase=$phase chat=${v.chatVisible} attach=${v.attachButton} gallery=${v.galleryEntry} open=${v.pickerOpen} albumSel=${v.albumSelected} album=${v.albumLabel} spinner=${v.albumsSwitcher} thumb=${v.thumbnail} send=${v.sendButton}"
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
        if (landingFirst) {
            // The landing gallery's first tile is the newest picture, which was verified to be the screenshot.
            if (!v.pickerOpen) return if (now - phaseSince > PICKER_PATIENCE_MS) finish(ShareOutcome.ABORT_NO_IMAGE) else AttachAction.Wait
            if (albumVerifiedAt < 0) { albumVerifiedAt = now; phaseSince = now }
            if (now - albumVerifiedAt < ALBUM_SETTLE_MS) return AttachAction.Wait
            if (v.thumbnail) { go(Phase.AWAIT_SEND, now); return click(AttachAction.ClickThumbnail, now) }
            return if (now - phaseSince > PICKER_PATIENCE_MS) finish(ShareOutcome.ABORT_NO_IMAGE) else AttachAction.Wait
        }
        // 1. The album must be the wanted one, as shown by the gallery itself.
        if (!v.albumSelected) {
            albumVerifiedAt = -1
            when {
                v.albumLabel && albumClicks < MAX_ALBUM_CLICKS -> { albumClicks++; return click(AttachAction.ClickAlbum, now) }
                v.albumsSwitcher && v.pickerOpen && !v.albumLabel && switcherClicks < MAX_SWITCHER_CLICKS && now - lastSwitcherAt >= SWITCHER_RETRY_MS -> {
                    switcherClicks++; lastSwitcherAt = now
                    return click(AttachAction.ClickAlbumsSwitcher, now)
                }
                // The album list is open but the album is further down.
                v.albumLabel.not() && switcherClicks > 0 && v.canScroll && now - lastSwitcherAt >= ALBUM_LOOK_MS && scrolls < MAX_SCROLLS -> {
                    scrolls++; return click(AttachAction.ScrollPicker, now)
                }
            }
            return if (now - phaseSince > PICKER_PATIENCE_MS) finish(ShareOutcome.ABORT_NO_ALBUM) else AttachAction.Wait
        }
        // 2. Album verified: let the grid reload, then take the newest picture.
        if (albumVerifiedAt < 0) { albumVerifiedAt = now; phaseSince = now }
        if (now - albumVerifiedAt < ALBUM_SETTLE_MS) return AttachAction.Wait
        if (v.thumbnail && v.pickerOpen) {
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
        const val PICKER_PATIENCE_MS = 10_000L
        const val ALBUM_LOOK_MS = 1_500L
        const val ALBUM_SETTLE_MS = 1_000L
        const val SWITCHER_RETRY_MS = 2_500L
        const val MAX_SWITCHER_CLICKS = 3
        const val MAX_ALBUM_CLICKS = 3
        const val MAX_SCROLLS = 6
        const val SEND_PATIENCE_MS = 6_000L
        const val RESULT_GRACE_MS = 3_000L
        const val SECOND_SEND_GAP_MS = 1_500L
        const val MAX_SEND_CLICKS = 3
    }
}
