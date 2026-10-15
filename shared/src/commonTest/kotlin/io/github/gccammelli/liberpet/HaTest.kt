package io.github.gccammelli.liberpet

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class HaTest {
    private fun arr(s: String) = json.parseToJsonElement(s).jsonArray

    private val devs = arr("""[
        {"id":"f1","name":"Granary Camera Feeder","model":"PLAF203","identifiers":[["petlibro","AF01"]],"area_id":"kitchen","sw_version":"3.1.54"},
        {"id":"w1","name":"Dockstream Smart Fountain","name_by_user":"Kitchen fountain","model":"PLWF105","identifiers":[["petlibro","WF01"]],"area_id":null},
        {"id":"p1","name":"Mochi","model":"Pet","identifiers":[["petlibro",42]]},
        {"id":"x","name":"Other","model":"Thing","identifiers":[["hue","1"]]}]""")
    private val ents = arr("""[
        {"entity_id":"sensor.feeder_today","unique_id":"AF01-today_feeding_quantity_weight-AABBCCDDEEFF","device_id":"f1","platform":"petlibro","disabled_by":null},
        {"entity_id":"binary_sensor.feeder_sched","unique_id":"AF01-feeding_schedule","device_id":"f1","platform":"petlibro","disabled_by":null},
        {"entity_id":"sensor.fountain_filter","unique_id":"WF01-remaining_filter_days-112233445566","device_id":"w1","platform":"petlibro","disabled_by":null},
        {"entity_id":"sensor.fountain_off","unique_id":"WF01-wifi_ssid-112233445566","device_id":"w1","platform":"petlibro","disabled_by":"integration"},
        {"entity_id":"number.mochi_drinking_goal","unique_id":"petlibro-drinkingGoal-42","device_id":"p1","platform":"petlibro","disabled_by":null}]""")
    private val areas = arr("""[{"area_id":"kitchen","name":"Kitchen"}]""")
    private fun e(id: String, state: String, attrs: String = "{}") = id to Entity(id, state, json.parseToJsonElement(attrs).jsonObject)
    private val states = mapOf(
        e("sensor.feeder_today", "70.0"),
        e("binary_sensor.feeder_sched", "on", """{"schedule":[
            {"id":7,"label":"","time":"03:20","amount_g":10.0,"amount_raw":1,"enabled":true,"repeat_days":[7,1,2,3,4,5,6],"today":true,"state":"dispensed"},
            {"id":8,"label":"Night","time":"20:15","amount_g":20.0,"amount_raw":2,"enabled":false,"repeat_days":[1,3],"today":false,"state":"pending"}]}"""),
        e("sensor.fountain_filter", "-53.0"),
        e("number.mochi_drinking_goal", "120"),
    )

    @Test
    fun devicesPetsAndKeys() {
        val h = homeFrom(devs, ents, areas, states)
        assertEquals(listOf("Granary Camera Feeder", "Kitchen fountain"), h.devices.map { it.name }) // registry order; the user's name wins
        val feeder = h.devices.first { it.isFeeder }
        assertEquals("Kitchen", feeder.room)
        assertEquals(70.0, feeder.num("today_feeding_quantity_weight"))
        val fountain = h.devices.first { it.isFountain }
        assertEquals(-53.0, fountain.num("remaining_filter_days"))
        assertEquals(null, fountain.e["wifi_ssid"]) // disabled entities are skipped
        assertEquals(120.0, h.pets.single().num("drinkingGoal"))
    }

    @Test
    fun plans() {
        val f = homeFrom(devs, ents, areas, states).devices.first { it.isFeeder }
        val p = f.plans
        assertEquals(listOf(7L, 8L), p.map { it.id })
        assertEquals("dispensed", p[0].todayState)
        assertEquals(null, p[1].todayState)
        assertEquals(listOf(1, 3), p[1].days)
        assertEquals(10.0, f.gramsPerPortion)
        assertTrue(f.scheduleEnabled)
    }

    private fun pt(t: String, s: String) = Point(Instant.parse(t), s)

    @Test
    fun drinksSkipPlaceholderJumpAtSetup() {
        val online = listOf(pt("2026-10-14T22:46:47Z", "off"), pt("2026-10-14T23:47:00Z", "on"))
        val count = listOf(pt("2026-10-14T22:46:47Z", "0"), pt("2026-10-14T23:47:01Z", "1"), pt("2026-10-15T08:00:00Z", "0"), pt("2026-10-15T09:30:05Z", "1"), pt("2026-10-15T10:00:05Z", "2"))
        val ml = listOf(pt("2026-10-14T22:46:47Z", "0"), pt("2026-10-14T23:47:01Z", "6"), pt("2026-10-15T08:00:00Z", "0"), pt("2026-10-15T09:30:06Z", "5"), pt("2026-10-15T10:00:06Z", "12"))
        val secs = listOf(pt("2026-10-15T08:00:00Z", "0"), pt("2026-10-15T09:30:06Z", "20"), pt("2026-10-15T10:00:06Z", "50"))
        val log = drinkLog(count, ml, secs, online)
        assertEquals(listOf("Your pet(s) drank 7 ml water in 30s.", "Your pet(s) drank 5 ml water in 20s."), log.map { it.text })
    }

    @Test
    fun feedingsScheduledOrManual() {
        // Today's date, so the plan's UTC time maps to local time with today's offset (as the app does).
        val d = kotlin.time.Clock.System.now().toString().take(10)
        val plan = Plan(1, shiftHm("08:00", kotlinx.datetime.TimeZone.UTC, zone), 10.0, 1, true, (1..7).toList(), "", null)
        val times = listOf(pt("${d}T08:00:30Z", "${d}T08:00:12Z"), pt("${d}T12:34:40Z", "${d}T12:34:20Z"))
        val grams = listOf(pt("${d}T07:00:00Z", "10.0"), pt("${d}T12:34:41Z", "20.0"))
        val log = feedLog(times, grams, listOf(plan))
        assertEquals(listOf("Manual feeding successful. 20g dispensed.", "Feeding schedule successful. 10g dispensed."), log.map { it.text })
    }

    @Test
    fun planTimesFollowHomeAssistantsZone() {
        val tokyo = kotlinx.datetime.TimeZone.of("Asia/Tokyo")
        fun firstPlan(tz: kotlinx.datetime.TimeZone) = homeFrom(devs, ents, areas, states, tz).devices.first { it.isFeeder }.plans[0].time
        assertEquals("03:20", firstPlan(zone)) // Home Assistant in the phone's zone: the cloud's time as is
        assertEquals(shiftHm("03:20", tokyo, zone), firstPlan(tokyo)) // elsewhere: shown in the phone's zone
        for (t in listOf("00:00", "04:20", "13:05", "23:59")) assertEquals(t, shiftHm(shiftHm(t, zone, tokyo), tokyo, zone))
    }
}
