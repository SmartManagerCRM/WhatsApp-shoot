package com.smartmanager.whatsappshoot.logic

enum class CaptureMode { AUTOMATIC, DELAYED, MANUAL }
enum class ImageFormat(val extension: String, val mime: String) { PNG("png", "image/png"), JPEG("jpg", "image/jpeg") }

/** Which Android mechanism takes the screenshot. */
enum class CaptureMethod {
    /** Accessibility screenshot where available (Android 11+), otherwise MediaProjection. */
    AUTO,
    MEDIA_PROJECTION,
}

/** Where the image comes from. */
enum class ImageSource {
    /** Default: the newest PHOTO on the phone, i.e. the first picture WhatsApp's own gallery shows; no folder is involved and nothing new is captured. */
    WHATSAPP_GALLERY,

    /** The newest existing screenshot from the configured Screenshots folder; nothing new is captured. */
    LATEST_SCREENSHOT,

    /** Legacy: take a new screenshot of the screen. */
    CAPTURE_NEW,
}

data class AutomationSettings(
    val imageSource: ImageSource = ImageSource.WHATSAPP_GALLERY,
    val sendBehavior: SendBehavior = SendBehavior.ONCE_PER_CHAT,
    /** Folder names (exact path segment, case-insensitive) that count as "the Screenshots folder". */
    val screenshotFolders: List<String> = listOf("Screenshots"),
    /** Explicit opt-in (set only after the user confirms a warning): the app may press WhatsApp's Send button itself. */
    val fullyAutomaticSend: Boolean = false,
    /** Fully automatic sending: take the first picture of WhatsApp's landing gallery when the screenshot is verified to be the newest picture on the phone. */
    val landingGalleryFirst: Boolean = true,
    val mode: CaptureMode = CaptureMode.AUTOMATIC,
    /** Delay used by [CaptureMode.DELAYED]. */
    val delayMs: Long = DEFAULT_DELAY_MS,
    /** Master switch for any capture that is not an explicit user action. */
    val automaticCaptureEnabled: Boolean = true,
    val floatingControlEnabled: Boolean = true,
    val format: ImageFormat = ImageFormat.PNG,
    val captureMethod: CaptureMethod = CaptureMethod.AUTO,
    val autoCleanup: Boolean = true,
    val retentionMs: Long = DEFAULT_RETENTION_MS,
    val deleteWhenWorkflowEnds: Boolean = true,
    /** Experimental: try to pick the matching chat in WhatsApp's share list. Never presses Send. */
    val directSelectExperimental: Boolean = false,
) {
    /** Short settle time before an AUTOMATIC capture so the conversation has finished rendering. */
    val effectiveDelayMs: Long
        get() = when (mode) {
            CaptureMode.AUTOMATIC -> SETTLE_MS
            CaptureMode.DELAYED -> delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
            CaptureMode.MANUAL -> 0L
        }

    companion object {
        const val SETTLE_MS = 800L
        const val DEFAULT_DELAY_MS = 3_000L
        const val MIN_DELAY_MS = 500L
        const val MAX_DELAY_MS = 30_000L
        const val DEFAULT_RETENTION_MS = 60L * 60 * 1000 // 1 hour
    }
}

/** True for every source that sends an EXISTING picture (as opposed to taking a new screenshot). */
val AutomationSettings.sendsExistingPicture: Boolean get() = imageSource != ImageSource.CAPTURE_NEW
