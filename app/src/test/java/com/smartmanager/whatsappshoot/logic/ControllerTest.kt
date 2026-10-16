package com.smartmanager.whatsappshoot.logic

import com.smartmanager.whatsappshoot.logic.AutomationState.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ControllerTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun rig(s: AutomationSettings = AutomationSettings(mode = CaptureMode.AUTOMATIC), licensed: Boolean = true) = Rig(tmp.newFolder(), s, licensed)

    // ---------------- WhatsApp detection scenarios ----------------

    @Test fun whatsAppClosed_nothingHappens() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(otherApp())
        assertEquals(WAITING_FOR_WHATSAPP, r.state); assertEquals(0, r.engine.calls)
    }

    @Test fun chatListOpen_noCapture() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(chatList()); r.scheduler.advance(60_000)
        assertEquals(WHATSAPP_DETECTED, r.state); assertEquals(0, r.engine.calls)
    }

    @Test fun individualConversation_automaticCaptureAfterSettle() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation())
        assertEquals(CONVERSATION_DETECTED, r.state); assertEquals(0, r.engine.calls)
        r.scheduler.advance(AutomationSettings.SETTLE_MS - 1); assertEquals(0, r.engine.calls)
        r.scheduler.advance(2)
        assertEquals(1, r.engine.calls); assertEquals(SCREENSHOT_READY, r.state)
        assertNotNull(r.controller.view.screenshot); assertEquals(1, r.shots())
    }

    @Test fun groupConversation_isDetectedAsGroup() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation("Family", group = true))
        assertEquals(ConversationType.GROUP, r.controller.view.conversationType)
    }

    @Test fun businessVariant_works() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation(pkg = "com.whatsapp.w4b")); r.scheduler.advance(5_000)
        assertEquals(SCREENSHOT_READY, r.state)
    }

    @Test fun repeatedEventsForSameScreen_captureOnce() {
        val r = rig(); r.controller.start()
        repeat(50) { r.controller.onScreen(conversation()); r.scheduler.advance(30) }
        r.scheduler.advance(10_000)
        assertEquals(1, r.engine.calls)
    }

    @Test fun eventsDuringSettleDoNotPostponeCapture() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation())
        r.scheduler.advance(500); r.controller.onScreen(conversation()); r.scheduler.advance(400)
        assertEquals(1, r.engine.calls)
    }

    @Test fun userChangesConversationBeforeCapture_timerRestartsForNewOne() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation("Alice")); r.scheduler.advance(500)
        r.controller.onScreen(conversation("Bob"))
        r.scheduler.advance(500); assertEquals("old timer cancelled", 0, r.engine.calls)
        r.scheduler.advance(400); assertEquals(1, r.engine.calls)
    }

    @Test fun userSwitchesApplicationBeforeCapture_noCapture() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(300)
        r.controller.onScreen(otherApp()); r.scheduler.advance(10_000)
        assertEquals(0, r.engine.calls); assertEquals(WAITING_FOR_WHATSAPP, r.state)
    }

    @Test fun ownUiAndSystemUiEventsDoNotResetAnything() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(300)
        r.controller.onScreen(ScreenSnapshot("com.smartmanager.whatsappshoot"))
        r.controller.onScreen(ScreenSnapshot("com.android.systemui"))
        r.scheduler.advance(600); assertEquals(1, r.engine.calls)
    }

    @Test fun previewScreenDoesNotTriggerRecursiveCapture() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(5_000)
        assertEquals(SCREENSHOT_READY, r.state)
        // user opens our preview, then goes back to the same conversation
        repeat(3) { r.controller.onScreen(ScreenSnapshot("com.smartmanager.whatsappshoot")); r.controller.onScreen(conversation()); r.scheduler.advance(10_000) }
        assertEquals(1, r.engine.calls); assertEquals(SCREENSHOT_READY, r.state)
    }

    @Test fun attachmentPickerOpen_noCaptureAndTimerCancelled() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(300)
        r.controller.onScreen(wa("conversation_contact_name", "entry", "pickfiletype_gallery_holder", cls = "com.whatsapp.Conversation"))
        r.scheduler.advance(10_000); assertEquals(0, r.engine.calls)
        r.controller.onScreen(conversation()); r.scheduler.advance(1_000)   // picker closed again
        assertEquals(1, r.engine.calls)
    }

    @Test fun leavingAndReturningToSameConversation_capturesAgain() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        r.controller.dismiss()
        r.controller.onScreen(chatList()); r.scheduler.advance(5_000)
        r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        assertEquals(2, r.engine.calls)
    }

    @Test fun unrecognisedWhatsAppScreen_noAutoCapture_andSaysSo() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(wa("something_new")); r.scheduler.advance(10_000)
        assertEquals(0, r.engine.calls); assertEquals(StatusMessage.CONVERSATION_NOT_RECOGNISED, r.message)
    }

    // ---------------- trigger modes & lifecycle ----------------

    @Test fun delayedMode_usesConfiguredDelay() {
        val r = rig(AutomationSettings(mode = CaptureMode.DELAYED, delayMs = 5_000)); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(4_999); assertEquals(0, r.engine.calls)
        r.scheduler.advance(2); assertEquals(1, r.engine.calls)
    }

    @Test fun delayIsClamped() {
        assertEquals(500, AutomationSettings(mode = CaptureMode.DELAYED, delayMs = 0).effectiveDelayMs)
        assertEquals(30_000, AutomationSettings(mode = CaptureMode.DELAYED, delayMs = 10_000_000).effectiveDelayMs)
    }

    @Test fun manualMode_neverCapturesAutomatically_butUserCan() {
        val r = rig(AutomationSettings(mode = CaptureMode.MANUAL)); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(60_000); assertEquals(0, r.engine.calls)
        assertTrue(r.controller.captureNow()); assertEquals(SCREENSHOT_READY, r.state)
    }

    @Test fun automaticCaptureDisabledSwitch() {
        val r = rig(AutomationSettings(mode = CaptureMode.AUTOMATIC, automaticCaptureEnabled = false)); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(60_000); assertEquals(0, r.engine.calls)
    }

    @Test fun stop_cancelsFutureCaptures_andDeletesScreenshot() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(300)
        r.controller.stop(); r.scheduler.advance(10_000)
        assertEquals(0, r.engine.calls); assertEquals(INACTIVE, r.state)
        r.controller.onScreen(conversation()); r.scheduler.advance(10_000)
        assertEquals("no capture while the session is disabled", 0, r.engine.calls)
        assertFalse(r.controller.captureNow())
    }

    @Test fun stopAfterCapture_removesScreenshotFile() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(1_000); assertEquals(1, r.shots())
        r.controller.stop(); assertEquals(0, r.shots()); assertNull(r.controller.view.screenshot)
    }

    @Test fun stopWhileCaptureInFlight_discardsResult() {
        val r = rig(); r.engine.deferred = true; r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        r.controller.stop(); r.engine.complete()
        assertEquals(INACTIVE, r.state); assertEquals(0, r.shots())
    }

    @Test fun pauseAndResume() {
        val r = rig(); r.controller.start()
        r.controller.onScreen(conversation()); r.scheduler.advance(300)
        r.controller.pause(); r.scheduler.advance(10_000)
        assertEquals(0, r.engine.calls); assertEquals(StatusMessage.PAUSED, r.message)
        r.controller.onScreen(conversation()); r.scheduler.advance(10_000); assertEquals("ignored while paused", 0, r.engine.calls)
        assertFalse(r.controller.captureNow())
        r.controller.resume(); r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        assertEquals(1, r.engine.calls)
    }

    @Test fun unlicensed_cannotStartOrCapture() {
        val r = rig(licensed = false)
        assertFalse(r.controller.start()); assertEquals(INACTIVE, r.state); assertEquals(StatusMessage.LICENSE_REQUIRED, r.message)
        assertEquals(0, r.engine.calls)
    }

    @Test fun licenseRevokedMidSession_stopsCapturing() {
        val r = rig(); r.controller.start(); r.licensed = false
        r.controller.onScreen(conversation()); r.scheduler.advance(5_000)
        assertEquals(0, r.engine.calls)
    }

    // ---------------- capture outcomes ----------------

    @Test fun capturePermissionRequired_thenGranted_retriesCapture() {
        val r = rig()
        r.engine.next = { CaptureResult.PermissionRequired }
        r.controller.start(); r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        assertEquals(CAPTURE_PERMISSION_REQUIRED, r.state); assertEquals(StatusMessage.CAPTURE_PERMISSION_REQUIRED, r.message)
        r.engine.next = null   // consent granted: capture now succeeds
        r.clock.advance(5_000)
        r.controller.onCapturePermissionGranted()
        assertEquals(SCREENSHOT_READY, r.state)
    }

    @Test fun capturePermissionDenied_isNotRequestedAgainAutomatically() {
        val r = rig(); r.engine.next = { CaptureResult.PermissionRequired }
        r.controller.start(); r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        r.controller.onCapturePermissionDenied()
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(StatusMessage.CAPTURE_PERMISSION_DENIED, r.message)
        val calls = r.engine.calls
        r.controller.onScreen(chatList()); r.controller.onScreen(conversation()); r.scheduler.advance(60_000)
        assertEquals("must not nag after denial", calls, r.engine.calls)
    }

    @Test fun permissionRevokedLater_surfacesAsPermissionRequiredAgain() {
        val r = rig(AutomationSettings(mode = CaptureMode.MANUAL)); r.controller.start()
        assertTrue(r.controller.captureNow()); r.controller.dismiss()
        r.clock.advance(5_000)
        r.engine.next = { CaptureResult.PermissionRequired }
        r.controller.captureNow()
        assertEquals(CAPTURE_PERMISSION_REQUIRED, r.state)
    }

    @Test fun protectedScreen_isReportedNotBypassed_andNotRetriedInALoop() {
        val r = rig(); r.engine.next = { CaptureResult.Protected }
        r.controller.start(); r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        assertEquals(ERROR, r.state); assertEquals(StatusMessage.PROTECTED_SCREEN, r.message)
        r.scheduler.advance(60_000); r.controller.onScreen(conversation()); r.scheduler.advance(60_000)
        assertEquals(1, r.engine.calls)
    }

    @Test fun temporaryStorageFailure_isReported() {
        val r = rig(); r.engine.next = { CaptureResult.Failed(FailureReason.STORAGE) }
        r.controller.start(); r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
        assertEquals(ERROR, r.state); assertEquals(StatusMessage.STORAGE_FAILED, r.message)
        r.controller.dismiss(); assertEquals(WAITING_FOR_WHATSAPP, r.state)
    }

    @Test fun lowMemoryAndSessionEndedFailures() {
        for (reason in listOf(FailureReason.MEMORY, FailureReason.SESSION_ENDED)) {
            val r = rig(); r.engine.next = { CaptureResult.Failed(reason) }
            r.controller.start(); r.controller.onScreen(conversation()); r.scheduler.advance(1_000)
            assertEquals(ERROR, r.state); assertEquals(StatusMessage.CAPTURE_FAILED, r.message)
        }
    }

    @Test fun deliberateRecaptureReplacesPreviousScreenshotFile() {
        val r = rig(AutomationSettings(mode = CaptureMode.MANUAL)); r.controller.start()
        r.controller.captureNow(); r.clock.advance(2_000); r.controller.captureNow()
        assertEquals(1, r.shots()); assertEquals(2, r.engine.calls)
    }

    @Test fun deleteScreenshot() {
        val r = rig(AutomationSettings(mode = CaptureMode.MANUAL)); r.controller.start(); r.controller.captureNow()
        r.controller.deleteScreenshot()
        assertEquals(0, r.shots()); assertNull(r.controller.view.screenshot); assertEquals(WAITING_FOR_WHATSAPP, r.state)
    }

    @Test fun dismissKeepsFileWhenConfigured() {
        val r = rig(AutomationSettings(mode = CaptureMode.MANUAL, deleteWhenWorkflowEnds = false)); r.controller.start(); r.controller.captureNow()
        r.controller.dismiss(); assertEquals(1, r.shots())
    }

    // ---------------- attachment workflow ----------------

    private fun captured(r: Rig, title: String? = "Alice") {
        r.controller.start(); r.controller.onScreen(conversation(title)); r.scheduler.advance(1_000)
        assertEquals(SCREENSHOT_READY, r.state)
    }

    @Test fun attachWithVerifiedDestination_opensShareAndStopsBeforeSending() {
        val r = rig(); captured(r)
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice")
        r.controller.requestAttach()
        assertEquals(ATTACHMENT_READY, r.state); assertEquals(1, r.launcher.calls); assertEquals(false, r.launcher.lastDirect)
        assertEquals(StatusMessage.ATTACHMENT_READY, r.message)
    }

    @Test fun userChangedConversationBeforeAttach_requiresManualAction_andNothingIsLaunched() {
        val r = rig(); captured(r)
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Bob")
        r.controller.requestAttach()
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(StatusMessage.DESTINATION_CHANGED, r.message); assertEquals(0, r.launcher.calls)
    }

    @Test fun destinationCannotBeIdentified_requiresManualConfirmation() {
        val r = rig(); captured(r, title = null)   // header not readable
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = null)
        r.controller.requestAttach()
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(StatusMessage.MANUAL_CONFIRMATION_REQUIRED, r.message); assertEquals(0, r.launcher.calls)
    }

    @Test fun whatsAppNotInFront_isUncertain_thenManualFallbackShares() {
        val r = rig(); captured(r)
        r.observer.screen = null
        r.controller.requestAttach()
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(0, r.launcher.calls)
        r.controller.confirmAndShareManually()
        assertEquals(ATTACHMENT_READY, r.state); assertEquals(1, r.launcher.calls); assertEquals(false, r.launcher.lastDirect)
        assertNotNull("screenshot preserved", r.controller.view.screenshot)
    }

    @Test fun attachmentWorkflowFails_isReportedHonestly_andScreenshotIsPreserved() {
        val r = rig(); captured(r)
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice")
        r.launcher.result = LaunchResult.Failed("blocked")
        r.controller.requestAttach()
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(StatusMessage.ATTACHMENT_FAILED, r.message)
        assertNotNull(r.controller.view.screenshot); assertEquals(1, r.shots())
    }

    @Test fun whatsAppMissing() {
        val r = rig(); captured(r); r.observer.screen = null; r.controller.requestAttach()
        r.launcher.result = LaunchResult.WhatsAppNotInstalled; r.controller.confirmAndShareManually()
        assertEquals(StatusMessage.WHATSAPP_NOT_INSTALLED, r.message); assertEquals(MANUAL_ACTION_REQUIRED, r.state)
    }

    @Test fun userCancelsBeforeSending_dismissCleansUp() {
        val r = rig(); captured(r)
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice"); r.controller.requestAttach()
        r.controller.dismiss()
        assertEquals(WAITING_FOR_WHATSAPP, r.state); assertEquals(0, r.shots())
    }

    @Test fun directSelect_onlyWhenEnabled_andVerified_andReportsOutcome() {
        val r = rig(AutomationSettings(mode = CaptureMode.AUTOMATIC, directSelectExperimental = true)); captured(r)
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice")
        r.launcher.result = LaunchResult.Launched(directSelectPending = true)
        r.controller.requestAttach()
        assertEquals(PREPARING_ATTACHMENT, r.state); assertEquals(true, r.launcher.lastDirect)
        r.controller.onDirectSelectionResult(DirectSelectOutcome.SELECTED)
        assertEquals(ATTACHMENT_READY, r.state)
    }

    @Test fun directSelectAmbiguousOrTimeout_fallsBackToManual() {
        for (outcome in listOf(DirectSelectOutcome.AMBIGUOUS, DirectSelectOutcome.NOT_FOUND)) {
            val r = rig(AutomationSettings(mode = CaptureMode.AUTOMATIC, directSelectExperimental = true)); captured(r)
            r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice")
            r.launcher.result = LaunchResult.Launched(true); r.controller.requestAttach()
            r.controller.onDirectSelectionResult(outcome)
            assertEquals(MANUAL_ACTION_REQUIRED, r.state)
        }
        val r = rig(AutomationSettings(mode = CaptureMode.AUTOMATIC, directSelectExperimental = true)); captured(r)
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice")
        r.launcher.result = LaunchResult.Launched(true); r.controller.requestAttach()
        r.scheduler.advance(AutomationController.DIRECT_SELECT_TIMEOUT_MS + 1)
        assertEquals("timeout", MANUAL_ACTION_REQUIRED, r.state)
    }

    @Test fun directSelectNeverUsedForUnverifiedDestination() {
        val r = rig(AutomationSettings(mode = CaptureMode.AUTOMATIC, directSelectExperimental = true)); captured(r)
        r.observer.screen = null
        r.controller.requestAttach(); r.controller.confirmAndShareManually()
        assertEquals(false, r.launcher.lastDirect)
    }

    @Test fun attachWithoutScreenshotIsIgnored() {
        val r = rig(); r.controller.start(); r.controller.requestAttach()
        assertEquals(0, r.launcher.calls); assertEquals(WAITING_FOR_WHATSAPP, r.state)
    }

    @Test fun controllerNeverReferencesTheConversationTitleInStatus() {
        val r = rig(); captured(r, "Super Secret Person")
        assertFalse(r.controller.view.toString().contains("Secret"))
    }
}
