package io.github.gccammelli.liberpet

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

class HistoryBoundaryTest {
    private val at = Instant.parse("2026-01-01T12:00:00Z")
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject

    @Test fun recorderSkipsEmptySeriesMissingFieldsAndUnknownStates() {
        val history = parseHistory("""[[],[{}],[{"entity_id":"sensor.test","last_changed":"2026-01-01T12:00:00Z","state":"1"},
            {"last_updated":"2026-01-01T12:01:00Z","state":"2"},{"state":"3"},
            {"last_changed":"2026-01-01T12:02:00Z"},{"last_changed":"2026-01-01T12:03:00Z","state":"unknown"},
            {"last_changed":"2026-01-01T12:04:00Z","state":"unavailable"}]]""", listOf("sensor.test", "sensor.absent"))
        assertEquals(listOf(Point(at, "1"), Point(at + 60.seconds, "2")), history["sensor.test"])
        assertEquals(emptyList(), history["sensor.absent"])
        assertEquals(emptyMap(), parseHistory("[]", emptyList()))
    }

    @Test fun feedHistoryRejectsInvalidTimesAndKeepsMissingWeightsExplicit() {
        val times = listOf(Point(at, "bad"), Point(at, at.toString()), Point(at + 30.seconds, at.toString()))
        for (weights in listOf(emptyList(), listOf(Point(at, "unknown")), listOf(Point(at + 60.seconds, "20")))) {
            assertEquals(listOf(LogEntry(at, "Manual feeding successful. --g dispensed.")), feedLog(times, weights, emptyList()))
        }
        val midnight = Instant.parse("2026-01-01T00:01:00Z")
        val localMinutes = midnight.local().time.let { it.hour * 60 + it.minute }
        val before = (localMinutes + 1438) % 1440
        val plan = Plan(1, "${pad(before / 60)}:${pad(before % 60)}", 10.0, 1, true, emptyList(), "", null)
        assertTrue(feedLog(listOf(Point(midnight, midnight.toString())), emptyList(), listOf(plan)).single().text.startsWith("Feeding schedule"))
    }

    @Test fun drinkingHistoryRejectsUnknownCountsAndOfflineJumps() {
        val counts = listOf(Point(at, "bad"), Point(at + 30.seconds, "1"), Point(at + 60.seconds, "2"))
        assertTrue(drinkLog(counts, emptyList(), emptyList(), emptyList()).isEmpty())
        val online = listOf(Point(at, "on"))
        val logs = drinkLog(counts, emptyList(), emptyList(), online)
        assertEquals(listOf(LogEntry(at + 60.seconds, "Your pet(s) drank 0 ml water in 0s.")), logs)
        val values = listOf(Point(at, "unknown"), Point(at + 60.seconds, "10"), Point(at + 120.seconds, "99"))
        assertEquals("Your pet(s) drank 10 ml water in 10s.", drinkLog(counts, values, values, online).single().text)
        val reset = listOf(Point(at, "20"), Point(at + 60.seconds, "5"))
        assertEquals("Your pet(s) drank 0 ml water in 0s.", drinkLog(counts, reset, reset, online).single().text)
    }

    @Test fun sparseActivityUsesDefaultsAndRetainsUnknownEventDescriptions() {
        assertTrue(parseActivity(obj("{}")).isEmpty())
        val activities = parseActivity(obj("""{"records":[null,{}, {"time":"bad"}, {"time":"2026-01-01T12:00:00Z"}]}"""))
        assertEquals(listOf(Activity(at, "", "", null, null, null, null)), activities)
        val entries = listOf(
            Activity(at, "DRINK", "", null, null, null, null),
            Activity(at, "DRINK", "", 3.0, 2.0, null, null),
            Activity(at, "GRAIN_OUTPUT_SUCCESS", "PLAN_SUCCESS", null, null, null, null),
            Activity(at, "GRAIN_OUTPUT_SUCCESS", "MANUAL", null, null, 2.0, null),
            Activity(at, "NEW_TYPE", "", null, null, null, null),
            Activity(at, "NEW_TYPE", "LOW_BATTERY", null, null, null, null),
            activities.single(),
        )
        assertEquals(listOf("Your pet(s) drank 0 ml water in 0s.", "Your pet(s) drank 3 ml water in 2s.",
            "Feeding schedule successful. 0g dispensed.", "Manual feeding successful. 20g dispensed.", "New type.", "Low battery.", "."), activityLog(entries, 10.0).map { it.text })
        assertEquals(mapOf(at.local().date to 12.0), dailyMax(listOf(Point(at, "unknown"), Point(at, "3"), Point(at, "12"))))
    }

    @Test fun historicalActivityMergesDeduplicatesAndSurvivesRecorderFailure() = runTest {
        val current = obj("""{"records":[{"time":"2026-01-01T12:00:00Z","type":"DRINK","ml":2}]}""")
        val backend = object : HaBackend {
            var response = """[[null,{}, {"attributes":{}}, {"attributes":{"records":[{"time":"2026-01-01T12:00:00Z","type":"DRINK","ml":1},{"time":"2026-01-01T11:00:00Z","type":"DRINK"}]}}]]"""
            var fail = false
            override val base = "https://home.example"
            override suspend fun get(path: String): String { if (fail) throw ApiException("Recorder unavailable"); return response }
            override suspend fun post(path: String, body: String) = error("No mutations expected")
            override suspend fun bytes(path: String) = error("No pictures expected")
            override suspend fun ws(commands: List<JsonObject>) = emptyList<JsonElement>()
        }
        val merged = backend.activity("sensor.activity", current, 10)
        assertEquals(2, merged.size); assertEquals(2.0, merged.first().ml)
        backend.fail = true
        assertEquals(parseActivity(current), backend.activity("sensor.activity", current, 10))
        assertEquals(emptyMap(), backend.history(emptyList(), 10))
    }

    @Test fun dailyStatisticsIgnoreIncompleteRowsAndMissingSensors() = runTest {
        val backend = object : HaBackend {
            var result: JsonElement? = null
            override val base = "https://home.example"
            override suspend fun ws(commands: List<JsonObject>) = listOf(result)
            override suspend fun get(path: String) = error("No request expected")
            override suspend fun post(path: String, body: String) = error("No mutation expected")
            override suspend fun bytes(path: String) = error("No picture expected")
        }
        for (value in listOf(null, JsonNull, obj("{}"))) {
            backend.result = value
            assertEquals(emptyMap(), backend.dailyStats("sensor.test", 10))
        }
        backend.result = obj("""{"sensor.test":[{}, {"start":"bad"}, {"start":0}, {"start":0,"change":"bad"}, {"start":${at.toEpochMilliseconds()},"change":12}]}""")
        assertEquals(mapOf(at.local().date to 12.0), backend.dailyStats("sensor.test", 10))
    }
}
