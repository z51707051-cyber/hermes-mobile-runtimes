package ai.hermes.mobile.runtime.bridge.observer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotSpecTest {
    @Test
    fun cropBoundsUseOverflowSafeArithmetic() {
        assertTrue(ScreenshotCrop(10, 20, 300, 400).fits(310, 420))
        assertFalse(ScreenshotCrop(10, 20, 301, 400).fits(310, 420))
        assertFalse(ScreenshotCrop(32_767, 32_767, 32_768, 32_768).fits(32_768, 32_768))
    }

    @Test
    fun displayAndCropValuesRemainInsideProtocolBounds() {
        assertThrows(IllegalArgumentException::class.java) { ScreenshotSpec(displayId = 8) }
        assertThrows(IllegalArgumentException::class.java) {
            ScreenshotCrop(0, 0, 0, 1)
        }
    }
}
