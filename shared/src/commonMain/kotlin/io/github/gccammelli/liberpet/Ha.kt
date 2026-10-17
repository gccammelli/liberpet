package io.github.gccammelli.liberpet

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/*
 * Petlibro devices through the user's Home Assistant, which runs the community PETLIBRO integration
 * (github.com/jjjonesjr33/petlibro) and holds the only Petlibro cloud login. The app never talks to Petlibro.
 */

const val INTEGRATION_URL = "https://github.com/jjjonesjr33/petlibro"
const val REPO_URL = "https://github.com/gccammelli/liberpet"
const val DONATE_URL = "https://buymeacoffee.com/cammelli"
/** A setup guide in LiberPet's repository ("docs/camera.md"). */
fun guide(path: String) = "$REPO_URL/blob/main/$path"

class ApiException(message: String) : Exception(message)
class AuthException(message: String) : Exception(message)

val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.a(k: String): JsonArray? = this[k] as? JsonArray

/** Home Assistant's API: real HTTP, or [FakeHomeAssistant] for the demo. */
interface HaBackend {
    val base: String
    suspend fun get(path: String): String
    suspend fun post(path: String, body: String): String
    /** Raw bytes (pictures through Home Assistant's image proxy). */
    suspend fun bytes(path: String): ByteArray
    /** Websocket commands in one session. Returns each result. */
    suspend fun ws(commands: List<JsonObject>): List<JsonElement?>
}

class HaHttp(url: String, private val token: String) : HaBackend {
    override val base = url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
    private val http = HttpClient {
        install(HttpTimeout) { connectTimeoutMillis = 15_000; requestTimeoutMillis = 60_000; socketTimeoutMillis = 60_000 }
        install(WebSockets)
        expectSuccess = false
    }

    override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> {
        val out = mutableListOf<JsonElement?>()
        http.webSocket(base.replaceFirst("http", "ws") + "/api/websocket") {
            suspend fun next() = json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
            next() // auth_required
            send(Frame.Text(buildJsonObject { put("type", "auth"); put("access_token", token) }.toString()))
            if (next().s("type") != "auth_ok") throw AuthException("Home Assistant didn't accept the access token")
            commands.forEachIndexed { i, c ->
                send(Frame.Text(JsonObject(c + ("id" to JsonPrimitive(i + 1))).toString()))
                var m: JsonObject
                do m = next() while (m.s("id") != "${i + 1}")
                if (m.s("success") != "true") throw ApiException(m.o("error")?.s("message") ?: "Home Assistant refused ${c.s("type")}")
                out += m["result"]
            }
        }
        return out
    }

    override suspend fun get(path: String) = check(http.get(base + path) { header("Authorization", "Bearer $token") })
    override suspend fun post(path: String, body: String) = check(http.post(base + path) {
        header("Authorization", "Bearer $token"); contentType(ContentType.Application.Json); setBody(body)
    })

    override suspend fun bytes(path: String): ByteArray {
        val r = http.get(base + path) { header("Authorization", "Bearer $token") }
        if (r.status.value !in 200..299) { check(r) }
        return r.readRawBytes()
    }

    private suspend fun check(r: HttpResponse): String {
        val text = r.bodyAsText()
        when (r.status.value) {
            in 200..299 -> return text
            401, 403 -> throw AuthException("Home Assistant didn't accept the access token")
            else -> throw ApiException("Home Assistant: HTTP ${r.status.value} ${text.take(200)}")
        }
    }
}

// ---------- Model ----------

class Entity(val id: String, val state: String, val attrs: JsonObject) {
    val num get() = state.toDoubleOrNull()
    val on get() = state == "on"
    val ok get() = state != "unavailable" && state != "unknown"
}

/** A Petlibro device (feeder or fountain) with its entities keyed by the integration's key ("today_drinking_amount"). */
/** [tz] is Home Assistant's time zone: the integration logs in with it, so the cloud's plan times are in it. */
data class Device(val haId: String, val serial: String, val name: String, val model: String, val room: String?, val firmware: String?, val e: Map<String, Entity>, val tz: TimeZone = TimeZone.UTC) {
    val isFeeder get() = model.startsWith("PLAF")
    val isFountain get() = model.startsWith("PLWF")
    fun num(k: String) = e[k]?.num
    fun state(k: String) = e[k]?.state
    val online get() = e["online"]?.on ?: true
}

data class Pet(val haId: String, val id: String, val name: String, val e: Map<String, Entity>) {
    fun num(k: String) = e[k]?.num
    /** The pet's photo through Home Assistant's image proxy (the integration's image entity), never Petlibro's server. */
    val avatar get() = e["avatar"]?.let { "/api/image_proxy/${it.id}?v=${it.state}" }
    val breed get() = e["breedName"]?.state?.takeIf { it.isNotBlank() && it != "unknown" }
    val sex get() = e["gender"]?.state
    val birthday get() = e["birthday"]?.state?.takeIf { it.length == 10 }
    val ageYears get() = e["age"]?.attrs?.s("years")?.toIntOrNull()
    val ageMonths get() = e["age"]?.attrs?.s("months")?.toIntOrNull()
    val neutered get() = e["sterilization"]?.on
    val linkedSerials get() = e["boundDeviceNums"]?.attrs?.keys?.toList().orEmpty()
}

/** A feeding plan. [time] is "HH:mm" in the phone's time zone; [days] are 1 = Monday … 7 = Sunday. */
data class Plan(val id: Long, val time: String, val grams: Double, val portions: Int, val enabled: Boolean, val days: List<Int>, val label: String, val todayState: String?)

/** A Home Assistant camera entity; [area] is its area's name (its own, or its device's). */
data class Camera(val id: String, val name: String, val area: String?)

data class Home(val devices: List<Device>, val pets: List<Pet>, val owner: String?, val cameras: List<Camera> = emptyList())

private val DEVICE_KEY = Regex("""^[^-]+-(.+?)(?:-[0-9A-F]{12})?$""")
private val PET_KEY = Regex("""^petlibro-(.+)-(\d+)$""")

suspend fun HaBackend.loadHome(): Home {
    val (devs, ents, areas) = ws(listOf("config/device_registry/list", "config/entity_registry/list", "config/area_registry/list")
        .map { buildJsonObject { put("type", it) } }).map { it as JsonArray }
    val states = json.parseToJsonElement(get("/api/states")).jsonArray.associate {
        val o = it.jsonObject
        o.s("entity_id")!! to Entity(o.s("entity_id")!!, o.s("state") ?: "", o.o("attributes") ?: JsonObject(emptyMap()))
    }
    val tz = json.parseToJsonElement(get("/api/config")).jsonObject.s("time_zone")?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.UTC
    return homeFrom(devs, ents, areas, states, tz)
}

fun homeFrom(devs: JsonArray, ents: JsonArray, areas: JsonArray, states: Map<String, Entity>, tz: TimeZone = TimeZone.UTC): Home {
    val areaName = areas.associate { it.jsonObject.s("area_id") to it.jsonObject.s("name") }
    val byDevice = ents.map { it.jsonObject }.filter { it.s("platform") == "petlibro" && it.s("disabled_by") == null }.groupBy { it.s("device_id") }
    val devices = mutableListOf<Device>()
    val pets = mutableListOf<Pet>()
    for (d in devs.map { it.jsonObject }) {
        val ident = d.a("identifiers")?.map { it.jsonArray }?.firstOrNull { (it[0] as JsonPrimitive).content == "petlibro" } ?: continue
        val serial = (ident[1] as JsonPrimitive).content
        val mine = byDevice[d.s("id")].orEmpty()
        val name = d.s("name_by_user") ?: d.s("name") ?: serial
        if (d.s("model") == "Pet") {
            val e = mine.mapNotNull { r -> PET_KEY.find(r.s("unique_id") ?: "")?.let { m -> states[r.s("entity_id")]?.let { m.groupValues[1] to it } } }.toMap()
            pets += Pet(d.s("id")!!, serial, name, e)
        } else {
            val e = mine.mapNotNull { r -> DEVICE_KEY.find(r.s("unique_id") ?: "")?.let { m -> states[r.s("entity_id")]?.let { m.groupValues[1] to it } } }.toMap()
            devices += Device(d.s("id")!!, serial, name, d.s("model") ?: "", areaName[d.s("area_id")], d.s("sw_version"), e, tz)
        }
    }
    val deviceArea = devs.associate { it.jsonObject.s("id") to it.jsonObject.s("area_id") }
    val cameras = ents.map { it.jsonObject }.filter { it.s("entity_id")?.startsWith("camera.") == true && it.s("disabled_by") == null }.mapNotNull { r ->
        val id = r.s("entity_id")!!
        val st = states[id] ?: return@mapNotNull null
        Camera(id, st.attrs.s("friendly_name") ?: id, areaName[r.s("area_id") ?: deviceArea[r.s("device_id")]])
    }
    val owner = states.values.firstOrNull { it.attrs.s("nickname") != null && it.id.startsWith("sensor.petlibro_") }?.attrs?.s("nickname")
    return Home(devices, pets.sortedWith(compareBy({ it.e["pet"]?.attrs?.s("mainPet") != "true" }, { it.id.toLongOrNull() ?: 0 })), owner, cameras) // devices in Home Assistant's order (Petlibro's isn't exposed); main pet first, as Petlibro
}

// ---------- Feeder ----------

val Device.plans: List<Plan>
    get() = e["feeding_schedule"]?.attrs?.a("schedule")?.mapNotNull { el ->
        val p = el.jsonObject
        Plan(
            id = p.s("id")?.toLongOrNull() ?: return@mapNotNull null,
            time = shiftHm(p.s("time")?.take(5) ?: return@mapNotNull null, tz, zone),
            grams = p.s("amount_g")?.toDoubleOrNull() ?: 0.0,
            portions = p.s("amount_raw")?.toIntOrNull() ?: 1,
            enabled = p.s("enabled") == "true",
            days = p.a("repeat_days")?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }.orEmpty(),
            label = p.s("label").orEmpty(),
            todayState = p.s("state").takeIf { p.s("today") == "true" },
        )
    }.orEmpty()

/** Grams in one portion (the feeder dispenses whole portions; 10 g on the Granary). */
val Device.gramsPerPortion: Double
    get() = plans.firstOrNull { it.portions > 0 }?.let { it.grams / it.portions } ?: 10.0

val Device.scheduleEnabled get() = e["feeding_schedule"]?.on ?: false

// ---------- Fountain ----------

val Device.dispensingModes get() = e["water_dispensing_mode"]?.attrs?.a("options")?.map { (it as JsonPrimitive).content }.orEmpty()

// ---------- Actions ----------

suspend fun HaBackend.call(domain: String, service: String, data: JsonObject) {
    post("/api/services/$domain/$service", data.toString())
}

suspend fun HaBackend.press(d: Device, key: String) {
    val id = d.e[key]?.id ?: throw ApiException("${d.name} has no \"$key\" in Home Assistant")
    call("button", "press", buildJsonObject { put("entity_id", id) })
}

suspend fun HaBackend.setNumber(id: String, value: Double) =
    call("number", "set_value", buildJsonObject { put("entity_id", id); put("value", value) })

suspend fun HaBackend.feedNow(d: Device, grams: Int) {
    setNumber(d.e["manual_feed_quantity"]?.id ?: throw ApiException("No manual feed in Home Assistant"), grams.toDouble())
    press(d, "manual_feed")
}

suspend fun HaBackend.petlibro(service: String, d: Device, data: JsonObject = JsonObject(emptyMap())) =
    call("petlibro", service, JsonObject(data + ("device_id" to JsonPrimitive(d.haId))))

/** [time] is the cloud's "HH:mm", i.e. in Home Assistant's time zone. */
fun planData(time: String, portions: Int, days: List<Int>, label: String) = buildJsonObject {
    put("time", "$time:00"); put("portions", portions); put("label", label)
    put("days", JsonArray(days.sorted().map { JsonPrimitive("$it") })); put("sound", false)
}

/** An HLS address for a camera, from Home Assistant's own streaming (valid while it's being watched). */
suspend fun HaBackend.hlsUrl(cameraId: String): String {
    val r = ws(listOf(buildJsonObject { put("type", "camera/stream"); put("entity_id", cameraId); put("format", "hls") })).first() as? JsonObject
    val path = r?.s("url") ?: throw ApiException("Home Assistant gave no stream for $cameraId")
    return if ("://" in path) path else base + path
}

/** A still from the camera, as JPEG (Home Assistant's camera proxy). */
suspend fun HaBackend.snapshot(cameraId: String): ByteArray = bytes("/api/camera_proxy/$cameraId")

/** The Home Assistant button the recorder watches: pressing it saves the last 10 s plus the next 30 (tools/recorder). */
const val RECORD_BUTTON = "input_button.liberpet_record"

/** Presses [RECORD_BUTTON]; false if that helper doesn't exist in Home Assistant yet. */
suspend fun HaBackend.recordClip(): Boolean {
    try { get("/api/states/$RECORD_BUTTON") } catch (e: ApiException) { return false } // 404: not set up
    post("/api/services/input_button/press", """{"entity_id":"$RECORD_BUTTON"}""")
    return true
}

/** The Home Assistant text helper holding the recorder's motion settings, which the recorder reads (tools/recorder). */
const val RECORDER_SETTINGS = "input_text.liberpet_recorder"
const val MOTION_COLS = 16
const val MOTION_ROWS = 9

/**
 * Where motion counts ([cells]: a 16×9 grid over the picture, row by row, true = watched) and how much of it starts a
 * clip ([sensitivity] 1–100; 50 is the recorder's old fixed threshold). Stored as "s=50;m=<36 hex digits>", within an
 * input_text's default 100 characters.
 */
data class MotionSettings(val sensitivity: Int = 50, val cells: List<Boolean> = List(MOTION_COLS * MOTION_ROWS) { true }) {
    fun encode() = "s=$sensitivity;m=" + cells.chunked(4).joinToString("") { b ->
        b.foldIndexed(0) { i, acc, on -> if (on) acc or (8 shr i) else acc }.toString(16)
    }

    companion object {
        fun decode(v: String): MotionSettings {
            val parts = v.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 }?.let { (k, x) -> k to x } }.toMap()
            val hex = parts["m"].orEmpty()
            return MotionSettings(
                parts["s"]?.toIntOrNull()?.coerceIn(1, 100) ?: 50,
                if (hex.length * 4 < MOTION_COLS * MOTION_ROWS) MotionSettings().cells
                else List(MOTION_COLS * MOTION_ROWS) { i -> (hex[i / 4].digitToIntOrNull(16) ?: 15) and (8 shr (i % 4)) != 0 })
        }
    }
}

/** The recorder's motion settings; null if [RECORDER_SETTINGS] doesn't exist in Home Assistant yet. */
suspend fun HaBackend.motionSettings(): MotionSettings? = try {
    MotionSettings.decode(json.parseToJsonElement(get("/api/states/$RECORDER_SETTINGS")).jsonObject.s("state").orEmpty())
} catch (e: ApiException) { null }

suspend fun HaBackend.setMotionSettings(m: MotionSettings) {
    post("/api/services/input_text/set_value", buildJsonObject { put("entity_id", RECORDER_SETTINGS); put("value", m.encode()) }.toString())
}

// ---------- Recordings (the recorder's folder, shared to Home Assistant as network media storage) ----------

/** Home Assistant media folder holding the recorder's clips (network storage named "liberpet", usage Media). */
const val RECORDINGS = "liberpet"

/** One recorded clip: [day] "YYYY-MM-DD", [time] "HH:mm:ss"; [video] is its media id, [thumb] its picture's path in Home Assistant. */
data class Clip(val day: String, val time: String, val reasons: List<String>, val seconds: Int?, val video: String, val thumb: String?)

private val CLIP_NAME = Regex("""^(\d{2})-(\d{2})-(\d{2})_([a-z-]+?)(?:_(\d+)s)?\.mp4$""")

/** A day folder's browse children → its clips, newest first. Names: HH-MM-SS_reason[-reason][_NNs].mp4 (+ .jpg). */
fun parseClips(folder: String, day: String, children: JsonArray): List<Clip> {
    val names = children.mapNotNull { it.jsonObject.s("title") }.toSet()
    return children.mapNotNull { c ->
        val title = c.jsonObject.s("title") ?: return@mapNotNull null
        val m = CLIP_NAME.find(title) ?: return@mapNotNull null
        val (h, mi, sec, reasons, dur) = m.destructured
        val jpg = title.removeSuffix(".mp4") + ".jpg"
        Clip(day, "$h:$mi:$sec", reasons.split('-'), dur.toIntOrNull(), c.jsonObject.s("media_content_id") ?: return@mapNotNull null,
            if (jpg in names) "/media/local/$folder/$day/$jpg" else null)
    }.sortedByDescending { it.time }
}

private fun mediaId(path: String) = "media-source://media_source/local/$path"

private suspend fun HaBackend.browse(path: String): JsonArray =
    ((ws(listOf(buildJsonObject { put("type", "media_source/browse_media"); put("media_content_id", mediaId(path)) })).first() as JsonObject)
        .a("children") ?: JsonArray(emptyList()))

private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * The recordings folder in Home Assistant's local media: one named like [RECORDINGS] in any capitalisation
 * (the network storage's name is the user's choice). If several match, the first that holds recording days wins.
 */
suspend fun HaBackend.recordingsFolder(): String? {
    val names = browse(".").mapNotNull { it.jsonObject.s("title") }.filter { it.equals(RECORDINGS, ignoreCase = true) }
    return names.firstOrNull { n -> runCatching { browse(n).any { it.jsonObject.s("title")?.matches(DAY) == true } }.getOrDefault(false) }
        ?: names.firstOrNull()
}

/** Days with recordings ("YYYY-MM-DD") in [folder], newest first. */
suspend fun HaBackend.recordingDays(folder: String): List<String> =
    browse(folder).mapNotNull { it.jsonObject.s("title")?.takeIf { t -> t.matches(DAY) } }.sortedDescending()

suspend fun HaBackend.clips(day: String, folder: String): List<Clip> = parseClips(folder, day, browse("$folder/$day"))

/** A playable address for a media id (signed by Home Assistant, valid for a day). */
suspend fun HaBackend.resolveMedia(id: String): String {
    val r = ws(listOf(buildJsonObject { put("type", "media_source/resolve_media"); put("media_content_id", id) })).first() as? JsonObject
    val path = r?.s("url") ?: throw ApiException("Home Assistant couldn't open that recording")
    return if ("://" in path) path else base + path
}

// ---------- Cat recognition (tools/recognizer: learns from the user's labels which cat is at which bowl) ----------

/** What the recognizer publishes; [LABEL_ENTITY] is how LiberPet sends it the user's answers. */
const val CATS_SENSOR = "sensor.liberpet_cats"
const val LABEL_ENTITY = "input_text.liberpet_label"
const val NO_CAT = "none"

/**
 * Something to label: a stretch of one clip with one cat at one bowl. [video] (a short loop, other bowl dimmed) and
 * [path] (its poster) are inside the recordings folder; [side] "left"/"right"; [guess] a pet id or null.
 */
data class LearnPhoto(val id: String, val path: String, val side: String, val guess: String?, val video: String? = null)

/**
 * The recognizer's state: "waiting" (no clips yet), "learning" or "ready"; [labels] per pet id (and [NO_CAT]),
 * [accuracy] how often it names labelled photos right, [clips] "YYYY-MM-DD/<clip name>" → side → pet id.
 */
data class Cats(val state: String, val accuracy: Double, val labels: Map<String, Int>, val toLabel: List<LearnPhoto>,
                val clips: Map<String, Map<String, String>>)

fun parseCats(text: String): Cats {
    val o = json.parseToJsonElement(text).jsonObject
    val a = o.o("attributes") ?: JsonObject(emptyMap())
    fun obj(k: String) = a[k] as? JsonObject ?: JsonObject(emptyMap())
    return Cats(
        state = o.s("state") ?: "waiting",
        accuracy = a.s("accuracy")?.toDoubleOrNull() ?: 0.0,
        labels = obj("labels").mapValues { (it.value as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 },
        toLabel = (a["to_label"] as? JsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val x = e.jsonObject
            LearnPhoto(x.s("id") ?: return@mapNotNull null, x.s("path") ?: return@mapNotNull null, x.s("side") ?: "left", x.s("guess"), x.s("video"))
        },
        clips = obj("clips").mapValues { (_, v) -> (v as? JsonObject ?: JsonObject(emptyMap())).mapNotNull { (k, x) -> (x as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap() },
    )
}

/** The recognizer's state, or null if it isn't set up. */
suspend fun HaBackend.cats(): Cats? = try { parseCats(get("/api/states/$CATS_SENSOR")) } catch (e: ApiException) { null }

/** Sends one answer ([pet] a pet id or [NO_CAT]); false if the text helper doesn't exist in Home Assistant yet. */
suspend fun HaBackend.label(photo: String, pet: String): Boolean {
    try { get("/api/states/$LABEL_ENTITY") } catch (e: ApiException) { return false }
    post("/api/services/input_text/set_value", buildJsonObject { put("entity_id", LABEL_ENTITY); put("value", "$photo=$pet") }.toString())
    return true
}

// ---------- Cat insights (GitHub #3): what the recognizer saw, as statistics per cat ----------

const val STATS_SENSOR = "sensor.liberpet_cat_stats"

/** One cat on one day: visits, seconds eating ([leftSeconds] at the left bowl), visits per hour, last visit "HH:MM:SS". */
data class CatDay(val visits: Int, val seconds: Int, val leftSeconds: Int, val byHour: List<Int>, val last: String)

/** The recognizer's daily statistics: day "YYYY-MM-DD" → pet id → [CatDay]. */
fun parseCatDays(text: String): Map<String, Map<String, CatDay>> {
    val days = json.parseToJsonElement(text).jsonObject.o("attributes")?.o("days") ?: return emptyMap()
    return days.mapValues { (_, pets) ->
        (pets as? JsonObject ?: JsonObject(emptyMap())).mapValues { (_, v) ->
            val o = v.jsonObject
            CatDay(o.s("v")?.toIntOrNull() ?: 0, o.s("s")?.toIntOrNull() ?: 0, o.s("l")?.toIntOrNull() ?: 0,
                (o["h"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 } ?: List(24) { 0 }, o.s("last") ?: "")
        }
    }
}

/** The daily statistics, or null if the recognizer isn't running. */
suspend fun HaBackend.catDays(): Map<String, Map<String, CatDay>>? = try { parseCatDays(get("/api/states/$STATS_SENSOR")) } catch (e: ApiException) { null }

/** One cat's statistics over some days: visits, seconds eating, typical visit, bowl split, visits by hour, last visit. */
data class CatStats(val visits: Int, val seconds: Int, val leftSeconds: Int, val byHour: List<Int>, val last: String?) {
    val typicalSeconds get() = if (visits == 0) 0 else seconds / visits
    val leftShare get() = if (seconds == 0) 0.0 else leftSeconds.toDouble() / seconds
}

/** [days] days ending [today] ("YYYY-MM-DD"); [CatStats.last] is "YYYY-MM-DDTHH:MM:SS". */
fun catStats(daily: Map<String, Map<String, CatDay>>, pet: String, today: String, days: Int): CatStats {
    val first = kotlinx.datetime.LocalDate.parse(today).minus(kotlinx.datetime.DatePeriod(days = days - 1)).toString()
    val mine = daily.filterKeys { it in first..today }.mapNotNull { (day, pets) -> pets[pet]?.let { day to it } }
    val hours = MutableList(24) { 0 }
    mine.forEach { (_, d) -> d.byHour.forEachIndexed { h, n -> if (h < 24) hours[h] += n } }
    return CatStats(mine.sumOf { it.second.visits }, mine.sumOf { it.second.seconds }, mine.sumOf { it.second.leftSeconds }, hours,
        mine.filter { it.second.last.isNotEmpty() }.maxOfOrNull { (day, d) -> "${day}T${d.last}" })
}
