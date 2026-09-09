package ai.hermes.mobile.runtime.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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
import ai.hermes.mobile.runtime.bridge.observer.UiBounds

/**
 * Read-only HMR-105–108 observer.
 *
 * Window callbacks retain identity only. UI nodes are traversed only for an
 * authorized phone.read_screen request, normalized under hard limits and
 * released before the result returns. Gesture dispatch remains disabled.
 */
class CurrentAppAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        PhoneStateStore.markConnected()
        SemanticUiCaptureGateway.connect(this)
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
        SemanticUiCaptureGateway.disconnect(this)
        PhoneStateStore.markDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        SemanticUiCaptureGateway.disconnect(this)
        PhoneStateStore.markDisconnected()
        super.onDestroy()
    }

    private companion object {
        const val UI_TREE_MEDIA_TYPE = "application/vnd.hermes.ui-tree+json"
        const val UI_TREE_TTL_MILLIS = 300_000L
        const val MAX_CHILDREN_PER_NODE = 500
        val ARTIFACTS = EncryptedInMemoryArtifactStore()
    }
}
