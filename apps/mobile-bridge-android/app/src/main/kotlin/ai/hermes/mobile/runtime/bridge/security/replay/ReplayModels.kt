package ai.hermes.mobile.runtime.bridge.security.replay

internal data class ReplayEnvelope(
    val sessionId: String,
    val sequence: Long,
    val nonce: ByteArray,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val sessionExpiresAtEpochMs: Long,
)

internal data class SeenNonce(
    val digest: String,
    val retainUntilEpochMs: Long,
)

internal data class ReplaySessionState(
    val highestSequence: Long,
    val expiresAtEpochMs: Long,
    val seenNonces: List<SeenNonce>,
)

internal data class ReplaySnapshot(
    val sessions: Map<String, ReplaySessionState> = emptyMap(),
)

internal interface ReplayStateStore {
    @Throws(ReplayStateException::class)
    fun load(): ReplaySnapshot

    @Throws(ReplayStateException::class)
    fun save(snapshot: ReplaySnapshot)
}

internal class ReplayStateException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal enum class ReplayStatus {
    ACCEPTED,
    INVALID,
    EXPIRED,
    NOT_YET_VALID,
    REPLAYED,
    SEQUENCE_GAP,
    CAPACITY_EXCEEDED,
    STATE_UNAVAILABLE,
}

internal data class ReplayDecision(
    val status: ReplayStatus,
) {
    val accepted: Boolean
        get() = status == ReplayStatus.ACCEPTED
}
