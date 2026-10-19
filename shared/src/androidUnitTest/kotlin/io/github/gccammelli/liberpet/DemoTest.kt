package io.github.gccammelli.liberpet

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The demo home loads through the same code as a real Home Assistant, and stays made-up. */
class DemoTest {
    @Test
    fun demoHomeLoads() = runTest {
        // The bundled files, read from the source tree (Compose's resource reader needs a running app).
        val ha = FakeHomeAssistant(read = { java.io.File("src/commonMain/composeResources/" + it).readBytes() })
        val home = ha.loadHome()
        assertEquals(listOf("Mochi", "Luna"), home.pets.map { it.name })
        assertEquals(1, home.devices.count { it.isFeeder }); assertEquals(2, home.devices.count { it.isFountain })
        val feeder = home.devices.first { it.isFeeder }
        assertEquals(listOf("07:00", "10:00", "13:00", "17:30", "21:00"), feeder.plans.map { it.time }.sorted())
        assertTrue(home.devices.all { it.serial.startsWith("DEMO") })
        assertTrue(ha.loadLogs(home).daily.values.all { it.size >= 30 }) // invented history for the charts
    }

    @Test
    fun indicatorLightsToggleInTheDemo() = runTest {
        val ha = FakeHomeAssistant(read = { java.io.File("src/commonMain/composeResources/" + it).readBytes() })
        suspend fun feeder() = ha.loadHome().devices.first { it.isFeeder }
        assertEquals(false, feeder().e["light_switch"]?.on)
        ha.press(feeder(), "light_on")
        assertEquals(true, feeder().e["light_switch"]?.on)
        ha.press(feeder(), "light_off")
        assertEquals(false, feeder().e["light_switch"]?.on)
    }
}
