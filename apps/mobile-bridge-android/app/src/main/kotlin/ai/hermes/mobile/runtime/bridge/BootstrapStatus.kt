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
            phase = "HMR-108",
            summary = "Bounded semantic active-window observation",
            enabledCapabilities = BridgeRuntime.availableCapabilities(),
        )
}
