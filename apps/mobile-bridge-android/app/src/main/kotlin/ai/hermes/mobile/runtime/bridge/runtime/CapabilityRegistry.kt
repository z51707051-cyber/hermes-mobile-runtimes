package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.protocol.ProtocolCodec

internal data class CapabilityDefinition(
    val tool: String,
    val minimumRisk: String,
    val stateChanging: Boolean,
)

internal data class CapabilityDescriptor(
    val tool: String,
    val providerId: String,
)

/** A provider can receive only an action already wrapped by AndroidToolRouter. */
internal interface CapabilityProvider {
    val descriptor: CapabilityDescriptor

    fun execute(action: AuthorizedAction): ByteArray
}

/**
 * Closed process-local provider registry.
 *
 * Missing providers are unavailable; no reflection, service lookup, shell or
 * raw Android command can become a capability implicitly.
 */
internal class CapabilityRegistry(
    providers: Iterable<CapabilityProvider> = emptyList(),
) {
    private val providersByTool: Map<String, CapabilityProvider>

    init {
        val entries = providers.toList()
        entries.forEach { provider ->
            val descriptor = provider.descriptor
            require(descriptor.tool in DEFINITIONS) { "unknown canonical capability: ${descriptor.tool}" }
            require(PROVIDER_ID.matches(descriptor.providerId)) { "invalid capability provider id" }
        }
        require(entries.map { it.descriptor.tool }.toSet().size == entries.size) {
            "duplicate capability provider"
        }
        providersByTool = entries.associateBy { it.descriptor.tool }
    }

    fun snapshot(): List<CapabilityDescriptor> =
        providersByTool.values.map { it.descriptor }.sortedBy { it.tool }

    fun requireProvider(tool: String): CapabilityProvider =
        providersByTool[tool]
            ?: throw AndroidRouteRejectedException(
                code = "CAPABILITY_UNAVAILABLE",
                message = "capability is unavailable: $tool",
            )

    fun definition(tool: String): CapabilityDefinition =
        DEFINITIONS[tool]
            ?: throw AndroidRouteRejectedException(
                code = "ACTION_REJECTED",
                message = "unknown canonical capability",
            )

    companion object {
        val definitions: Set<CapabilityDefinition> =
            setOf(
                CapabilityDefinition("phone.read_screen", "L0", false),
                CapabilityDefinition("phone.screenshot", "L0", false),
                CapabilityDefinition("phone.tap", "L1", true),
                CapabilityDefinition("phone.long_press", "L1", true),
                CapabilityDefinition("phone.type", "L2", true),
                CapabilityDefinition("phone.swipe", "L1", true),
                CapabilityDefinition("phone.back", "L1", true),
                CapabilityDefinition("phone.home", "L1", true),
                CapabilityDefinition("phone.open_app", "L1", true),
                CapabilityDefinition("phone.wait", "L0", false),
                CapabilityDefinition("phone.notifications", "L0", false),
                CapabilityDefinition("phone.current_app", "L0", false),
                CapabilityDefinition("phone.device_state", "L0", false),
            )

        private val DEFINITIONS = definitions.associateBy { it.tool }
        private val PROVIDER_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        init {
            check(DEFINITIONS.keys == ProtocolCodec.canonicalToolNames()) {
                "capability catalog does not match the protocol"
            }
        }
    }
}
