package ai.hermes.mobile.runtime.bridge

import android.app.Application
import ai.hermes.mobile.runtime.bridge.device.AndroidDeviceStateGateway
import ai.hermes.mobile.runtime.bridge.model.ModelConfigStore
import ai.hermes.mobile.runtime.bridge.runtime.HermesTaskCoordinator
import ai.hermes.mobile.runtime.bridge.runtime.HermesTaskRuntimeLoader

class HermesMobileApplication : Application() {
    lateinit var taskCoordinator: HermesTaskCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidDeviceStateGateway.initialize(this)
        taskCoordinator =
            HermesTaskCoordinator(
                context = this,
                modelConfigStore = ModelConfigStore(this),
                runtime = HermesTaskRuntimeLoader.load(this),
            )
    }
}
