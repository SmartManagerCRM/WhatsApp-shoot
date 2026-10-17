package com.smartmanager.whatsappshoot.logic

/**
 * Orchestrates detection -> capture -> verification -> attachment, driven by [AutomationStateMachine].
 * Contains no Android types so every rule is unit-testable on the JVM. All methods must be called from
 * one thread (Android: main).
 *
 * Privacy: the controller sees the conversation title only inside [onScreen] and immediately reduces it to a
 * salted hash ([ConversationIdentity]). Neither the text nor the hash is logged or persisted.
 */
class AutomationController(
    private val classifier: ScreenClassifier,
    private val settings: () -> AutomationSettings,
    private val engine: CaptureEngine,
    private val repository: ScreenshotRepository,
    private val observer: ScreenObserver,
    private val launcher: AttachmentLauncher,
    private val scheduler: Scheduler,
    private val clock: Clock,
    /** Licensing is a separate subsystem; the controller only asks "may automation run?". */
    private val isLicensed: () -> Boolean,
    private val latest: LatestScreenshotProvider = NoLatestScreenshots,
    private val ledger: SentLedger = InMemoryLedger(),
    /** Per-install secret salt for conversation hashes, so ledger keys stay stable across restarts. */
    private val identitySalt: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
) {
    private val machine = AutomationStateMachine()
    private val guard = CaptureGuard(clock)
    private var hasher = IdentityHasher(identitySalt)

    private var message = StatusMessage.STOPPED
    private var screenshot: StoredScreenshot? = null
    private var shotIdentity: ConversationIdentity? = null
    private var currentIdentity: ConversationIdentity? = null
    private var conversationType = ConversationType.UNKNOWN
    private var pendingAutoCapture: Cancellable? = null
    private var directSelectTimeout: Cancellable? = null
    private var captureDenied = false
    private var captureIdentity: ConversationIdentity? = null
    private var shotImage: LatestImage? = null
    private var currentOutgoing: Int? = null
    private var baselineOutgoing: Int? = null
    private var shareEngine: ShareFlowEngine? = null
    private var attachEngine: AttachFlowEngine? = null
    private var shareTimeout: Cancellable? = null
    private var captureEpoch = 0
    private var captureWatchdog: Cancellable? = null
    private var lastShareTrace = ""

    var onChange: (() -> Unit)? = null

    init {
        machine.listener = { from, to, event -> ActivityLog.add("state $from -> $to ($event)") }
    }

    /** Arms a timer so a lookup/capture that never reports back cannot leave the app stuck. Returns the token its callbacks must carry. */
    private fun armWatchdog(): Int {
        val e = ++captureEpoch
        captureWatchdog?.cancel()
        captureWatchdog = scheduler.schedule(CAPTURE_TIMEOUT_MS) {
            if (e == captureEpoch && machine.state == AutomationState.CAPTURING_SCREEN) {
                captureEpoch++
                ActivityLog.add("watchdog: lookup/capture did not finish in ${CAPTURE_TIMEOUT_MS / 1000}s")
                guard.finish()
                fail(StatusMessage.CAPTURE_FAILED)
            }
        }
        return e
    }

    private fun disarmWatchdog() {
        captureWatchdog?.cancel(); captureWatchdog = null
    }

    /** True if [text] (e.g. a title seen on screen) is the verified chat. Used by the accessibility layer to tell the chat from share screens. */
    fun expectedTitleMatches(text: String?): Boolean = shotIdentity?.matches(hasher.identityOf(text)) == true

    val view: ControllerState
        get() = ControllerState(machine.state, machine.paused, message, screenshot, conversationType)

    // ---------------- lifecycle ----------------

    fun start(): Boolean {
        if (!isLicensed()) return set(StatusMessage.LICENSE_REQUIRED).let { false }
        if (machine.state != AutomationState.INACTIVE) return true
        hasher = IdentityHasher(identitySalt)
        guard.reset()
        captureDenied = false
        machine.dispatch(AutomationEvent.START)
        if (settings().autoCleanup) repository.cleanupOlderThan(settings().retentionMs)
        set(StatusMessage.WAITING_FOR_WHATSAPP)
        return true
    }

    fun stop() {
        captureEpoch++; disarmWatchdog()
        shareEngine = null; attachEngine = null
        shareTimeout?.cancel(); shareTimeout = null
        cancelPending() // emergency stop: nothing further is clicked
        machine.dispatch(AutomationEvent.STOP)
        discardScreenshot()
        currentIdentity = null
        shotIdentity = null
        captureIdentity = null
        conversationType = ConversationType.UNKNOWN
        guard.reset()
        set(StatusMessage.STOPPED)
    }

    fun pause() {
        if (machine.pause()) {
            cancelPending()
            set(StatusMessage.PAUSED)
        }
    }

    fun resume() {
        if (machine.resume()) {
            // The visit is re-evaluated on the next screen event.
            set(stateMessage())
        }
    }

    // ---------------- observation ----------------

    fun onScreen(s: ScreenSnapshot) {
        if (machine.state == AutomationState.INACTIVE || machine.paused) return
        val c = classifier.classify(s)
        if (settings().imageSource == ImageSource.LATEST_SCREENSHOT && machine.state == AutomationState.ATTACHMENT_READY && handedOffAndLeft(c)) dismiss()
        when {
            c.kind == ScreenKind.IGNORED -> return
            !c.isWhatsApp -> onLeftWhatsApp()
            else -> onWhatsApp(c)
        }
    }

    /** After a hand-off to WhatsApp, leaving the chat (list, other chat, other app) ends the workflow so the next chat starts clean. */
    private fun handedOffAndLeft(c: ScreenClassification): Boolean = when (c.kind) {
        ScreenKind.IGNORED -> false
        ScreenKind.CHAT_LIST, ScreenKind.NOT_WHATSAPP -> true
        ScreenKind.CONVERSATION -> shotIdentity?.isKnown == true && !shotIdentity!!.matches(hasher.identityOf(c.headerText))
        else -> false
    }

    private fun onLeftWhatsApp() {
        if (shareFlowActive) return // a flow in progress may pass through system pickers; its own timeout ends it
        cancelPending()
        guard.conversationLeft()
        currentIdentity = null
        conversationType = ConversationType.UNKNOWN
        if (machine.dispatch(AutomationEvent.WHATSAPP_BACKGROUND)) set(StatusMessage.WAITING_FOR_WHATSAPP)
    }

    private fun onWhatsApp(c: ScreenClassification) {
        machine.dispatch(AutomationEvent.WHATSAPP_FOREGROUND)
        when (c.kind) {
            ScreenKind.CONVERSATION -> onConversation(c)
            // Never capture over these; keep state, wait until the conversation is visible again.
            ScreenKind.ATTACHMENT_PICKER, ScreenKind.MEDIA_VIEWER -> cancelPending()
            ScreenKind.UNKNOWN_WHATSAPP -> {
                leaveConversation()
                if (machine.state == AutomationState.WHATSAPP_DETECTED) set(StatusMessage.CONVERSATION_NOT_RECOGNISED)
            }
            else -> {
                leaveConversation()
                if (machine.state == AutomationState.WHATSAPP_DETECTED) set(StatusMessage.WHATSAPP_DETECTED)
            }
        }
    }

    private fun leaveConversation() {
        cancelPending()
        guard.conversationLeft()
        currentIdentity = null
        conversationType = ConversationType.UNKNOWN
        machine.dispatch(AutomationEvent.CONVERSATION_CLOSED)
    }

    private fun onConversation(c: ScreenClassification) {
        val id = hasher.identityOf(c.headerText)
        machine.dispatch(AutomationEvent.CONVERSATION_OPENED)
        if (machine.state != AutomationState.CONVERSATION_DETECTED) return // workflow in progress: ignore
        if (id != currentIdentity) { // switched conversation (or first sight)
            cancelPending()
            guard.conversationLeft()
            currentIdentity = id
        }
        conversationType = c.conversationType
        currentOutgoing = c.outgoingMediaCount
        if (message != StatusMessage.CONVERSATION_DETECTED) set(StatusMessage.CONVERSATION_DETECTED)
        scheduleAutomaticCapture()
    }

    private fun scheduleAutomaticCapture() {
        val st = settings()
        if (st.mode == CaptureMode.MANUAL || !st.automaticCaptureEnabled) return
        if (pendingAutoCapture != null || guard.currentVisitHandled || captureDenied) return
        pendingAutoCapture = scheduler.schedule(st.effectiveDelayMs) {
            pendingAutoCapture = null
            startCapture(userInitiated = false)
        }
    }

    // ---------------- capture ----------------

    /** Explicit user capture (button or floating control). Allowed in any active, unpaused session. */
    fun captureNow(): Boolean {
        captureDenied = false
        return startCapture(userInitiated = true)
    }

    private fun startCapture(userInitiated: Boolean): Boolean {
        if (machine.state == AutomationState.INACTIVE || machine.paused) return false
        if (!isLicensed()) return set(StatusMessage.LICENSE_REQUIRED).let { false }
        if (!userInitiated && machine.state != AutomationState.CONVERSATION_DETECTED) return false
        // Reading an existing screenshot needs no throttle (one attempt per conversation visit already prevents loops);
        // the cooldown protects the screen-capture APIs.
        if (!guard.tryBegin(userInitiated, useCooldown = settings().imageSource != ImageSource.LATEST_SCREENSHOT)) return false
        if (settings().imageSource == ImageSource.LATEST_SCREENSHOT) return startLatest(userInitiated)
        // Identity of what is on screen right now (null for a manual capture of an unrecognised screen).
        captureIdentity = if (machine.state == AutomationState.CONVERSATION_DETECTED) currentIdentity else null
        if (!machine.dispatch(AutomationEvent.CAPTURE_STARTED)) {
            guard.cancelStart()
            return false
        }
        set(StatusMessage.PREPARING_SCREENSHOT)
        val epoch = armWatchdog()
        engine.capture(settings()) { r ->
            if (epoch != captureEpoch) { (r as? CaptureResult.Saved)?.let { repository.delete(it.shot) }; return@capture } // timed out or stopped
            disarmWatchdog(); handleCaptureResult(r)
        }
        return true
    }

    private fun handleCaptureResult(r: CaptureResult) {
        if (machine.state != AutomationState.CAPTURING_SCREEN) {
            // Session was stopped while capturing: honour that, keep nothing.
            (r as? CaptureResult.Saved)?.let { repository.delete(it.shot) }
            guard.finish()
            return
        }
        guard.finish()
        when (r) {
            is CaptureResult.Saved -> {
                discardScreenshot()
                screenshot = r.shot
                shotIdentity = captureIdentity
                machine.dispatch(AutomationEvent.CAPTURE_SUCCEEDED)
                set(StatusMessage.SCREENSHOT_CAPTURED)
            }
            CaptureResult.PermissionRequired -> {
                machine.dispatch(AutomationEvent.CAPTURE_PERMISSION_MISSING)
                set(StatusMessage.CAPTURE_PERMISSION_REQUIRED)
            }
            CaptureResult.Protected -> fail(StatusMessage.PROTECTED_SCREEN)
            is CaptureResult.Failed -> fail(if (r.reason == FailureReason.STORAGE) StatusMessage.STORAGE_FAILED else StatusMessage.CAPTURE_FAILED)
        }
    }

    private fun fail(m: StatusMessage) {
        machine.dispatch(AutomationEvent.CAPTURE_FAILED)
        set(m)
    }

    /** Platform reports that the user granted screen-capture consent. [retry] re-runs the capture the user wanted. */
    fun onCapturePermissionGranted(retry: Boolean = true) {
        captureDenied = false
        if (machine.state != AutomationState.CAPTURE_PERMISSION_REQUIRED) return
        machine.dispatch(AutomationEvent.CAPTURE_PERMISSION_GRANTED)
        set(StatusMessage.CONVERSATION_DETECTED)
        if (retry) startCapture(userInitiated = true)
    }

    /** The user declined the system consent dialog: we do not ask again until they ask for a capture. */
    fun onCapturePermissionDenied() {
        captureDenied = true
        if (machine.dispatch(AutomationEvent.CAPTURE_PERMISSION_DENIED)) set(StatusMessage.CAPTURE_PERMISSION_DENIED)
    }

    // ---------------- latest existing screenshot (no new capture) ----------------

    private fun startLatest(userInitiated: Boolean): Boolean {
        captureIdentity = if (machine.state == AutomationState.CONVERSATION_DETECTED) currentIdentity else null
        baselineOutgoing = if (machine.state == AutomationState.CONVERSATION_DETECTED) currentOutgoing else null
        if (!machine.dispatch(AutomationEvent.CAPTURE_STARTED)) {
            guard.cancelStart()
            return false
        }
        set(StatusMessage.LOOKING_FOR_SCREENSHOT)
        val epoch = armWatchdog()
        latest.find(settings()) { if (epoch == captureEpoch) handleSelection(it, userInitiated, epoch) }
        return true
    }

    private fun skipWith(m: StatusMessage) {
        disarmWatchdog()
        guard.finish()
        machine.dispatch(AutomationEvent.CAPTURE_SKIPPED)
        set(m)
    }

    private fun handleSelection(r: SelectionResult, userInitiated: Boolean, epoch: Int) {
        ActivityLog.add("lookup result: ${r::class.simpleName}" + ((r as? SelectionResult.Found)?.let { " (skippedNewer=${it.image.skippedNewer})" } ?: ""))
        if (machine.state != AutomationState.CAPTURING_SCREEN) { guard.finish(); return } // stopped meanwhile
        when (r) {
            is SelectionResult.Found -> {
                val image = r.image
                // An explicit user request bypasses the repeat-send policy; automatic triggers obey it.
                val decision = if (userInitiated) SendDecision.Proceed else SendPolicy.decide(settings().sendBehavior, captureIdentity, image, ledger)
                when (decision) {
                    is SendDecision.Skip -> skipWith(when (decision.reason) {
                        SkipReason.ALREADY_HANDLED_CHAT -> StatusMessage.ALREADY_HANDLED_CHAT
                        SkipReason.SAME_IMAGE_ALREADY_HANDLED -> StatusMessage.SAME_IMAGE_HANDLED
                        SkipReason.NOT_NEWER -> StatusMessage.NOT_NEWER
                    })
                    is SendDecision.NeedManual -> stageLatest(image, StatusMessage.IDENTITY_UNRELIABLE, epoch)
                    SendDecision.Proceed -> stageLatest(image, if (image.skippedNewer) StatusMessage.NEWER_SCREENSHOT_UNREADABLE else null, epoch)
                }
            }
            SelectionResult.NoScreenshots -> skipWith(StatusMessage.NO_SCREENSHOT)
            SelectionResult.PermissionDenied -> skipWith(StatusMessage.MEDIA_PERMISSION_REQUIRED)
            SelectionResult.PartialAccess -> skipWith(StatusMessage.MEDIA_PERMISSION_PARTIAL)
            SelectionResult.AllUnreadable -> skipWith(StatusMessage.SCREENSHOT_UNREADABLE)
            SelectionResult.Failed -> skipWith(StatusMessage.CAPTURE_FAILED)
        }
    }

    private fun stageLatest(image: LatestImage, forceManual: StatusMessage?, epoch: Int) {
        latest.stage(image, settings()) { res ->
            if (epoch != captureEpoch || machine.state != AutomationState.CAPTURING_SCREEN) {
                (res as? CaptureResult.Saved)?.let { repository.delete(it.shot) }
                guard.finish(); return@stage
            }
            disarmWatchdog()
            guard.finish()
            when (res) {
                is CaptureResult.Saved -> {
                    discardScreenshot()
                    screenshot = res.shot
                    shotIdentity = captureIdentity
                    shotImage = image
                    machine.dispatch(AutomationEvent.CAPTURE_SUCCEEDED)
                    if (forceManual != null) set(forceManual) else {
                        set(StatusMessage.LATEST_READY)
                        requestAttach() // verify the destination again, then open WhatsApp sharing
                    }
                }
                is CaptureResult.Failed -> fail(if (res.reason == FailureReason.STORAGE) StatusMessage.STORAGE_FAILED else StatusMessage.CAPTURE_FAILED)
                else -> fail(StatusMessage.CAPTURE_FAILED)
            }
        }
    }

    /** A new image appeared in the media library. Only acts in mode C while a conversation is open and idle. */
    fun onNewScreenshotAvailable() {
        val st = settings()
        if (machine.state == AutomationState.INACTIVE || machine.paused) return
        if (st.imageSource != ImageSource.LATEST_SCREENSHOT || st.sendBehavior != SendBehavior.NEW_SCREENSHOT_ONLY) return
        if (machine.state != AutomationState.CONVERSATION_DETECTED || guard.isCapturing) return
        cancelPending()
        guard.conversationLeft() // a new image makes this a fresh opportunity within the same visit
        scheduleAutomaticCapture()
    }

    /** "Reset status" for the conversation that is open now. */
    fun resetCurrentChatStatus(): Boolean {
        val key = currentIdentity?.key ?: return false
        ledger.resetChat(key); guard.conversationLeft(); set(StatusMessage.CHAT_STATUS_RESET); return true
    }

    fun resetAllChatStatus() { ledger.resetAll(); set(StatusMessage.CHAT_STATUS_RESET) }

    // ---------------- attachment ----------------

    /** "Attach to WhatsApp": re-checks the destination, then opens WhatsApp's share UI. Never sends. */
    fun requestAttach() {
        val shot = screenshot ?: return
        if (!machine.dispatch(AutomationEvent.VERIFY_REQUESTED)) return
        set(StatusMessage.CHECKING_CONVERSATION)
        when (DestinationVerifier.check(shotIdentity, observer.currentScreen(), hasher)) {
            DestinationCheck.VERIFIED -> {
                machine.dispatch(AutomationEvent.VERIFIED)
                // Pressing Send ourselves needs an explicit opt-in, a reliable identity and an unambiguous "latest" image.
                val autoSend = settings().fullyAutomaticSend && shotIdentity?.isKnown == true && shotImage?.skippedNewer == false
                prepareAttachment(shot, direct = autoSend || settings().directSelectExperimental, allowSend = autoSend)
            }
            DestinationCheck.CHANGED -> {
                machine.dispatch(AutomationEvent.VERIFY_CHANGED)
                set(StatusMessage.DESTINATION_CHANGED)
            }
            DestinationCheck.UNCERTAIN -> {
                machine.dispatch(AutomationEvent.VERIFY_UNCERTAIN)
                set(StatusMessage.MANUAL_CONFIRMATION_REQUIRED)
            }
        }
    }

    /** From the manual-confirmation card: the user will choose and verify the recipient inside WhatsApp. */
    fun confirmAndShareManually() {
        val shot = screenshot ?: return
        if (!machine.dispatch(AutomationEvent.USER_CONFIRMED_DESTINATION)) return
        prepareAttachment(shot, direct = false, allowSend = false)
    }

    private fun prepareAttachment(shot: StoredScreenshot, direct: Boolean, allowSend: Boolean = false) {
        set(StatusMessage.PREPARING_ATTACHMENT)
        if (allowSend) {
            // Fully automatic: stay in the open chat and use WhatsApp's own attach menu (no recipient list, so no name matching).
            val route = shotImage?.let { latest.galleryRoute(it, settings().screenshotFolders) } ?: GalleryRoute.ALBUM
            if (route == GalleryRoute.BLOCKED_VIDEO) {
                ActivityLog.add("a video newer than the screenshot is in the screenshots folder: not sending")
                attachmentFailed(StatusMessage.AUTO_SEND_NOT_PHOTO)
                return
            }
            val landing = settings().landingGalleryFirst && route == GalleryRoute.LANDING
            ActivityLog.add(if (landing) "screenshot is the newest item on the phone: using the landing gallery" else "using the Screenshots album")
            attachEngine = AttachFlowEngine(baselineOutgoing, clock, landing)
            shareTimeout = scheduler.schedule(ATTACH_FLOW_TIMEOUT_MS) { onShareOutcome(attachEngine?.timeout()?.outcome ?: ShareOutcome.ABORT_TIMEOUT) }
            ActivityLog.add("in-chat attach flow started")
            return
        }
        when (val r = launcher.launchShare(shot, shotIdentity, hasher, direct)) {
            is LaunchResult.Launched -> if (r.directSelectPending) {
                shareEngine = ShareFlowEngine(shotIdentity, hasher, allowSend, baselineOutgoing, clock)
                val limit = if (allowSend) SEND_FLOW_TIMEOUT_MS else DIRECT_SELECT_TIMEOUT_MS
                shareTimeout = scheduler.schedule(limit) { onShareOutcome(shareEngine?.timeout()?.outcome ?: ShareOutcome.ABORT_TIMEOUT) }
            } else {
                record(LedgerStatus.PREPARED)
                machine.dispatch(AutomationEvent.ATTACHMENT_PREPARED)
                set(StatusMessage.ATTACHMENT_READY)
            }
            LaunchResult.WhatsAppNotInstalled -> attachmentFailed(StatusMessage.WHATSAPP_NOT_INSTALLED)
            is LaunchResult.Failed -> attachmentFailed(StatusMessage.ATTACHMENT_FAILED)
        }
    }

    /** Reported by the accessibility layer after it tried to pick the chat in WhatsApp's share list. */
    fun onDirectSelectionResult(outcome: DirectSelectOutcome) {
        if (machine.state != AutomationState.PREPARING_ATTACHMENT) return
        directSelectTimeout?.cancel(); directSelectTimeout = null
        if (outcome == DirectSelectOutcome.SELECTED) {
            machine.dispatch(AutomationEvent.ATTACHMENT_PREPARED)
            set(StatusMessage.ATTACHMENT_READY)
        } else {
            // The share UI is open; the user simply picks the chat themselves.
            attachmentFailed(StatusMessage.MANUAL_CONFIRMATION_REQUIRED)
        }
    }

    // ---- share-flow driving (called by the accessibility layer) ----

    /** True while the accessibility layer should keep reporting WhatsApp's share screens. */
    val shareFlowActive: Boolean get() = (shareEngine != null || attachEngine != null) && machine.state == AutomationState.PREPARING_ATTACHMENT

    /** True when the running flow works inside the open chat (attach -> Gallery -> album -> Send). */
    val attachFlowActive: Boolean get() = attachEngine != null && machine.state == AutomationState.PREPARING_ATTACHMENT

    /** Folder names shown as albums in WhatsApp's gallery. */
    fun albumNames(): List<String> = ScreenshotFolderMatcher.leafNames(settings().screenshotFolders)

    fun attachStep(view: AttachScreenView): AttachAction? {
        val e = attachEngine ?: return null
        if (machine.state != AutomationState.PREPARING_ATTACHMENT || machine.paused) { attachEngine = null; return null }
        val a = e.step(view)
        val trace = "attach: ${e.note} -> ${a::class.simpleName}" + ((a as? AttachAction.Done)?.let { " ${it.outcome}" } ?: "")
        if (trace != lastShareTrace) { ActivityLog.add(trace); lastShareTrace = trace }
        if (a is AttachAction.Done) onShareOutcome(a.outcome)
        return a
    }

    /** The click the flow asked for could not be performed (control not found/clickable). */
    fun attachClickFailed(a: AttachAction) {
        ActivityLog.add("click failed: ${a::class.simpleName}")
        onShareOutcome(when (a) {
            AttachAction.ClickSend -> ShareOutcome.ABORT_SEND_UNAVAILABLE
            AttachAction.ClickAlbum, AttachAction.ClickAlbumsSwitcher -> ShareOutcome.ABORT_NO_ALBUM
            AttachAction.ClickThumbnail -> ShareOutcome.ABORT_NO_IMAGE
            else -> ShareOutcome.ABORT_NO_GALLERY
        })
    }

    /** Feed what WhatsApp shows now; returns the click to perform, if any. Stops acting the moment automation is stopped. */
    fun shareStep(view: ShareScreenView): ShareAction? {
        val e = shareEngine ?: return null
        if (machine.state != AutomationState.PREPARING_ATTACHMENT || machine.paused) { shareEngine = null; return null }
        val a = e.step(view)
        val trace = "share: stage=${view.stage} rows=${view.rowTitles.size} texts=${view.visibleTexts.size} recipientLabel=${!view.previewRecipient.isNullOrBlank()} sendButton=${view.sendButtonAvailable} canScroll=${view.canScroll} ${e.note} -> ${a::class.simpleName}" +
            ((a as? ShareAction.Done)?.let { " ${it.outcome}" } ?: "")
        if (trace != lastShareTrace) { ActivityLog.add(trace); lastShareTrace = trace }
        if (a is ShareAction.Done) onShareOutcome(a.outcome)
        return a
    }

    fun shareClickPerformed() {
        val a = shareEngine?.afterClick()
        if (a is ShareAction.Done) onShareOutcome(a.outcome)
    }

    fun onShareOutcome(o: ShareOutcome) {
        if (machine.state != AutomationState.PREPARING_ATTACHMENT) return
        shareTimeout?.cancel(); shareTimeout = null; shareEngine = null; attachEngine = null
        when (o) {
            ShareOutcome.ROW_SELECTED -> { record(LedgerStatus.PREPARED); machine.dispatch(AutomationEvent.ATTACHMENT_PREPARED); set(StatusMessage.ATTACHMENT_READY) }
            ShareOutcome.SENT_CONFIRMED -> { record(LedgerStatus.SENT_CONFIRMED); machine.dispatch(AutomationEvent.ATTACHMENT_PREPARED); set(StatusMessage.SEND_CONFIRMED) }
            ShareOutcome.SENT_UNCONFIRMED -> { record(LedgerStatus.SENT_UNCONFIRMED); machine.dispatch(AutomationEvent.ATTACHMENT_PREPARED); set(StatusMessage.SEND_UNCONFIRMED) }
            ShareOutcome.ABORT_AMBIGUOUS -> attachmentFailed(StatusMessage.AUTO_SEND_AMBIGUOUS)
            ShareOutcome.ABORT_NOT_FOUND -> attachmentFailed(StatusMessage.AUTO_SEND_NOT_FOUND)
            ShareOutcome.ABORT_RECIPIENT_MISMATCH -> attachmentFailed(StatusMessage.AUTO_SEND_RECIPIENT_MISMATCH)
            ShareOutcome.ABORT_RECIPIENT_UNVERIFIED -> attachmentFailed(StatusMessage.AUTO_SEND_RECIPIENT_UNVERIFIED)
            ShareOutcome.ABORT_SEND_UNAVAILABLE -> attachmentFailed(StatusMessage.AUTO_SEND_UNAVAILABLE)
            ShareOutcome.ABORT_TIMEOUT -> attachmentFailed(StatusMessage.AUTO_SEND_TIMEOUT)
            ShareOutcome.ABORT_STOPPED -> attachmentFailed(StatusMessage.STOPPED)
            ShareOutcome.ABORT_NO_GALLERY -> attachmentFailed(StatusMessage.AUTO_SEND_NO_GALLERY)
            ShareOutcome.ABORT_NO_ALBUM -> attachmentFailed(StatusMessage.AUTO_SEND_NO_ALBUM)
            ShareOutcome.ABORT_NO_IMAGE -> attachmentFailed(StatusMessage.AUTO_SEND_NO_IMAGE)
            ShareOutcome.ABORT_NOT_PHOTO -> attachmentFailed(StatusMessage.AUTO_SEND_NOT_PHOTO)
        }
    }

    private fun record(status: LedgerStatus) {
        val chat = shotIdentity?.key ?: return
        val image = shotImage ?: return
        ledger.record(chat, image.key, image.dateMs, status, clockNow())
    }

    private val clockNow: () -> Long = { clock.nowMs() }

    private fun attachmentFailed(m: StatusMessage) {
        machine.dispatch(AutomationEvent.ATTACHMENT_FAILED)
        set(m)
    }

    // ---------------- cleanup ----------------

    /** Finish the workflow (after sending or abandoning). Deletes the screenshot if configured. */
    fun dismiss() {
        cancelPending()
        if (settings().deleteWhenWorkflowEnds) discardScreenshot() else { screenshot = null }
        shotIdentity = null
        captureIdentity = null
        shotImage = null
        shareEngine = null; attachEngine = null
        if (machine.dispatch(AutomationEvent.DISMISS)) set(StatusMessage.WAITING_FOR_WHATSAPP)
    }

    /** User pressed "delete screenshot". */
    fun deleteScreenshot() {
        discardScreenshot()
        shotIdentity = null
        if (machine.dispatch(AutomationEvent.DISMISS)) set(StatusMessage.WAITING_FOR_WHATSAPP) else notifyChange()
    }

    private fun discardScreenshot() {
        screenshot?.let { repository.delete(it) }
        screenshot = null
    }

    // ---------------- helpers ----------------

    private fun cancelPending() {
        pendingAutoCapture?.cancel(); pendingAutoCapture = null
        directSelectTimeout?.cancel(); directSelectTimeout = null
        if (!shareFlowActive) { shareTimeout?.cancel(); shareTimeout = null } // a running flow keeps its own timeout
    }

    private fun set(m: StatusMessage) {
        message = m
        ActivityLog.add("status: $m")
        notifyChange()
    }

    private fun notifyChange() = onChange?.invoke() ?: Unit

    private fun stateMessage() = when (machine.state) {
        AutomationState.INACTIVE -> StatusMessage.STOPPED
        AutomationState.WAITING_FOR_WHATSAPP -> StatusMessage.WAITING_FOR_WHATSAPP
        AutomationState.WHATSAPP_DETECTED -> StatusMessage.WHATSAPP_DETECTED
        AutomationState.CONVERSATION_DETECTED -> StatusMessage.CONVERSATION_DETECTED
        else -> message
    }

    companion object {
        const val DIRECT_SELECT_TIMEOUT_MS = 15_000L
        const val SEND_FLOW_TIMEOUT_MS = 25_000L
        const val ATTACH_FLOW_TIMEOUT_MS = 40_000L
        const val CAPTURE_TIMEOUT_MS = 12_000L
    }
}


private object NoLatestScreenshots : LatestScreenshotProvider {
    override fun find(settings: AutomationSettings, callback: (SelectionResult) -> Unit) = callback(SelectionResult.NoScreenshots)
    override fun stage(image: LatestImage, settings: AutomationSettings, callback: (CaptureResult) -> Unit) =
        callback(CaptureResult.Failed(FailureReason.UNSUPPORTED))
}
