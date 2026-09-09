package ai.hermes.mobile.runtime.bridge.observer

internal data class ActionStatePrecondition(
    val stateId: String,
    val maximumAgeMillis: Long,
    val foregroundPackage: String?,
)

internal sealed interface NavigationTarget {
    val stateId: String
}

internal data class NodeNavigationTarget(
    override val stateId: String,
    val nodeId: String,
) : NavigationTarget

internal data class CoordinateNavigationTarget(
    override val stateId: String,
    val xPx: Int,
    val yPx: Int,
) : NavigationTarget

internal sealed interface NavigationCommand {
    val tool: String
    val precondition: ActionStatePrecondition?
}

internal data class TapCommand(
    val target: NavigationTarget,
    override val precondition: ActionStatePrecondition,
) : NavigationCommand {
    override val tool = "phone.tap"
}

internal data class LongPressCommand(
    val target: NavigationTarget,
    val durationMillis: Long,
    override val precondition: ActionStatePrecondition,
) : NavigationCommand {
    override val tool = "phone.long_press"
}

internal data class TypeCommand(
    val text: String,
    val replace: Boolean,
    val target: NodeNavigationTarget?,
    override val precondition: ActionStatePrecondition,
) : NavigationCommand {
    override val tool = "phone.type"
}

internal data class SwipeCommand(
    val start: CoordinateNavigationTarget,
    val end: CoordinateNavigationTarget,
    val durationMillis: Long,
    override val precondition: ActionStatePrecondition,
) : NavigationCommand {
    override val tool = "phone.swipe"
}

internal data class BackCommand(
    override val precondition: ActionStatePrecondition?,
) : NavigationCommand {
    override val tool = "phone.back"
}

internal data class HomeCommand(
    override val precondition: ActionStatePrecondition?,
) : NavigationCommand {
    override val tool = "phone.home"
}

internal data class OpenAppCommand(
    val packageName: String,
    override val precondition: ActionStatePrecondition?,
) : NavigationCommand {
    override val tool = "phone.open_app"
}

internal data class NavigationVerificationRequest(
    val condition: String,
    val expected: String?,
)

internal data class NavigationVerificationEvaluation(
    val status: String,
    val explanation: String,
)

internal object NavigationVerifier {
    fun evaluate(
        after: SemanticUiCapture,
        request: NavigationVerificationRequest?,
    ): NavigationVerificationEvaluation {
        if (after.state.captureStatus != PhoneStateCaptureStatus.COMPLETE) {
            return result("INCONCLUSIVE", "post-action observation is not complete")
        }
        val condition = request?.condition ?: "STATE_CHANGED"
        val expected = request?.expected
        return when (condition) {
            "STATE_CHANGED" ->
                when (after.state.transition) {
                    ScreenTransition.CHANGED ->
                        result("PASSED", "comparable screen fingerprint changed")
                    ScreenTransition.NONE ->
                        result("FAILED", "comparable screen fingerprint did not change")
                    ScreenTransition.UNKNOWN ->
                        result("INCONCLUSIVE", "screen fingerprints are not comparable")
                }
            "FOREGROUND_APP_IS" ->
                if (after.state.packageName == expected) {
                    result("PASSED", "foreground package matches the requested package")
                } else {
                    result("FAILED", "foreground package does not match the requested package")
                }
            "TEXT_PRESENT" ->
                if (expected.isNullOrEmpty()) {
                    result("INCONCLUSIVE", "text verification requires a non-empty expected value")
                } else if (after.visibleText.any { expected in it }) {
                    result("PASSED", "requested text is present in the protected observation")
                } else {
                    result("FAILED", "requested text is absent from the protected observation")
                }
            "TEXT_ABSENT" ->
                if (expected.isNullOrEmpty()) {
                    result("INCONCLUSIVE", "text verification requires a non-empty expected value")
                } else if (after.visibleText.none { expected in it }) {
                    result("PASSED", "requested text is absent from the protected observation")
                } else {
                    result("FAILED", "requested text remains present in the protected observation")
                }
            else -> result("INCONCLUSIVE", "verification condition is unsupported")
        }
    }

    private fun result(
        status: String,
        explanation: String,
    ) = NavigationVerificationEvaluation(status, explanation)
}

internal data class NavigationExecution(
    val beforeState: PhoneStateSnapshot,
    val afterState: PhoneStateSnapshot,
    val verificationStatus: String,
    val verificationExplanation: String,
    val redactions: List<String>,
)

internal enum class NavigationFailureReason(
    val errorCode: String,
    val retryDisposition: String,
    val recoverable: Boolean,
    val outcomeUnknown: Boolean = false,
) {
    NODE_TARGET_UNAVAILABLE("NODE_NOT_FOUND", "REOBSERVE", true),
    TARGET_CHANGED("ACTION_REJECTED", "REOBSERVE", true),
    COORDINATE_TARGET_FORBIDDEN("ACTION_REJECTED", "REPLAN", true),
    DESTRUCTIVE_TARGET_BLOCKED("PERMISSION_DENIED", "ASK_USER", false),
    ACTION_NOT_ACCEPTED("ACTION_REJECTED", "REOBSERVE", true),
    GESTURE_CANCELLED("ACTION_REJECTED", "REOBSERVE", true),
    GESTURE_TIMEOUT("ACTION_TIMEOUT", "REOBSERVE", true),
    TYPE_TARGET_UNAVAILABLE("NODE_NOT_FOUND", "REOBSERVE", true),
    EXPLICIT_TYPE_TARGET_REQUIRED("ACTION_REJECTED", "REOBSERVE", true),
    PASSWORD_APPEND_FORBIDDEN("ACTION_REJECTED", "REPLAN", true),
    TEXT_RESULT_TOO_LARGE("ACTION_REJECTED", "REPLAN", true),
    APP_NOT_FOUND("APP_NOT_FOUND", "NEVER", false),
    APP_LAUNCH_DENIED("ACTION_REJECTED", "NEVER", false),
    POST_ACTION_OBSERVATION_FAILED("VERIFICATION_FAILED", "REOBSERVE", true, true),
    INTERNAL_ERROR("ACTION_REJECTED", "REPLAN", true),
}

internal class NavigationFailureException(
    val reason: NavigationFailureReason,
    var beforeState: PhoneStateSnapshot? = null,
) : IllegalStateException("navigation action failed: $reason")

internal interface NavigationActionSource {
    fun availability(): PhoneStateUnavailableReason?

    /** Re-resolves live semantics without retaining an Android node. */
    fun requiredRisk(command: NavigationCommand): String

    /** Re-resolves again, executes once, then observes and verifies. */
    fun execute(
        command: NavigationCommand,
        verification: NavigationVerificationRequest?,
    ): NavigationExecution
}
