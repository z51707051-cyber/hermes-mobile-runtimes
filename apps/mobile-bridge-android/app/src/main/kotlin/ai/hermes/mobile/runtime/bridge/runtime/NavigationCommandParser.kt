package ai.hermes.mobile.runtime.bridge.runtime

import ai.hermes.mobile.runtime.bridge.observer.ActionStatePrecondition
import ai.hermes.mobile.runtime.bridge.observer.BackCommand
import ai.hermes.mobile.runtime.bridge.observer.CoordinateNavigationTarget
import ai.hermes.mobile.runtime.bridge.observer.HomeCommand
import ai.hermes.mobile.runtime.bridge.observer.LongPressCommand
import ai.hermes.mobile.runtime.bridge.observer.NavigationCommand
import ai.hermes.mobile.runtime.bridge.observer.NavigationTarget
import ai.hermes.mobile.runtime.bridge.observer.NavigationVerificationRequest
import ai.hermes.mobile.runtime.bridge.observer.NodeNavigationTarget
import ai.hermes.mobile.runtime.bridge.observer.OpenAppCommand
import ai.hermes.mobile.runtime.bridge.observer.SwipeCommand
import ai.hermes.mobile.runtime.bridge.observer.TapCommand
import ai.hermes.mobile.runtime.bridge.observer.TypeCommand

internal object NavigationCommandParser {
    private val tools =
        setOf(
            "phone.tap",
            "phone.long_press",
            "phone.type",
            "phone.swipe",
            "phone.back",
            "phone.home",
            "phone.open_app",
        )

    fun supports(tool: String): Boolean = tool in tools

    fun parse(action: AuthorizedAction): NavigationCommand {
        val precondition = precondition(action.message["state_precondition"])
        val parameters = action.parameters
        return when (action.tool) {
            "phone.tap" ->
                TapCommand(
                    target(parameters.getValue("target")),
                    requireNotNull(precondition),
                )
            "phone.long_press" ->
                LongPressCommand(
                    target = target(parameters.getValue("target")),
                    durationMillis = (parameters.getValue("duration_ms") as Number).toLong(),
                    precondition = requireNotNull(precondition),
                )
            "phone.type" ->
                TypeCommand(
                    text = parameters.getValue("text") as String,
                    replace = parameters.getValue("mode") == "REPLACE",
                    target = parameters["target"]?.let(::nodeTarget),
                    precondition = requireNotNull(precondition),
                )
            "phone.swipe" ->
                SwipeCommand(
                    start = coordinateTarget(parameters.getValue("start")),
                    end = coordinateTarget(parameters.getValue("end")),
                    durationMillis = (parameters.getValue("duration_ms") as Number).toLong(),
                    precondition = requireNotNull(precondition),
                )
            "phone.back" -> BackCommand(precondition)
            "phone.home" -> HomeCommand(precondition)
            "phone.open_app" ->
                OpenAppCommand(
                    packageName = parameters.getValue("package") as String,
                    precondition = precondition,
                )
            else -> throw IllegalArgumentException("unsupported navigation tool")
        }
    }

    fun verification(action: AuthorizedAction): NavigationVerificationRequest? {
        val value = action.message["verification"] as? Map<*, *> ?: return null
        return NavigationVerificationRequest(
            condition = value["condition"] as String,
            expected = value["expected"] as? String,
        )
    }

    private fun precondition(value: Any?): ActionStatePrecondition? {
        val state = value as? Map<*, *> ?: return null
        return ActionStatePrecondition(
            stateId = state["state_id"] as String,
            maximumAgeMillis = (state["maximum_age_ms"] as Number).toLong(),
            foregroundPackage = state["foreground_package"] as? String,
        )
    }

    private fun target(value: Any?): NavigationTarget {
        val target = value as Map<*, *>
        return if ("node_id" in target) nodeTarget(target) else coordinateTarget(target)
    }

    private fun nodeTarget(value: Any?): NodeNavigationTarget {
        val target = value as Map<*, *>
        return NodeNavigationTarget(
            stateId = target["state_id"] as String,
            nodeId = target["node_id"] as String,
        )
    }

    private fun coordinateTarget(value: Any?): CoordinateNavigationTarget {
        val target = value as Map<*, *>
        return CoordinateNavigationTarget(
            stateId = target["state_id"] as String,
            xPx = (target["x_px"] as Number).toInt(),
            yPx = (target["y_px"] as Number).toInt(),
        )
    }
}
