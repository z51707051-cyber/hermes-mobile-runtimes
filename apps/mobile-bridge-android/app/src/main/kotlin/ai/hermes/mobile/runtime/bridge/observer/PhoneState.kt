package ai.hermes.mobile.runtime.bridge.observer

import android.os.SystemClock
import java.security.MessageDigest
import java.util.UUID

internal enum class PhoneStateUnavailableReason {
    SERVICE_DISCONNECTED,
    NO_WINDOW_STATE,
    STALE_WINDOW_STATE,
}

internal class PhoneStateUnavailableException(
    val reason: PhoneStateUnavailableReason,
) : IllegalStateException("current app observation is unavailable: $reason")

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
        val screenFingerprint: ScreenFingerprint,
        val captureStatus: PhoneStateCaptureStatus,
        val captureErrors: List<String>,
        val transition: ScreenTransition,
        val capturedAtEpochMillis: Long,
        val capturedAtElapsedMillis: Long,
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

    @Synchronized
    fun recordWindow(
        packageName: String?,
        activityName: String?,
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
            )
        return true
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
        return PhoneStateSnapshot(
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
        )
    }

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
        private val PACKAGE_NAME =
            Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        private val ACTIVITY_NAME = Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,511}")
    }
}

internal object PhoneStateStore : PhoneStateSource {
    private val tracker = PhoneStateObserver()

    fun markConnected() = tracker.markConnected()

    fun markDisconnected() = tracker.markDisconnected()

    fun recordWindow(
        packageName: String?,
        activityName: String?,
    ): Boolean = tracker.recordWindow(packageName, activityName)

    override fun availability(maximumAgeMillis: Long): PhoneStateUnavailableReason? =
        tracker.availability(maximumAgeMillis)

    override fun current(maximumAgeMillis: Long): PhoneStateSnapshot =
        tracker.current(maximumAgeMillis)
}
