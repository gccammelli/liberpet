package io.github.gccammelli.liberpet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class DemoBackendTest {
    private fun demo() = FakeHomeAssistant(
        now = { Instant.parse("2026-10-23T20:00:00Z") },
        read = { java.io.File("src/commonMain/composeResources/$it").readBytes() },
    )

    @Test fun recognitionAndInsightsAreConsistentWithTheInventedPets() = runBlocking {
        val demo = demo()
        val home = demo.loadHome()
        val cats = json.parseToJsonElement(demo.get("/api/states/$CATS_SENSOR")).jsonObject
        assertEquals("ready", cats.s("state"))
        val labels = cats.o("attributes")!!.o("labels")!!
        assertTrue(home.pets.all { labels[it.id] != null })
        val stats = json.parseToJsonElement(demo.get("/api/states/$STATS_SENSOR")).jsonObject.o("attributes")!!.o("days")!!
        assertEquals(30, stats.size)
        for (day in stats.values) for ((id, value) in day.jsonObject) {
            assertTrue(home.pets.any { it.id == id })
            val record = value.jsonObject
            assertEquals(record.s("v")!!.toDouble().toInt(), record.a("h")!!.sumOf { it.jsonPrimitive.int })
            assertTrue(record.s("s")!!.toDouble() >= record.s("l")!!.toDouble())
        }
    }

    @Test fun mutableDemoSettingsAndImagesStayLocalAndUnsupportedActionsFail() = runBlocking<Unit> {
        val demo = demo()
        val states = json.parseToJsonElement(demo.get("/api/states")).jsonArray.map { it.jsonObject }
        val switch = states.first { it.s("entity_id")!!.startsWith("switch.") }.s("entity_id")!!
        for ((service, expected) in listOf("turn_on" to "on", "turn_off" to "off")) {
            demo.post("/api/services/switch/$service", """{"entity_id":"$switch"}""")
            assertEquals(expected, json.parseToJsonElement(demo.get("/api/states/$switch")).jsonObject.s("state"))
        }
        val number = states.first { it.s("entity_id")!!.startsWith("number.") }.s("entity_id")!!
        demo.post("/api/services/number/set_value", """{"entity_id":"$number","value":42}""")
        assertEquals("42", json.parseToJsonElement(demo.get("/api/states/$number")).jsonObject.s("state"))
        val select = states.first { it.s("entity_id")!!.startsWith("select.") }.s("entity_id")!!
        demo.post("/api/services/select/select_option", """{"entity_id":"$select","option":"test"}""")
        assertEquals("test", json.parseToJsonElement(demo.get("/api/states/$select")).jsonObject.s("state"))
        demo.post("/api/services/homeassistant/update_entity", "{}")
        for (cat in listOf("mochi", "luna")) assertTrue(demo.bytes("/api/image_proxy/$cat").size > 100)
        assertFailsWith<ApiException> { demo.bytes("/missing") }
        assertFailsWith<ApiException> { demo.get("/missing") }
        assertFailsWith<ApiException> { demo.post("/api/services/petlibro/feed", "{}") }
        assertFailsWith<ApiException> { demo.ws(listOf(buildJsonObject { put("type", "not-implemented") })) }
    }
}
