package com.smartmanager.whatsappshoot.logic

import com.smartmanager.whatsappshoot.logic.AutomationEvent.*
import com.smartmanager.whatsappshoot.logic.AutomationState.*
import org.junit.Assert.*
import org.junit.Test

class StateMachineTest {
    private fun drive(vararg events: AutomationEvent): AutomationStateMachine =
        AutomationStateMachine().also { m -> events.forEach { assertTrue("$it rejected in ${m.state}", m.dispatch(it)) } }

    @Test fun startsInactive_andOnlyStartIsAccepted() {
        val m = AutomationStateMachine()
        assertEquals(INACTIVE, m.state)
        AutomationEvent.values().filter { it != START }.forEach { assertFalse("$it", m.dispatch(it)) }
        assertEquals(INACTIVE, m.state)
    }

    @Test fun happyPathThroughEveryState() {
        val m = drive(START, WHATSAPP_FOREGROUND, CONVERSATION_OPENED, CAPTURE_STARTED, CAPTURE_SUCCEEDED,
            VERIFY_REQUESTED, VERIFIED, ATTACHMENT_PREPARED)
        assertEquals(ATTACHMENT_READY, m.state)
    }

    @Test fun permissionPath() {
        val m = drive(START, WHATSAPP_FOREGROUND, CONVERSATION_OPENED, CAPTURE_PERMISSION_MISSING)
        assertEquals(CAPTURE_PERMISSION_REQUIRED, m.state)
        m.dispatch(CAPTURE_PERMISSION_DENIED); assertEquals(MANUAL_ACTION_REQUIRED, m.state)
    }

    @Test fun uncertainDestinationEndsInManualAction() {
        val m = drive(START, CONVERSATION_OPENED, CAPTURE_STARTED, CAPTURE_SUCCEEDED, VERIFY_REQUESTED, VERIFY_UNCERTAIN)
        assertEquals(MANUAL_ACTION_REQUIRED, m.state)
        m.dispatch(USER_CONFIRMED_DESTINATION); assertEquals(PREPARING_ATTACHMENT, m.state)
    }

    @Test fun stopFromEveryActiveStateReturnsToInactive() {
        for (s in AutomationState.values().filter { it != INACTIVE }) {
            assertEquals(INACTIVE, AutomationStateMachine.nextState(s, STOP))
        }
        assertNull(AutomationStateMachine.nextState(INACTIVE, STOP))
    }

    @Test fun observationEventsDoNotDisturbAWorkflowInProgress() {
        // Leaving WhatsApp for our own preview must not reset a finished capture (prevents capture loops).
        for (s in listOf(CAPTURING_SCREEN, SCREENSHOT_READY, VERIFYING_CONVERSATION, PREPARING_ATTACHMENT, ATTACHMENT_READY, MANUAL_ACTION_REQUIRED, ERROR)) {
            for (e in listOf(WHATSAPP_BACKGROUND, WHATSAPP_FOREGROUND, CONVERSATION_OPENED, CONVERSATION_CLOSED)) {
                assertNull("$s + $e", AutomationStateMachine.nextState(s, e))
            }
        }
    }

    @Test fun nothingSkipsVerificationOnTheWayToAttachment() {
        // The only way into PREPARING_ATTACHMENT is VERIFIED or an explicit user confirmation.
        val into = AutomationStateMachine.allTransitions().filter { it.third == PREPARING_ATTACHMENT }
        assertEquals(setOf(VERIFIED, USER_CONFIRMED_DESTINATION), into.map { it.second }.toSet())
        // ...and the only way into ATTACHMENT_READY is from PREPARING_ATTACHMENT.
        assertEquals(setOf(PREPARING_ATTACHMENT), AutomationStateMachine.allTransitions().filter { it.third == ATTACHMENT_READY }.map { it.first }.toSet())
    }

    @Test fun capturingOnlyStartsFromExpectedStates() {
        val from = AutomationStateMachine.allTransitions().filter { it.third == CAPTURING_SCREEN }.map { it.first }.toSet()
        assertEquals(setOf(WAITING_FOR_WHATSAPP, WHATSAPP_DETECTED, CONVERSATION_DETECTED, CAPTURE_PERMISSION_REQUIRED, SCREENSHOT_READY, ATTACHMENT_READY, MANUAL_ACTION_REQUIRED), from)
    }

    @Test fun pauseIsOrthogonalAndClearedOnStop() {
        val m = drive(START)
        assertTrue(m.pause()); assertTrue(m.paused); assertFalse(m.pause())
        assertTrue(m.resume()); assertFalse(m.resume())
        m.pause(); m.dispatch(STOP); assertFalse(m.paused)
        assertFalse(AutomationStateMachine().pause()) // cannot pause when inactive
    }

    @Test fun listenerSeesTransitions() {
        val seen = mutableListOf<Triple<AutomationState, AutomationState, AutomationEvent>>()
        val m = AutomationStateMachine().apply { listener = { f, t, e -> seen += Triple(f, t, e) } }
        m.dispatch(START); m.dispatch(START)
        assertEquals(listOf(Triple(INACTIVE, WAITING_FOR_WHATSAPP, START)), seen)
    }
}
