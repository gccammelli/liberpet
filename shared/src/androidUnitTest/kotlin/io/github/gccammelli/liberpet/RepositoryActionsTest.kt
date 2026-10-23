package io.github.gccammelli.liberpet

import io.github.gccammelli.liberpet.ui.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RepositoryActionsTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = mutableMapOf<String, String>()
    private val secrets = mutableMapOf<String, String>()
    private val storage = PlatformStorage(object : KeyValue {
        override fun getString(key: String) = settings[key]
        override fun putString(key: String, value: String?) { if (value == null) settings.remove(key) else settings[key] = value }
        override fun clear() = settings.clear()
    }, object : SecretStore {
        override fun get(key: String) = secrets[key]
        override fun set(key: String, value: String?) { if (value == null) secrets.remove(key) else secrets[key] = value }
    }, object : AppFiles {
        override fun read(name: String): ByteArray? = null
        override fun write(name: String, bytes: ByteArray) = Unit
        override fun delete(name: String) = Unit
    })
    private fun initialize() {
        Repo.javaClass.getDeclaredField("store").apply { isAccessible = true }.set(Repo, null)
        Repo.init(storage)
    }
    private fun backend(value: HaBackend?) = Repo.javaClass.getDeclaredField("ha").apply { isAccessible = true }.set(Repo, value)
    private fun demo() = FakeHomeAssistant(read = { java.io.File("src/commonMain/composeResources/$it").readBytes() })

    @Before fun setup() { Dispatchers.setMain(dispatcher); initialize(); Repo.logout() }
    @After fun cleanup() { Repo.logout(); ThemeMode.mode = "system"; Dispatchers.resetMain() }

    @Test fun restoresOnlyCompleteCredentialsAndHonorsSavedTheme() {
        settings["theme"] = "light"
        initialize()
        assertEquals("light", ThemeMode.mode); assertFalse(Repo.connected)
        settings["ha_url"] = "https://home.example"
        initialize()
        assertFalse(Repo.connected)
        secrets["ha_token"] = "saved-token"
        initialize()
        assertTrue(Repo.connected); assertEquals("https://home.example", Repo.ha!!.base)
        assertTrue(Repo.state.value.on)
        settings.remove("ha_url")
        backend(null); initialize()
        assertFalse(Repo.connected)
        settings["demo"] = "true"
        initialize()
        assertTrue(Repo.ha is FakeHomeAssistant); assertTrue(Repo.demo)
        // Repeated initialization must leave the selected backend and settings intact.
        val restored = Repo.ha
        Repo.init(storage)
        assertSame(restored, Repo.ha)
    }

    @Test fun refreshWithoutConnectionIsANoopAndFailuresClearLoading() = runTest(dispatcher) {
        val before = Repo.state.value
        Repo.refresh().join()
        assertEquals(before, Repo.state.value)
        Repo.act(null) { error("Disconnected actions must not run") }.join()
        val demo = demo()
        for (failure in listOf(Exception("offline"), Exception())) {
            backend(object : HaBackend by demo {
                override suspend fun get(path: String): String = throw failure
            })
            Repo.refresh().join()
            assertEquals(failure.message ?: failure.toString(), Repo.state.value.error)
            assertFalse(Repo.state.value.loading)
        }
        backend(demo)
        Repo.refresh().join()
        assertNull(Repo.state.value.error); assertNotNull(Repo.state.value.home)
        assertFalse(Repo.state.value.loading)
    }

    @Test fun plansAndDeviceControlsSendCorrectServicePayloadsAndRefresh() = runTest(dispatcher) {
        val demo = demo()
        val calls = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, JsonObject>>()
        backend(object : HaBackend by demo {
            override suspend fun post(path: String, body: String): String {
                calls += path to json.parseToJsonElement(body).jsonObject
                return "[]"
            }
        })
        val feeder = demo.loadHome().devices.first { it.isFeeder }
        val errors = mutableListOf<String>()
        val plan = feeder.plans.first()
        Repo.setPlan(feeder, null, "08:30", 2, listOf(5, 1), "Breakfast", errors::add).join()
        Repo.setPlan(feeder, plan, "09:30", 3, emptyList(), "", errors::add).join()
        Repo.deletePlan(feeder, plan, errors::add).join()
        for (enabled in listOf(true, false)) {
            Repo.togglePlan(feeder, plan, enabled, errors::add).join()
            Repo.toggleSchedule(feeder, enabled, errors::add).join()
        }
        Repo.setNumber(null, "number.goal", 120.0, errors::add).join()
        Repo.select(feeder.copy(e = mapOf("mode" to Entity("select.mode", "off", JsonObject(emptyMap())))), "mode", "on", errors::add).join()
        Repo.press(feeder.copy(e = mapOf("test" to Entity("button.test", "off", JsonObject(emptyMap())))), "test", errors::add).join()
        Repo.feedNow(feeder, 20, errors::add).join()
        assertEquals(emptyList(), errors)
        fun service(name: String) = calls.filter { it.first.endsWith("/$name") }.map { it.second }
        val add = service("add_feeding_plan").single()
        assertEquals(feeder.haId, add.s("device_id")); assertEquals("Breakfast", add.s("label"))
        assertEquals("2", add.s("portions")); assertEquals(listOf("1", "5"), add.a("days")!!.map { it.jsonPrimitive.content })
        assertEquals(plan.id.toString(), service("edit_feeding_plan").single().s("plan_id"))
        assertEquals(plan.id.toString(), service("delete_feeding_plan").single().s("plan_id"))
        assertEquals(listOf("true", "false"), service("toggle_feeding_plan").map { it.s("enable") })
        assertEquals(listOf("true", "false"), service("toggle_feeding_schedule").map { it.s("enable") })
        assertEquals("on", service("select_option").single().s("option"))
        assertTrue(service("press").any { it.s("entity_id") == "button.test" })
        assertTrue(service("set_value").any { it.s("entity_id") == "number.goal" && it.s("value") == "120.0" })
        assertTrue(service("set_value").any { it.s("entity_id") == feeder.e["manual_feed_quantity"]!!.id && it.s("value") == "20.0" })
        assertNotNull(Repo.state.value.home); assertNull(Repo.state.value.error)
        assertTrue(service("update_entity").isNotEmpty())
    }

    @Test fun actionFailuresReachCallbackIncludingExceptionsWithoutMessages() = runTest(dispatcher) {
        backend(demo())
        val errors = mutableListOf<String>()
        for (failure in listOf(Exception("denied"), Exception())) {
            Repo.act(null, errors::add) { throw failure }.join()
            assertEquals(failure.message ?: failure.toString(), errors.last())
        }
        val empty = Device("id", "s", "Empty", "PLAF", null, null, emptyMap())
        var acted = false
        Repo.act(empty) { acted = true }.join()
        assertTrue(acted)
    }

    @Test fun historyFallbackSupportsDevicesWithoutActivityOrOptionalSensors() = runTest {
        val demo = demo()
        val home = demo.loadHome()
        val withoutActivity = home.copy(devices = home.devices.map { it.copy(e = it.e - "recent_activity") })
        val logs = demo.loadLogs(withoutActivity)
        assertTrue(logs.feeds.containsKey(home.devices.first { it.isFeeder }.serial))
        assertTrue(logs.drinks.containsKey(home.devices.first { it.isFountain }.serial))
        val noSensors = withoutActivity.copy(devices = withoutActivity.devices.map { it.copy(e = emptyMap()) })
        val empty = demo.loadLogs(noSensors)
        assertTrue(empty.daily.values.all { it.isEmpty() })
        assertTrue(empty.feeds.values.all { it.isEmpty() }); assertTrue(empty.drinks.values.all { it.isEmpty() })
    }

    @Test fun connectionCommitsCredentialsOnlyAfterFindingDevicesOrPets() = runTest(dispatcher) {
        val demo = demo()
        // A reachable but unconfigured HA must not overwrite an existing saved connection.
        settings["ha_url"] = "https://previous.example"
        secrets["ha_token"] = "previous-token"
        val empty = object : HaBackend by demo {
            override suspend fun ws(commands: List<JsonObject>) = commands.map { JsonArray(emptyList()) }
        }
        assertFailsWith<ApiException> { Repo.connect(empty, "new-token") }
        assertEquals("https://previous.example", settings["ha_url"])
        assertEquals("previous-token", secrets["ha_token"]); assertFalse(Repo.connected)
        val denied = object : HaBackend by demo {
            override suspend fun get(path: String): String = throw AuthException("denied")
        }
        assertFailsWith<AuthException> { Repo.connect(denied, "bad-token") }
        assertEquals("previous-token", secrets["ha_token"])
        Repo.connect(demo, " new \ntoken\t")
        Repo.refresh().join()
        assertSame(demo, Repo.ha); assertEquals(demo.base, settings["ha_url"])
        assertEquals("newtoken", secrets["ha_token"]); assertTrue(Repo.state.value.on)
        assertNotNull(Repo.state.value.home)
        val petsOnly = object : HaBackend by demo {
            override suspend fun ws(commands: List<JsonObject>) = demo.ws(commands).mapIndexed { i, value ->
                if (i == 0 && commands.first().s("type") == "config/device_registry/list") JsonArray(value!!.jsonArray.filter { it.jsonObject.s("model") == "Pet" }) else value
            }
        }
        Repo.connect(petsOnly, "pet-token")
        Repo.refresh().join()
        assertTrue(Repo.state.value.home!!.devices.isEmpty()); assertTrue(Repo.state.value.home!!.pets.isNotEmpty())
        assertEquals("pet-token", secrets["ha_token"])
    }

    @Test fun enteringDemoEnablesItsFeaturesAndLogsOutWithoutKeepingDemoCredentials() = runTest(dispatcher) {
        Repo.startDemo()
        Repo.refresh().join()
        assertTrue(Repo.demo); assertTrue(Repo.ha is FakeHomeAssistant)
        assertTrue(Feature.CARE_TAB.isOn); assertTrue(Feature.CAT_INSIGHTS.isOn)
        assertTrue(Repo.state.value.on)
        Repo.logout()
        assertFalse(Repo.demo); assertFalse(Repo.connected)
        assertFalse(Feature.CARE_TAB.isOn); assertFalse(Feature.CAT_INSIGHTS.isOn)
    }
}
