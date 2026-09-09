package ai.hermes.mobile.runtime.bridge

import android.app.Application
import ai.hermes.mobile.runtime.bridge.device.AndroidDeviceStateGateway

class HermesMobileApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidDeviceStateGateway.initialize(this)
    }
}
