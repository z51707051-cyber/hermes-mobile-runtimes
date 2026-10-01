package ai.hermes.mobile.runtime.bridge.runtime

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

/** Embedded backend included only in the complete ARM64 internal-test APK. */
class ChaquopyHermesTaskRuntime(private val context: Context) : HermesTaskRuntime {
    override val isAvailable = true

    override fun runTask(
        request: HermesRuntimeRequest,
        androidBridge: HermesAndroidToolBridge,
    ): String {
        ensurePythonStarted()
        return Python.getInstance()
            .getModule("hermes_mobile_runtime")
            .callAttr(
                "run_task",
                request.endpoint.baseUrl,
                request.apiKey.concatToString(),
                request.endpoint.model,
                request.prompt,
                androidBridge,
            ).toString()
    }

    private fun ensurePythonStarted() {
        synchronized(PYTHON_START_LOCK) {
            if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        }
    }

    private companion object {
        val PYTHON_START_LOCK = Any()
    }
}
