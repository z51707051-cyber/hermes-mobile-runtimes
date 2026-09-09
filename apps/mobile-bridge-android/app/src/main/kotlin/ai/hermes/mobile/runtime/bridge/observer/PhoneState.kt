package ai.hermes.mobile.runtime.bridge.observer

import android.os.SystemClock
import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference
import java.security.MessageDigest
import java.util.UUID

internal enum class PhoneStateUnavailableReason {
    SERVICE_DISCONNECTED,
    NO_WINDOW_STATE,
    STALE_WINDOW_STATE,
    ACTIVE_WINDOW_UNAVAILABLE,
    UI_WINDOW_MISMATCH,
    UI_CAPTURE_FAILED,
    SCREENSHOT_WINDOW_CHANGED,
}

internal class PhoneStateUnavailableException(
    val reason: PhoneStateUnavailableReason,
) : IllegalStateException("phone state observation is unavailable: $reason")

internal enum class ScreenTransition {
    NONE,
    CHANGED,
    UNKNOWN,
}

internal enum class PhoneStateCaptureStatus {
    COMPLETE,
    PARTIAL,
    INCOHERENT,
}

internal enum class ScreenFingerprintBasis {
    WINDOW_IDENTITY,
    UI_HIERARCHY,
    SCREENSHOT,
    FUSED,
}

internal data class ScreenFingerprint(
    val basis: ScreenFingerprintBasis,
    val digest: String,
)

internal data class PhoneStateSnapshot(
    val stateId: String,
    val previousStateId: String?,
    val packageName: String,
    val activityName: String?,
    val screenFingerprint: ScreenFingerprint,
    val captureStatus: PhoneStateCaptureStatus,
    val captureErrors: List<String>,
    val transition: ScreenTransition,
    val capturedAtEpochMillis: Long,
    val freshnessMillis: Long,
    val artifacts: List<ArtifactReference> = emptyList(),
)

internal fun interface ElapsedRealtimeClock {
    fun nowMillis(): Long
}

internal fun interface EpochClock {
    fun nowMillis(): Long
}

internal fun interface StateIdGenerator {
    fun next(): String
}

internal interface PhoneStateSource {
    fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason?

    fun current(maximumAgeMillis: Long): PhoneStateSnapshot
}

/**
 * Stores only the latest safe window identity from the Accessibility callback.
 *
 * It never retains AccessibilityEvent, AccessibilityNodeInfo, text, bounds, or
 * any object that can inspect or mutate the UI.
 */
internal class PhoneStateObserver(
    private val elapsedClock: ElapsedRealtimeClock = ElapsedRealtimeClock { SystemClock.elapsedRealtime() },
    private val epochClock: EpochClock = EpochClock { System.currentTimeMillis() },
    private val stateIds: StateIdGenerator = StateIdGenerator { "state:${UUID.randomUUID()}" },
) : PhoneStateSource {
    private data class StoredObservation(
        val stateId: String,
        val previousStateId: String?,
        val packageName: String,
        val activityName: String?,
        val windowId: Int?,
        val screenFingerprint: ScreenFingerprint,
        val captureStatus: PhoneStateCaptureStatus,
        val captureErrors: List<String>,
        val transition: ScreenTransition,
        val capturedAtEpochMillis: Long,
        val capturedAtElapsedMillis: Long,
        val artifacts: List<ArtifactReference>,
    )

    private var connected = false
    private var latest: StoredObservation? = null

    @Synchronized
    fun markConnected() {
        connected = true
    }

    @Synchronized
    fun markDisconnected() {
        connected = false
        latest = null
    }

    /** Fails closed after a dependent artifact/index commit cannot complete. */
    @Synchronized
    fun invalidateCurrent() {
        latest = null
    }

    @Synchronized
    fun recordWindow(
        packageName: String?,
        activityName: String?,
        windowId: Int? = null,
    ): Boolean {
        if (!connected) return false
        val safePackage = packageName?.takeIf(::validPackageName) ?: return false
        val safeActivity =
            activityName
                ?.let { value -> if (value.startsWith('.')) "$safePackage$value" else value }
                ?.takeIf(::validActivityName)
        val previous = latest
        val fingerprint = windowIdentityFingerprint(safePackage, safeActivity)
        val captureErrors =
            if (safeActivity == null) listOf(FOREGROUND_ACTIVITY_UNAVAILABLE) else emptyList()
        val captureStatus =
            if (captureErrors.isEmpty()) {
                PhoneStateCaptureStatus.COMPLETE
            } else {
                PhoneStateCaptureStatus.PARTIAL
            }
        latest =
            StoredObservation(
                stateId = stateIds.next(),
                previousStateId = previous?.stateId,
                packageName = safePackage,
                activityName = safeActivity,
                windowId = windowId,
                screenFingerprint = fingerprint,
                captureStatus = captureStatus,
                captureErrors = captureErrors,
                transition =
                    when {
                        captureStatus != PhoneStateCaptureStatus.COMPLETE -> ScreenTransition.UNKNOWN
                        previous == null -> ScreenTransition.UNKNOWN
                        previous.captureStatus != PhoneStateCaptureStatus.COMPLETE -> ScreenTransition.UNKNOWN
                        previous.screenFingerprint == fingerprint -> ScreenTransition.NONE
                        else -> ScreenTransition.CHANGED
                    },
                capturedAtEpochMillis = epochClock.nowMillis(),
                capturedAtElapsedMillis = elapsedClock.nowMillis(),
                artifacts = emptyList(),
            )
        return true
    }

    @Synchronized
    fun recordUiTree(
        packageName: String,
        windowId: Int,
        fingerprintDigest: String,
        captureErrors: List<String>,
        artifact: ArtifactReference,
    ): PhoneStateSnapshot {
        if (!connected) throw PhoneStateUnavailableException(PhoneStateUnavailableReason.SERVICE_DISCONNECTED)
        val previous =
            latest
                ?: throw PhoneStateUnavailableException(PhoneStateUnavailableReason.NO_WINDOW_STATE)
        if (previous.packageName != packageName || previous.windowId != windowId) {
            throw PhoneStateUnavailableException(PhoneStateUnavailableReason.UI_WINDOW_MISMATCH)
        }
        require(DIGEST.matches(fingerprintDigest)) { "UI hierarchy fingerprint is invalid" }
        require(artifact.digest == fingerprintDigest) { "UI artifact fingerprint mismatch" }
        require(
            artifact.mediaType == UI_TREE_MEDIA_TYPE &&
                artifact.sensitivity == "D3" &&
                artifact.retentionClass == "EPHEMERAL"
        ) {
            "UI artifact policy is invalid"
        }
        require(captureErrors.size <= MAX_CAPTURE_ERRORS) { "too many UI capture errors" }
        require(captureErrors.distinct().size == captureErrors.size) {
            "UI capture errors must be unique"
        }
        require(captureErrors.all(STABLE_ERROR::matches)) { "UI capture error is invalid" }
        val fingerprint =
            ScreenFingerprint(
                basis = ScreenFingerprintBasis.UI_HIERARCHY,
                digest = fingerprintDigest,
            )
        val captureStatus =
            if (captureErrors.isEmpty()) {
                PhoneStateCaptureStatus.COMPLETE
            } else {
                PhoneStateCaptureStatus.PARTIAL
            }
        val observation =
            StoredObservation(
                stateId = stateIds.next(),
                previousStateId = previous.stateId,
                packageName = packageName,
                activityName = previous.activityName,
                windowId = windowId,
                screenFingerprint = fingerprint,
                captureStatus = captureStatus,
                captureErrors = captureErrors.sorted(),
                transition =
                    when {
                        captureStatus != PhoneStateCaptureStatus.COMPLETE -> ScreenTransition.UNKNOWN
                        previous.captureStatus != PhoneStateCaptureStatus.COMPLETE -> ScreenTransition.UNKNOWN
                        previous.screenFingerprint.basis != fingerprint.basis -> ScreenTransition.UNKNOWN
                        previous.screenFingerprint == fingerprint -> ScreenTransition.NONE
                        else -> ScreenTransition.CHANGED
                    },
                capturedAtEpochMillis = epochClock.nowMillis(),
                capturedAtElapsedMillis = elapsedClock.nowMillis(),
                artifacts = listOf(artifact),
            )
        latest = observation
        return snapshot(observation)
    }

    @Synchronized
    fun recordScreenshot(
        expectedStateId: String,
        fingerprintDigest: String,
        artifact: ArtifactReference,
    ): PhoneStateSnapshot {
        if (!connected) {
            throw PhoneStateUnavailableException(PhoneStateUnavailableReason.SERVICE_DISCONNECTED)
        }
        val previous =
            latest
                ?: throw PhoneStateUnavailableException(PhoneStateUnavailableReason.NO_WINDOW_STATE)
        if (previous.stateId != expectedStateId) {
            throw PhoneStateUnavailableException(
                PhoneStateUnavailableReason.SCREENSHOT_WINDOW_CHANGED,
            )
        }
        require(DIGEST.matches(fingerprintDigest)) { "screenshot fingerprint is invalid" }
        require(artifact.digest == fingerprintDigest) { "screenshot artifact fingerprint mismatch" }
        require(
            artifact.mediaType in SCREENSHOT_MEDIA_TYPES &&
                artifact.sensitivity == "D3" &&
                artifact.redactionStatus == "NONE" &&
                artifact.retentionClass == "EPHEMERAL"
        ) {
            "screenshot artifact policy is invalid"
        }
        val fingerprint =
            ScreenFingerprint(
                basis = ScreenFingerprintBasis.SCREENSHOT,
                digest = fingerprintDigest,
            )
        val observation =
            StoredObservation(
                stateId = stateIds.next(),
                previousStateId = previous.stateId,
                packageName = previous.packageName,
                activityName = previous.activityName,
                windowId = previous.windowId,
                screenFingerprint = fingerprint,
                captureStatus = PhoneStateCaptureStatus.COMPLETE,
                captureErrors = emptyList(),
                transition =
                    if (previous.captureStatus == PhoneStateCaptureStatus.COMPLETE &&
                        previous.screenFingerprint.basis == ScreenFingerprintBasis.SCREENSHOT
                    ) {
                        if (previous.screenFingerprint == fingerprint) {
                            ScreenTransition.NONE
                        } else {
                            ScreenTransition.CHANGED
                        }
                    } else {
                        ScreenTransition.UNKNOWN
                    },
                capturedAtEpochMillis = epochClock.nowMillis(),
                capturedAtElapsedMillis = elapsedClock.nowMillis(),
                artifacts = listOf(artifact),
            )
        latest = observation
        return snapshot(observation)
    }

    /**
     * Revalidates a possibly stale identity against a live active-window root.
     * This is read-only capture preparation, never a mutation precondition.
     */
    @Synchronized
    fun visualCaptureAnchor(
        packageName: String,
        windowId: Int,
    ): PhoneStateSnapshot {
        if (!connected) {
            throw PhoneStateUnavailableException(PhoneStateUnavailableReason.SERVICE_DISCONNECTED)
        }
        val observation =
            latest
                ?: throw PhoneStateUnavailableException(PhoneStateUnavailableReason.NO_WINDOW_STATE)
        if (observation.packageName != packageName || observation.windowId != windowId) {
            throw PhoneStateUnavailableException(PhoneStateUnavailableReason.UI_WINDOW_MISMATCH)
        }
        return snapshot(observation)
    }

    @Synchronized
    override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? {
        require(maximumAgeMillis in 1..MAXIMUM_FRESHNESS_MILLIS) {
            "maximum current-app age must be within 1..$MAXIMUM_FRESHNESS_MILLIS ms"
        }
        if (!connected) return PhoneStateUnavailableReason.SERVICE_DISCONNECTED
        val observation = latest ?: return PhoneStateUnavailableReason.NO_WINDOW_STATE
        if (freshness(observation) > maximumAgeMillis) {
            return PhoneStateUnavailableReason.STALE_WINDOW_STATE
        }
        return null
    }

    @Synchronized
    override fun current(maximumAgeMillis: Long): PhoneStateSnapshot {
        availability(maximumAgeMillis)?.let { throw PhoneStateUnavailableException(it) }
        val observation = checkNotNull(latest)
        return snapshot(observation)
    }

    private fun snapshot(observation: StoredObservation): PhoneStateSnapshot =
        PhoneStateSnapshot(
            stateId = observation.stateId,
            previousStateId = observation.previousStateId,
            packageName = observation.packageName,
            activityName = observation.activityName,
            screenFingerprint = observation.screenFingerprint,
            captureStatus = observation.captureStatus,
            captureErrors = observation.captureErrors,
            transition = observation.transition,
            capturedAtEpochMillis = observation.capturedAtEpochMillis,
            freshnessMillis = freshness(observation),
            artifacts = observation.artifacts,
        )

    private fun freshness(observation: StoredObservation): Long =
        (elapsedClock.nowMillis() - observation.capturedAtElapsedMillis).coerceAtLeast(0)

    private fun validPackageName(value: String): Boolean =
        value.length <= 255 && PACKAGE_NAME.matches(value)

    private fun validActivityName(value: String): Boolean =
        value.length <= 512 && ACTIVITY_NAME.matches(value)

    private fun windowIdentityFingerprint(
        packageName: String,
        activityName: String?,
    ): ScreenFingerprint {
        val activity = activityName.orEmpty()
        val canonical =
            "${packageName.length}:$packageName|${activity.length}:$activity"
                .toByteArray(Charsets.UTF_8)
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(canonical)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return ScreenFingerprint(
            basis = ScreenFingerprintBasis.WINDOW_IDENTITY,
            digest = "sha256:$digest",
        )
    }

    companion object {
        const val DEFAULT_MAXIMUM_AGE_MILLIS = 5_000L
        private const val FOREGROUND_ACTIVITY_UNAVAILABLE = "FOREGROUND_ACTIVITY_UNAVAILABLE"
        private const val MAXIMUM_FRESHNESS_MILLIS = 5_000L
        private const val MAX_CAPTURE_ERRORS = 16
        private const val UI_TREE_MEDIA_TYPE = "application/vnd.hermes.ui-tree+json"
        private val SCREENSHOT_MEDIA_TYPES = setOf("image/png", "image/webp")
        private val PACKAGE_NAME =
            Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val ACTIVITY_NAME = Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,511}")
        private val DIGEST = Regex("sha256:[0-9a-f]{64}")
        private val STABLE_ERROR = Regex("[A-Z][A-Z0-9_]{0,63}")
    }
}

internal object PhoneStateStore : PhoneStateSource {
    private val tracker = PhoneStateObserver()

    fun markConnected() = tracker.markConnected()

    fun markDisconnected() = tracker.markDisconnected()

    fun invalidateCurrent() = tracker.invalidateCurrent()

    fun recordWindow(
        packageName: String?,
        activityName: String?,
        windowId: Int? = null,
    ): Boolean = tracker.recordWindow(packageName, activityName, windowId)

    fun recordUiTree(
        packageName: String,
        windowId: Int,
        fingerprintDigest: String,
        captureErrors: List<String>,
        artifact: ArtifactReference,
    ): PhoneStateSnapshot =
        tracker.recordUiTree(
            packageName,
            windowId,
            fingerprintDigest,
            captureErrors,
            artifact,
        )

    fun recordScreenshot(
        expectedStateId: String,
        fingerprintDigest: String,
        artifact: ArtifactReference,
    ): PhoneStateSnapshot =
        tracker.recordScreenshot(
            expectedStateId,
            fingerprintDigest,
            artifact,
        )

    fun visualCaptureAnchor(
        packageName: String,
        windowId: Int,
    ): PhoneStateSnapshot = tracker.visualCaptureAnchor(packageName, windowId)

    override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? =
        tracker.availability(maximumAgeMillis)

    override fun current(maximumAgeMillis: Long): PhoneStateSnapshot =
        tracker.current(maximumAgeMillis)
}
