package ai.hermes.mobile.runtime.bridge.security.replay

import java.security.MessageDigest
import java.util.Base64

internal data class ReplayPolicy(
    val maximumAuthorizationLifetimeMs: Long = 30_000,
    val maximumSessionLifetimeMs: Long = 24 * 60 * 60 * 1_000,
    val allowedClockSkewMs: Long = 120_000,
    val maximumSessions: Int = 8,
    val maximumNoncesPerSession: Int = 1_024,
) {
    init {
        require(maximumAuthorizationLifetimeMs > 0)
        require(maximumSessionLifetimeMs >= maximumAuthorizationLifetimeMs)
        require(allowedClockSkewMs >= 0)
        require(maximumSessions > 0)
        require(maximumNoncesPerSession > 0)
    }
}

/**
 * Atomically records a nonce and sequence before capability dispatch.
 *
 * Callers must treat every result except ACCEPTED as a hard stop. A corrupt or
 * unwritable ledger therefore fails closed instead of resetting replay state.
 */
internal class ReplayProtector(
    private val store: ReplayStateStore,
    private val policy: ReplayPolicy = ReplayPolicy(),
) {
    @Synchronized
    fun checkAndRecord(
        envelope: ReplayEnvelope,
        nowEpochMs: Long,
    ): ReplayDecision {
        validate(envelope, nowEpochMs)?.let { return ReplayDecision(it) }

        val loaded =
            try {
                store.load()
            } catch (_: ReplayStateException) {
                return ReplayDecision(ReplayStatus.STATE_UNAVAILABLE)
            }

        val sessions =
            loaded.sessions
                .filterValues { it.expiresAtEpochMs > nowEpochMs }
                .toMutableMap()
        val existing = sessions[envelope.sessionId]

        if (existing == null && sessions.size >= policy.maximumSessions) {
            return ReplayDecision(ReplayStatus.CAPACITY_EXCEEDED)
        }
        if (existing != null && existing.expiresAtEpochMs != envelope.sessionExpiresAtEpochMs) {
            return ReplayDecision(ReplayStatus.INVALID)
        }

        val highestSequence = existing?.highestSequence ?: 0L
        if (envelope.sequence <= highestSequence) {
            return ReplayDecision(ReplayStatus.REPLAYED)
        }
        if (envelope.sequence != highestSequence + 1) {
            return ReplayDecision(ReplayStatus.SEQUENCE_GAP)
        }

        val nonceDigest = digest(envelope.nonce)
        val retainedNonces = existing?.seenNonces.orEmpty()
        if (retainedNonces.any { it.digest == nonceDigest }) {
            return ReplayDecision(ReplayStatus.REPLAYED)
        }
        if (retainedNonces.size >= policy.maximumNoncesPerSession) {
            return ReplayDecision(ReplayStatus.CAPACITY_EXCEEDED)
        }

        sessions[envelope.sessionId] =
            ReplaySessionState(
                highestSequence = envelope.sequence,
                expiresAtEpochMs = envelope.sessionExpiresAtEpochMs,
                seenNonces =
                    retainedNonces +
                        SeenNonce(
                            digest = nonceDigest,
                            retainUntilEpochMs = envelope.sessionExpiresAtEpochMs,
                        ),
            )

        return try {
            store.save(ReplaySnapshot(sessions.toMap()))
            ReplayDecision(ReplayStatus.ACCEPTED)
        } catch (_: ReplayStateException) {
            ReplayDecision(ReplayStatus.STATE_UNAVAILABLE)
        }
    }

    private fun validate(
        envelope: ReplayEnvelope,
        nowEpochMs: Long,
    ): ReplayStatus? {
        if (nowEpochMs <= 0) return ReplayStatus.INVALID
        if (!SESSION_ID.matches(envelope.sessionId)) return ReplayStatus.INVALID
        if (envelope.sequence <= 0) return ReplayStatus.INVALID
        if (envelope.nonce.size !in MIN_NONCE_BYTES..MAX_NONCE_BYTES) {
            return ReplayStatus.INVALID
        }
        if (envelope.issuedAtEpochMs <= 0 || envelope.expiresAtEpochMs <= 0) {
            return ReplayStatus.INVALID
        }
        if (envelope.sessionExpiresAtEpochMs <= 0) return ReplayStatus.INVALID
        if (exceedsWindow(envelope.issuedAtEpochMs, nowEpochMs, policy.allowedClockSkewMs)) {
            return ReplayStatus.NOT_YET_VALID
        }
        if (envelope.expiresAtEpochMs <= nowEpochMs) return ReplayStatus.EXPIRED
        if (envelope.expiresAtEpochMs <= envelope.issuedAtEpochMs) {
            return ReplayStatus.INVALID
        }
        if (exceedsWindow(
                envelope.expiresAtEpochMs,
                envelope.issuedAtEpochMs,
                policy.maximumAuthorizationLifetimeMs,
            )
        ) {
            return ReplayStatus.INVALID
        }
        if (envelope.sessionExpiresAtEpochMs < envelope.expiresAtEpochMs) {
            return ReplayStatus.INVALID
        }
        if (exceedsWindow(
                envelope.sessionExpiresAtEpochMs,
                envelope.issuedAtEpochMs,
                policy.maximumSessionLifetimeMs,
            )
        ) {
            return ReplayStatus.INVALID
        }
        return null
    }

    private fun exceedsWindow(
        value: Long,
        origin: Long,
        maximumDelta: Long,
    ): Boolean {
        if (maximumDelta < 0 || value < origin) return true
        val maximumValue =
            if (origin > Long.MAX_VALUE - maximumDelta) Long.MAX_VALUE else origin + maximumDelta
        return value > maximumValue
    }

    private fun digest(nonce: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(nonce)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9_-]{22,128}")
        const val MIN_NONCE_BYTES = 16
        const val MAX_NONCE_BYTES = 64
    }
}
