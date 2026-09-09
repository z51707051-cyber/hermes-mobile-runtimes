package ai.hermes.mobile.runtime.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
import android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS
import android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT
import ai.hermes.mobile.runtime.bridge.artifact.ArtifactWriteRequest
import ai.hermes.mobile.runtime.bridge.artifact.RuntimeArtifactStore
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateStore
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableException
import ai.hermes.mobile.runtime.bridge.observer.PhoneStateUnavailableReason
import ai.hermes.mobile.runtime.bridge.observer.BackCommand
import ai.hermes.mobile.runtime.bridge.observer.CoordinateNavigationTarget
import ai.hermes.mobile.runtime.bridge.observer.HomeCommand
import ai.hermes.mobile.runtime.bridge.observer.LongPressCommand
import ai.hermes.mobile.runtime.bridge.observer.NavigationCommand
import ai.hermes.mobile.runtime.bridge.observer.NavigationExecution
import ai.hermes.mobile.runtime.bridge.observer.NavigationFailureException
import ai.hermes.mobile.runtime.bridge.observer.NavigationFailureReason
import ai.hermes.mobile.runtime.bridge.observer.NavigationVerificationRequest
import ai.hermes.mobile.runtime.bridge.observer.NavigationVerifier
import ai.hermes.mobile.runtime.bridge.observer.NodeNavigationTarget
import ai.hermes.mobile.runtime.bridge.observer.NormalizedSemanticUiTree
import ai.hermes.mobile.runtime.bridge.observer.OpenAppCommand
import ai.hermes.mobile.runtime.bridge.observer.ResolvedSemanticTarget
import ai.hermes.mobile.runtime.bridge.observer.SemanticActionTargetDescriptor
import ai.hermes.mobile.runtime.bridge.observer.SemanticNodeInput
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiCapture
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiLimits
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiProbe
import ai.hermes.mobile.runtime.bridge.observer.SemanticUiTreeBuilder
import ai.hermes.mobile.runtime.bridge.observer.SemanticTargetException
import ai.hermes.mobile.runtime.bridge.observer.SemanticTargetRegistry
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCapture
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotCaptureException
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFailureReason
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotFormat
import ai.hermes.mobile.runtime.bridge.observer.ScreenshotSpec
import ai.hermes.mobile.runtime.bridge.observer.SwipeCommand
import ai.hermes.mobile.runtime.bridge.observer.TapCommand
import ai.hermes.mobile.runtime.bridge.observer.TypeCommand
import ai.hermes.mobile.runtime.bridge.observer.UiBounds
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMR-105–110 observer and guarded navigation adapter.
 *
 * Window callbacks retain identity only. UI nodes are traversed only for an
 * authorized phone.read_screen request, normalized under hard limits and
 * released before the result returns. Screenshot bytes also stay behind the
 * protected artifact boundary. HMR-110 mutations are reachable only through
 * the Router/PEP and are re-resolved against an exact observed generation.
 */
class CurrentAppAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        PhoneStateStore.markConnected()
        SemanticUiCaptureGateway.connect(this)
        ScreenshotCaptureGateway.connect(this)
        NavigationActionGateway.connect(this)
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
        val captured = collectSemanticUi(limits)
        val packageName = captured.packageName
        val windowId = captured.windowId
        val tree = captured.tree
        val reference =
            try {
                RuntimeArtifactStore.put(
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
        var statePublished = false
        return try {
            val state =
                PhoneStateStore.recordUiTree(
                    packageName = packageName,
                    windowId = windowId,
                    fingerprintDigest = reference.digest,
                    captureErrors = tree.captureErrors,
                    artifact = reference,
                )
            statePublished = true
            if (state.captureStatus == ai.hermes.mobile.runtime.bridge.observer.PhoneStateCaptureStatus.COMPLETE) {
                TARGETS.register(
                    stateId = state.stateId,
                    packageName = packageName,
                    windowId = windowId,
                    targets = tree.actionTargets,
                )
            }
            SemanticUiCapture(
                state = state,
                artifact = reference,
                redactions = tree.redactions,
                visibleText = tree.visibleText,
            )
        } catch (exc: Exception) {
            if (statePublished) {
                PhoneStateStore.invalidateCurrent()
                TARGETS.clear()
            }
            RuntimeArtifactStore.delete(reference.artifactId)
            throw exc
        }
    }

    /** Captures condition evidence without creating a retrievable UI-tree artifact. */
    internal fun probeSemanticUi(limits: SemanticUiLimits): SemanticUiProbe {
        val captured = collectSemanticUi(limits)
        val tree = captured.tree
        return try {
            val fingerprint = protectedProbeDigest(tree.payload)
            val state =
                PhoneStateStore.recordUiProbe(
                    packageName = captured.packageName,
                    windowId = captured.windowId,
                    fingerprintDigest = fingerprint,
                    captureErrors = tree.captureErrors,
                )
            SemanticUiProbe(
                state = state,
                visibleText = tree.visibleText,
                redactions = tree.redactions,
            )
        } finally {
            tree.payload.fill(0)
        }
    }

    @Suppress("DEPRECATION")
    private fun collectSemanticUi(limits: SemanticUiLimits): CapturedSemanticUi {
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
        return CapturedSemanticUi(packageName, windowId, builder.build(packageName))
    }

    private fun protectedProbeDigest(payload: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(PROBE_DIGEST_KEY, "HmacSHA256"))
        return "sha256:" + mac.doFinal(payload).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    /** Device-side semantic risk re-resolution performed by the Android PEP. */
    internal fun requiredRisk(command: NavigationCommand): String =
        when (command) {
            is TapCommand -> targetRisk(command.target, "L1")
            is LongPressCommand -> targetRisk(command.target, "L1")
            is TypeCommand ->
                command.target?.let { targetRisk(it, "L2") }
                    ?: throw NavigationFailureException(
                        NavigationFailureReason.EXPLICIT_TYPE_TARGET_REQUIRED,
                    )
            is SwipeCommand ->
                if (verticalNavigationSwipe(command)) "L1" else "L2"
            is BackCommand, is HomeCommand, is OpenAppCommand -> "L1"
        }

    /** Executes exactly once, then obtains a fresh semantic observation. */
    internal fun executeNavigation(
        command: NavigationCommand,
        verification: NavigationVerificationRequest?,
    ): NavigationExecution {
        val maximumAge =
            command.precondition?.maximumAgeMillis
                ?: ai.hermes.mobile.runtime.bridge.observer.PhoneStateObserver.DEFAULT_MAXIMUM_AGE_MILLIS
        val before = PhoneStateStore.current(maximumAge)
        if (currentVisualAnchor().stateId != before.stateId) {
            throw NavigationFailureException(NavigationFailureReason.TARGET_CHANGED, before)
        }
        command.precondition?.let { precondition ->
            if (
                before.stateId != precondition.stateId ||
                precondition.foregroundPackage?.let { it != before.packageName } == true
            ) {
                throw NavigationFailureException(NavigationFailureReason.TARGET_CHANGED, before)
            }
        }
        val observed =
            try {
                val accepted =
                    when (command) {
                        is TapCommand -> performTap(command)
                        is LongPressCommand -> performLongPress(command)
                        is TypeCommand -> performType(command)
                        is SwipeCommand -> performSwipe(command)
                        is BackCommand -> performGlobalAction(GLOBAL_ACTION_BACK)
                        is HomeCommand -> performGlobalAction(GLOBAL_ACTION_HOME)
                        is OpenAppCommand -> openApp(command.packageName)
                    }
                if (!accepted) {
                    throw NavigationFailureException(
                        NavigationFailureReason.ACTION_NOT_ACCEPTED,
                    )
                }
                try {
                    observeAfterAction()
                } catch (exc: Exception) {
                    throw NavigationFailureException(
                        NavigationFailureReason.POST_ACTION_OBSERVATION_FAILED,
                    )
                }
            } catch (exc: NavigationFailureException) {
                exc.beforeState = before
                throw exc
            }
        val verificationResult = NavigationVerifier.evaluate(observed, verification)
        return NavigationExecution(
            beforeState = before,
            afterState = observed.state,
            verificationStatus = verificationResult.status,
            verificationExplanation = verificationResult.explanation,
            redactions = observed.redactions,
        )
    }

    private fun targetRisk(
        target: ai.hermes.mobile.runtime.bridge.observer.NavigationTarget,
        minimum: String,
    ): String =
        when (target) {
            is CoordinateNavigationTarget -> "L4"
            is NodeNavigationTarget ->
                withResolvedNode(target) { _, resolved ->
                    maximumRisk(minimum, resolved.requiredRisk)
                }
        }

    private fun performTap(command: TapCommand): Boolean =
        when (val target = command.target) {
            is CoordinateNavigationTarget ->
                throw NavigationFailureException(
                    NavigationFailureReason.COORDINATE_TARGET_FORBIDDEN,
                )
            is NodeNavigationTarget ->
                withResolvedNode(target) { node, resolved ->
                    if (
                        !resolved.descriptor.clickable ||
                        !resolved.descriptor.enabled ||
                        !resolved.descriptor.visibleToUser
                    ) {
                        throw NavigationFailureException(
                            NavigationFailureReason.NODE_TARGET_UNAVAILABLE,
                        )
                    }
                    node.performAction(ACTION_CLICK)
                }
        }

    private fun performLongPress(command: LongPressCommand): Boolean =
        when (val target = command.target) {
            is CoordinateNavigationTarget ->
                throw NavigationFailureException(
                    NavigationFailureReason.COORDINATE_TARGET_FORBIDDEN,
                )
            is NodeNavigationTarget ->
                withResolvedNode(target) { _, resolved ->
                    val bounds = resolved.descriptor.bounds
                    if (
                        !resolved.descriptor.enabled ||
                        !resolved.descriptor.visibleToUser ||
                        !validGestureBounds(bounds)
                    ) {
                        throw NavigationFailureException(
                            NavigationFailureReason.NODE_TARGET_UNAVAILABLE,
                        )
                    }
                    dispatchPath(
                        startX = ((bounds.left.toLong() + bounds.right) / 2L).toFloat(),
                        startY = ((bounds.top.toLong() + bounds.bottom) / 2L).toFloat(),
                        endX = null,
                        endY = null,
                        durationMillis = command.durationMillis,
                    )
                }
        }

    private fun performSwipe(command: SwipeCommand): Boolean =
        dispatchPath(
            startX = command.start.xPx.toFloat(),
            startY = command.start.yPx.toFloat(),
            endX = command.end.xPx.toFloat(),
            endY = command.end.yPx.toFloat(),
            durationMillis = command.durationMillis,
        )

    private fun performType(command: TypeCommand): Boolean {
        val target =
            command.target
                ?: throw NavigationFailureException(
                    NavigationFailureReason.EXPLICIT_TYPE_TARGET_REQUIRED,
                )
        return withResolvedNode(target) { node, resolved ->
            setNodeText(node, resolved.descriptor, command)
        }
    }

    private fun setNodeText(
        node: AccessibilityNodeInfo,
        descriptor: SemanticActionTargetDescriptor,
        command: TypeCommand,
    ): Boolean {
        if (!descriptor.editable || !descriptor.enabled || !descriptor.visibleToUser) {
            throw NavigationFailureException(NavigationFailureReason.TYPE_TARGET_UNAVAILABLE)
        }
        if (!command.replace && descriptor.password) {
            throw NavigationFailureException(NavigationFailureReason.PASSWORD_APPEND_FORBIDDEN)
        }
        val existing =
            if (command.replace) {
                null
            } else {
                val live = normalized(node.text?.toString(), MAX_RESULT_TEXT_CHARS)
                if (live.orEmpty().length > MAX_TARGET_TEXT_CHARS) {
                    throw NavigationFailureException(NavigationFailureReason.TEXT_RESULT_TOO_LARGE)
                }
                if (live != descriptor.text) {
                    throw NavigationFailureException(NavigationFailureReason.TARGET_CHANGED)
                }
                live
            }
        val value = if (command.replace) command.text else existing.orEmpty() + command.text
        if (value.length > MAX_RESULT_TEXT_CHARS) {
            throw NavigationFailureException(NavigationFailureReason.TEXT_RESULT_TOO_LARGE)
        }
        node.performAction(ACTION_FOCUS)
        val arguments =
            Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value,
                )
            }
        return node.performAction(ACTION_SET_TEXT, arguments)
    }

    private fun openApp(packageName: String): Boolean {
        val component =
            packageManager.getLaunchIntentForPackage(packageName)?.component
                ?: throw NavigationFailureException(NavigationFailureReason.APP_NOT_FOUND)
        if (component.packageName != packageName) {
            throw NavigationFailureException(NavigationFailureReason.APP_LAUNCH_DENIED)
        }
        val intent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            startActivity(intent)
            true
        } catch (exc: Exception) {
            throw NavigationFailureException(NavigationFailureReason.APP_LAUNCH_DENIED)
        }
    }

    private fun dispatchPath(
        startX: Float,
        startY: Float,
        endX: Float?,
        endY: Float?,
        durationMillis: Long,
    ): Boolean {
        val path = Path().apply { moveTo(startX, startY) }
        if (endX != null && endY != null) path.lineTo(endX, endY)
        val gesture =
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis))
                .build()
        val result = CompletableFuture<Boolean>()
        val dispatched =
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        result.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        result.complete(false)
                    }
                },
                NAVIGATION_CALLBACK_HANDLER,
            )
        if (!dispatched) return false
        return try {
            if (
                result.get(
                    (durationMillis + GESTURE_COMPLETION_GRACE_MILLIS).coerceAtMost(
                        MAX_GESTURE_WAIT_MILLIS,
                    ),
                    TimeUnit.MILLISECONDS,
                )
            ) {
                true
            } else {
                throw NavigationFailureException(NavigationFailureReason.GESTURE_CANCELLED)
            }
        } catch (exc: TimeoutException) {
            throw NavigationFailureException(NavigationFailureReason.GESTURE_TIMEOUT)
        } catch (exc: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NavigationFailureException(NavigationFailureReason.GESTURE_TIMEOUT)
        } catch (exc: ExecutionException) {
            throw NavigationFailureException(NavigationFailureReason.GESTURE_CANCELLED)
        }
    }

    private fun observeAfterAction(): SemanticUiCapture {
        SystemClock.sleep(POST_ACTION_SETTLE_MILLIS)
        val deadline = SystemClock.elapsedRealtime() + POST_ACTION_OBSERVE_MILLIS
        var lastFailure: PhoneStateUnavailableException? = null
        do {
            try {
                return captureSemanticUi(POST_ACTION_UI_LIMITS)
            } catch (exc: PhoneStateUnavailableException) {
                lastFailure = exc
                if (
                    exc.reason !in
                    setOf(
                        PhoneStateUnavailableReason.ACTIVE_WINDOW_UNAVAILABLE,
                        PhoneStateUnavailableReason.UI_WINDOW_MISMATCH,
                        PhoneStateUnavailableReason.NO_WINDOW_STATE,
                    )
                ) {
                    throw exc
                }
                SystemClock.sleep(POST_ACTION_POLL_MILLIS)
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        throw lastFailure
            ?: PhoneStateUnavailableException(PhoneStateUnavailableReason.UI_CAPTURE_FAILED)
    }

    private fun targetDescriptor(
        node: AccessibilityNodeInfo,
        nodeId: String,
    ): SemanticActionTargetDescriptor {
        val bounds = Rect().also(node::getBoundsInScreen)
        return SemanticActionTargetDescriptor(
            nodeId = nodeId,
            className = normalizedMetadata(node.className?.toString()),
            resourceId = normalizedMetadata(node.viewIdResourceName),
            text = normalizedTargetText(node.text?.toString(), node.isPassword),
            contentDescription =
                normalizedTargetText(node.contentDescription?.toString(), node.isPassword),
            bounds = UiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
            password = node.isPassword,
        )
    }

    @Suppress("DEPRECATION")
    private fun <T> withResolvedNode(
        target: NodeNavigationTarget,
        operation: (AccessibilityNodeInfo, ResolvedSemanticTarget) -> T,
    ): T {
        val ordinal = target.nodeId.removePrefix("node-").toIntOrNull()
        if (ordinal == null || ordinal !in 1..MAX_SEMANTIC_TARGETS) {
            throw NavigationFailureException(NavigationFailureReason.NODE_TARGET_UNAVAILABLE)
        }
        val root =
            rootInActiveWindow
                ?: throw NavigationFailureException(
                    NavigationFailureReason.NODE_TARGET_UNAVAILABLE,
                )
        val packageName = root.packageName?.toString()
        if (packageName == null) {
            root.recycle()
            throw NavigationFailureException(NavigationFailureReason.NODE_TARGET_UNAVAILABLE)
        }
        val windowId = root.windowId
        var visited = 0
        fun search(
            node: AccessibilityNodeInfo,
            depth: Int,
        ): T? {
            visited += 1
            if (visited == ordinal) {
                val descriptor = targetDescriptor(node, target.nodeId)
                val resolved = TARGETS.resolve(target.stateId, packageName, windowId, descriptor)
                return operation(node, resolved)
            }
            if (depth >= MAX_TARGET_DEPTH || visited >= MAX_SEMANTIC_TARGETS) return null
            val children = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
            for (index in 0 until children) {
                val child = node.getChild(index) ?: continue
                try {
                    val result = search(child, depth + 1)
                    if (result != null) return result
                } finally {
                    child.recycle()
                }
            }
            return null
        }
        return try {
            search(root, 0)
                ?: throw NavigationFailureException(
                    NavigationFailureReason.NODE_TARGET_UNAVAILABLE,
                )
        } catch (exc: SemanticTargetException) {
            throw NavigationFailureException(NavigationFailureReason.TARGET_CHANGED)
        } finally {
            root.recycle()
        }
    }

    private fun normalizedTargetText(
        value: String?,
        password: Boolean,
    ): String? =
        if (password) null else normalized(value, MAX_TARGET_TEXT_CHARS)

    private fun normalizedMetadata(value: String?): String? = normalized(value, MAX_TARGET_METADATA_CHARS)

    private fun normalized(
        value: String?,
        maximumChars: Int,
    ): String? {
        val normalized =
            value
                ?.map { character -> if (character.isISOControl()) ' ' else character }
                ?.joinToString("")
                ?.trim()
                ?.replace(TARGET_WHITESPACE, " ")
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        var end = maximumChars.coerceAtMost(normalized.length)
        if (
            end in 1 until normalized.length &&
            Character.isHighSurrogate(normalized[end - 1]) &&
            Character.isLowSurrogate(normalized[end])
        ) {
            end -= 1
        }
        return normalized.substring(0, end)
    }

    private fun maximumRisk(
        first: String,
        second: String,
    ): String = if (first.removePrefix("L").toInt() >= second.removePrefix("L").toInt()) first else second

    private fun verticalNavigationSwipe(command: SwipeCommand): Boolean {
        val horizontal = kotlin.math.abs(command.end.xPx - command.start.xPx)
        val vertical = kotlin.math.abs(command.end.yPx - command.start.yPx)
        return vertical.toLong() >= MINIMUM_NAVIGATION_SWIPE_PX &&
            vertical.toLong() >= horizontal.toLong() * 2L
    }

    private fun validGestureBounds(bounds: UiBounds): Boolean =
        bounds.left in 0 until MAX_SCREEN_DIMENSION &&
            bounds.top in 0 until MAX_SCREEN_DIMENSION &&
            bounds.right in 1..MAX_SCREEN_DIMENSION &&
            bounds.bottom in 1..MAX_SCREEN_DIMENSION &&
            bounds.right > bounds.left &&
            bounds.bottom > bounds.top

    /** Captures one bounded display image after Router/PEP authorization. */
    internal fun captureScreenshot(spec: ScreenshotSpec): ScreenshotCapture {
        val before = currentVisualAnchor()
        val result = awaitScreenshot(spec.displayId)
        val encoded = encodeScreenshot(result, spec)
        val reference =
            try {
                RuntimeArtifactStore.put(
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
            RuntimeArtifactStore.delete(reference.artifactId)
            if (exc.reason == PhoneStateUnavailableReason.SCREENSHOT_WINDOW_CHANGED) {
                throw ScreenshotCaptureException(ScreenshotFailureReason.WINDOW_CHANGED)
            }
            throw exc
        } catch (exc: Exception) {
            RuntimeArtifactStore.delete(reference.artifactId)
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
        NavigationActionGateway.disconnect(this)
        ScreenshotCaptureGateway.disconnect(this)
        SemanticUiCaptureGateway.disconnect(this)
        PhoneStateStore.markDisconnected()
        TARGETS.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        NavigationActionGateway.disconnect(this)
        ScreenshotCaptureGateway.disconnect(this)
        SemanticUiCaptureGateway.disconnect(this)
        PhoneStateStore.markDisconnected()
        TARGETS.clear()
        super.onDestroy()
    }

    private companion object {
        data class CapturedSemanticUi(
            val packageName: String,
            val windowId: Int,
            val tree: NormalizedSemanticUiTree,
        )

        const val UI_TREE_MEDIA_TYPE = "application/vnd.hermes.ui-tree+json"
        const val UI_TREE_TTL_MILLIS = 300_000L
        const val SCREENSHOT_TTL_MILLIS = 300_000L
        const val MAX_CHILDREN_PER_NODE = 500
        const val SCREENSHOT_TIMEOUT_MILLIS = 1_000L
        const val MAX_SCREEN_DIMENSION = 32_768
        const val MAX_SCREEN_PIXELS = 16_777_216L
        const val MAX_SCREENSHOT_BYTES = 16_777_216
        const val MAX_RESULT_TEXT_CHARS = 20_000
        const val MAX_SEMANTIC_TARGETS = 500
        const val MAX_TARGET_DEPTH = 64
        const val MAX_TARGET_TEXT_CHARS = 4_096
        const val MAX_TARGET_METADATA_CHARS = 512
        const val POST_ACTION_SETTLE_MILLIS = 200L
        const val POST_ACTION_OBSERVE_MILLIS = 1_500L
        const val POST_ACTION_POLL_MILLIS = 100L
        const val GESTURE_COMPLETION_GRACE_MILLIS = 1_000L
        const val MAX_GESTURE_WAIT_MILLIS = 6_000L
        const val MINIMUM_NAVIGATION_SWIPE_PX = 64L
        val POST_ACTION_UI_LIMITS = SemanticUiLimits(200, 10_000)
        val TARGET_WHITESPACE = Regex("\\s+")
        val PROBE_DIGEST_KEY = ByteArray(32).also(SecureRandom()::nextBytes)
        val TARGETS = SemanticTargetRegistry()
        val NAVIGATION_CALLBACK_THREAD =
            HandlerThread("hmr-navigation-callback").apply { start() }
        val NAVIGATION_CALLBACK_HANDLER = Handler(NAVIGATION_CALLBACK_THREAD.looper)
        val SCREENSHOT_CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hmr-screenshot-callback").apply { isDaemon = true }
        }
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
