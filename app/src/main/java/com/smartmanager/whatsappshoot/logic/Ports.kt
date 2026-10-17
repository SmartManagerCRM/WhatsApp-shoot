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

/** Finds and stages (copies to private temporary storage) the newest existing screenshot. Callbacks arrive on the main thread. */
interface LatestScreenshotProvider {
    fun find(settings: AutomationSettings, callback: (SelectionResult) -> Unit)
    fun stage(image: LatestImage, settings: AutomationSettings, callback: (CaptureResult) -> Unit)

    /** Which gallery route is safe for sending [image] as a photo (see [GalleryRoute]). */
    fun galleryRoute(image: LatestImage, folders: List<String>): GalleryRoute = GalleryRoute.ALBUM
}

enum class GalleryRoute {
    /** [image] is the newest item (photo or video) on the phone: WhatsApp's first landing-gallery tile is it. */
    LANDING,

    /** Use the Screenshots album of WhatsApp's gallery. */
    ALBUM,

    /** A video newer than [image] sits in the screenshots folder: a photo-only send cannot be guaranteed. */
    BLOCKED_VIDEO,
}

enum class DirectSelectOutcome { SELECTED, AMBIGUOUS, NOT_FOUND, TIMEOUT }

enum class StatusMessage {
    STOPPED, PAUSED, WAITING_FOR_WHATSAPP, WHATSAPP_DETECTED, CONVERSATION_DETECTED, CONVERSATION_NOT_RECOGNISED,
    CAPTURE_PERMISSION_REQUIRED, CAPTURE_PERMISSION_DENIED, PREPARING_SCREENSHOT, SCREENSHOT_CAPTURED,
    CHECKING_CONVERSATION, PREPARING_ATTACHMENT, ATTACHMENT_READY, MANUAL_CONFIRMATION_REQUIRED, DESTINATION_CHANGED,
    PROTECTED_SCREEN, CAPTURE_FAILED, STORAGE_FAILED, ATTACHMENT_FAILED, WHATSAPP_NOT_INSTALLED, LICENSE_REQUIRED,
    LOOKING_FOR_SCREENSHOT, LATEST_READY, NO_SCREENSHOT, MEDIA_PERMISSION_REQUIRED, MEDIA_PERMISSION_PARTIAL, SCREENSHOT_UNREADABLE,
    ALREADY_HANDLED_CHAT, SAME_IMAGE_HANDLED, NOT_NEWER, IDENTITY_UNRELIABLE, NEWER_SCREENSHOT_UNREADABLE,
    SEND_CONFIRMED, SEND_UNCONFIRMED, AUTO_SEND_AMBIGUOUS, AUTO_SEND_NOT_FOUND, AUTO_SEND_RECIPIENT_MISMATCH,
    AUTO_SEND_RECIPIENT_UNVERIFIED, AUTO_SEND_UNAVAILABLE, AUTO_SEND_TIMEOUT, AUTO_SEND_NO_GALLERY, AUTO_SEND_NO_ALBUM, AUTO_SEND_NO_IMAGE, AUTO_SEND_NOT_PHOTO, CHAT_STATUS_RESET,
}

data class ControllerState(
    val state: AutomationState = AutomationState.INACTIVE,
    val paused: Boolean = false,
    val message: StatusMessage = StatusMessage.STOPPED,
    val screenshot: StoredScreenshot? = null,
    val conversationType: ConversationType = ConversationType.UNKNOWN,
)
