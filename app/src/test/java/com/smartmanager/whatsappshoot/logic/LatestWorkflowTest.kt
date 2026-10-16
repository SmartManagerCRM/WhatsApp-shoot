package com.smartmanager.whatsappshoot.logic

import com.smartmanager.whatsappshoot.logic.AutomationState.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LatestWorkflowTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun rig(b: SendBehavior = SendBehavior.ONCE_PER_CHAT, mode: CaptureMode = CaptureMode.AUTOMATIC, auto: Boolean = false) =
        LatestRig(tmp.newFolder(), b, mode, auto).also { it.controller.start() }

    /** Chat is verified and WhatsApp's share screen was opened for the image. */
    private fun LatestRig.handedOff() = state == ATTACHMENT_READY && launcher.calls > 0

    // ---- basics ----
    @Test fun neverTakesANewScreenshot_usesLatestExistingOne() {
        val r = rig(); r.source.add(1)
        r.open("Alice")   // LatestRig's engine throws if capture() were called
        assertTrue(r.handedOff()); assertEquals(1, r.launcher.calls); assertEquals(1, r.provider.finds)
        assertEquals(1, r.repo.list().size)
    }

    @Test fun noScreenshotsAvailable() {
        val r = rig(); r.open("Alice")
        assertEquals(CONVERSATION_DETECTED, r.state); assertEquals(StatusMessage.NO_SCREENSHOT, r.message); assertEquals(0, r.launcher.calls)
        r.scheduler.advance(60_000); assertEquals("no retry loop", 1, r.provider.finds)
    }

    @Test fun missingPermissions() {
        val r = rig(); r.source.add(1); r.source.access = MediaAccess.NONE; r.open("Alice")
        assertEquals(StatusMessage.MEDIA_PERMISSION_REQUIRED, r.message); assertEquals(0, r.launcher.calls)
        val p = rig(); p.source.add(1); p.source.access = MediaAccess.PARTIAL; p.open("Alice")
        assertEquals(StatusMessage.MEDIA_PERMISSION_PARTIAL, p.message)
        val s = rig(); s.source.add(1); s.source.throwSecurity = true; s.open("Alice")
        assertEquals(StatusMessage.MEDIA_PERMISSION_REQUIRED, s.message)
    }

    @Test fun deletedOrInaccessibleLatest_olderOneIsNotSentAutomatically() {
        val r = rig(); r.source.add(1, c = cand(1, r.clock, ageMs = 600_000)); r.source.add(2, bytes = null, c = cand(2, r.clock, ageMs = 10_000))
        r.open("Alice")
        assertEquals("needs the user: the newest one could not be read", SCREENSHOT_READY, r.state)
        assertEquals(StatusMessage.NEWER_SCREENSHOT_UNREADABLE, r.message); assertEquals(0, r.launcher.calls)
        val u = rig(); u.source.add(1, bytes = null); u.open("Alice")
        assertEquals(StatusMessage.SCREENSHOT_UNREADABLE, u.message); assertEquals(0, u.launcher.calls)
    }

    @Test fun stagingFailureIsReported() {
        val r = rig(); r.source.add(1); r.provider.failStaging = true; r.open("Alice")
        assertEquals(ERROR, r.state); assertEquals(StatusMessage.STORAGE_FAILED, r.message)
    }

    // ---- behavior A ----
    @Test fun everyChatOpen_preparesAgainEachTimeAChatIsOpened() {
        val r = rig(SendBehavior.EVERY_CHAT_OPEN); r.source.add(1)
        r.open("Alice"); assertEquals(1, r.launcher.calls)
        r.leaveToChatList(); r.scheduler.advance(5_000)
        r.open("Alice"); assertEquals("same chat reopened", 2, r.launcher.calls)
        r.leaveToChatList(); r.scheduler.advance(5_000)
        r.open("Bob"); assertEquals(3, r.launcher.calls)
    }

    @Test fun everyChatOpen_notRepeatedWhileStayingInTheSameChat() {
        val r = rig(SendBehavior.EVERY_CHAT_OPEN); r.source.add(1)
        r.open("Alice"); repeat(5) { r.open("Alice"); r.scheduler.advance(10_000) }
        assertEquals(1, r.launcher.calls)
    }

    // ---- behavior B ----
    @Test fun oncePerChat_multipleConversationsAndReopening() {
        val r = rig(SendBehavior.ONCE_PER_CHAT); r.source.add(1)
        r.open("Alice"); assertEquals(1, r.launcher.calls)
        r.leaveToChatList(); r.open("Bob"); assertEquals("another chat gets its own", 2, r.launcher.calls)
        r.leaveToChatList(); r.open("Alice")
        assertEquals("Alice already handled", 2, r.launcher.calls); assertEquals(StatusMessage.ALREADY_HANDLED_CHAT, r.message)
        r.source.add(2, c = cand(2, r.clock, ageMs = 5_000))
        r.leaveToChatList(); r.open("Alice"); assertEquals("a newer screenshot does not matter in mode B", 2, r.launcher.calls)
    }

    @Test fun oncePerChat_resetStatusAllowsAnotherSend() {
        val r = rig(SendBehavior.ONCE_PER_CHAT); r.source.add(1)
        r.open("Alice"); r.leaveToChatList(); r.open("Alice"); assertEquals(1, r.launcher.calls)
        assertTrue(r.controller.resetCurrentChatStatus())
        r.leaveToChatList(); r.open("Alice"); assertEquals(2, r.launcher.calls)
        r.controller.resetAllChatStatus(); assertEquals(0, r.ledger.size())
    }

    @Test fun oncePerChat_statusSurvivesRestartOfAutomation() {
        val r = rig(SendBehavior.ONCE_PER_CHAT); r.source.add(1)
        r.open("Alice"); r.controller.stop(); r.controller.start()
        r.open("Alice"); assertEquals(1, r.launcher.calls)
    }

    @Test fun unidentifiableChat_modeB_neverAutoSends_andNeverSharesAStatusWithAnotherChat() {
        val r = rig(SendBehavior.ONCE_PER_CHAT); r.source.add(1)
        r.open(null)
        assertEquals(SCREENSHOT_READY, r.state); assertEquals(StatusMessage.IDENTITY_UNRELIABLE, r.message); assertEquals(0, r.launcher.calls)
        r.controller.requestAttach()   // user insists: destination cannot be verified -> manual confirmation
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(0, r.launcher.calls)
        assertEquals("nothing recorded for an unknown chat", 0, r.ledger.size())
        r.controller.confirmAndShareManually(); assertEquals(1, r.launcher.calls)
        assertEquals(0, r.ledger.size())
    }

    // ---- behavior C ----
    @Test fun newScreenshotOnly_sameImageNotSentTwice_newImageIs() {
        val r = rig(SendBehavior.NEW_SCREENSHOT_ONLY); r.source.add(1, c = cand(1, r.clock, ageMs = 120_000))
        r.open("Alice"); assertEquals(1, r.launcher.calls)
        r.leaveToChatList(); r.open("Alice")
        assertEquals(1, r.launcher.calls); assertEquals(StatusMessage.SAME_IMAGE_HANDLED, r.message)
        r.source.add(2, c = cand(2, r.clock, ageMs = 5_000))
        r.leaveToChatList(); r.open("Alice"); assertEquals("newly created screenshot", 2, r.launcher.calls)
        r.leaveToChatList(); r.open("Bob"); assertEquals("same newest image, different chat", 3, r.launcher.calls)
    }

    @Test fun newScreenshotOnly_identicalImageFileIsNotResent() {
        val r = rig(SendBehavior.NEW_SCREENSHOT_ONLY); val bytes = byteArrayOf(4, 4, 4)
        r.source.add(1, bytes = bytes, c = cand(1, r.clock, ageMs = 120_000))
        r.open("Alice"); r.source.add(2, bytes = bytes, c = cand(2, r.clock, ageMs = 5_000))   // a copy: same content, newer file
        r.leaveToChatList(); r.open("Alice")
        assertEquals(1, r.launcher.calls); assertEquals(StatusMessage.SAME_IMAGE_HANDLED, r.message)
    }

    @Test fun newScreenshotOnly_screenshotTakenWhileChatIsOpenTriggersHandoff() {
        val r = rig(SendBehavior.NEW_SCREENSHOT_ONLY); r.source.add(1, c = cand(1, r.clock, ageMs = 120_000))
        r.open("Alice"); r.controller.dismiss()      // user finished with the first hand-off, still in the chat
        r.open("Alice"); assertEquals(1, r.launcher.calls)
        r.source.add(2, c = cand(2, r.clock, ageMs = 2_000))
        r.controller.onNewScreenshotAvailable(); r.scheduler.advance(AutomationSettings.SETTLE_MS + 50)
        assertEquals(2, r.launcher.calls)
    }

    @Test fun newScreenshotSignalIsIgnoredInOtherModesAndOutsideAChat() {
        for (b in listOf(SendBehavior.EVERY_CHAT_OPEN, SendBehavior.ONCE_PER_CHAT)) {
            val r = rig(b); r.source.add(1); r.open("Alice"); r.controller.dismiss(); r.source.add(2)
            val calls = r.launcher.calls; r.controller.onNewScreenshotAvailable(); r.scheduler.advance(5_000)
            assertEquals(calls, r.launcher.calls)
        }
        val c = rig(SendBehavior.NEW_SCREENSHOT_ONLY); c.source.add(1)
        c.controller.onNewScreenshotAvailable(); c.scheduler.advance(5_000)    // not in any chat
        assertEquals(0, c.provider.finds)
    }

    // ---- destination safety ----
    @Test fun conversationChangesBeforeSending_nothingIsShared() {
        val r = rig(SendBehavior.EVERY_CHAT_OPEN, mode = CaptureMode.DELAYED); r.source.add(1)
        r.settings = r.settings.copy(delayMs = 5_000)
        r.open("Alice")                                   // timer pending
        r.leaveToChatList(); r.open("Bob")                // user moved on before the delay elapsed
        r.scheduler.advance(10_000)
        assertEquals("the share went to the chat that was open at the time", 1, r.launcher.calls)
        assertEquals(r.chatKey("Bob"), r.ledger.let { r.chatKey("Bob") })
    }

    @Test fun conversationChangedBetweenSelectionAndHandoff_manualConfirmation() {
        val r = rig(SendBehavior.EVERY_CHAT_OPEN); r.source.add(1)
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice")
        r.controller.onScreen(r.convSnapshot("Alice")); r.scheduler.advance(AutomationSettings.SETTLE_MS - 100)
        // while the lookup is pending the user flips to Bob: the verifier sees Bob on screen at hand-off time
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Bob")
        r.scheduler.advance(200)
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(StatusMessage.DESTINATION_CHANGED, r.message); assertEquals(0, r.launcher.calls)
    }

    @Test fun whatsAppNotInFrontAtHandoff_isManual() {
        val r = rig(SendBehavior.EVERY_CHAT_OPEN); r.source.add(1)
        r.observer.screen = null
        r.controller.onScreen(r.convSnapshot("Alice")); r.scheduler.advance(2_000)
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(0, r.launcher.calls)
    }

    @Test fun interfaceChangedButStructureIsRecognisable_stillHandsOff() {
        val r = rig(SendBehavior.EVERY_CHAT_OPEN); r.source.add(1)
        val snap = ScreenSnapshot("com.whatsapp", setOf("totally_new_id"), null, "com.whatsapp.NewConversationActivity", hasEditableInput = true, topTitle = "Alice")
        r.observer.screen = ScreenClassification(ScreenKind.CONVERSATION, headerText = "Alice")
        r.controller.onScreen(snap); r.scheduler.advance(2_000)
        assertEquals(1, r.launcher.calls); assertEquals(ATTACHMENT_READY, r.state)
    }

    @Test fun whatsAppInterfaceChanged_screenNotRecognised_noAutomaticHandoff() {
        val r = rig(SendBehavior.EVERY_CHAT_OPEN); r.source.add(1)
        r.controller.onScreen(ScreenSnapshot("com.whatsapp", setOf("totally_new_id"), "Alice", "com.whatsapp.NewConversationActivity"))
        r.scheduler.advance(10_000)
        assertEquals(0, r.provider.finds); assertEquals(0, r.launcher.calls); assertEquals(StatusMessage.CONVERSATION_NOT_RECOGNISED, r.message)
    }

    // ---- stopping ----
    @Test fun stopCancelsPendingLookup_andLaterEvents() {
        val r = rig(); r.source.add(1); r.controller.onScreen(r.convSnapshot("Alice")); r.scheduler.advance(300)
        r.controller.stop(); r.scheduler.advance(10_000)
        assertEquals(0, r.provider.finds); assertEquals(INACTIVE, r.state)
        r.open("Alice"); assertEquals(0, r.provider.finds)
    }

    @Test fun pausedAndUnlicensed() {
        val p = rig(); p.source.add(1); p.controller.pause(); p.open("Alice"); assertEquals(0, p.provider.finds)
        val u = rig(); u.source.add(1); u.licensed = false; u.open("Alice"); assertEquals(0, u.provider.finds)
    }

    @Test fun manualMode_onlyOnUserRequest_andUserRequestBypassesRepeatPolicy() {
        val r = rig(SendBehavior.ONCE_PER_CHAT, CaptureMode.MANUAL); r.source.add(1)
        r.open("Alice"); assertEquals(0, r.provider.finds)
        assertTrue(r.controller.captureNow()); assertEquals(1, r.launcher.calls)
        r.controller.dismiss(); r.clock.advance(5_000); r.controller.onScreen(r.convSnapshot("Alice"))
        assertTrue("explicit request overrides 'once per chat'", r.controller.captureNow()); assertEquals(2, r.launcher.calls)
    }

    // ---- hand-off bookkeeping ----
    @Test fun ledgerRecordsHandOffsWithoutNames() {
        val r = rig(SendBehavior.ONCE_PER_CHAT); r.source.add(1); r.open("Alice")
        assertTrue(r.ledger.chatHasAny(r.chatKey("Alice"))); assertFalse(r.ledger.chatHasAny(r.chatKey("Bob")))
    }

    @Test fun workflowEndsWhenUserLeavesChat_soNextChatStartsClean() {
        val r = rig(SendBehavior.ONCE_PER_CHAT); r.source.add(1)
        r.open("Alice"); assertEquals(ATTACHMENT_READY, r.state)
        r.open("Bob")    // user moved to another chat without pressing Done
        assertEquals(2, r.launcher.calls); assertEquals("previous temporary copy removed", 1, r.repo.list().size)
    }

    // ---- fully automatic send (explicit opt-in) ----
    private fun autoRig(b: SendBehavior = SendBehavior.ONCE_PER_CHAT, auto: Boolean = true): LatestRig {
        val r = rig(b, auto = auto); r.source.add(1)
        r.launcher.result = LaunchResult.Launched(directSelectPending = true)
        return r
    }
    private fun LatestRig.list(vararg t: String) = controller.shareStep(ShareScreenView(ShareStage.SHARE_LIST, rowTitles = t.toList()))
    private fun LatestRig.preview(recipient: String?) = controller.shareStep(ShareScreenView(ShareStage.SHARE_PREVIEW, previewRecipient = recipient, sendButtonAvailable = true))
    private fun LatestRig.chat(title: String, outgoing: Int?) = controller.shareStep(ShareScreenView(ShareStage.CONVERSATION, conversationHeader = title, outgoingMediaCount = outgoing))

    @Test fun fullAutoSend_confirmedWhenEvidenceExists() {
        val r = autoRig(); r.open("Alice", outgoing = 4)
        assertEquals(PREPARING_ATTACHMENT, r.state); assertTrue(r.controller.shareFlowActive)
        assertEquals(ShareAction.ClickRow(1), r.list("Bob", "Alice"))
        assertEquals(ShareAction.ClickSend, r.preview("Alice"))
        r.chat("Alice", 5)
        assertEquals(StatusMessage.SEND_CONFIRMED, r.message); assertEquals(ATTACHMENT_READY, r.state)
        assertTrue(r.ledger.hasHandled(r.chatKey("Alice"), r.provider.selector.selectLatest(listOf("Screenshots")).let { (it as SelectionResult.Found).image.key }))
    }

    @Test fun fullAutoSend_withoutEvidenceNeverClaimsSuccess() {
        val r = autoRig(); r.open("Alice", outgoing = 4)
        r.list("Alice"); r.preview("Alice"); r.chat("Alice", 4)
        assertEquals(StatusMessage.SEND_UNCONFIRMED, r.message)
    }

    @Test fun fullAutoSend_recipientMismatchStopsBeforeSend() {
        val r = autoRig(); r.open("Alice", outgoing = 0)
        r.list("Alice")
        assertEquals(ShareAction.Done(ShareOutcome.ABORT_RECIPIENT_MISMATCH), r.preview("Bob"))
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(StatusMessage.AUTO_SEND_RECIPIENT_MISMATCH, r.message)
        assertEquals("nothing was handed off, so nothing is recorded", 0, r.ledger.size())
    }

    @Test fun fullAutoSend_ambiguousListAborts() {
        val r = autoRig(); r.open("Alice", outgoing = 0)
        r.list("Alice", "Alice")
        assertEquals(StatusMessage.AUTO_SEND_AMBIGUOUS, r.message); assertEquals(MANUAL_ACTION_REQUIRED, r.state)
    }

    @Test fun fullAutoSend_timeoutAbortsToManual() {
        val r = autoRig(); r.open("Alice", outgoing = 0)
        r.scheduler.advance(AutomationController.SEND_FLOW_TIMEOUT_MS + 10)
        assertEquals(MANUAL_ACTION_REQUIRED, r.state); assertEquals(StatusMessage.AUTO_SEND_TIMEOUT, r.message)
    }

    @Test fun emergencyStop_haltsFlowImmediately_noFurtherClicks() {
        val r = autoRig(); r.open("Alice", outgoing = 0)
        r.list("Alice")
        r.controller.stop()
        assertNull(r.preview("Alice")); assertFalse(r.controller.shareFlowActive); assertEquals(INACTIVE, r.state)
    }

    @Test fun fullAutoSendIsOffUnlessOptedIn_evenIfDirectSelectIsOn() {
        val r = autoRig(auto = false); r.settings = r.settings.copy(directSelectExperimental = true)
        r.open("Alice", outgoing = 0)
        assertEquals(ShareAction.ClickRow(0), r.list("Alice"))
        r.controller.shareClickPerformed()
        assertEquals(ATTACHMENT_READY, r.state)
        assertEquals("user presses Send", StatusMessage.ATTACHMENT_READY, r.message)
        assertNull("flow is finished; a preview screen is never acted upon", r.preview("Alice"))
    }

    @Test fun fullAutoSendNeedsReliableIdentity_andUnambiguousImage() {
        val noId = autoRig(SendBehavior.EVERY_CHAT_OPEN); noId.open(null, outgoing = 0)
        assertEquals("unknown chat: nothing automatic", 0, noId.launcher.calls)
        val skipped = rig(SendBehavior.EVERY_CHAT_OPEN, auto = true)
        skipped.source.add(1, c = cand(1, skipped.clock, ageMs = 600_000)); skipped.source.add(2, bytes = null, c = cand(2, skipped.clock, ageMs = 10_000))
        skipped.open("Alice"); assertEquals(0, skipped.launcher.calls)
    }
}
