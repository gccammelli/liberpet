package io.github.gccammelli.liberpet

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class DemoEndpointsTest {
    private fun backend() = FakeHomeAssistant(now = { Instant.parse("2026-10-21T16:00:00Z") }, read = {
        java.io.File("src/commonMain/composeResources/$it").readBytes()
    })

    @Test fun settingsChangeTheSessionAndUnavailableActionsFailExplicitly() = runTest {
        val ha = backend()
        val states = json.parseToJsonElement(ha.get("/api/states")).jsonArray.map { it.jsonObject }
        for ((domain, service, field, value) in listOf(
            listOf("switch", "turn_on", "", "on"), listOf("switch", "turn_off", "", "off"),
            listOf("select", "select_option", "option", "test-option"), listOf("number", "set_value", "value", "7")
        )) {
            val id = states.first { it.s("entity_id")!!.startsWith("$domain.") }.s("entity_id")!!
            val body = buildJsonObject { put("entity_id", id); if (field.isNotEmpty()) put(field, value) }
            ha.post("/api/services/$domain/$service", body.toString())
            assertEquals(value, json.parseToJsonElement(ha.get("/api/states/$id")).jsonObject.s("state"))
        }
        assertEquals("[]", ha.post("/api/services/homeassistant/update_entity", "invalid"))
        assertFailsWith<ApiException> { ha.post("/api/services/petlibro/manual_feed", "{}") }
        assertFailsWith<ApiException> { ha.get("/missing") }
        assertFailsWith<ApiException> { ha.get("/api/states/sensor.missing") }
        assertFailsWith<ApiException> { ha.ws(listOf(buildJsonObject { put("type", "unknown") })) }
        assertFailsWith<ApiException> { ha.bytes("/api/image_proxy/missing") }
        assertTrue(ha.bytes("/api/image_proxy/image.mochi").isNotEmpty())
        assertTrue(ha.bytes("/api/image_proxy/image.luna").isNotEmpty())
        assertEquals("[]", ha.get("/api/history/period"))
    }

    @Test fun recognitionAndStatisticsAreConsistentAndRepeatable() = runTest {
        val ha = backend()
        val cats = json.parseToJsonElement(ha.get("/api/states/$CATS_SENSOR")).jsonObject
        assertEquals("ready", cats.s("state"))
        assertEquals(3, cats.o("attributes")!!.o("labels")!!.size)
        val stats = json.parseToJsonElement(ha.get("/api/states/$STATS_SENSOR")).jsonObject
        assertEquals(30, stats.o("attributes")!!.o("days")!!.size)
        assertEquals(stats.toString(), ha.get("/api/states/$STATS_SENSOR"))
        for (id in listOf("sensor.today_feeding", "sensor.today_drinking")) {
            val commands = listOf(buildJsonObject { put("type", "recorder/statistics_during_period"); put("statistic_ids", JsonArray(listOf(JsonPrimitive(id)))) })
            val result = ha.ws(commands)
            assertEquals(result, ha.ws(commands))
            assertEquals(30, result.single()!!.jsonObject.getValue(id).jsonArray.size)
        }
    }
}
