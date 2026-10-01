package ai.hermes.mobile.runtime.bridge.runtime

import android.os.Handler
import android.os.Looper
import ai.hermes.mobile.runtime.bridge.attachment.SelectedAttachment
import ai.hermes.mobile.runtime.bridge.model.ModelConfigStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

internal class HermesTaskController(
    private val modelConfigStore: ModelConfigStore,
    private val runtime: HermesTaskRuntime,
    private val onStateChanged: (HermesTaskSession) -> Unit,
    private val selectedAttachmentProvider: () -> SelectedAttachment? = { null },
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
) : AutoCloseable {
    private val taskIds = AtomicLong()

    @Volatile
    private var state = HermesTaskSession()
    private var activeBridge: HermesAndroidToolBridge? = null
    private var activeFuture: Future<*>? = null
    private var activeWorkerStarted = false
    private var closed = false

    val isRuntimeAvailable: Boolean
        get() = runtime.isAvailable

    @Synchronized
    fun currentState(): HermesTaskSession = state

    @Synchronized
    fun submit(prompt: String): Boolean {
        if (closed || state.phase == HermesTaskPhase.RUNNING || state.phase == HermesTaskPhase.STOPPING) {
            return false
        }
        val taskId = taskIds.incrementAndGet()
        val next = runCatching { HermesTaskStateMachine.begin(state, taskId, prompt) }.getOrNull()
            ?: return false
        if (!runtime.isAvailable) {
            state = HermesTaskStateMachine.fail(next, taskId, HermesTaskFailure.RUNTIME_UNAVAILABLE)
            publish(state)
            return false
        }
        val modelStatus = modelConfigStore.status()
        if (modelStatus.endpoint == null || !modelStatus.hasApiKey) {
            state = HermesTaskStateMachine.fail(next, taskId, HermesTaskFailure.CONFIGURATION)
            publish(state)
            return false
        }

        val bridge = HermesAndroidToolBridge.createForUserTask(selectedAttachmentProvider())
        activeBridge = bridge
        activeWorkerStarted = false
        state = next
        publish(next)
        activeFuture =
            executor.submit {
                val shouldRun =
                    synchronized(this) {
                        val accepted =
                            !closed &&
                                state.taskId == taskId &&
                                state.phase == HermesTaskPhase.RUNNING
                        if (accepted) activeWorkerStarted = true
                        accepted
                    }
                if (!shouldRun) {
                    bridge.close()
                    return@submit
                }
                val result =
                    runCatching {
                        modelConfigStore.withRuntimeConfig { config ->
                            runtime.runTask(
                                HermesRuntimeRequest(config.endpoint, config.apiKey, next.prompt),
                                bridge,
                            )
                        }
                    }
                bridge.close()
                mainHandler.post {
                    finish(taskId, result)
                }
            }
        return true
    }

    @Synchronized
    fun cancel() {
        if (state.phase != HermesTaskPhase.RUNNING) return
        activeBridge?.close()
        activeFuture?.cancel(true)
        state = HermesTaskStateMachine.requestCancel(state)
        if (!activeWorkerStarted) {
            activeBridge = null
            activeFuture = null
            state = HermesTaskStateMachine.finishCancel(state)
        }
        publish(state)
    }

    @Synchronized
    private fun finish(
        taskId: Long,
        result: Result<String>,
    ) {
        if (closed || state.taskId != taskId) return
        activeBridge = null
        activeFuture = null
        activeWorkerStarted = false
        if (state.phase == HermesTaskPhase.STOPPING) {
            state = HermesTaskStateMachine.finishCancel(state)
            publish(state)
            return
        }
        if (state.phase != HermesTaskPhase.RUNNING) return
        state =
            result.fold(
                onSuccess = { HermesTaskStateMachine.complete(state, taskId, it) },
                onFailure = {
                    HermesTaskStateMachine.fail(state, taskId, HermesTaskFailure.EXECUTION)
                },
            )
        publish(state)
    }

    private fun publish(snapshot: HermesTaskSession) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onStateChanged(snapshot)
        } else {
            mainHandler.post { onStateChanged(snapshot) }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        activeBridge?.close()
        activeFuture?.cancel(true)
        activeBridge = null
        activeFuture = null
        activeWorkerStarted = false
        executor.shutdownNow()
    }
}
