package io.github.gccammelli.liberpet

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/*
 * Logs and charts come from Home Assistant's recorder: every change of the integration's sensors is a state row.
 * A feeding is a new "last feed time"; a drink is a rise in "today's drinking times". The integration polls the
 * cloud every minute, so two drinks inside one minute show as one. Rows go back as far as the recorder keeps them
 * (10 days by default); daily totals beyond that come from long-term statistics.
 */

data class Point(val at: Instant, val state: String)

/** One log line: a feeding (grams, manual or scheduled) or a drink (ml, seconds). */
data class LogEntry(val at: Instant, val text: String)

val zone get() = TimeZone.currentSystemDefault()
fun Instant.local() = toLocalDateTime(zone)

/** Parses /api/history/period output (minimal_response) into each entity's points. */
fun parseHistory(text: String, ids: List<String>): Map<String, List<Point>> {
    val out = mutableMapOf<String, List<Point>>()
    for (series in json.parseToJsonElement(text).jsonArray) {
        val rows = series.jsonArray.map { it.jsonObject }
        val id = rows.firstOrNull()?.s("entity_id") ?: continue
        out[id] = rows.mapNotNull { r ->
            val t = r.s("last_changed") ?: r.s("last_updated") ?: return@mapNotNull null
            Point(Instant.parse(t), r.s("state") ?: return@mapNotNull null)
        }.filter { it.state != "unavailable" && it.state != "unknown" }
    }
    return ids.associateWith { out[it].orEmpty() }
}

suspend fun HaBackend.history(ids: List<String>, days: Int): Map<String, List<Point>> {
    if (ids.isEmpty()) return emptyMap()
    val now = Clock.System.now()
    val start = now - days.days
    // Without end_time Home Assistant returns only one day.
    return parseHistory(get("/api/history/period/$start?filter_entity_id=${ids.joinToString(",")}&end_time=${now + 1.hours}&minimal_response&no_attributes"), ids)
}

/** The value in effect at [t] (a sensor updated in the same poll lands a moment after). */
private fun List<Point>.at(t: Instant): Double? = lastOrNull { it.at <= t + 10.seconds }?.state?.toDoubleOrNull()
private fun List<Point>.before(t: Instant): Double = lastOrNull { it.at < t - 10.seconds }?.state?.toDoubleOrNull() ?: 0.0

/** Feedings, newest first. Scheduled when the time matches a plan (± 2 min), manual otherwise. */
fun feedLog(times: List<Point>, grams: List<Point>, plans: List<Plan>): List<LogEntry> {
    val planMinutes = plans.map { minutesOf(it.time) }
    return times.map { it.state }.distinct().mapNotNull { s ->
        val at = runCatching { Instant.parse(s) }.getOrNull() ?: return@mapNotNull null
        val changed = times.first { it.state == s }.at
        val g = grams.at(changed)?.let { fmt(it) } ?: "--"
        val l = at.local().time
        val m = l.hour * 60 + l.minute
        val scheduled = planMinutes.any { kotlin.math.abs(it - m) <= 2 || kotlin.math.abs(it - m) >= 1438 }
        LogEntry(at, if (scheduled) "Feeding schedule successful. ${g}g dispensed." else "Manual feeding successful. ${g}g dispensed.")
    }.sortedByDescending { it.at }
}

/**
 * Drinks, newest first: each rise in the day's drink count, with the ml and seconds added alongside it.
 * Rises from before the device reported online are skipped: right after setup or a restart the integration
 * shows placeholder zeros, and the jump to the real count isn't a drink.
 */
fun drinkLog(count: List<Point>, ml: List<Point>, secs: List<Point>, online: List<Point>): List<LogEntry> {
    val out = mutableListOf<LogEntry>()
    for (i in count.indices) {
        val c = count[i].state.toDoubleOrNull() ?: continue
        val prev = count.getOrNull(i - 1) ?: continue
        if (online.lastOrNull { it.at <= prev.at }?.state != "on") continue
        val p = prev.state.toDoubleOrNull() ?: continue
        if (c <= p) continue
        val t = count[i].at
        val dml = ((ml.at(t) ?: 0.0) - ml.before(t)).coerceAtLeast(0.0)
        val ds = ((secs.at(t) ?: 0.0) - secs.before(t)).coerceAtLeast(0.0)
        out += LogEntry(t, "Your pet(s) drank ${fmt(dml)} ml water in ${dur(ds)}.")
    }
    return out.sortedByDescending { it.at }
}

/** One record of the integration's "Recent Activity" sensor: Petlibro's own log, with exact times. */
data class Activity(val at: Instant, val type: String, val event: String, val ml: Double?, val secs: Double?, val portions: Double?, val planId: Long?)

fun parseActivity(attrs: JsonObject): List<Activity> = attrs.a("records")?.mapNotNull { el ->
    val r = el as? JsonObject ?: return@mapNotNull null
    Activity(
        at = r.s("time")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null,
        type = r.s("type") ?: "", event = r.s("event") ?: "",
        ml = r.s("ml")?.toDoubleOrNull(), secs = r.s("seconds")?.toDoubleOrNull(),
        portions = r.s("portions")?.toDoubleOrNull(), planId = r.s("plan_id")?.toLongOrNull(),
    )
}.orEmpty()

/** The sensor keeps the latest records (25); its recorded states hold the older ones. Merged, newest first. */
suspend fun HaBackend.activity(id: String, current: JsonObject, days: Int): List<Activity> {
    val now = Clock.System.now()
    val past = runCatching {
        json.parseToJsonElement(get("/api/history/period/${now - days.days}?filter_entity_id=$id&end_time=${now + 1.hours}"))
            .jsonArray.flatMap { series -> series.jsonArray.flatMap { st -> (st as? JsonObject)?.o("attributes")?.let(::parseActivity).orEmpty() } }
    }.getOrDefault(emptyList())
    return (parseActivity(current) + past).distinctBy { it.at to it.type }.sortedByDescending { it.at }
}

/** Log lines in Petlibro's words. Feedings with a plan id were scheduled; others manual. */
fun activityLog(acts: List<Activity>, gramsPerPortion: Double): List<LogEntry> = acts.map { a ->
    LogEntry(a.at, when {
        a.type == "DRINK" -> "Your pet(s) drank ${fmt(a.ml ?: 0.0)} ml water in ${dur(a.secs ?: 0.0)}."
        a.type == "GRAIN_OUTPUT_SUCCESS" -> {
            val g = fmt((a.portions ?: 0.0) * gramsPerPortion)
            if (a.planId != null || "PLAN" in a.event) "Feeding schedule successful. ${g}g dispensed." else "Manual feeding successful. ${g}g dispensed."
        }
        else -> (a.event.ifBlank { a.type }).lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } + "."
    })
}

/** Each local day's total of a sensor that counts up through the day and resets at midnight (its daily maximum). */
fun dailyMax(points: List<Point>): Map<LocalDate, Double> =
    points.groupBy { it.at.local().date }.mapValues { (_, p) -> p.maxOf { it.state.toDoubleOrNull() ?: 0.0 } }

/** Long-term statistics: per local day, how much a daily-resetting counter grew. Empty until the recorder has compiled some. */
suspend fun HaBackend.dailyStats(id: String, days: Int): Map<LocalDate, Double> {
    val start = Clock.System.now() - days.days
    val r = ws(listOf(buildJsonObject {
        put("type", "recorder/statistics_during_period"); put("start_time", start.toString())
        put("statistic_ids", JsonArray(listOf(JsonPrimitive(id)))); put("period", "day"); put("types", JsonArray(listOf(JsonPrimitive("change"))))
    })).first() as? JsonObject ?: return emptyMap()
    return r.a(id)?.mapNotNull { el ->
        val o = el.jsonObject
        val startMs = o.s("start")?.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        Instant.fromEpochMilliseconds(startMs).local().date to (o.s("change")?.toDoubleOrNull() ?: return@mapNotNull null)
    }?.toMap().orEmpty()
}

/** "HH:mm" → minutes after midnight. */
fun minutesOf(hm: String) = hm.split(":").let { (h, m) -> h.toInt() * 60 + m.toInt() }

/** Today's "HH:mm" in [from] as "HH:mm" in [to]. */
fun shiftHm(hm: String, from: TimeZone, to: TimeZone): String {
    if (from == to) return hm
    val (h, m) = hm.split(":").map { it.toInt() }
    val t = with(from) { kotlinx.datetime.LocalDateTime(Clock.System.now().toLocalDateTime(from).date, LocalTime(h, m)).toInstant() }.toLocalDateTime(to).time
    return "${pad(t.hour)}:${pad(t.minute)}"
}

/** Seconds as Petlibro writes them: "12s", "01m01s". */
fun dur(s: Double) = s.toInt().let { if (it < 60) "${it}s" else "${pad(it / 60)}m${pad(it % 60)}s" }

fun pad(n: Int) = n.toString().padStart(2, '0')
fun fmt(v: Double) = if (v == kotlin.math.floor(v)) v.toLong().toString() else ((v * 10).toLong() / 10.0).toString()
