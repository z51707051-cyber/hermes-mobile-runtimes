package ai.hermes.mobile.runtime.bridge.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesTaskForegroundPolicyTest {
    @Test
    fun `keeps service only while a task is active or stopping`() {
        assertFalse(HermesTaskForegroundPolicy.requiresForegroundService(HermesTaskPhase.IDLE))
        assertTrue(HermesTaskForegroundPolicy.requiresForegroundService(HermesTaskPhase.RUNNING))
        assertTrue(HermesTaskForegroundPolicy.requiresForegroundService(HermesTaskPhase.STOPPING))
        assertFalse(HermesTaskForegroundPolicy.requiresForegroundService(HermesTaskPhase.COMPLETED))
        assertFalse(HermesTaskForegroundPolicy.requiresForegroundService(HermesTaskPhase.FAILED))
        assertFalse(HermesTaskForegroundPolicy.requiresForegroundService(HermesTaskPhase.CANCELLED))
    }
}
