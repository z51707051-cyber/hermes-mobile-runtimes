package ai.hermes.mobile.runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BootstrapStatusTest {
    @Test
    fun bootstrapExposesNoDeviceCapabilities() {
        val status = BootstrapStatusProvider.current()

        assertEquals("HMR-102", status.phase)
        assertTrue(status.enabledCapabilities.isEmpty())
    }
}
