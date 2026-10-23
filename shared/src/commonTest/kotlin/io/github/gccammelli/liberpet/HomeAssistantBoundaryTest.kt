package io.github.gccammelli.liberpet

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class HomeAssistantBoundaryTest {
    private fun obj(text: String) = json.parseToJsonElement(text).jsonObject
    private fun arr(text: String) = json.parseToJsonElement(text).jsonArray
    private fun entity(state: String, attrs: String = "{}") = Entity("sensor.test", state, obj(attrs))
    private fun device(entities: Map<String, Entity> = emptyMap()) = Device("ha-id", "serial", "Feeder", "PLAF203", null, null, entities)

    @Test fun missingAndMalformedPetMetadataStayUnknown() {
        for (state in listOf("unavailable", "unknown")) assertFalse(entity(state).ok)
        assertTrue(entity("off").ok)
        val empty = Pet("ha", "1", "Cat", emptyMap())
        assertNull(empty.num("weight")); assertNull(empty.avatar); assertNull(empty.breed)
        assertNull(empty.sex); assertNull(empty.birthday); assertNull(empty.ageYears)
        assertNull(empty.ageMonths); assertNull(empty.neutered); assertEquals(emptyList(), empty.linkedSerials)
        for (breed in listOf("", "unknown", "Siamese")) {
            assertEquals(breed.takeIf { it == "Siamese" }, empty.copy(e = mapOf("breedName" to entity(breed))).breed)
        }
        for (date in listOf("invalid", "2020-01-02")) {
            assertEquals(date.takeIf { it.length == 10 }, empty.copy(e = mapOf("birthday" to entity(date))).birthday)
        }
        for (attrs in listOf("{}", """{"years":"bad","months":"bad"}""", """{"years":3,"months":4}""")) {
            val pet = empty.copy(e = mapOf("age" to entity("ok", attrs)))
            assertEquals(if (attrs.contains("\"years\":3")) 3 else null, pet.ageYears)
            assertEquals(if (attrs.contains("\"months\":4")) 4 else null, pet.ageMonths)
        }
        assertNull(device().state("missing")); assertTrue(device().online)
        assertFalse(device(mapOf("online" to entity("off"))).online)
        assertFalse(device().scheduleEnabled); assertEquals(emptyList(), device().dispensingModes)
        assertEquals(emptyList(), device(mapOf("water_dispensing_mode" to entity("on"))).dispensingModes)
        assertEquals("$REPO_URL/blob/main/docs/camera.md", guide("docs/camera.md"))
        assertNull(obj("""{"x":42}""").o("x"))
    }

    @Test fun malformedPlansAreSkippedAndOptionalValuesUseDocumentedDefaults() {
        val schedule = """{"schedule":[{}, {"id":"bad"}, {"id":1}, {"id":2,"time":"08:30","amount_g":"bad","amount_raw":"bad","repeat_days":[{},"bad",1]}, {"id":3,"time":"09:00","amount_raw":0}]}"""
        val d = device(mapOf("feeding_schedule" to entity("off", schedule))).copy(tz = zone)
        assertEquals(listOf(2L, 3L), d.plans.map { it.id })
        val plan = d.plans.first()
        assertEquals("08:30", plan.time); assertEquals(0.0, plan.grams); assertEquals(1, plan.portions)
        assertEquals(listOf(1), plan.days); assertEquals("", plan.label); assertNull(plan.todayState)
        assertFalse(plan.enabled); assertFalse(d.scheduleEnabled)
        assertEquals(10.0, device().gramsPerPortion)
        val zero = device(mapOf("feeding_schedule" to entity("on", """{"schedule":[{"id":1,"time":"08:00","amount_raw":0}]}""")))
        assertEquals(10.0, zero.gramsPerPortion)
    }

    @Test fun recognizerToleratesIncompleteOptionalFieldsWithoutInventingLabels() {
        assertEquals(Cats("waiting", 0.0, emptyMap(), emptyList(), emptyMap()), parseCats("{}"))
        val cats = parseCats("""{"attributes":{"accuracy":"bad","labels":{"object":{},"null":null,"bad":"x"},"to_label":[{}, {"id":"missing-path"},{"id":"p","path":"p.jpg"}],"clips":{"empty":null,"one":{"invalid":{},"none":null,"left":"7"}}}}""")
        assertEquals(0.0, cats.accuracy)
        assertEquals(mapOf("object" to 0, "null" to 0, "bad" to 0), cats.labels)
        assertEquals(listOf(LearnPhoto("p", "p.jpg", "left", null)), cats.toLabel)
        assertEquals(mapOf("empty" to emptyMap(), "one" to mapOf("left" to "7")), cats.clips)
        assertTrue(parseCatDays("{}").isEmpty())
        assertTrue(parseCatDays("""{"attributes":{}}""").isEmpty())
        val days = parseCatDays("""{"attributes":{"days":{"2026-01-01":null,"2026-01-02":{"a":{},"b":{"v":"bad","s":"bad","l":"bad","h":[null,{},"bad",2]}}}}}""")
        assertEquals(emptyMap(), days["2026-01-01"])
        assertEquals(CatDay(0, 0, 0, List(24) { 0 }, ""), days["2026-01-02"]!!["a"])
        assertEquals(listOf(0, 0, 0, 2), days["2026-01-02"]!!["b"]!!.byHour)
    }

    @Test fun statisticsExcludeFutureDaysMissingCatsAndHoursBeyondOneDay() {
        val record = CatDay(2, 20, 10, List(25) { 1 }, "")
        val daily = mapOf("2026-01-01" to mapOf("a" to record), "2026-01-03" to mapOf("a" to record), "2026-01-02" to mapOf("other" to record))
        val stats = catStats(daily, "a", "2026-01-02", 2)
        assertEquals(2, stats.visits); assertEquals(List(24) { 1 }, stats.byHour); assertNull(stats.last)
        val none = catStats(daily, "missing", "2026-01-02", 2)
        assertEquals(0, none.typicalSeconds); assertEquals(0.0, none.leftShare); assertNull(none.last)
    }

    private class Backend(var result: JsonElement? = null, var text: String = "{}") : HaBackend {
        override val base = "https://home.example"
        val posts = mutableListOf<Pair<String, JsonObject>>()
        var unavailable = false
        override suspend fun get(path: String): String { if (unavailable) throw ApiException("Missing helper"); return text }
        override suspend fun post(path: String, body: String): String { posts += path to json.parseToJsonElement(body).jsonObject; return "[]" }
        override suspend fun bytes(path: String) = byteArrayOf()
        override suspend fun ws(commands: List<JsonObject>) = commands.map { result }
    }

    @Test fun unavailableHelpersAndMissingStreamsReturnExplicitFailures() = runTest {
        val backend = Backend()
        for (value in listOf(null, JsonNull, obj("{}"))) {
            backend.result = value
            assertFailsWith<ApiException> { backend.hlsUrl("camera.test") }
            assertFailsWith<ApiException> { backend.resolveMedia("clip") }
        }
        for (path in listOf("/stream", "https://signed.example/stream")) {
            backend.result = obj("""{"url":"$path"}""")
            val expected = if (path.startsWith("/")) backend.base + path else path
            assertEquals(expected, backend.hlsUrl("camera.test")); assertEquals(expected, backend.resolveMedia("clip"))
        }
        backend.unavailable = true
        assertNull(backend.cats()); assertNull(backend.catDays()); assertFalse(backend.label("photo", "cat"))
        assertTrue(backend.posts.isEmpty())
        backend.unavailable = false
        assertTrue(backend.label("photo", "cat"))
        assertEquals("photo=cat", backend.posts.single().second.s("value"))
        assertEquals(MotionSettings(), backend.motionSettings())
        val motion = MotionSettings()
        backend.setMotionSettings(motion)
        assertEquals(motion.encode(), backend.posts.last().second.s("value"))
        assertFailsWith<ApiException> { backend.press(device(), "missing") }
        assertFailsWith<ApiException> { backend.feedNow(device(), 10) }
        backend.petlibro("test", device())
        assertEquals("ha-id", backend.posts.last().second.s("device_id"))
    }

    @Test fun recordingsIgnoreNamelessChildrenAndClipsWithoutMediaIds() = runTest {
        val backend = Backend(result = obj("{}"))
        assertNull(backend.recordingsFolder()); assertEquals(emptyList(), backend.recordingDays("liberpet"))
        backend.result = obj("""{"children":[{}, {"title":"notes"},{"title":"2026-01-01"}]}""")
        assertEquals(listOf("2026-01-01"), backend.recordingDays("liberpet"))
        assertEquals(emptyList(), parseClips("liberpet", "2026-01-01", arr("""[{}, {"title":"08-00-00_manual.mp4"}]""")))
    }

    @Test fun registrySkipsIncompleteEntitiesAndFallsBackToSerialAndCameraEntityId() {
        val devices = arr("""[{"id":"f","identifiers":[["petlibro","AF01"]]}, {"id":"p","model":"Pet","identifiers":[["petlibro","42"]]}, {"id":"p2","model":"Pet","identifiers":[["petlibro","43"]]}, {"id":"x","model":"Pet","identifiers":[["petlibro","not-numeric"]]}, {"id":"other"}]""")
        val entities = arr("""[
            {"entity_id":"missing","device_id":"f","platform":"petlibro","unique_id":"AF01-online"},
            {"entity_id":"unmatched","device_id":"f","platform":"petlibro","unique_id":"invalid"},
            {"entity_id":"no-key","device_id":"f","platform":"petlibro"},
            {"entity_id":"pet-missing","device_id":"p","platform":"petlibro","unique_id":"petlibro-age-42"},
            {"entity_id":"pet-bad-key","device_id":"p","platform":"petlibro","unique_id":"bad"},
            {"entity_id":"pet-no-key","device_id":"p","platform":"petlibro"},
            {"entity_id":"camera.missing"}, {"entity_id":"camera.present","area_id":"room"}, {},
            {"entity_id":"sensor.main","device_id":"p","platform":"petlibro","unique_id":"petlibro-pet-42"}
        ]""")
        val main = Entity("sensor.main", "on", obj("""{"mainPet":true,"nickname":"Owner"}"""))
        val home = homeFrom(devices, entities, arr("""[{"area_id":"room","name":"Room"}]"""), mapOf("camera.present" to entity("idle"), main.id to main))
        assertEquals("AF01", home.devices.single().name); assertEquals("", home.devices.single().model)
        assertTrue(home.devices.single().e.isEmpty())
        assertEquals(listOf("42", "not-numeric", "43"), home.pets.map { it.id })
        assertEquals(listOf(Camera("camera.present", "camera.present", "Room")), home.cameras)
        assertNull(home.owner)
        val owner = Entity("sensor.petlibro_owner", "on", obj("""{"nickname":"Owner"}"""))
        assertEquals("Owner", homeFrom(arr("[]"), arr("[]"), arr("[]"), mapOf(owner.id to owner)).owner)
        assertTrue(homeFrom(arr("[]"), arr("[]"), arr("[]"), emptyMap()).devices.isEmpty())
    }

    @Test fun loadHomeDefaultsMissingStateAttributesAndInvalidTimeZones() = runTest {
        val registries = listOf(arr("""[{"id":"f","model":"PLAF203","identifiers":[["petlibro","AF01"]]}]"""), arr("""[{"entity_id":"sensor.test","platform":"petlibro","device_id":"f","unique_id":"AF01-online"}]"""), arr("[]"))
        val backend = object : HaBackend {
            var config = "{}"
            override val base = "https://home.example"
            override suspend fun ws(commands: List<JsonObject>) = registries
            override suspend fun get(path: String) = if (path == "/api/config") config else """[{"entity_id":"sensor.test"}]"""
            override suspend fun bytes(path: String) = error("No image request expected")
            override suspend fun post(path: String, body: String) = error("No mutation expected")
        }
        for ((config, expected) in listOf("{}" to "UTC", """{"time_zone":"invalid-zone"}""" to "UTC", """{"time_zone":"Asia/Tokyo"}""" to "Asia/Tokyo")) {
            backend.config = config
            val loaded = backend.loadHome().devices.single()
            assertEquals(expected, loaded.tz.id)
            assertEquals("", loaded.e["online"]!!.state); assertTrue(loaded.e["online"]!!.attrs.isEmpty())
        }
    }
}
