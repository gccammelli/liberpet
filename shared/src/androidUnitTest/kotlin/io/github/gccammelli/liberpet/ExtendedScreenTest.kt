package io.github.gccammelli.liberpet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.gccammelli.liberpet.ui.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import kotlin.time.Clock
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtendedScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val messages = mutableListOf<String>()
    private val pictures = mutableListOf<ByteArray>()
    private val requests = java.util.concurrent.ConcurrentLinkedQueue<Pair<String,String>>()
    private var streamFailure = false
    private var pickedImage: ByteArray? = null
    private val platform = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) { messages += message }
        override fun setDarkBars(dark: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = done(pickedImage)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) { pictures += jpeg; done(null) }
    }
    private companion object {
        val values = mutableMapOf<String, String>()
        val settings = object : KeyValue {
            override fun getString(key: String) = values[key]
            override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun clear() = values.clear()
        }
    }
    private lateinit var st: UiState
    private val feeder get() = st.home!!.devices.first { it.isFeeder }
    private val fountain get() = st.home!!.devices.first { it.isFountain }

    @Before fun home() = runBlocking {
        ThemeMode.mode = "system"
        settings.clear()
        // Each test must own the process singleton's settings, including after other suites.
        Repo::class.java.getDeclaredField("store").apply { isAccessible = true }.set(Repo, null)
        Repo.init(PlatformStorage(settings, object : SecretStore {
            override fun get(key: String): String? = null
            override fun set(key: String, value: String?) = Unit
        }, object : AppFiles {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
            override fun delete(name: String) = Unit
        }))
        Repo.logout()
        Nav.stack.clear(); Nav.tab = 0
        Feature.entries.forEach { Repo.setFeature(it, true) }
        val ha = FakeHomeAssistant(read = { java.io.File("src/commonMain/composeResources/$it").readBytes() })
        val home = ha.loadHome()
        st = UiState(on = true, home = home, logs = ha.loadLogs(home))
        Repo.state.value = st
    }

    private fun show(title: String, content: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme { content() } } }
        compose.onAllNodesWithText(title, substring = true).onFirst().assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun homeShowsDevices() = show(feeder.name) { HomeTab(st) }
    @Test fun careShowsPets() = show(st.home!!.pets.first().name) { CareTab(st) }
    @Test fun accountShowsSettings() = show("Home Assistant") { MeTab(st) }
    @Test fun preferencesShowOptionalFeatures() = show("Settings") { SettingsScreen() }
    @Test fun feederShowsSchedule() = show(feeder.name) { FeederScreen(feeder, st) }
    @Test fun fountainShowsIntake() = show(fountain.name) { FountainScreen(fountain, st) }
    @Test fun feedingScheduleShowsPlans() = show("Feeding Schedule") { ScheduleScreen(feeder) }
    @Test fun newPlanShowsTime() = show("Time") { PlanEditScreen(feeder, null) }
    @Test fun existingPlanShowsTime() = show("Time") { PlanEditScreen(feeder, feeder.plans.first()) }
    @Test fun feederSettingsShowControls() = show("Settings") { FeederSettingsScreen(feeder) }
    @Test fun fountainSettingsShowControls() = show("Settings") { FountainSettingsScreen(fountain, st) }
    @Test fun deviceInfoShowsSerial() = show(feeder.serial) { AboutScreen(feeder) }
    @Test fun petProfileShowsName() = show(st.home!!.pets.first().name) { PetScreen(st.home!!.pets.first(), st) }
    @Test fun waterHistoryShowsRanges() = show("Water") { WaterIntakeScreen(fountain, st) }
    @Test fun drinkingGoalShowsPets() = show("Goal") { GoalScreen(fountain, st) }
    @Test fun logsShowEvents() = show("Log") { LogsScreen(feeder, st) }
    @Test fun recordingsExplainSetup() = show("Recordings") { GalleryScreen(feeder, st) }
    @Test fun recognitionExplainsSetup() = show("Teach LiberPet") { TeachScreen(feeder, st) }
    @Test fun motionShowsSetup() = show("Motion") { MotionScreen(feeder) }
    @Test fun feedDialogShowsPortions() = show("FEED") { FeedNowSheet(feeder, {}, {}) }
    @Test fun repeatDialogShowsWeekdays() = show("REPEAT") { RepeatSheet(listOf(1, 3), {}, {}) }
    @Test fun timeDialogShowsConfirm() = show("Confirm") { TimeSheet("07:30", {}, {}) }
    @Test fun firmwareDialogShowsVersion() = show("Firmware") { FirmwareSheet(feeder, {}) }
    @Test fun maintenanceShowsFilter() = show("FILTER") { MaintenanceSheet(fountain, "filter", {}) }
    @Test fun flowDialogShowsOptions() = show("Choose Flow Type") { FlowTypeSheet(fountain, {}) }
    @Test fun reminderExplainsNotifications() = show("Reminder") { ReminderDialog({}) }

    @Test fun rootNavigatesBetweenDeviceCareAndAccount() {
        show("Device") { Root() }
        for (tab in listOf("Care", "Me", "Device")) {
            compose.onAllNodesWithText(tab).onLast().performClick()
            compose.onAllNodesWithText(tab).onLast().assertIsDisplayed()
        }
    }

    @Test fun historyRangesShowCharts() {
        show("Water") { WaterIntakeScreen(fountain, st) }
        for (label in listOf("wk.", "mth.", "yr.", "td.")) {
            compose.onAllNodesWithText(label, substring = true).onFirst().performClick()
            compose.onAllNodesWithText(label, substring = true).onFirst().assertExists()
        }
    }
    private fun backend(empty: Boolean = false, recognition: String = "ready", questions: Boolean = false, recorder: Boolean = true) {
        val demo = FakeHomeAssistant(read = { java.io.File("src/commonMain/composeResources/$it").readBytes() })
        val backend = object : HaBackend by demo {
            override suspend fun get(path: String): String = when {
                path.endsWith(RECORDER_SETTINGS) -> """{"state":"${MotionSettings().encode()}"}"""
                path.endsWith(CATS_SENSOR) -> """{"state":"$recognition","attributes":{"accuracy":0.9,"labels":{"${st.home!!.pets.first().id}":5,"none":2},"to_label":${if (questions) """[{"id":"photo1","path":"_learn/mochi.jpg","side":"left","guess":"none"}]""" else "[]"},"clips":{}}}"""
                path.endsWith(RECORD_BUTTON) && recorder -> """{"state":"unknown"}"""
                path.endsWith(LABEL_ENTITY) -> """{"state":""}"""
                else -> demo.get(path)
            }
            override suspend fun bytes(path: String): ByteArray = java.io.File("src/commonMain/composeResources/files/demo/mochi.jpg").readBytes()
            override suspend fun post(path: String,body: String): String {
                requests += path to body
                return if(path.contains("input_text/") || path.contains("input_button/")) "[]" else demo.post(path,body)
            }
            override suspend fun ws(commands: List<JsonObject>): List<JsonElement?> = commands.map { c ->
                when(c.s("type")) {
                    "camera/stream" -> {
                        if(streamFailure) throw ApiException("Camera offline")
                        buildJsonObject { put("url","file:///nonexistent-liberpet-camera.mp4") }
                    }
                    "media_source/browse_media" -> {
                        val id = c.s("media_content_id").orEmpty().substringAfter("local/")
                        val names = when (id) {
                            "." -> listOf("liberpet")
                            "liberpet" -> if(empty) emptyList() else listOf(Clock.System.now().local().date.toString(), "2026-10-01")
                            else -> listOf("08-30-00_feed-motion_42s.mp4", "09-00-00_manual.mp4", "10-00-00_continuous.mp4")
                        }
                        buildJsonObject { put("children", buildJsonArray { names.forEach { n -> add(buildJsonObject { put("title", n); put("media_content_id", "media-source://media_source/local/$id/$n") }) } }) }
                    }
                    "media_source/resolve_media" -> buildJsonObject { put("url", "/media/test.mp4") }
                    else -> demo.ws(listOf(c)).first()
                }
            }
        }
        Repo::class.java.getDeclaredField("ha").apply { isAccessible = true }.set(Repo, backend)
    }

    @Test fun recordingsShowTimelineAndRecordedReasons() {
        backend()
        show("Recordings") { GalleryScreen(feeder, st) }
        compose.onNodeWithText("3 clips").assertExists()
        compose.onAllNodesWithText("08:30").onFirst().performClick()
        assertTrue(Nav.stack.last() is Screen.Clip)
    }

    @Test fun emptyRecordingFolderExplainsWhenClipsAppear() {
        backend(empty = true)
        show("Recordings") { GalleryScreen(feeder, st) }
        compose.onNodeWithText("No clips yet").assertExists()
    }

    @Test fun recognitionShowsLearnedProgress() {
        backend()
        show("Cat Recognition") { CatsCard(feeder) }
        compose.onNodeWithText("Knows your cats", substring = true).assertExists()
        compose.onNodeWithText("Cat Recognition").performClick()
        assertTrue(Nav.stack.last() is Screen.Teach)
    }

    @Test fun teachingExplainsWhenAllClipsAreLabelled() {
        backend()
        show("Teach LiberPet") { TeachScreen(feeder, st) }
        compose.onNodeWithText("All caught up").assertExists()
    }

    @Test fun motionRequiresAtLeastOneWatchedSquare() {
        backend()
        show("Motion Detection") { MotionScreen(feeder) }
        compose.onNodeWithText("Watch none").performClick()
        compose.onNodeWithText("Watch at least one square", substring = true).assertExists()
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Watch all").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().assertIsEnabled()
    }

    @Test fun insightsShowVisitsAndChangeDateRange() {
        val p = st.home!!.pets.first()
        val today = Clock.System.now().local().date.toString()
        val daily = mapOf(today to mapOf(p.id to CatDay(3, 150, 100, List(24) { if(it == 8) 3 else 0 }, "08:30:00")))
        show("Insights") { CatInsights(p, daily) }
        for (label in listOf("Today", "30 days", "7 days")) {
            compose.onNodeWithText(label).performClick()
            compose.onNodeWithText("Left bowl 66%", substring = true).assertExists()
        }
    }

    @Test fun insightsKeepNoVisitsExplicit() {
        show("Insights") { CatInsights(st.home!!.pets.first(), emptyMap()) }
        compose.onNodeWithText("No visits seen").assertExists()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("No visits seen today").assertExists()
    }

    @Test fun localPreferencesRoundTripWithoutCloud() {
        val devices = st.home!!.devices
        Repo.saveOrder(devices.reversed().map { it.serial })
        assertEquals(devices.reversed(), Repo.ordered(devices))
        Repo.setHeader(byteArrayOf(1, 2)); assertArrayEquals(byteArrayOf(1, 2), Repo.header.value)
        Repo.setHeader(null); assertNull(Repo.header.value)
        Repo.setTheme("dark"); assertEquals("dark", settings.getString("theme"))
        Repo.setCamera(feeder, ""); assertNull(Repo.cameraFor(feeder, st.home!!))
        Repo.setCamera(feeder, null)
        assertFalse(Repo.connected)
        backend(); assertTrue(Repo.connected)
    }

    @Test fun connectionScreenExplainsIntegrationBeforeNetworkCalls() {
        Repo.logout()
        show("LiberPet") { Root() }
        compose.onNodeWithText("Try the demo:", substring = true).assertExists()
    }

    @Test fun navigationRoutesShowTheirDestination() {
        show("Device") { Root() }
        val destinations = listOf(
            Screen.Feeder(feeder.serial) to feeder.name,
            Screen.Fountain(fountain.serial) to fountain.name,
            Screen.Schedule(feeder.serial) to "Feeding Schedule",
            Screen.PlanEdit(feeder.serial, null) to "Time",
            Screen.FeederSettings(feeder.serial) to "Settings",
            Screen.FountainSettings(fountain.serial) to "Settings",
            Screen.Logs(feeder.serial) to "Log",
            Screen.WaterIntake(fountain.serial) to "Water",
            Screen.Goal(fountain.serial) to "Goal",
            Screen.About(feeder.serial) to feeder.serial,
            Screen.Pet(st.home!!.pets.first().id) to st.home!!.pets.first().name,
            Screen.Gallery(feeder.serial) to "Recordings",
            Screen.Teach(feeder.serial) to "Teach LiberPet",
            Screen.Settings to "Settings")
        for ((screen,title) in destinations) {
            compose.runOnIdle { Nav.stack.clear(); Nav.go(screen) }
            compose.onAllNodesWithText(title, substring = true).onFirst().assertExists()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun repositoryReloadsDemoAndReportsUnsupportedActions() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            backend()
            Repo.refresh().join()
            assertNotNull(Repo.state.value.home)
            assertFalse(Repo.state.value.loading)
            var failure: String? = null
            Repo.feedNow(feeder, 10) { failure = it }.join()
            Repo.act(null, { failure = it }) { throw ApiException("Rejected test action") }.join()
            assertEquals("Rejected test action", failure)
            Repo.setPlan(feeder, null, "07:30", 1, listOf(1), "Morning") {}.join()
            Repo.setPlan(feeder, feeder.plans.first(), "07:30", 2, listOf(2), "Morning") {}.join()
            Repo.deletePlan(feeder, feeder.plans.first()) {}.join()
            Repo.togglePlan(feeder, feeder.plans.first(), false) {}.join()
            Repo.toggleSchedule(feeder, false) {}.join()
            val numeric = feeder.e.values.firstOrNull { it.id.startsWith("number.") }
            if(numeric != null) Repo.setNumber(feeder, numeric.id, 1.0) {}.join()
            val select = feeder.e.entries.firstOrNull { it.value.id.startsWith("select.") }
            if(select != null) Repo.select(feeder, select.key, "on") {}.join()
            assertFalse(Repo.state.value.loading)
            assertNull(Repo.state.value.error)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun teachingLetsTheOwnerSkipAnUncertainClip() {
        backend(questions = true)
        show("Teach LiberPet") { TeachScreen(feeder, st) }
        compose.onNodeWithText("Who's at the left bowl in this clip?").assertExists()
        compose.onNodeWithText("No cat at this bowl").assertExists()
        compose.onNodeWithText("Skip").performScrollTo().performClick()
        compose.onNodeWithText("All caught up").assertExists()
    }

    @Test fun learningRecognitionShowsAccuracyWithoutClaimingReady() {
        backend(recognition = "learning")
        show("Cat Recognition") { CatsCard(feeder) }
        compose.onNodeWithText("Learning · 7 clips labelled", substring = true).assertExists()
    }

    @Test fun recordedVideoProvidesLocalPlaybackControls() {
        show("0:00") {
            VideoPlayer("file:///nonexistent-liberpet-test.mp4", true, androidx.compose.ui.Modifier, {}, controls = true, loop = true)
        }
        compose.onAllNodes(hasContentDescription("Pause") or hasContentDescription("Play")).onFirst().performClick()
        compose.onAllNodesWithText("0:00").onFirst().assertExists()
    }

    private fun cameraHome() {
        val home=st.home!!.copy(cameras=listOf(Camera("camera.test","Feeder camera",feeder.room)))
        st=st.copy(home=home)
        Repo.state.value=st
        Repo.setCamera(feeder,"camera.test")
    }

    @Test fun cameraControlsRecordAndSaveSnapshotThroughHomeAssistant() {
        backend(); cameraHome()
        show("LIVE") { CameraCard(feeder,st) }
        compose.onNodeWithContentDescription("Unmute").performClick()
        compose.onNodeWithContentDescription("Mute").assertExists()
        compose.onNodeWithContentDescription("Record a clip").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("input_button/press") } }
        org.junit.Assert.assertTrue(messages.any { it.startsWith("Recording:") })
        compose.onNodeWithContentDescription("Take a snapshot").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); pictures.isNotEmpty() }
        assertArrayEquals(java.io.File("src/commonMain/composeResources/files/demo/mochi.jpg").readBytes(),pictures.single())
        compose.onNodeWithContentDescription("Full screen").performClick()
        assertTrue(Nav.stack.last() is Screen.CameraFull)
    }

    @Test fun missingRecorderHelperExplainsSetupAndCameraStreamCanRetry() {
        backend(recorder=false); cameraHome(); streamFailure=true
        show("LIVE") { CameraCard(feeder,st) }
        compose.onNodeWithText("The camera didn't answer").assertExists()
        compose.onNodeWithContentDescription("Record a clip").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); messages.any { it.startsWith("To record, add") } }
        streamFailure=false
        compose.onNodeWithText("Tap to try again").performClick()
        compose.onAllNodesWithText("The camera didn't answer").assertCountEquals(0)
    }

    @Test fun fullScreenCameraReturnsToPriorScreen() {
        backend(); cameraHome(); Nav.go(Screen.CameraFull(feeder.serial))
        show(feeder.name) { CameraFullScreen(feeder,st) }
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(Nav.stack.isEmpty())
    }

    @Test fun teachingAnAnswerSendsTheSelectedPetIdentity() {
        backend(questions=true)
        show("Teach LiberPet") { TeachScreen(feeder,st) }
        compose.onNodeWithText(st.home!!.pets.first().name).performScrollTo().performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("input_text/set_value") } }
        val body=json.parseToJsonElement(requests.last().second).jsonObject
        assertEquals(LABEL_ENTITY,body.s("entity_id"))
        assertEquals("photo1=${st.home!!.pets.first().id}",body.s("value"))
    }

    @Test fun editingAPlanChangesRepeatTimeAndAmountBeforeSaving() {
        backend()
        show("Schedule") { PlanEditScreen(feeder, null) }
        compose.onNodeWithText("Repeat").performClick()
        compose.onNodeWithText("Never").performClick()
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Mo").performClick()
        compose.onNodeWithText("Su").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Tue, Wed, Thu, Fri, Sun").assertExists()
        compose.onNodeWithText("Feeding Time").performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Feeding Amount").performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3f) }
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Label", substring = false).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Lunch time")
        compose.onNodeWithText("Confirm").performClick()
        compose.onNodeWithText("Lunchtime").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("/add_feeding_plan") } }
        val body = json.parseToJsonElement(requests.first { it.first.endsWith("/add_feeding_plan") }.second).jsonObject
        assertEquals("Lunchtime", body["label"]!!.jsonPrimitive.content)
        assertEquals(3, body["portions"]!!.jsonPrimitive.int)
    }

    @Test fun existingPlanCanBeDeletedAndScheduleCanBeToggled() {
        backend()
        val plan = feeder.plans.first()
        show("Schedule") { PlanEditScreen(feeder, plan) }
        compose.onNodeWithText("DELETE").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("/delete_feeding_plan") } }
        assertTrue(requests.first { it.first.endsWith("/delete_feeding_plan") }.second.contains(plan.id.toString()))
    }

    @Test fun cameraSettingsAndMaintenanceExplainAvailableControls() {
        backend()
        show("Settings") { FeederSettingsScreen(feeder) }
        compose.onNodeWithText("Camera Settings").performScrollTo().performClick()
        compose.onNodeWithText("Night Vision").assertExists()
        compose.onNodeWithText("Recording Mode").assertExists()
        compose.onNodeWithText("Camera settings are read-only through Home Assistant.").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Desiccant Bag").performScrollTo().performClick()
        compose.onNodeWithText("Reset (I replaced it)").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("/press") } }
    }

    @Test fun profileWeightEditorSubmitsTheDisplayedWeight() {
        backend()
        val pet = st.home!!.pets.first()
        show("Profile") { PetScreen(pet, st) }
        compose.onNodeWithText("Weight", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("/set_value") } }
        val request = requests.first { it.first.endsWith("/set_value") }
        assertTrue(request.second.contains(pet.e.getValue("weight").id))
    }

    @Test fun petOverviewOffersBothFoodAndWaterTrends() {
        backend()
        Repo.setFeature(Feature.CAT_INSIGHTS, false)
        val pet = st.home!!.pets.first()
        show("Overview") { CareTab(st) }
        compose.onAllNodesWithText("View 7-Day Trend").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("FOOD INTAKE · 7 DAYS").assertExists()
        compose.onNodeWithText("Average", substring = true).assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onAllNodesWithText("View 7-Day Trend").onLast().performScrollTo().performClick()
        compose.onNodeWithText("WATER INTAKE · 7 DAYS").assertExists()
    }

    @Test fun fountainCalibrationRequiresConfirmationAndSubmitsOnlyThen() {
        backend()
        show("Settings") { FountainSettingsScreen(fountain, st) }
        compose.onNodeWithText("Calibration", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Do not place any objects on the base.", substring = true).assertExists()
        assertFalse(requests.any { it.first.endsWith("/press") })
        compose.onNodeWithText("CONFIRM", substring = false).performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("/press") } }
    }

    @Test fun resolvedClipShowsPlaybackAndBackNavigation() {
        backend()
        Nav.go(Screen.Clip(feeder.serial, "test-clip", "Test recording"))
        show("Test recording") { ClipScreen("test-clip", "Test recording") }
        compose.onNodeWithContentDescription("Back").performClick()
        assertTrue(Nav.stack.isEmpty())
    }

    @Test fun feederCameraSelectionCanBeExplicitAutomaticOrDisabled() {
        backend(); cameraHome()
        show("Settings") { FeederSettingsScreen(feeder) }
        compose.onNodeWithText("Live Camera").performScrollTo().performClick()
        compose.onAllNodesWithText("Feeder camera", substring = true).onLast().performClick()
        assertEquals("camera.test", Repo.cameraFor(feeder, st.home!!)!!.id)
        compose.onNodeWithText("Live Camera").performScrollTo().performClick()
        compose.onAllNodesWithText("None", substring = false).onLast().performClick()
        assertNull(Repo.cameraFor(feeder, st.home!!))
        compose.onNodeWithText("Live Camera").performScrollTo().performClick()
        compose.onNodeWithText("Automatic", substring = true).performClick()
        assertEquals("camera.test", Repo.cameraFor(feeder, st.home!!)!!.id)
    }

    @Test fun timedWaterFlowAndHydrationGoalsSubmitThroughHomeAssistant() {
        backend()
        show("Choose Flow Type") { FlowTypeSheet(fountain, {}) }
        compose.onNodeWithText("Timed Flow").performClick()
        compose.onNodeWithText("Pause", substring = false).assertExists()
        compose.onNodeWithText("Flow", substring = false).assertExists()
        compose.onNodeWithText("Confirm").performClick()
        compose.waitUntil(5000) { compose.waitForIdle(); requests.any { it.first.endsWith("/select_option") } }
        assertTrue(requests.first { it.first.endsWith("/select_option") }.second.contains(fountain.dispensingModes[1]))
    }

    @Test fun hydrationGoalIsSplitBetweenLinkedPetsOnlyWhenConfirmed() {
        backend()
        show("Daily Hydration Goal") { GoalScreen(fountain, st) }
        assertFalse(requests.any { it.first.endsWith("/set_value") })
        compose.onNodeWithText("Confirm").performClick()
        val pets = linkedPets(fountain, st)
        compose.waitUntil(5000) { compose.waitForIdle(); requests.count { it.first.endsWith("/set_value") } == pets.size }
        for (pet in pets) assertTrue(requests.any { it.second.contains(pet.e.getValue("drinkingGoal").id) })
    }

    @Test fun deviceCardsCanBeReorderedByLongPressAndKeepTheirOrder() {
        val viewState = st.copy(home = st.home!!.copy(devices = listOf(feeder, fountain)))
        show(feeder.name) { HomeTab(viewState) }
        val from = compose.onNodeWithText(feeder.name).fetchSemanticsNode().boundsInRoot.center
        val to = compose.onNodeWithText(fountain.name).fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithText(feeder.name).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(to - from, 200)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(fountain.serial, feeder.serial), Repo.ordered(listOf(feeder, fountain)).map { it.serial })
    }

    @Test fun homePictureRejectsInvalidImagesAndCanRestoreTheDefault() {
        show(feeder.name) { HomeTab(st) }
        fun openPicture() {
            compose.onRoot().performTouchInput { longClick(androidx.compose.ui.geometry.Offset(120f,130f)) }
            compose.onNodeWithText("HEADER PICTURE").assertExists()
        }
        pickedImage = byteArrayOf(1,2,3)
        openPicture()
        compose.onNodeWithText("Choose photo").performClick()
        assertTrue(messages.last().contains("isn't a picture"))
        pickedImage = java.io.File("src/commonMain/composeResources/files/demo/mochi.jpg").readBytes()
        openPicture()
        compose.onNodeWithText("Choose photo").performClick()
        assertArrayEquals(pickedImage,Repo.header.value)
        openPicture()
        compose.onNodeWithText("Use default").performClick()
        assertNull(Repo.header.value)
    }

}
