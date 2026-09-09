package ai.hermes.mobile.runtime.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import ai.hermes.mobile.runtime.bridge.artifact.ArtifactWriteRequest
import ai.hermes.mobile.runtime.bridge.artifact.EncryptedInMemoryArtifactStore
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateStore
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.SemanticNodeInput
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCapture
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiLimits
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiTreeBuilder
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCapture
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureException
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFailureReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFormat
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotSpec
import ai.hermes.mobile.runtime.bridge.observer.UiBounds
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Read-only HMR-105–109 observer.
 *
 * Window callbacks retain identity only. UI nodes are traversed only for an
 * authorized phone.read_screen request, normalized under hard limits and
 * released before the result returns. Screenshot bytes also stay behind the
 * protected artifact boundary. Gesture dispatch remains disabled.
 */
class CurrentAppAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        PhoneStateStore.markConnected()
        SemanticUiCaptureGateway.connect(this)
        ScreenshotCaptureGateway.connect(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        PhoneStateStore.recordWindow(
            packageName = event.packageName?.toString(),
            activityName = event.className?.toString(),
            windowId = event.windowId,
        )
    }

    @Suppress("DEPRECATION")
    internal fun captureSemanticUi(limits: SemanticUiLimits): SemanticUiCapture {
        val root =
            rootInActiveWindow
                ?: throw PhoneStateUnavailableException(
                    PhoneStateUnavailableReason.ACTIVE_WINDOW_UNAVAILABLE,
                )
        val packageName =
            root.packageName?.toString()
                ?: run {
                    root.recycle()
                    throw PhoneStateUnavailableException(
                        PhoneStateUnavailableReason.UI_CAPTURE_FAILED,
                    )
                }
        val windowId = root.windowId
        val capturedAt = System.currentTimeMillis()
        val builder = SemanticUiTreeBuilder(limits)
        try {
            walk(root, builder, parentId = null, depth = 0, childIndex = 0)
        } catch (exc: PhoneStateUnavailableException) {
            throw exc
        } catch (exc: Exception) {
            throw PhoneStateUnavailableException(PhoneStateUnavailableReason.UI_CAPTURE_FAILED)
        } finally {
            root.recycle()
        }
        val tree = builder.build(packageName, capturedAt)
        val reference =
            try {
                ARTIFACTS.put(
                    ArtifactWriteRequest(
                        mediaType = UI_TREE_MEDIA_TYPE,
                        content = tree.payload,
                        sensitivity = "D3",
                        redactionStatus = if (tree.redactions.isEmpty()) "NONE" else "REDACTED",
                        timeToLiveMillis = UI_TREE_TTL_MILLIS,
                    ),
                )
            } finally {
                tree.payload.fill(0)
            }
        return try {
            SemanticUiCapture(
                state =
                    PhoneStateStore.recordUiTree(
                        packageName = packageName,
                        windowId = windowId,
                        fingerprintDigest = reference.digest,
                        captureErrors = tree.captureErrors,
                        artifact = reference,
                    ),
                artifact = reference,
                redactions = tree.redactions,
            )
        } catch (exc: Exception) {
            ARTIFACTS.delete(reference.artifactId)
            throw exc
        }
    }

    /** Captures one bounded display image after Router/PEP authorization. */
    internal fun captureScreenshot(spec: ScreenshotSpec): ScreenshotCapture {
        val before = currentVisualAnchor()
        val result = awaitScreenshot(spec.displayId)
        val encoded = encodeScreenshot(result, spec)
        val reference =
            try {
                ARTIFACTS.put(
                    ArtifactWriteRequest(
                        mediaType =
                            when (spec.format) {
                                ScreenshotFormat.PNG -> "image/png"
                                ScreenshotFormat.WEBP -> "image/webp"
                            },
                        content = encoded,
                        sensitivity = "D3",
                        redactionStatus = "NONE",
                        timeToLiveMillis = SCREENSHOT_TTL_MILLIS,
                    ),
                )
            } catch (exc: IllegalArgumentException) {
                throw ScreenshotCaptureException(ScreenshotFailureReason.IMAGE_TOO_LARGE)
            } finally {
                encoded.fill(0)
            }
        return try {
            ScreenshotCapture(
                beforeState = before,
                state =
                    PhoneStateStore.recordScreenshot(
                        expectedStateId = before.stateId,
                        fingerprintDigest = reference.digest,
                        artifact = reference,
                    ),
                artifact = reference,
            )
        } catch (exc: PhoneStateUnavailableException) {
            ARTIFACTS.delete(reference.artifactId)
            if (exc.reason == PhoneStateUnavailableReason.SCREENSHOT_WINDOW_CHANGED) {
                throw ScreenshotCaptureException(ScreenshotFailureReason.WINDOW_CHANGED)
            }
            throw exc
        } catch (exc: Exception) {
            ARTIFACTS.delete(reference.artifactId)
            throw exc
        }
    }

    @Suppress("DEPRECATION")
    private fun currentVisualAnchor() =
        rootInActiveWindow?.let { root ->
            try {
                val packageName =
                    root.packageName?.toString()
                        ?: throw PhoneStateUnavailableException(
                            PhoneStateUnavailableReason.ACTIVE_WINDOW_UNAVAILABLE,
                        )
                PhoneStateStore.visualCaptureAnchor(packageName, root.windowId)
            } finally {
                root.recycle()
            }
        } ?: throw PhoneStateUnavailableException(
            PhoneStateUnavailableReason.ACTIVE_WINDOW_UNAVAILABLE,
        )

    private fun awaitScreenshot(displayId: Int): ScreenshotResult {
        val result = CompletableFuture<ScreenshotResult>()
        try {
            takeScreenshot(
                displayId,
                SCREENSHOT_CALLBACK_EXECUTOR,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        if (!result.complete(screenshot)) screenshot.hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        result.completeExceptionally(
                            ScreenshotCaptureException(screenshotFailure(errorCode)),
                        )
                    }
                },
            )
        } catch (exc: Exception) {
            throw ScreenshotCaptureException(ScreenshotFailureReason.INTERNAL_ERROR)
        }
        return try {
            result.get(SCREENSHOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (exc: TimeoutException) {
            result.cancel(false)
            throw ScreenshotCaptureException(ScreenshotFailureReason.CAPTURE_TIMEOUT)
        } catch (exc: InterruptedException) {
            result.cancel(false)
            Thread.currentThread().interrupt()
            throw ScreenshotCaptureException(ScreenshotFailureReason.CAPTURE_TIMEOUT)
        } catch (exc: ExecutionException) {
            val cause = exc.cause
            if (cause is ScreenshotCaptureException) throw cause
            throw ScreenshotCaptureException(ScreenshotFailureReason.INTERNAL_ERROR)
        }
    }

    private fun encodeScreenshot(
        result: ScreenshotResult,
        spec: ScreenshotSpec,
    ): ByteArray {
        val buffer = result.hardwareBuffer
        try {
            val width = buffer.width
            val height = buffer.height
            if (width !in 1..MAX_SCREEN_DIMENSION || height !in 1..MAX_SCREEN_DIMENSION) {
                throw ScreenshotCaptureException(ScreenshotFailureReason.IMAGE_TOO_LARGE)
            }
            val crop = spec.crop
            if (crop != null && !crop.fits(width, height)) {
                throw ScreenshotCaptureException(ScreenshotFailureReason.CROP_OUT_OF_BOUNDS)
            }
            val outputWidth = crop?.widthPx ?: width
            val outputHeight = crop?.heightPx ?: height
            if (outputWidth.toLong() * outputHeight > MAX_SCREEN_PIXELS) {
                throw ScreenshotCaptureException(ScreenshotFailureReason.IMAGE_TOO_LARGE)
            }
            val wrapped =
                Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    ?: throw ScreenshotCaptureException(ScreenshotFailureReason.BITMAP_UNAVAILABLE)
            try {
                val selected =
                    if (crop == null) {
                        wrapped
                    } else {
                        Bitmap.createBitmap(
                            wrapped,
                            crop.xPx,
                            crop.yPx,
                            crop.widthPx,
                            crop.heightPx,
                        )
                    }
                try {
                    val software =
                        selected.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw ScreenshotCaptureException(
                                ScreenshotFailureReason.BITMAP_UNAVAILABLE,
                            )
                    try {
                        val output = BoundedByteArrayOutputStream(MAX_SCREENSHOT_BYTES)
                        val compressed =
                            software.compress(
                                when (spec.format) {
                                    ScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG
                                    ScreenshotFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSLESS
                                },
                                100,
                                output,
                            )
                        if (!compressed) {
                            throw ScreenshotCaptureException(
                                ScreenshotFailureReason.ENCODE_FAILED,
                            )
                        }
                        return output.toByteArray()
                    } catch (exc: ScreenshotSizeLimitException) {
                        throw ScreenshotCaptureException(ScreenshotFailureReason.IMAGE_TOO_LARGE)
                    } finally {
                        software.recycle()
                    }
                } finally {
                    if (selected !== wrapped) selected.recycle()
                }
            } finally {
                wrapped.recycle()
            }
        } catch (exc: ScreenshotCaptureException) {
            throw exc
        } catch (exc: Exception) {
            throw ScreenshotCaptureException(ScreenshotFailureReason.ENCODE_FAILED)
        } finally {
            buffer.close()
        }
    }

    private fun screenshotFailure(errorCode: Int): ScreenshotFailureReason =
        when (errorCode) {
            2 -> ScreenshotFailureReason.ACCESSIBILITY_ACCESS_REVOKED
            3 -> ScreenshotFailureReason.INTERVAL_TOO_SHORT
            4 -> ScreenshotFailureReason.INVALID_DISPLAY
            5 -> ScreenshotFailureReason.INVALID_WINDOW
            6 -> ScreenshotFailureReason.SECURE_WINDOW
            else -> ScreenshotFailureReason.INTERNAL_ERROR
        }

    @Suppress("DEPRECATION")
    private fun walk(
        node: AccessibilityNodeInfo,
        builder: SemanticUiTreeBuilder,
        parentId: String?,
        depth: Int,
        childIndex: Int,
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val admission =
            builder.add(
                SemanticNodeInput(
                    className = node.className?.toString(),
                    resourceId = node.viewIdResourceName,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
                    childCount = node.childCount,
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    enabled = node.isEnabled,
                    focused = node.isFocused,
                    selected = node.isSelected,
                    visibleToUser = node.isVisibleToUser,
                    password = node.isPassword,
                ),
                parentId = parentId,
                depth = depth,
                childIndex = childIndex,
            ) ?: return
        if (!admission.descend) {
            if (node.childCount > 0) builder.markDepthLimit()
            return
        }
        val childrenToVisit = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
        if (childrenToVisit < node.childCount) builder.markNodeLimit()
        for (index in 0 until childrenToVisit) {
            if (!builder.hasNodeCapacity()) {
                builder.markNodeLimit()
                break
            }
            val child = node.getChild(index)
            if (child == null) {
                builder.markUnavailableChild()
                continue
            }
            try {
                walk(child, builder, admission.nodeId, depth + 1, index)
            } finally {
                child.recycle()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        ScreenshotCaptureGateway.disconnect(this)
        SemanticUiCaptureGateway.disconnect(this)
        PhoneStateStore.markDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ScreenshotCaptureGateway.disconnect(this)
        SemanticUiCaptureGateway.disconnect(this)
        PhoneStateStore.markDisconnected()
        super.onDestroy()
    }

    private companion object {
        const val UI_TREE_MEDIA_TYPE = "application/vnd.hermes.ui-tree+json"
        const val UI_TREE_TTL_MILLIS = 300_000L
        const val SCREENSHOT_TTL_MILLIS = 300_000L
        const val MAX_CHILDREN_PER_NODE = 500
        const val SCREENSHOT_TIMEOUT_MILLIS = 1_000L
        const val MAX_SCREEN_DIMENSION = 32_768
        const val MAX_SCREEN_PIXELS = 16_777_216L
        const val MAX_SCREENSHOT_BYTES = 16_777_216
        val SCREENSHOT_CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hmr-screenshot-callback").apply { isDaemon = true }
        }
        val ARTIFACTS = EncryptedInMemoryArtifactStore()
    }
}

private class ScreenshotSizeLimitException : IllegalStateException()

private class BoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : OutputStream() {
    private val delegate = ByteArrayOutputStream()

    override fun write(value: Int) {
        ensureCapacity(1)
        delegate.write(value)
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size) {
            "invalid screenshot output range"
        }
        ensureCapacity(length)
        delegate.write(bytes, offset, length)
    }

    fun toByteArray(): ByteArray = delegate.toByteArray()

    private fun ensureCapacity(additionalBytes: Int) {
        if (delegate.size().toLong() + additionalBytes > maximumBytes) {
            throw ScreenshotSizeLimitException()
        }
    }
}
