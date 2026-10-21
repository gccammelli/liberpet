package io.github.gccammelli.liberpet

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsNotEnabled
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import io.github.gccammelli.liberpet.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val platform = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) = Unit
        override fun setDarkBars(dark: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = done(null)
    }

    private lateinit var st: UiState
    private lateinit var backend: HaBackend
    private val feeder get() = st.home!!.devices.first { it.isFeeder }
    private val fountain get() = st.home!!.devices.first { it.isFountain }

    @Before fun home() {
        val values = mutableMapOf("demo" to "true")
        Repo.init(PlatformStorage(object : KeyValue {
            override fun getString(key: String) = values[key]
            override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun clear() = values.clear()
        }, object : SecretStore {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) = Unit
        }, object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        backend = FakeHomeAssistant(read = { java.io.File("src/commonMain/composeResources/$it").readBytes() })
        st = runBlocking { val home = backend.loadHome(); UiState(on = true, home = home, logs = backend.loadLogs(home)) }
        Repo.state.value = st
        useBackend(backend)
        Nav.stack.clear()
        Nav.tab = 0
        Feature.entries.forEach { Repo.setFeature(it, true) }
    }

    // Replace only the transport, without exposing a production setter or starting a refresh job.
    private fun useBackend(value: HaBackend) {
        Repo.javaClass.getDeclaredField("ha").apply { isAccessible = true }.set(Repo, value)
    }

    private fun show(title: String, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme(content) } }
        compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun connectionScreenExplainsLocalHomeAssistantSetup() {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { Connect() } } }
        compose.onNodeWithText("Home Assistant address").assertIsDisplayed()
    }

    @Test fun homeNamesDevices() = show(st.home!!.devices.first().name) { HomeTab(st) }
    @Test fun feederShowsItsName() = show(feeder.name) { FeederScreen(feeder, st) }
    @Test fun fountainShowsItsName() = show(fountain.name) { FountainScreen(fountain, st) }
    @Test fun scheduleCanNavigateToNewPlan() {
        show("Schedule") { ScheduleScreen(feeder) }
        compose.onNodeWithText("Add Schedule").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(Screen.PlanEdit(feeder.serial, null), Nav.stack.last()) }
    }
    @Test fun newPlanHasSaveAction() = show("Save") { PlanEditScreen(feeder, null) }
    @Test fun existingPlanHasDeleteAction() = show("DELETE") { PlanEditScreen(feeder, feeder.plans.first()) }
    @Test fun feederSettingsExplainCameraChoice() = show("Settings") { FeederSettingsScreen(feeder) }
    @Test fun fountainSettingsShowSettings() = show("Settings") { FountainSettingsScreen(fountain, st) }
    @Test fun waterHistoryShowsChart() = show("WATER INTAKE") { WaterIntakeScreen(fountain, st) }
    @Test fun goalShowsWaterTarget() = show("Daily Hydration Goal") { GoalScreen(fountain, st) }
    @Test fun aboutShowsDeviceIdentity() = show("About Device") { AboutScreen(feeder) }
    @Test fun feederLogsShowRecorderHistory() = show("Logs") { LogsScreen(feeder, st) }
    @Test fun fountainLogsShowRecorderHistory() = show("Logs") { LogsScreen(fountain, st) }
    @Test fun petProfileShowsName() = show(st.home!!.pets.first().name) { PetScreen(st.home!!.pets.first(), st) }
    @Test fun dashboardShowsPets() = show("Dashboard") { CareTab(st) }
    @Test fun emptyDashboardExplainsMissingPets() = show("No pets in Home Assistant yet.") { CareTab(UiState()) }
    @Test fun settingsShowPreferences() = show("Settings") { SettingsScreen() }
    @Test fun motionShowsConfiguration() = show("Motion Detection") { MotionScreen(feeder) }
    @Test fun galleryShowsRecordings() = show("Recordings") { GalleryScreen(feeder, st) }
    @Test fun teachShowsRecognition() = show("Teach LiberPet") { TeachScreen(feeder, st) }

    @Test fun manualFeedConfirmsPortionInGrams() {
        var grams = 0
        show("FEEDING AMOUNT") { FeedNowSheet(feeder, {}, { grams = it }) }
        compose.onNodeWithText("Confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(feeder.gramsPerPortion.toInt().coerceAtLeast(1), grams) }
    }
    @Test fun repeatCanChooseNoDays() {
        var days = listOf(1)
        show("REPEAT") { RepeatSheet((1..7).toList(), {}, { days = it }) }
        compose.onNodeWithText("Never").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(emptyList(), days) }
    }
    @Test fun repeatCanChooseCustomWeekdays() {
        var days = emptyList<Int>()
        show("REPEAT") { RepeatSheet(emptyList(), {}, { days = it }) }
        compose.onNodeWithText("Custom").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals((1..5).toList(), days) }
    }
    @Test fun timePickerPreservesInitialTime() {
        var time = ""
        show("Feeding Time") { TimeSheet("07:35", {}, { time = it }) }
        compose.onNodeWithText("Confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals("07:35", time) }
    }
    @Test fun planLabelRemovesWhitespace() {
        show("Save") { PlanEditScreen(feeder, null) }
        compose.onNodeWithText("Label").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNode(hasSetTextAction()).performTextReplacement(" Breakfast time ")
        compose.onNodeWithText("Confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Breakfasttime").assertIsDisplayed()
    }
    @Test fun feederCameraSettingsAreExplicitlyReadOnly() {
        show("Settings") { FeederSettingsScreen(feeder) }
        compose.onNodeWithText("Camera Settings").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Camera settings are read-only through Home Assistant.").assertIsDisplayed()
    }
    @Test fun cameraPickerOffersAutomaticAndNone() {
        show("Settings") { FeederSettingsScreen(feeder) }
        compose.onNodeWithText("Live Camera").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Automatic (the camera in the feeder's area)").assertIsDisplayed()
        compose.onAllNodesWithText("None").onLast().assertIsDisplayed()
    }
    @Test fun firmwareShowsInstalledVersion() = show("Newest Version") { FirmwareSheet(feeder) {} }
    @Test fun filterReplacementExplainsMaintenance() = show("FILTER") { MaintenanceSheet(fountain, "filter") {} }
    @Test fun cleaningExplainsMaintenance() = show("CLEANING") { MaintenanceSheet(fountain, "cleaning") {} }
    @Test fun reminderCanBeDismissed() {
        var dismissed = false
        show("Important Reminder") { ReminderDialog { dismissed = true } }
        compose.onNodeWithText("Previous Step").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(true, dismissed) }
    }
    @Test fun timedFlowShowsPauseAndDuration() {
        show("Choose Flow Type") { FlowTypeSheet(fountain) {} }
        compose.onNodeWithText("Timed Flow").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Pause").assertIsDisplayed()
        compose.onNodeWithText("Flow").assertIsDisplayed()
    }
    @Test fun appearanceCanSwitchToDark() {
        show("My Pet") { MeTab(st) }
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Dark").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals("dark", ThemeMode.mode) }
    }
    @Test fun insightsShowRecognizedVisitStatistics() {
        val day = kotlin.time.Clock.System.now().local().date.toString()
        val pet = st.home!!.pets.first()
        val daily = mapOf(day to mapOf(pet.id to CatDay(2, 150, 100, List(24) { if (it == 8) 2 else 0 }, "${day}T08:00:00")))
        show("Insights") { androidx.compose.foundation.layout.Column { CatInsights(pet, daily) } }
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("Today").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("visits").assertIsDisplayed()
        compose.onNodeWithText("30 days").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("2").assertIsDisplayed()
    }

    private fun recorder() {
        val day = kotlin.time.Clock.System.now().local().date.toString()
        val fake = backend
        useBackend(object : HaBackend by fake {
            override suspend fun get(path: String): String = when (path) {
                "/api/states/$RECORDER_SETTINGS" -> buildJsonObject { put("state", MotionSettings().encode()) }.toString()
                "/api/states/$CATS_SENSOR" -> """{"state":"learning","attributes":{"accuracy":0.8,"labels":{"${st.home!!.pets.first().id}":5},"to_label":[{"id":"photo","path":"photo.jpg","side":"left","guess":"${st.home!!.pets.first().id}"}],"clips":{}}}"""
                else -> fake.get(path)
            }
            override suspend fun bytes(path: String) = java.io.File("src/commonMain/composeResources/files/demo/mochi.jpg").readBytes()
            override suspend fun ws(commands: List<JsonObject>): List<JsonElement> = commands.map { c ->
                if (c.s("type") != "media_source/browse_media") return@map fake.ws(listOf(c)).single() ?: JsonNull
                val path = c.s("media_content_id").orEmpty()
                val titles = when {
                    path.endsWith("/$day") -> listOf("08-00-00_feed-motion_60s.mp4", "08-00-00_feed-motion_60s.jpg", "09-00-00_manual_30s.mp4")
                    path.endsWith("/liberpet") -> listOf(day)
                    else -> listOf("liberpet")
                }
                buildJsonObject { put("children", buildJsonArray { titles.forEach { title -> add(buildJsonObject { put("title", title); put("media_content_id", "$path/$title") }) } }) }
            }
        })
    }

    @Test fun recorderGalleryListsClipsAndNavigatesToPlayback() {
        recorder()
        show("Recordings") { GalleryScreen(feeder, st) }
        compose.onNodeWithText("2 clips").assertIsDisplayed()
        compose.onNodeWithText("08:00").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { check(Nav.stack.last() is Screen.Clip) }
    }
    @Test fun motionRequiresAtLeastOneWatchedSquare() {
        recorder()
        show("Motion Detection") { MotionScreen(feeder) }
        compose.onNodeWithText("Watch none").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Watch at least one square, or motion recording stops.").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Watch all").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Save").assertIsDisplayed()
    }
    @Test fun recognizerShowsQuestionAndSkipAdvancesQueue() {
        recorder()
        show("Teach LiberPet") { TeachScreen(feeder, st) }
        compose.onNodeWithText("Who's at the left bowl in this clip?").assertIsDisplayed()
        compose.onNodeWithText("Skip").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("No clips to label right now").assertIsDisplayed()
    }
    @Test fun rootRoutesDeviceSettingsAndProfileScreens() {
        show("Device") { Root() }
        val routes = listOf(
            Screen.Feeder(feeder.serial) to feeder.name,
            Screen.Schedule(feeder.serial) to "Schedule",
            Screen.PlanEdit(feeder.serial, null) to "Save",
            Screen.FeederSettings(feeder.serial) to "Settings",
            Screen.Motion(feeder.serial) to "Motion Detection",
            Screen.Logs(feeder.serial) to "Logs",
            Screen.Fountain(fountain.serial) to fountain.name,
            Screen.WaterIntake(fountain.serial) to "WATER INTAKE",
            Screen.FountainSettings(fountain.serial) to "Settings",
            Screen.Goal(fountain.serial) to "Daily Hydration Goal",
            Screen.About(feeder.serial) to "About Device",
            Screen.Pet(st.home!!.pets.first().id) to st.home!!.pets.first().name,
            Screen.Gallery(feeder.serial) to "Recordings",
            Screen.Teach(feeder.serial) to "Teach LiberPet",
            Screen.Settings to "Settings",
        )
        for ((route, title) in routes) {
            compose.runOnIdle { Nav.stack.clear(); Nav.go(route) }
            compose.onAllNodesWithText(title).onFirst().assertIsDisplayed()
        }
    }
    @Test fun androidStoragePersistsSettingsAndFilesAndRejectsCorruptTokens() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val storage = androidStorage(context)
        storage.settings.putString("theme", "dark")
        assertEquals("dark", androidStorage(context).settings.getString("theme"))
        storage.settings.putString("theme", null)
        assertEquals(null, storage.settings.getString("theme"))
        storage.settings.putString("key", "value")
        storage.settings.clear()
        assertEquals(null, storage.settings.getString("key"))
        storage.files.write("test.img", byteArrayOf(1, 2, 3))
        kotlin.test.assertContentEquals(byteArrayOf(1, 2, 3), storage.files.read("test.img"))
        storage.files.delete("test.img")
        assertEquals(null, storage.files.read("test.img"))
        context.getSharedPreferences("auth", 0).edit().putString("token", "corrupt").commit()
        assertEquals(null, storage.secrets.get("token"))
        assertEquals(false, context.getSharedPreferences("auth", 0).contains("token"))
        storage.secrets.set("token", null)
        assertEquals(null, storage.secrets.get("token"))
        val image = java.io.File("src/commonMain/composeResources/files/demo/mochi.jpg").readBytes()
        check(decodeImage(image, 32)!!.width <= 64)
        assertEquals(null, decodeImage(byteArrayOf(0), 32))
    }
    @Test fun rootConnectionFailureShowsErrorAndRetry() {
        Repo.state.value = UiState(on = true, error = "Home Assistant is offline")
        show("Home Assistant is offline") { Root() }
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
    @Test fun planEditorCanChangeTimeAndRepeatWithoutSavingToDevice() {
        show("Save") { PlanEditScreen(feeder, null) }
        compose.onNodeWithText("Feeding Time").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Repeat").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Never").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Never").assertIsDisplayed()
    }
    @Test fun desiccantReplacementRequiresExplicitConfirmation() {
        show("Settings") { FeederSettingsScreen(feeder) }
        compose.onNodeWithText("Desiccant Bag").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Reset (I replaced it)").assertIsDisplayed()
    }
}
