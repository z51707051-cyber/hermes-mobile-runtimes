package ai.hermes.mobile.runtime.bridge

internal data class BootstrapStatus(
    val phase: String,
    val summary: String,
    val enabledCapabilities: List<String>,
)

internal object BootstrapStatusProvider {
    fun current(): BootstrapStatus =
        BootstrapStatus(
            phase = "HMR-102",
            summary = "Device security kernel; no phone capabilities",
            enabledCapabilities = emptyList(),
        )
}
