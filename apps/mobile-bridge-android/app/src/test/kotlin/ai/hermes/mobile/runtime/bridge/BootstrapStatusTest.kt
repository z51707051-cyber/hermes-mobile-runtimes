package ai.hermes.mobile.runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapStatusTest {
    @Test
    fun bootstrapRequiresLiveAccessibilityObservationBeforeAdvertisingCapability() {
        val status = BootstrapStatusProvider.current()

        assertEquals("HMR-112", status.phase)
        assertEquals(listOf("phone.wait"), status.enabledCapabilities)
    }
}
