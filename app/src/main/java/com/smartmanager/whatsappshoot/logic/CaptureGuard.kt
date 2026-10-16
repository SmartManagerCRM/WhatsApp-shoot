package com.smartmanager.whatsappshoot.logic

fun interface Clock { fun nowMs(): Long }

/**
 * Prevents duplicate captures: repeated accessibility events for one screen, parallel requests,
 * re-capture of a conversation that has not been left, and double taps on the capture button.
 *
 * "Visit" based rather than identity based, so it also works when the conversation title could not be read.
 */
class CaptureGuard(private val clock: Clock, private val cooldownMs: Long = DEFAULT_COOLDOWN_MS) {
    private var inFlight = false
    private var lastFinishedAt = Long.MIN_VALUE / 2
    private var lastUserBegin = Long.MIN_VALUE / 2
    private var visitHandled = false

    val isCapturing: Boolean get() = inFlight
    val currentVisitHandled: Boolean get() = visitHandled

    /** Returns true if a capture may start now, and marks one as in flight. */
    fun tryBegin(userInitiated: Boolean, useCooldown: Boolean = true): Boolean {
        val now = clock.nowMs()
        if (inFlight) return false
        if (userInitiated) {
            if (now - lastUserBegin < USER_DEBOUNCE_MS) return false // double tap
            lastUserBegin = now
        } else {
            if (visitHandled) return false
            if (useCooldown && now - lastFinishedAt < cooldownMs) return false
        }
        inFlight = true
        return true
    }

    /** Undo [tryBegin] when the capture could not actually start (e.g. state machine refused). */
    fun cancelStart() {
        inFlight = false
    }

    /** Failures also mark the visit as handled so a failing capture can never loop automatically. */
    fun finish() {
        inFlight = false
        lastFinishedAt = clock.nowMs()
        visitHandled = true
    }

    /** The user left (or switched) the conversation: a later visit may be captured again. */
    fun conversationLeft() {
        visitHandled = false
    }

    fun reset() {
        inFlight = false
        visitHandled = false
        lastFinishedAt = Long.MIN_VALUE / 2
        lastUserBegin = Long.MIN_VALUE / 2
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 3_000L
        const val USER_DEBOUNCE_MS = 700L
    }
}
