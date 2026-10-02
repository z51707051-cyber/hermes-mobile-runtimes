package ai.hermes.mobile.runtime.bridge.runtime

object HermesTaskForegroundPolicy {
    fun requiresForegroundService(phase: HermesTaskPhase): Boolean =
        phase == HermesTaskPhase.RUNNING || phase == HermesTaskPhase.STOPPING
}
