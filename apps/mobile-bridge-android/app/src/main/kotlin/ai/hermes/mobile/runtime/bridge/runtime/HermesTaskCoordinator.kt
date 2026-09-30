package ai.hermes.mobile.runtime.bridge.runtime

import android.content.Context
import android.content.Intent
import ai.hermes.mobile.runtime.bridge.model.ModelConfigStore
import java.util.concurrent.CopyOnWriteArraySet

class HermesTaskCoordinator(
    context: Context,
    modelConfigStore: ModelConfigStore,
    runtime: HermesTaskRuntime,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val listeners = CopyOnWriteArraySet<(HermesTaskSession) -> Unit>()

    @Volatile
    private var state = HermesTaskSession()

    private val controller =
        HermesTaskController(
            modelConfigStore = modelConfigStore,
            runtime = runtime,
            onStateChanged = ::publish,
        )

    val isRuntimeAvailable: Boolean
        get() = controller.isRuntimeAvailable

    fun currentState(): HermesTaskSession = state

    fun addListener(listener: (HermesTaskSession) -> Unit) {
        listeners.add(listener)
        listener(state)
    }

    fun removeListener(listener: (HermesTaskSession) -> Unit) {
        listeners.remove(listener)
    }

    fun submit(prompt: String): Boolean {
        if (!controller.submit(prompt)) return false
        return try {
            appContext.startForegroundService(HermesTaskForegroundService.startIntent(appContext))
            true
        } catch (_: RuntimeException) {
            controller.cancel()
            false
        }
    }

    fun cancel() {
        controller.cancel()
    }

    private fun publish(snapshot: HermesTaskSession) {
        state = snapshot
        listeners.forEach { listener -> listener(snapshot) }
    }

    override fun close() {
        controller.close()
        listeners.clear()
    }
}
