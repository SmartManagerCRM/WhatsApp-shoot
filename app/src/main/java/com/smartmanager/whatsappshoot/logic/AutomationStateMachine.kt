package com.smartmanager.whatsappshoot.logic

enum class AutomationState {
    INACTIVE,
    WAITING_FOR_WHATSAPP,
    WHATSAPP_DETECTED,
    CONVERSATION_DETECTED,
    CAPTURE_PERMISSION_REQUIRED,
    CAPTURING_SCREEN,
    SCREENSHOT_READY,
    VERIFYING_CONVERSATION,
    PREPARING_ATTACHMENT,
    ATTACHMENT_READY,
    MANUAL_ACTION_REQUIRED,
    ERROR,
}

enum class AutomationEvent {
    START,
    STOP,
    WHATSAPP_FOREGROUND,
    WHATSAPP_BACKGROUND,
    CONVERSATION_OPENED,
    CONVERSATION_CLOSED,
    CAPTURE_PERMISSION_MISSING,
    CAPTURE_PERMISSION_GRANTED,
    CAPTURE_STARTED,
    CAPTURE_SUCCEEDED,
    CAPTURE_FAILED,
    CAPTURE_PERMISSION_DENIED,
    VERIFY_REQUESTED,
    VERIFIED,
    VERIFY_UNCERTAIN,
    VERIFY_CHANGED,
    USER_CONFIRMED_DESTINATION,
    ATTACHMENT_PREPARED,
    ATTACHMENT_FAILED,
    DISMISS,
}

/**
 * Explicit, table-driven automation state machine.
 *
 * Documented transitions (docs/ARCHITECTURE.md contains the same table):
 *
 * ```
 * INACTIVE                    --START-->                    WAITING_FOR_WHATSAPP
 * (any state but INACTIVE)    --STOP-->                     INACTIVE
 * WAITING_FOR_WHATSAPP        --WHATSAPP_FOREGROUND-->      WHATSAPP_DETECTED
 * WAITING_FOR_WHATSAPP        --CONVERSATION_OPENED-->      CONVERSATION_DETECTED
 * WAITING_FOR_WHATSAPP        --CAPTURE_STARTED-->          CAPTURING_SCREEN   (explicit user capture only)
 * WHATSAPP_DETECTED           --CAPTURE_STARTED-->          CAPTURING_SCREEN   (explicit user capture only)
 * WHATSAPP_DETECTED           --CONVERSATION_OPENED-->      CONVERSATION_DETECTED
 * WHATSAPP_DETECTED           --WHATSAPP_BACKGROUND-->      WAITING_FOR_WHATSAPP
 * CONVERSATION_DETECTED       --CONVERSATION_CLOSED-->      WHATSAPP_DETECTED
 * CONVERSATION_DETECTED       --WHATSAPP_BACKGROUND-->      WAITING_FOR_WHATSAPP
 * CONVERSATION_DETECTED       --CAPTURE_PERMISSION_MISSING--> CAPTURE_PERMISSION_REQUIRED
 * CONVERSATION_DETECTED       --CAPTURE_STARTED-->          CAPTURING_SCREEN
 * CAPTURE_PERMISSION_REQUIRED --CAPTURE_PERMISSION_GRANTED--> CONVERSATION_DETECTED
 * CAPTURE_PERMISSION_REQUIRED --CAPTURE_PERMISSION_DENIED-->  MANUAL_ACTION_REQUIRED
 * CAPTURE_PERMISSION_REQUIRED --CONVERSATION_CLOSED/WHATSAPP_BACKGROUND--> WHATSAPP_DETECTED / WAITING_FOR_WHATSAPP
 * CAPTURE_PERMISSION_REQUIRED --CAPTURE_STARTED-->          CAPTURING_SCREEN   (permission arrived)
 * CAPTURING_SCREEN            --CAPTURE_SUCCEEDED-->        SCREENSHOT_READY
 * CAPTURING_SCREEN            --CAPTURE_FAILED-->           ERROR
 * CAPTURING_SCREEN            --CAPTURE_PERMISSION_MISSING--> CAPTURE_PERMISSION_REQUIRED
 * SCREENSHOT_READY            --VERIFY_REQUESTED-->         VERIFYING_CONVERSATION
 * SCREENSHOT_READY            --CAPTURE_STARTED-->          CAPTURING_SCREEN   (deliberate re-capture)
 * SCREENSHOT_READY            --DISMISS-->                  WAITING_FOR_WHATSAPP
 * VERIFYING_CONVERSATION      --VERIFIED-->                 PREPARING_ATTACHMENT
 * VERIFYING_CONVERSATION      --VERIFY_UNCERTAIN/VERIFY_CHANGED--> MANUAL_ACTION_REQUIRED
 * PREPARING_ATTACHMENT        --ATTACHMENT_PREPARED-->      ATTACHMENT_READY
 * PREPARING_ATTACHMENT        --ATTACHMENT_FAILED-->        MANUAL_ACTION_REQUIRED
 * ATTACHMENT_READY            --VERIFY_REQUESTED-->         VERIFYING_CONVERSATION (attach again)
 * ATTACHMENT_READY            --CAPTURE_STARTED-->          CAPTURING_SCREEN
 * ATTACHMENT_READY            --DISMISS-->                  WAITING_FOR_WHATSAPP
 * MANUAL_ACTION_REQUIRED      --USER_CONFIRMED_DESTINATION--> PREPARING_ATTACHMENT
 * MANUAL_ACTION_REQUIRED      --VERIFY_REQUESTED-->         VERIFYING_CONVERSATION
 * MANUAL_ACTION_REQUIRED      --CAPTURE_STARTED-->          CAPTURING_SCREEN
 * MANUAL_ACTION_REQUIRED      --DISMISS-->                  WAITING_FOR_WHATSAPP
 * ERROR                       --DISMISS-->                  WAITING_FOR_WHATSAPP
 * ```
 *
 * Observation events (WHATSAPP_*, CONVERSATION_*) are deliberately NOT accepted once a capture is under
 * way or finished (CAPTURING_SCREEN onward): the user leaving WhatsApp for our own preview screen must not
 * restart the workflow, which is what prevents recursive capture loops.
 *
 * Pausing is orthogonal ([paused]); while paused, observation events and automatic captures are ignored.
 * Not thread-safe by design; callers confine it to one thread (the main thread on Android).
 */
class AutomationStateMachine {
    var state: AutomationState = AutomationState.INACTIVE
        private set
    var paused: Boolean = false
        private set

    var listener: ((from: AutomationState, to: AutomationState, event: AutomationEvent) -> Unit)? = null

    /** @return true if the event caused a transition; false if it was not valid in the current state. */
    fun dispatch(event: AutomationEvent): Boolean {
        val next = nextState(state, event) ?: return false
        val prev = state
        state = next
        if (next == AutomationState.INACTIVE) paused = false
        listener?.invoke(prev, next, event)
        return true
    }

    fun pause(): Boolean {
        if (state == AutomationState.INACTIVE || paused) return false
        paused = true
        return true
    }

    fun resume(): Boolean {
        if (!paused) return false
        paused = false
        return true
    }

    companion object {
        private val S = AutomationState.values().toList()

        /** Pure transition function; null means "invalid in this state". */
        fun nextState(from: AutomationState, e: AutomationEvent): AutomationState? {
            if (e == AutomationEvent.STOP) return if (from == AutomationState.INACTIVE) null else AutomationState.INACTIVE
            return when (from) {
                AutomationState.INACTIVE -> if (e == AutomationEvent.START) AutomationState.WAITING_FOR_WHATSAPP else null
                AutomationState.WAITING_FOR_WHATSAPP -> when (e) {
                    AutomationEvent.WHATSAPP_FOREGROUND -> AutomationState.WHATSAPP_DETECTED
                    AutomationEvent.CONVERSATION_OPENED -> AutomationState.CONVERSATION_DETECTED
                    AutomationEvent.CAPTURE_STARTED -> AutomationState.CAPTURING_SCREEN
                    else -> null
                }
                AutomationState.WHATSAPP_DETECTED -> when (e) {
                    AutomationEvent.CONVERSATION_OPENED -> AutomationState.CONVERSATION_DETECTED
                    AutomationEvent.CAPTURE_STARTED -> AutomationState.CAPTURING_SCREEN
                    AutomationEvent.WHATSAPP_BACKGROUND -> AutomationState.WAITING_FOR_WHATSAPP
                    else -> null
                }
                AutomationState.CONVERSATION_DETECTED -> when (e) {
                    AutomationEvent.CONVERSATION_CLOSED -> AutomationState.WHATSAPP_DETECTED
                    AutomationEvent.WHATSAPP_BACKGROUND -> AutomationState.WAITING_FOR_WHATSAPP
                    AutomationEvent.CAPTURE_PERMISSION_MISSING -> AutomationState.CAPTURE_PERMISSION_REQUIRED
                    AutomationEvent.CAPTURE_STARTED -> AutomationState.CAPTURING_SCREEN
                    else -> null
                }
                AutomationState.CAPTURE_PERMISSION_REQUIRED -> when (e) {
                    AutomationEvent.CAPTURE_PERMISSION_GRANTED -> AutomationState.CONVERSATION_DETECTED
                    AutomationEvent.CAPTURE_STARTED -> AutomationState.CAPTURING_SCREEN
                    AutomationEvent.CAPTURE_PERMISSION_DENIED -> AutomationState.MANUAL_ACTION_REQUIRED
                    AutomationEvent.CONVERSATION_CLOSED -> AutomationState.WHATSAPP_DETECTED
                    AutomationEvent.WHATSAPP_BACKGROUND -> AutomationState.WAITING_FOR_WHATSAPP
                    AutomationEvent.DISMISS -> AutomationState.WAITING_FOR_WHATSAPP
                    else -> null
                }
                AutomationState.CAPTURING_SCREEN -> when (e) {
                    AutomationEvent.CAPTURE_SUCCEEDED -> AutomationState.SCREENSHOT_READY
                    AutomationEvent.CAPTURE_FAILED -> AutomationState.ERROR
                    AutomationEvent.CAPTURE_PERMISSION_MISSING -> AutomationState.CAPTURE_PERMISSION_REQUIRED
                    else -> null
                }
                AutomationState.SCREENSHOT_READY -> when (e) {
                    AutomationEvent.VERIFY_REQUESTED -> AutomationState.VERIFYING_CONVERSATION
                    AutomationEvent.CAPTURE_STARTED -> AutomationState.CAPTURING_SCREEN
                    AutomationEvent.DISMISS -> AutomationState.WAITING_FOR_WHATSAPP
                    else -> null
                }
                AutomationState.VERIFYING_CONVERSATION -> when (e) {
                    AutomationEvent.VERIFIED -> AutomationState.PREPARING_ATTACHMENT
                    AutomationEvent.VERIFY_UNCERTAIN, AutomationEvent.VERIFY_CHANGED -> AutomationState.MANUAL_ACTION_REQUIRED
                    else -> null
                }
                AutomationState.PREPARING_ATTACHMENT -> when (e) {
                    AutomationEvent.ATTACHMENT_PREPARED -> AutomationState.ATTACHMENT_READY
                    AutomationEvent.ATTACHMENT_FAILED -> AutomationState.MANUAL_ACTION_REQUIRED
                    else -> null
                }
                AutomationState.ATTACHMENT_READY -> when (e) {
                    AutomationEvent.VERIFY_REQUESTED -> AutomationState.VERIFYING_CONVERSATION
                    AutomationEvent.CAPTURE_STARTED -> AutomationState.CAPTURING_SCREEN
                    AutomationEvent.DISMISS -> AutomationState.WAITING_FOR_WHATSAPP
                    else -> null
                }
                AutomationState.MANUAL_ACTION_REQUIRED -> when (e) {
                    AutomationEvent.USER_CONFIRMED_DESTINATION -> AutomationState.PREPARING_ATTACHMENT
                    AutomationEvent.VERIFY_REQUESTED -> AutomationState.VERIFYING_CONVERSATION
                    AutomationEvent.CAPTURE_STARTED -> AutomationState.CAPTURING_SCREEN
                    AutomationEvent.DISMISS -> AutomationState.WAITING_FOR_WHATSAPP
                    else -> null
                }
                AutomationState.ERROR -> if (e == AutomationEvent.DISMISS) AutomationState.WAITING_FOR_WHATSAPP else null
            }
        }

        /** Every (state, event) pair that is valid, for documentation and exhaustive tests. */
        fun allTransitions(): List<Triple<AutomationState, AutomationEvent, AutomationState>> =
            S.flatMap { s -> AutomationEvent.values().mapNotNull { e -> nextState(s, e)?.let { Triple(s, e, it) } } }
    }
}
