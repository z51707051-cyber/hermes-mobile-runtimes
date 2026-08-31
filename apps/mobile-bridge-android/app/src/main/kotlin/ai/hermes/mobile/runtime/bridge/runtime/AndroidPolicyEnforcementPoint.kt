package ai.hermes.mobile.runtime.bridge.runtime

internal enum class PepDisposition {
    ALLOW,
    DENY,
}

internal data class PepDecision(
    val disposition: PepDisposition,
    val errorCode: String?,
) {
    init {
        require(
            (disposition == PepDisposition.ALLOW && errorCode == null) ||
                (disposition == PepDisposition.DENY && errorCode in DENIAL_CODES),
        ) { "invalid PEP decision" }
    }

    companion object {
        private val DENIAL_CODES =
            setOf(
                "PERMISSION_DENIED",
                "AUTHORIZATION_INVALID",
                "AUTHORIZATION_EXPIRED",
                "REPLAY_DETECTED",
                "ACTION_MISMATCH",
                "RISK_UPGRADE_REQUIRED",
                "DEVICE_LOCKED",
                "AUDIT_UNAVAILABLE",
                "CAPABILITY_UNAVAILABLE",
            )

        fun allow(): PepDecision = PepDecision(PepDisposition.ALLOW, null)

        fun deny(errorCode: String): PepDecision = PepDecision(PepDisposition.DENY, errorCode)
    }
}

/** Device-side enforcement required immediately before provider dispatch. */
internal fun interface AndroidPolicyEnforcementPoint {
    fun evaluate(action: AuthorizedAction): PepDecision
}

/** Safe production default until an authenticated broker supplies authorization. */
internal object DenyAllPolicyEnforcementPoint : AndroidPolicyEnforcementPoint {
    override fun evaluate(action: AuthorizedAction): PepDecision =
        PepDecision.deny("CAPABILITY_UNAVAILABLE")
}
