package ai.hermes.mobile.runtime.bridge.runtime

enum class HermesTaskPhase {
    IDLE,
    RUNNING,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class HermesTaskFailure {
    CONFIGURATION,
    RUNTIME_UNAVAILABLE,
    EXECUTION,
}

data class HermesTaskSession(
    val taskId: Long = 0,
    val phase: HermesTaskPhase = HermesTaskPhase.IDLE,
    val prompt: String = "",
    val response: String = "",
    val failure: HermesTaskFailure? = null,
)

internal object HermesTaskStateMachine {
    fun begin(
        current: HermesTaskSession,
        taskId: Long,
        prompt: String,
    ): HermesTaskSession {
        require(current.phase != HermesTaskPhase.RUNNING) { "a task is already running" }
        val normalized = prompt.trim()
        require(normalized.isNotEmpty()) { "task prompt is required" }
        require(normalized.length <= MAXIMUM_PROMPT_CHARACTERS) { "task prompt is too long" }
        return HermesTaskSession(
            taskId = taskId,
            phase = HermesTaskPhase.RUNNING,
            prompt = normalized,
        )
    }

    fun complete(
        current: HermesTaskSession,
        taskId: Long,
        response: String,
    ): HermesTaskSession {
        if (current.taskId != taskId || current.phase != HermesTaskPhase.RUNNING) return current
        val bounded = response.trim().take(MAXIMUM_RESPONSE_CHARACTERS)
        return current.copy(
            phase = HermesTaskPhase.COMPLETED,
            response = bounded,
            failure = null,
        )
    }

    fun fail(
        current: HermesTaskSession,
        taskId: Long,
        failure: HermesTaskFailure,
    ): HermesTaskSession {
        if (current.taskId != taskId || current.phase != HermesTaskPhase.RUNNING) return current
        return current.copy(phase = HermesTaskPhase.FAILED, failure = failure)
    }

    fun requestCancel(current: HermesTaskSession): HermesTaskSession =
        if (current.phase == HermesTaskPhase.RUNNING) {
            current.copy(phase = HermesTaskPhase.STOPPING, failure = null)
        } else {
            current
        }

    fun finishCancel(current: HermesTaskSession): HermesTaskSession =
        if (current.phase == HermesTaskPhase.STOPPING) {
            current.copy(phase = HermesTaskPhase.CANCELLED, failure = null)
        } else {
            current
        }

    private const val MAXIMUM_PROMPT_CHARACTERS = 20_000
    private const val MAXIMUM_RESPONSE_CHARACTERS = 100_000
}
