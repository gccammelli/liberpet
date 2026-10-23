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

    @Test fun malformedOrMissingSettingTargetsDoNotChangeOtherDemoEntities() = runBlocking {
        val demo = demo()
        val before = demo.get("/api/states")
        for ((service, body) in listOf(
            "switch/turn_on" to "{}", "switch/turn_off" to """{"entity_id":"switch.missing"}""",
            "number/set_value" to "{}", "select/select_option" to "{}",
            "button/press" to "{}", "button/press" to """{"entity_id":"button.unrelated"}""",
            "button/press" to """{"entity_id":"button.missing_turn_on_indicator"}""",
        )) assertEquals("[]", demo.post("/api/services/$service", body))
        assertEquals(before, demo.get("/api/states"))
        for (ids in listOf<JsonElement?>(null, JsonArray(emptyList()))) {
            val result = demo.ws(listOf(buildJsonObject { put("type", "recorder/statistics_during_period"); ids?.let { put("statistic_ids", it) } }))
            assertEquals(30, result.single()!!.jsonObject.getValue("").jsonArray.size)
        }
    }

    @Test fun emptyDemoSchedulesRetainExplicitZerosAndProvideFallbackInsights() = runBlocking {
        val raw = json.parseToJsonElement(java.io.File("src/commonMain/composeResources/files/demo/home.json").readText()).jsonObject
        val states = raw.getValue("states").jsonArray.map { entry ->
            val e = entry.jsonObject
            if (e.s("entity_id")!!.endsWith("_feeding_schedule")) JsonObject(e + ("attributes" to buildJsonObject { put("schedule", JsonArray(emptyList())) })) else e
        }
        val snapshot = JsonObject(raw + ("states" to JsonArray(states)))
        val backend = FakeHomeAssistant(now = { Instant.parse("2026-01-01T00:00:00Z") }, read = { snapshot.toString().encodeToByteArray() })
        val home = backend.loadHome()
        val feeder = home.devices.first { it.isFeeder }
        assertTrue(feeder.plans.isEmpty())
        assertEquals(0.0, feeder.num("today_feeding_times"))
        assertEquals(0.0, feeder.num("today_feeding_quantity_weight"))
        assertEquals(emptyList(), parseActivity(json.parseToJsonElement(backend.get("/api/states/${feeder.e.getValue("recent_activity").id}")).jsonObject.o("attributes")!!))
        assertEquals(30, parseCatDays(backend.get("/api/states/$STATS_SENSOR")).size)
    }

    @Test fun optionalPlanNumbersDefaultWithoutPreventingDemoHistory() = runBlocking {
        val raw = json.parseToJsonElement(java.io.File("src/commonMain/composeResources/files/demo/home.json").readText()).jsonObject
        val states = raw.getValue("states").jsonArray.map { entry ->
            val e = entry.jsonObject
            if (e.s("entity_id")!!.endsWith("_feeding_schedule")) JsonObject(e + ("attributes" to buildJsonObject {
                put("schedule", buildJsonArray { add(buildJsonObject { put("time", "08:00"); put("enabled", true) }) })
            })) else e
        }
        val snapshot = JsonObject(raw + ("states" to JsonArray(states)) + ("ignored_extra" to JsonPrimitive("2026-99-99T08:00:00Z")))
        val backend = FakeHomeAssistant(now = { Instant.parse("2026-01-01T20:00:00Z") }, read = { snapshot.toString().encodeToByteArray() })
        val feeder = backend.loadHome().devices.first { it.isFeeder }
        val records = parseActivity(json.parseToJsonElement(backend.get("/api/states/${feeder.e.getValue("recent_activity").id}")).jsonObject.o("attributes")!!)
        assertEquals(3, records.size)
        assertTrue(records.all { it.portions == 1.0 && it.planId == 0L })
        assertEquals(10.0, feeder.num("today_feeding_quantity_weight"))
    }
}
