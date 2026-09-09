package ai.hermes.mobile.runtime.bridge.observer

import ai.hermes.mobile.runtime.bridge.artifact.ArtifactReference

internal data class DeviceStateQuery(
    val fields: Set<String>,
)

internal data class DeviceStateCapture(
    val artifact: ArtifactReference,
    val redactions: List<String>,
)

internal interface DeviceStateCaptureSource {
    fun availability(): PhoneStateUnavailableReason?

    fun capture(query: DeviceStateQuery): DeviceStateCapture
}
