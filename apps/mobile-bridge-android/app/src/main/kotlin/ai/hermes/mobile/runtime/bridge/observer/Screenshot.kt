package ai.hermes.mobile.runtime.bridge.observer

internal enum class ScreenshotFormat {
    PNG,
    WEBP,
}

internal data class ScreenshotCrop(
    val xPx: Int,
    val yPx: Int,
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(xPx in 0..32_767) { "screenshot crop x is invalid" }
        require(yPx in 0..32_767) { "screenshot crop y is invalid" }
        require(widthPx in 1..32_768) { "screenshot crop width is invalid" }
        require(heightPx in 1..32_768) { "screenshot crop height is invalid" }
    }

    fun fits(width: Int, height: Int): Boolean =
        xPx.toLong() + widthPx <= width.toLong() &&
            yPx.toLong() + heightPx <= height.toLong()
}

internal data class ScreenshotSpec(
    val displayId: Int = 0,
    val format: ScreenshotFormat = ScreenshotFormat.PNG,
    val crop: ScreenshotCrop? = null,
) {
    init {
        require(displayId in 0..7) { "screenshot display id is invalid" }
    }
}

internal enum class ScreenshotFailureReason(
    val errorCode: String,
    val retryDisposition: String,
    val recoverable: Boolean,
) {
    INTERNAL_ERROR("CAPABILITY_UNAVAILABLE", "RETRY_SAME_ACTION", true),
    ACCESSIBILITY_ACCESS_REVOKED("PERMISSION_DENIED", "ASK_USER", false),
    INTERVAL_TOO_SHORT("ACTION_REJECTED", "RETRY_SAME_ACTION", true),
    INVALID_DISPLAY("ACTION_REJECTED", "NEVER", false),
    INVALID_WINDOW("CAPABILITY_UNAVAILABLE", "REOBSERVE", true),
    SECURE_WINDOW("PERMISSION_DENIED", "NEVER", false),
    BITMAP_UNAVAILABLE("CAPABILITY_UNAVAILABLE", "RETRY_SAME_ACTION", true),
    CROP_OUT_OF_BOUNDS("ACTION_REJECTED", "REOBSERVE", true),
    IMAGE_TOO_LARGE("ACTION_REJECTED", "REPLAN", true),
    ENCODE_FAILED("CAPABILITY_UNAVAILABLE", "RETRY_SAME_ACTION", true),
    CAPTURE_TIMEOUT("ACTION_TIMEOUT", "RETRY_SAME_ACTION", true),
    WINDOW_CHANGED("UNEXPECTED_TRANSITION", "REOBSERVE", true),
}

internal class ScreenshotCaptureException(
    val reason: ScreenshotFailureReason,
) : IllegalStateException("screenshot capture failed: $reason")

internal data class ScreenshotCapture(
    val beforeState: PhoneStateSnapshot,
    val state: PhoneStateSnapshot,
    val artifact: ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference,
)

internal interface ScreenshotCaptureSource {
    fun availability(): PhoneStateUnavailableReason?

    fun capture(spec: ScreenshotSpec): ScreenshotCapture
}
