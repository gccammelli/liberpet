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
