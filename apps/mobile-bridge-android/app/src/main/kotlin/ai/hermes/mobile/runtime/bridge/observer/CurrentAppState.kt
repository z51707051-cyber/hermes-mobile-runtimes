package ai.hermes.mobile.runtime.bridge.observer

import android.os.SystemClock
import java.util.UUID

internal enum class CurrentAppUnavailableReason {
    SERVICE_DISCONNECTED,
    NO_WINDOW_STATE,
    STALE_WINDOW_STATE,
}

internal class CurrentAppUnavailableException(
    val reason: CurrentAppUnavailableReason,
) : IllegalStateException("current app observation is unavailable: $reason")

internal data class CurrentAppObservation(
    val stateId: String,
    val packageName: String,
    val activityName: String?,
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

internal interface CurrentAppSource {
    fun availability(maximumAgeMillis: Long): CurrentAppUnavailableReason?

    fun current(maximumAgeMillis: Long): CurrentAppObservation
}

/**
 * Stores only the latest safe window identity from the Accessibility callback.
 *
 * It never retains AccessibilityEvent, AccessibilityNodeInfo, text, bounds, or
 * any object that can inspect or mutate the UI.
 */
internal class CurrentAppTracker(
    private val elapsedClock: ElapsedRealtimeClock = ElapsedRealtimeClock { SystemClock.elapsedRealtime() },
    private val epochClock: EpochClock = EpochClock { System.currentTimeMillis() },
    private val stateIds: StateIdGenerator = StateIdGenerator { "state:${UUID.randomUUID()}" },
) : CurrentAppSource {
    private data class StoredObservation(
        val stateId: String,
        val packageName: String,
        val activityName: String?,
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
    }

    @Synchronized
    fun recordWindow(
        packageName: String?,
        activityName: String?,
    ): Boolean {
        val safePackage = packageName?.takeIf(::validPackageName) ?: return false
        val safeActivity = activityName?.takeIf(::validActivityName)
        latest =
            StoredObservation(
                stateId = stateIds.next(),
                packageName = safePackage,
                activityName = safeActivity,
                capturedAtEpochMillis = epochClock.nowMillis(),
                capturedAtElapsedMillis = elapsedClock.nowMillis(),
            )
        return true
    }

    @Synchronized
    override fun availability(maximumAgeMillis: Long): CurrentAppUnavailableReason? {
        require(maximumAgeMillis in 1..MAXIMUM_FRESHNESS_MILLIS) {
            "maximum current-app age must be within 1..$MAXIMUM_FRESHNESS_MILLIS ms"
        }
        if (!connected) return CurrentAppUnavailableReason.SERVICE_DISCONNECTED
        val observation = latest ?: return CurrentAppUnavailableReason.NO_WINDOW_STATE
        if (freshness(observation) > maximumAgeMillis) {
            return CurrentAppUnavailableReason.STALE_WINDOW_STATE
        }
        return null
    }

    @Synchronized
    override fun current(maximumAgeMillis: Long): CurrentAppObservation {
        availability(maximumAgeMillis)?.let { throw CurrentAppUnavailableException(it) }
        val observation = checkNotNull(latest)
        return CurrentAppObservation(
            stateId = observation.stateId,
            packageName = observation.packageName,
            activityName = observation.activityName,
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

    companion object {
        const val DEFAULT_MAXIMUM_AGE_MILLIS = 5_000L
        private const val MAXIMUM_FRESHNESS_MILLIS = 60_000L
        private val PACKAGE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}")
        private val ACTIVITY_NAME = Regex("[A-Za-z0-9_.$]+")
    }
}

internal object CurrentAppStateStore : CurrentAppSource {
    private val tracker = CurrentAppTracker()

    fun markConnected() = tracker.markConnected()

    fun markDisconnected() = tracker.markDisconnected()

    fun recordWindow(
        packageName: String?,
        activityName: String?,
    ): Boolean = tracker.recordWindow(packageName, activityName)

    override fun availability(maximumAgeMillis: Long): CurrentAppUnavailableReason? =
        tracker.availability(maximumAgeMillis)

    override fun current(maximumAgeMillis: Long): CurrentAppObservation =
        tracker.current(maximumAgeMillis)
}
