package com.smartmanager.whatsappshoot.logic

/** Ports (interfaces) the controller needs from the platform; implemented in `platform/`, faked in tests. */

fun interface Cancellable { fun cancel() }

interface Scheduler {
    /** Runs [block] on the main thread after [delayMs]. */
    fun schedule(delayMs: Long, block: () -> Unit): Cancellable
}

enum class FailureReason { STORAGE, MEMORY, SESSION_ENDED, UNSUPPORTED, UNKNOWN }

sealed class CaptureResult {
    data class Saved(val shot: StoredScreenshot) : CaptureResult()

    /** User consent for screen capture is needed (never obtained silently). */
    object PermissionRequired : CaptureResult()

    /** The foreground content is protected (FLAG_SECURE) or capture is blocked by the system. */
    object Protected : CaptureResult()
    data class Failed(val reason: FailureReason) : CaptureResult()
}

interface CaptureEngine {
    /** Captures one full-screen frame. [callback] must be invoked exactly once, on the main thread. */
    fun capture(settings: AutomationSettings, callback: (CaptureResult) -> Unit)
}

fun interface ScreenObserver {
    /** Classification of what WhatsApp shows right now, or null if nothing observable (WhatsApp not in front). */
    fun currentScreen(): ScreenClassification?
}

sealed class LaunchResult {
    /** Share UI opened. [directSelectPending] = we will try to pick the chat and report via the controller. */
    data class Launched(val directSelectPending: Boolean) : LaunchResult()
    object WhatsAppNotInstalled : LaunchResult()
    data class Failed(val message: String) : LaunchResult()
}

interface AttachmentLauncher {
    fun launchShare(shot: StoredScreenshot, expected: ConversationIdentity?, hasher: IdentityHasher, tryDirectSelect: Boolean): LaunchResult
}

enum class DirectSelectOutcome { SELECTED, AMBIGUOUS, NOT_FOUND, TIMEOUT }

enum class StatusMessage {
    STOPPED, PAUSED, WAITING_FOR_WHATSAPP, WHATSAPP_DETECTED, CONVERSATION_DETECTED, CONVERSATION_NOT_RECOGNISED,
    CAPTURE_PERMISSION_REQUIRED, CAPTURE_PERMISSION_DENIED, PREPARING_SCREENSHOT, SCREENSHOT_CAPTURED,
    CHECKING_CONVERSATION, PREPARING_ATTACHMENT, ATTACHMENT_READY, MANUAL_CONFIRMATION_REQUIRED, DESTINATION_CHANGED,
    PROTECTED_SCREEN, CAPTURE_FAILED, STORAGE_FAILED, ATTACHMENT_FAILED, WHATSAPP_NOT_INSTALLED, LICENSE_REQUIRED,
}

data class ControllerState(
    val state: AutomationState = AutomationState.INACTIVE,
    val paused: Boolean = false,
    val message: StatusMessage = StatusMessage.STOPPED,
    val screenshot: StoredScreenshot? = null,
    val conversationType: ConversationType = ConversationType.UNKNOWN,
)
