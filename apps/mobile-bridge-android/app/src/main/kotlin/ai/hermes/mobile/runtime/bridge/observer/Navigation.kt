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
