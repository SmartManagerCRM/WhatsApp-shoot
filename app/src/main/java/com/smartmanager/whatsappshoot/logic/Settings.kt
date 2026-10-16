package com.smartmanager.whatsappshoot.logic

enum class CaptureMode { AUTOMATIC, DELAYED, MANUAL }
enum class ImageFormat(val extension: String, val mime: String) { PNG("png", "image/png"), JPEG("jpg", "image/jpeg") }

/** Which Android mechanism takes the screenshot. */
enum class CaptureMethod {
    /** Accessibility screenshot where available (Android 11+), otherwise MediaProjection. */
    AUTO,
    MEDIA_PROJECTION,
}

data class AutomationSettings(
    val mode: CaptureMode = CaptureMode.MANUAL,
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
