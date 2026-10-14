package io.github.gccammelli.petlibre

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class Logs(
    val feeds: Map<String, List<LogEntry>> = emptyMap(),
    val drinks: Map<String, List<LogEntry>> = emptyMap(),
    /** Per device serial: ml (fountain) or g (feeder) per local day. */
    val daily: Map<String, Map<LocalDate, Double>> = emptyMap(),
)

data class UiState(val on: Boolean = false, val home: Home? = null, val logs: Logs = Logs(), val loading: Boolean = false, val error: String? = null)

object Repo {
    private lateinit var store: PlatformStorage
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var ha: HaBackend? = null
        private set
    val state = MutableStateFlow(UiState())

    val connected get() = ha != null
    val haUrl get() = store.settings.getString("ha_url")

    fun init(s: PlatformStorage) {
        if (::store.isInitialized) return
        store = s
        val url = s.settings.getString("ha_url")
        val token = s.secrets.get("ha_token")
        if (url != null && token != null) { ha = HaHttp(url, token); state.value = UiState(on = true) }
    }

    /** Checks the address and token by loading once; saves them only if that works. */
    suspend fun connect(url: String, token: String) {
        val b = HaHttp(url, token.filterNot { it.isWhitespace() })
        val home = withContext(Dispatchers.Default) { b.loadHome() }
        if (home.devices.isEmpty() && home.pets.isEmpty())
            throw ApiException("Connected, but Home Assistant has no PETLIBRO devices. Add the PETLIBRO integration there first.")
        store.settings.putString("ha_url", b.base)
        store.secrets.set("ha_token", token.filterNot { it.isWhitespace() })
        ha = b
        state.value = UiState(on = true, home = home)
        refresh()
    }

    fun logout() {
        store.secrets.set("ha_token", null)
        store.settings.clear()
        ha = null
        state.value = UiState()
    }

    fun refresh() = scope.launch { load() }

    private suspend fun load() {
        val b = ha ?: return
        state.update { it.copy(loading = true) }
        try {
            val home = withContext(Dispatchers.Default) { b.loadHome() }
            state.update { it.copy(home = home, error = null) }
            val logs = withContext(Dispatchers.Default) { b.loadLogs(home) }
            state.update { it.copy(logs = logs) }
        } catch (e: Exception) {
            state.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            state.update { it.copy(loading = false) }
        }
    }

    /** Runs a Home Assistant action, asks the integration to re-poll the device, then reloads. Errors go to [onError]. */
    fun act(d: Device?, onError: (String) -> Unit = {}, block: suspend HaBackend.() -> Unit) = scope.launch {
        val b = ha ?: return@launch
        try {
            withContext(Dispatchers.Default) {
                b.block()
                d?.e?.values?.firstOrNull()?.let { b.call("homeassistant", "update_entity", buildJsonObject { put("entity_id", it.id) }) }
            }
        } catch (e: Exception) {
            onError(e.message ?: e.toString())
        }
        delay(1500)
        load()
    }

    fun feedNow(d: Device, grams: Int, onError: (String) -> Unit) = act(d, onError) { feedNow(d, grams) }

    fun setPlan(d: Device, plan: Plan?, localTime: String, portions: Int, days: List<Int>, label: String, onError: (String) -> Unit) = act(d, onError) {
        val data = planData(utcHm(localTime), portions, days, label)
        if (plan == null) petlibro("add_feeding_plan", d, data)
        else petlibro("edit_feeding_plan", d, kotlinx.serialization.json.JsonObject(data + ("plan_id" to JsonPrimitive(plan.id))))
    }

    fun deletePlan(d: Device, plan: Plan, onError: (String) -> Unit) =
        act(d, onError) { petlibro("delete_feeding_plan", d, buildJsonObject { put("plan_id", plan.id) }) }

    fun togglePlan(d: Device, plan: Plan, on: Boolean, onError: (String) -> Unit) =
        act(d, onError) { petlibro("toggle_feeding_plan", d, buildJsonObject { put("plan_id", plan.id); put("enable", on) }) }

    fun toggleSchedule(d: Device, on: Boolean, onError: (String) -> Unit) =
        act(d, onError) { petlibro("toggle_feeding_schedule", d, buildJsonObject { put("enable", on) }) }

    fun press(d: Device, key: String, onError: (String) -> Unit) = act(d, onError) { press(d, key) }

    fun select(d: Device, key: String, option: String, onError: (String) -> Unit) = act(d, onError) {
        call("select", "select_option", buildJsonObject { put("entity_id", d.e[key]!!.id); put("option", option) })
    }

    fun setNumber(d: Device?, id: String, value: Double, onError: (String) -> Unit) = act(d, onError) { setNumber(id, value) }
}

/** Ten days of logs from the recorder; daily totals from it, topped up from long-term statistics for older days. */
suspend fun HaBackend.loadLogs(home: Home): Logs {
    val ids = home.devices.flatMap { d ->
        if (d.isFeeder) listOfNotNull(d.e["last_feed_time"]?.id, d.e["last_feed_quantity_weight"]?.id, d.e["today_feeding_quantity_weight"]?.id)
        else listOfNotNull(d.e["today_drinking_count"]?.id, d.e["today_drinking_amount"]?.id, d.e["today_drinking_time"]?.id, d.e["online"]?.id)
    }
    val h = history(ids, 10)
    fun pts(d: Device, k: String) = d.e[k]?.id?.let { h[it] }.orEmpty()
    val feeds = mutableMapOf<String, List<LogEntry>>()
    val drinks = mutableMapOf<String, List<LogEntry>>()
    val daily = mutableMapOf<String, Map<LocalDate, Double>>()
    for (d in home.devices) {
        val total = if (d.isFeeder) "today_feeding_quantity_weight" else "today_drinking_amount"
        if (d.isFeeder) feeds[d.serial] = feedLog(pts(d, "last_feed_time"), pts(d, "last_feed_quantity_weight"), d.plans)
        else drinks[d.serial] = drinkLog(pts(d, "today_drinking_count"), pts(d, "today_drinking_amount"), pts(d, "today_drinking_time"), pts(d, "online"))
        val stats = d.e[total]?.id?.let { runCatching { dailyStats(it, 366) }.getOrNull() }.orEmpty()
        daily[d.serial] = stats + dailyMax(pts(d, total))
    }
    return Logs(feeds, drinks, daily)
}

