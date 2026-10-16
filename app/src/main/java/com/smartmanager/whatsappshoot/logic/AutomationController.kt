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
    clock: Clock,
    /** Licensing is a separate subsystem; the controller only asks "may automation run?". */
    private val isLicensed: () -> Boolean,
) {
    private val machine = AutomationStateMachine()
    private val guard = CaptureGuard(clock)
    private var hasher = IdentityHasher()

    private var message = StatusMessage.STOPPED
    private var screenshot: StoredScreenshot? = null
    private var shotIdentity: ConversationIdentity? = null
    private var currentIdentity: ConversationIdentity? = null
    private var conversationType = ConversationType.UNKNOWN
    private var pendingAutoCapture: Cancellable? = null
    private var directSelectTimeout: Cancellable? = null
    private var captureDenied = false
    private var captureIdentity: ConversationIdentity? = null

    var onChange: (() -> Unit)? = null

    val view: ControllerState
        get() = ControllerState(machine.state, machine.paused, message, screenshot, conversationType)

    // ---------------- lifecycle ----------------

    fun start(): Boolean {
        if (!isLicensed()) return set(StatusMessage.LICENSE_REQUIRED).let { false }
        if (machine.state != AutomationState.INACTIVE) return true
        hasher = IdentityHasher()
        guard.reset()
        captureDenied = false
        machine.dispatch(AutomationEvent.START)
        if (settings().autoCleanup) repository.cleanupOlderThan(settings().retentionMs)
        set(StatusMessage.WAITING_FOR_WHATSAPP)
        return true
    }

    fun stop() {
        cancelPending()
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
        when {
            c.kind == ScreenKind.IGNORED -> return
            !c.isWhatsApp -> onLeftWhatsApp()
            else -> onWhatsApp(c)
        }
    }

    private fun onLeftWhatsApp() {
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
        if (!guard.tryBegin(userInitiated)) return false
        // Identity of what is on screen right now (null for a manual capture of an unrecognised screen).
        captureIdentity = if (machine.state == AutomationState.CONVERSATION_DETECTED) currentIdentity else null
        if (!machine.dispatch(AutomationEvent.CAPTURE_STARTED)) {
            guard.cancelStart()
            return false
        }
        set(StatusMessage.PREPARING_SCREENSHOT)
        engine.capture(settings()) { handleCaptureResult(it) }
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

    // ---------------- attachment ----------------

    /** "Attach to WhatsApp": re-checks the destination, then opens WhatsApp's share UI. Never sends. */
    fun requestAttach() {
        val shot = screenshot ?: return
        if (!machine.dispatch(AutomationEvent.VERIFY_REQUESTED)) return
        set(StatusMessage.CHECKING_CONVERSATION)
        when (DestinationVerifier.check(shotIdentity, observer.currentScreen(), hasher)) {
            DestinationCheck.VERIFIED -> {
                machine.dispatch(AutomationEvent.VERIFIED)
                prepareAttachment(shot, direct = settings().directSelectExperimental)
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
        prepareAttachment(shot, direct = false)
    }

    private fun prepareAttachment(shot: StoredScreenshot, direct: Boolean) {
        set(StatusMessage.PREPARING_ATTACHMENT)
        when (val r = launcher.launchShare(shot, shotIdentity, hasher, direct)) {
            is LaunchResult.Launched -> if (r.directSelectPending) {
                directSelectTimeout = scheduler.schedule(DIRECT_SELECT_TIMEOUT_MS) { onDirectSelectionResult(DirectSelectOutcome.TIMEOUT) }
            } else {
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
    }

    private fun set(m: StatusMessage) {
        message = m
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
    }
}
