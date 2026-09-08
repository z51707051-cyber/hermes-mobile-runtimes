package ai.hermes.mobile.runtime.bridge

import ai.hermes.mobile.runtime.bridge.runtime.BridgeRuntime

internal data class BootstrapStatus(
    val phase: String,
    val summary: String,
    val enabledCapabilities: List<String>,
)

internal object BootstrapStatusProvider {
    fun current(): BootstrapStatus =
        BootstrapStatus(
            phase = "HMR-106",
            summary = "Coherent minimal PhoneState observation",
            enabledCapabilities = BridgeRuntime.availableCapabilities(),
        )
}
