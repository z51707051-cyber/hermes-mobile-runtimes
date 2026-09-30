package ai.hermes.mobile.runtime.bridge.runtime

import android.content.Context
import ai.hermes.mobile.runtime.bridge.model.ModelEndpoint

/** In-memory request passed only to the embedded runtime for one user task. */
data class HermesRuntimeRequest internal constructor(
    val endpoint: ModelEndpoint,
    val apiKey: CharArray,
    val prompt: String,
)

interface HermesTaskRuntime {
    val isAvailable: Boolean

    fun runTask(
        request: HermesRuntimeRequest,
        androidBridge: HermesAndroidToolBridge,
    ): String
}

object HermesTaskRuntimeLoader {
    private const val EMBEDDED_RUNTIME_CLASS =
        "ai.hermes.mobile.runtime.bridge.runtime.ChaquopyHermesTaskRuntime"

    fun load(context: Context): HermesTaskRuntime =
        try {
            val runtimeClass = Class.forName(EMBEDDED_RUNTIME_CLASS)
            val instance =
                runtimeClass.getConstructor(Context::class.java)
                    .newInstance(context.applicationContext)
            instance as? HermesTaskRuntime ?: UnavailableHermesTaskRuntime
        } catch (_: ReflectiveOperationException) {
            UnavailableHermesTaskRuntime
        } catch (_: LinkageError) {
            UnavailableHermesTaskRuntime
        }

    private object UnavailableHermesTaskRuntime : HermesTaskRuntime {
        override val isAvailable = false

        override fun runTask(
            request: HermesRuntimeRequest,
            androidBridge: HermesAndroidToolBridge,
        ): String = error("embedded Hermes runtime is unavailable")
    }
}
