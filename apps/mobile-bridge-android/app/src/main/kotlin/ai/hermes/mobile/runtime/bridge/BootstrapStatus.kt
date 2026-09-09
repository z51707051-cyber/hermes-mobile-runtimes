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
            phase = "HMR-110",
            summary = "State-bound navigation with post-action verification",
            enabledCapabilities = BridgeRuntime.availableCapabilities(),
        )
}
