package io.github.gccammelli.liberpet

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionSettingsTest {
    @Test fun encodesWhatTheRecorderReads() {
        // Same strings as tools/recorder/test_settings.py.
        assertEquals("s=50;m=" + "f".repeat(36), MotionSettings().encode())
        val topLeft = MotionSettings(100, List(MOTION_COLS * MOTION_ROWS) { it == 0 })
        assertEquals("s=100;m=8" + "0".repeat(35), topLeft.encode())
        assertEquals(topLeft, MotionSettings.decode(topLeft.encode()))
    }

    @Test fun anEmptyHelperMeansDefaults() {
        assertEquals(MotionSettings(), MotionSettings.decode(""))
        assertEquals(MotionSettings(7), MotionSettings.decode("s=7"))
    }
}
