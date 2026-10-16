package io.github.gccammelli.liberpet

import io.github.gccammelli.liberpet.res.Res
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * "Try the demo": a pretend Home Assistant answering the same calls as a real one, so every screen runs its real
 * code. Its home is a scrubbed snapshot of a real one (files/demo/home.json: invented pets, serials, ids and places;
 * nothing personal), brought up to date: times move with the clock, today's schedule follows it, and charts and
 * Cat Insights get invented history. Switches and settings change for the session; feeding and plans don't.
 */
class FakeHomeAssistant(
    private val now: () -> Instant = { Clock.System.now() },
    /** Reads a bundled demo file ("files/demo/…"); tests read them from disk. */
    private val read: suspend (String) -> ByteArray = { Res.readBytes(it) },
) : HaBackend {
    override val base = "demo"
    private val tz = TimeZone.currentSystemDefault()
    private var states: MutableMap<String, JsonObject>? = null
    private var registry: Triple<JsonArray, JsonArray, JsonArray>? = null

    private suspend fun home(): MutableMap<String, JsonObject> {
        states?.let { return it }
        val raw = json.parseToJsonElement(read("files/demo/home.json").decodeToString()).jsonObject
        val shift = now() - Instant.parse(raw.s("t0")!!)
        val shifted = shiftTimes(raw, shift).jsonObject
        registry = Triple(shifted.a("devices")!!, shifted.a("entities")!!, shifted.a("areas")!!)
        return shifted.a("states")!!.associate { it.jsonObject.s("entity_id")!! to it.jsonObject }.toMutableMap()
            .also { m -> m.replaceAll { _, st -> livened(st) }; states = m }
    }

    /** Moves every date-time in the snapshot forward, so "2 hours ago" stays 2 hours ago. Dates alone (birthdays) stay. */
    private fun shiftTimes(e: JsonElement, by: kotlin.time.Duration): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { shiftTimes(it.value, by) })
        is JsonArray -> JsonArray(e.map { shiftTimes(it, by) })
        is JsonPrimitive -> if (e.isString && DATE_TIME.matches(e.content))
            runCatching { JsonPrimitive((Instant.parse(e.content) + by).toString()) }.getOrDefault(e) else e
        else -> e
    }

    /** The feeder's day as it would be now: plans before now dispensed, the rest pending, and its logs and totals to match. */
    private fun livened(st: JsonObject): JsonObject {
        val id = st.s("entity_id")!!
        val schedule = st.o("attributes")?.a("schedule") ?: return st
        if (!id.endsWith("_feeding_schedule")) return st
        val nowLocal = now().toLocalDateTime(tz)
        val items = schedule.map { p ->
            val o = p.jsonObject
            val done = (o.s("time") ?: "99:99") <= "${pad(nowLocal.hour)}:${pad(nowLocal.minute)}"
            JsonObject(o + ("state" to JsonPrimitive(if (done) "dispensed" else "pending")) + ("today" to JsonPrimitive(true)))
        }
        return JsonObject(st + ("attributes" to JsonObject(st.o("attributes")!! + ("schedule" to JsonArray(items)))))
    }

    private fun feederPlans(m: Map<String, JsonObject>) = m.values.firstOrNull { it.s("entity_id")!!.endsWith("_feeding_schedule") && it.o("attributes")?.a("schedule")?.isNotEmpty() == true }
        ?.o("attributes")?.a("schedule").orEmpty().map { it.jsonObject }

    /** Three days of feedings at the plan times (the snapshot's own times are someone's real routine). */
    private fun feedRecords(m: Map<String, JsonObject>): JsonArray = buildJsonArray {
        val today = now().toLocalDateTime(tz).date
        for (back in 0..2) for (p in feederPlans(m).sortedByDescending { it.s("time") }) {
            val (h, mi) = p.s("time")!!.split(":").map { it.toInt() }
            val at = today.minus(DatePeriod(days = back)).atTime(LocalTime(h, mi)).toInstant(tz) + 9.seconds
            if (at < now()) add(buildJsonObject {
                put("time", at.toString()); put("type", "GRAIN_OUTPUT_SUCCESS"); put("event", "FEEDING_PLAN_SUCCESS")
                put("portions", p.s("amount_raw")?.toIntOrNull() ?: 1); put("plan_id", p.s("id")?.toLongOrNull() ?: 0)
            })
        }
    }

    override suspend fun get(path: String): String {
        val m = home()
        return when {
            path == "/api/config" -> buildJsonObject { put("time_zone", tz.id) }.toString()
            path == "/api/states" -> JsonArray(m.values.map(::withLiveFeeding)).toString()
            path.startsWith("/api/history/period") -> "[]"
            path == "/api/states/$CATS_SENSOR" -> catsSensor(m).toString()
            path == "/api/states/$STATS_SENSOR" -> statsSensor(m).toString()
            path.startsWith("/api/states/") -> m[path.removePrefix("/api/states/")]?.let(::withLiveFeeding)?.toString()
                ?: throw ApiException("Home Assistant: HTTP 404")
            else -> throw ApiException("Home Assistant: HTTP 404")
        }
    }

    /** The feeder's log, last feed and today's totals, from [feedRecords]. */
    private fun withLiveFeeding(st: JsonObject): JsonObject {
        val m = states ?: return st
        val id = st.s("entity_id")!!
        val records = feedRecords(m)
        val today = now().toLocalDateTime(tz).date
        val todays = records.filter { Instant.parse(it.jsonObject.s("time")!!).toLocalDateTime(tz).date == today }
        val grams = (feederPlans(m).firstOrNull()?.s("amount_g")?.toDoubleOrNull() ?: 10.0)
        fun state(v: Any) = JsonObject(st + ("state" to JsonPrimitive(v.toString())))
        return when {
            id.endsWith("granary_camera_feeder_recent_activity") ->
                JsonObject(st + ("attributes" to JsonObject(st.o("attributes")!! + ("records" to records))))
            id.endsWith("feeder_today_feeding_times") -> state(todays.size)
            id.endsWith("feeder_today_feeding_quantity_weight") -> state(todays.size * grams)
            id.endsWith("feeder_last_feed_time") -> records.firstOrNull()?.let { state(it.jsonObject.s("time")!!) } ?: st
            else -> st
        }
    }

    override suspend fun post(path: String, body: String): String {
        val m = home()
        val data = runCatching { json.parseToJsonElement(body).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        val target = data.s("entity_id")
        fun set(v: String) { target?.let { t -> m[t]?.let { m[t] = JsonObject(it + ("state" to JsonPrimitive(v))) } } }
        when (path.removePrefix("/api/services/")) {
            "switch/turn_on" -> set("on")
            "switch/turn_off" -> set("off")
            "select/select_option" -> set(data.s("option") ?: return "[]")
            "number/set_value" -> set(data.s("value") ?: return "[]")
            "button/press", "homeassistant/update_entity" -> {}
            else -> throw ApiException("This is the demo: there's no real feeder or fountain to do that")
        }
        return "[]"
    }

    override suspend fun bytes(path: String): ByteArray = when {
        path.startsWith("/api/image_proxy/") && "mochi" in path -> read("files/demo/mochi.jpg")
        path.startsWith("/api/image_proxy/") && "luna" in path -> read("files/demo/luna.jpg")
        else -> throw ApiException("Home Assistant: HTTP 404")
    }

    override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
        home()
        val (devices, entities, areas) = registry!!
        return commands.map { c ->
            when (c.s("type")) {
                "config/device_registry/list" -> devices
                "config/entity_registry/list" -> entities
                "config/area_registry/list" -> areas
                "recorder/statistics_during_period" -> dailyTotals(c.a("statistic_ids")?.firstOrNull()?.let { (it as JsonPrimitive).content } ?: "")
                else -> throw ApiException("That isn't part of the demo")
            }
        }
    }

    /** 30 invented days of food (g) or water (ml) totals, the same every time. */
    private fun dailyTotals(id: String): JsonObject {
        val food = "feeding" in id
        val today = now().toLocalDateTime(tz).date
        val rnd = Random(id.hashCode())
        return buildJsonObject {
            put(id, buildJsonArray {
                for (back in 30 downTo 1) {
                    val start = today.minus(DatePeriod(days = back)).atTime(LocalTime(0, 0)).toInstant(tz)
                    add(buildJsonObject {
                        put("start", start.toEpochMilliseconds())
                        put("change", if (food) 40.0 + rnd.nextInt(4) * 10 else 70.0 + rnd.nextInt(90))
                    })
                }
            })
        }
    }

    private fun pets(m: Map<String, JsonObject>) = registry!!.first.map { it.jsonObject }.filter { it.s("model") == "Pet" }
        .map { it.a("identifiers")!![0].jsonArray[1].let { id -> (id as JsonPrimitive).content } }

    /** Cat recognition, already taught. */
    private fun catsSensor(m: Map<String, JsonObject>) = buildJsonObject {
        put("entity_id", CATS_SENSOR); put("state", "ready")
        putJsonObject("attributes") {
            put("accuracy", 0.95)
            putJsonObject("labels") { pets(m).forEachIndexed { i, p -> put(p, 6 - i) }; put(NO_CAT, 2) }
            put("to_label", JsonArray(emptyList())); putJsonObject("clips") {}
        }
    }

    /** Cat Insights: 30 invented days per cat, around the feeding times. One cat prefers the left bowl, one the right. */
    private fun statsSensor(m: Map<String, JsonObject>) = buildJsonObject {
        put("entity_id", STATS_SENSOR); put("state", now().toString())
        val today = now().toLocalDateTime(tz)
        val hours = feederPlans(m).mapNotNull { it.s("time")?.take(2)?.toIntOrNull() }.ifEmpty { listOf(7, 12, 18) }
        putJsonObject("attributes") {
            putJsonObject("days") {
                for (back in 0..29) {
                    val day = today.date.minus(DatePeriod(days = back))
                    putJsonObject(day.toString()) {
                        pets(m).forEachIndexed { i, pet ->
                            val rnd = Random(day.toEpochDays() * 31 + i)
                            val visits = hours.filter { back > 0 || it <= today.hour }.filter { rnd.nextInt(10) < 8 }
                            if (visits.isEmpty()) return@forEachIndexed
                            val secs = visits.map { 50 + rnd.nextInt(90) }
                            putJsonObject(pet) {
                                put("v", visits.size); put("s", secs.sum())
                                put("l", secs.sumOf { if (rnd.nextInt(10) < (if (i == 0) 7 else 2)) it else 0 })
                                put("h", JsonArray((0..23).map { h -> JsonPrimitive(visits.count { it == h }) }))
                                put("last", "${pad(visits.max())}:${pad(rnd.nextInt(10))}:${pad(rnd.nextInt(60))}")
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val DATE_TIME = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*""")
    }
}
