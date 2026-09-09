package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.PhoneStateSnapshot
import java.time.Instant

internal fun PhoneStateSnapshot.protocolValue(deviceId: String): Map<String, Any?> =
    mapOf(
        "state_id" to stateId,
        "previous_state_id" to previousStateId,
        "captured_at" to Instant.ofEpochMilli(capturedAtEpochMillis).toString(),
        "freshness_ms" to freshnessMillis,
        "device_id" to deviceId,
        "foreground_package" to packageName,
        "foreground_activity" to activityName,
        "screen_fingerprint" to
            mapOf(
                "basis" to screenFingerprint.basis.name,
                "digest" to screenFingerprint.digest,
            ),
        "capture_status" to captureStatus.name,
        "capture_errors" to captureErrors,
        "transition" to transition.name,
        "artifacts" to artifacts.map { it.protocolValue() },
    )
