package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.style.TextAlign
import io.github.gccammelli.liberpet.VideoPlayer
import io.github.gccammelli.liberpet.hlsUrl
import io.github.gccammelli.liberpet.recordClip
import io.github.gccammelli.liberpet.snapshot
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.Device
import io.github.gccammelli.liberpet.LocalPlatform
import io.github.gccammelli.liberpet.LogEntry
import io.github.gccammelli.liberpet.Plan
import io.github.gccammelli.liberpet.Repo
import kotlinx.coroutines.launch
import io.github.gccammelli.liberpet.UiState
import io.github.gccammelli.liberpet.fmt
import io.github.gccammelli.liberpet.gramsPerPortion
import io.github.gccammelli.liberpet.local
import io.github.gccammelli.liberpet.minutesOf
import io.github.gccammelli.liberpet.petlibro
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.gccammelli.liberpet.pad
import io.github.gccammelli.liberpet.plans
import io.github.gccammelli.liberpet.scheduleEnabled
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.DatePeriod
import kotlin.time.Clock

@Composable
fun errorToast(): (String) -> Unit { val p = LocalPlatform.current; return { p.toast(it) } }

@Composable
fun FeederScreen(d: Device, st: UiState) {
    var feedNow by remember { mutableStateOf(false) }
    val err = errorToast()
    Page(d.name, { Nav.back() }, action = { GearButton { Nav.go(Screen.FeederSettings(d.serial)) } }, bottom = {
        BottomActions(listOf(Triple("Feed Now", Icons.Default.Restaurant) { feedNow = true }, Triple("Schedule", Icons.Default.EditCalendar) { Nav.go(Screen.Schedule(d.serial)) }))
    }) {
        RoomLine(d)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            CameraCard(d, st)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Gap(16.dp)
                PetsLinked(d, st)
                Gap(12.dp)
                if (io.github.gccammelli.liberpet.Feature.CAT_RECOGNITION.on()) CatsCard(d)
                Card(padding = PaddingValues(vertical = 22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BigStat(fmt(d.num("today_feeding_quantity_weight") ?: 0.0), "g", "Today's Feeding Amount", Modifier.weight(1f))
                        Box(Modifier.width(1.dp).height(36.dp).background(C.Line))
                        BigStat("${d.num("today_feeding_times")?.toInt() ?: 0}", "times", "Today's Feeding Times", Modifier.weight(1f))
                    }
                }
                Gap(12.dp)
                TodaySchedule(d)
                Gap(12.dp)
                Card(padding = PaddingValues(horizontal = 24.dp, vertical = 20.dp)) {
                    T("Status", 18.sp, weight = FontWeight.Medium)
                    Gap(14.dp)
                    StatusLine(Icons.Outlined.GridView, "Desiccant Bag", d.num("remaining_desiccant"), d.num("desiccant_frequency")) { Nav.go(Screen.FeederSettings(d.serial)) }
                }
                Gap(12.dp)
                LogsCard(st.logs.feeds[d.serial].orEmpty()) { Nav.go(Screen.Logs(d.serial)) }
                Gap(16.dp)
            }
        }
    }
    if (feedNow) FeedNowSheet(d, onDismiss = { feedNow = false }) { g -> feedNow = false; Repo.feedNow(d, g, err) }
}

@Composable
fun GearButton(onClick: () -> Unit) = Box(Modifier.size(44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(Icons.Outlined.Settings, "Settings", Modifier.size(28.dp), tint = C.Text)
}

@Composable
fun RoomLine(d: Device) = Row(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 26.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    T(d.room ?: "", 16.sp, C.Sub, modifier = Modifier.weight(1f))
    StatusIcons(d)
}

/** The live view, played through Home Assistant's camera stream (see docs/camera.md for the local setup behind it). */
@Composable
fun CameraCard(d: Device, st: UiState) = Column(Modifier.fillMaxWidth().background(C.Card).padding(top = 8.dp)) {
    val cam = st.home?.let { Repo.cameraFor(d, it) }
    var muted by remember { mutableStateOf(true) }
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
        if (cam == null) T(if (Repo.demo) "Live view isn't part of the demo: with a real feeder, its camera shows here" else "Add the feeder's camera to Home Assistant to watch it here", 13.sp, Color(0xFF9A9A9A), align = TextAlign.Center, modifier = Modifier.padding(24.dp))
        else {
            LiveStream(cam, muted, Modifier.matchParentSize())
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, if (muted) "Unmute" else "Mute",
                    Modifier.size(30.dp).clickable { muted = !muted }, tint = Color.White)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.Fullscreen, "Full screen", Modifier.size(30.dp).clickable { Nav.go(Screen.CameraFull(d.serial)) }, tint = Color.White)
            }
            T("LIVE", 11.sp, Color.White, FontWeight.Bold, Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(3.dp)).background(C.Red).padding(horizontal = 5.dp, vertical = 1.dp))
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        // Record and snapshot go through Home Assistant too; no mic: the camera's open-source stream has no talk-back.
        val ctx = LocalPlatform.current; val scope = androidx.compose.runtime.rememberCoroutineScope()
        val camOn = if (cam != null) C.Text else C.Track
        val recordings = io.github.gccammelli.liberpet.Feature.RECORDINGS.on()
        if (recordings) Icon(Icons.Outlined.Videocam, "Record a clip", Modifier.size(30.dp).clickable(enabled = cam != null) {
            scope.launch {
                runCatching { Repo.ha!!.recordClip() }.fold(
                    { ok -> ctx.toast(if (ok) "Recording: the last 10 s and the next 30 s will be in Recordings in a minute" else "To record, add a Home Assistant button named \"LiberPet record\" (see the recorder's guide)") },
                    { ctx.toast("Couldn't start recording: ${it.message}") })
            }
        }, tint = camOn)
        Icon(Icons.Outlined.CameraAlt, "Take a snapshot", Modifier.size(30.dp).clickable(enabled = cam != null) {
            scope.launch {
                runCatching { Repo.ha!!.snapshot(cam!!.id) }.onSuccess { jpeg ->
                    ctx.saveImage(jpeg, "LiberPet " + Clock.System.now().local().toString().take(19).replace(':', '-')) { e -> ctx.toast(if (e == null) "Snapshot saved to Photos" else "Couldn't save the snapshot: ${e.message}") }
                }.onFailure { ctx.toast("Couldn't take a snapshot: ${it.message}") }
            }
        }, tint = camOn)
        // Playback and gallery: the recorder's clips through Home Assistant.
        if (recordings) listOf(Icons.Outlined.PlayCircle to "Recordings", Icons.Outlined.Image to "Gallery").forEach { (icon, label) ->
            Icon(icon, label, Modifier.size(30.dp).clickable { Nav.go(Screen.Gallery(d.serial)) }, tint = C.Text)
        }
    }
}

/** Asks Home Assistant for a fresh stream address, plays it, and offers a retry if the camera doesn't answer. */
@Composable
fun LiveStream(cam: io.github.gccammelli.liberpet.Camera, muted: Boolean, modifier: Modifier) {
    var attempt by remember { mutableStateOf(0) }
    var url by remember(cam.id, attempt) { mutableStateOf<String?>(null) }
    var error by remember(cam.id, attempt) { mutableStateOf<String?>(null) }
    LaunchedEffect(cam.id, attempt) {
        val ha = Repo.ha ?: return@LaunchedEffect
        runCatching { ha.hlsUrl(cam.id) }.onSuccess { url = it }.onFailure { error = it.message ?: "No stream" }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val u = url
        when {
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { attempt++ }) {
                T("The camera didn't answer", 14.sp, Color.White); T("Tap to try again", 12.sp, Color(0xFF9A9A9A))
            }
            u == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            else -> VideoPlayer(u, muted, Modifier.fillMaxSize(), onError = { error = it })
        }
    }
}

@Composable
fun CameraFullScreen(d: Device, st: UiState) {
    val cam = st.home?.let { Repo.cameraFor(d, it) } ?: return Nav.back().let {}
    var muted by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LiveStream(cam, muted, Modifier.fillMaxSize())
        Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            T(d.name, 16.sp, Color.White, modifier = Modifier.weight(1f))
            Icon(if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp, null, Modifier.size(30.dp).clickable { muted = !muted }, tint = Color.White)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun PetsLinked(d: Device, st: UiState) {
    val pets = st.home?.pets.orEmpty().filter { d.serial in it.linkedSerials }
    Card(padding = PaddingValues(horizontal = 24.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Pets Linked", 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            pets.forEachIndexed { i, p -> PetAvatar(p.avatar, p.name, 26.dp, Modifier.offset(x = (-6 * i).dp)) }
            Box(Modifier.offset(x = (-6 * pets.size).dp).size(26.dp).clip(CircleShape).background(C.Green), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = Color.White)
            }
        }
    }
}

@Composable
fun BigStat(value: String, unit: String, label: String, modifier: Modifier) = Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Row(verticalAlignment = Alignment.Bottom) {
        T(value, 30.sp, weight = FontWeight.Bold)
        T(" $unit", 16.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
    T(label, 13.sp, C.Sub)
}

/**
 * Today's state: "Completed"/"Skipped"/"Failed" once its time has passed, else null (upcoming, shown as an on/off switch).
 * A plan whose time hasn't come yet is upcoming whatever the cloud says (its "today" lags just after midnight).
 */
private fun pastStatus(p: Plan): String? {
    val now = Clock.System.now().local().time.let { it.hour * 60 + it.minute }
    if (minutesOf(p.time) > now) return null
    return when (p.todayState) {
        "dispensed" -> "Completed"
        "skipped", "to_be_skipped" -> "Skipped"
        "pending" -> null
        else -> "Failed"
    }
}

@Composable
fun TodaySchedule(d: Device) {
    val err = errorToast()
    val today = d.plans.filter { it.todayState != null && it.enabled }.sortedBy { minutesOf(it.time) }
    val upcoming = today.filter { pastStatus(it) == null }
    Card(padding = PaddingValues(horizontal = 24.dp, vertical = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Today's Schedule", 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (upcoming.isNotEmpty()) Toggle(upcoming.any { it.todayState != "to_be_skipped" }) { on ->
                Repo.act(d, err) { petlibro("toggle_today_feeding_schedule", d, buildJsonObject { put("enable", on) }) }
            }
        }
        Gap(14.dp)
        if (today.isEmpty()) T("No feeding plans today", 15.sp, C.Faint, modifier = Modifier.padding(vertical = 10.dp))
        today.forEach { p ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
            val status = pastStatus(p)
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                T(p.time, 20.sp, if (status != null) C.Faint else C.Text, modifier = Modifier.width(86.dp))
                T("${fmt(p.grams)}g", 18.sp, if (status != null) C.Faint else C.Text, modifier = Modifier.weight(1f))
                // Switch off = skip this feed today only.
                if (status == null) Toggle(p.todayState != "to_be_skipped") { on ->
                    Repo.act(d, err) { petlibro("skip_feeding_plan", d, buildJsonObject { put("plan_id", p.id); put("skip", !on) }) }
                } else T(status, 13.sp, C.Faint)
            }
        }
    }
}

/** "Desiccant Bag  48 day(s) overdue >" with its bar; also used for the fountain's filter and cleaning. */
@Composable
fun StatusLine(icon: ImageVector, label: String, remaining: Double?, cycle: Double?, onClick: () -> Unit) {
    val r = remaining ?: return
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallIcon(icon, C.Text, 22.dp); Spacer(Modifier.width(10.dp))
            T(label, 16.sp, modifier = Modifier.weight(1f))
            if (r <= 0) T("${(-r).toInt()} day(s) overdue", 15.sp, C.Red) else T("${r.toInt()} day(s) left", 15.sp, C.Sub)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = if (r <= 0) C.Red else C.Faint)
        }
        Gap(14.dp)
        Bar(if (cycle != null && cycle > 0) (r / cycle).toFloat() else 0f, C.Green, Modifier.padding(start = 32.dp))
    }
}

@Composable
fun BottomActions(items: List<Triple<String, ImageVector, () -> Unit>>) =
    Row(Modifier.fillMaxWidth().background(C.Card).navigationBarsPadding().height(76.dp)) {
        items.forEach { (label, icon, go) ->
            Column(Modifier.weight(1f).fillMaxSize().clickable(onClick = go), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(icon, label, Modifier.size(32.dp), tint = C.Text)
                Gap(4.dp)
                T(label, 13.sp)
            }
        }
    }

// ---------- Logs ----------

private val DOW = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
fun dayTitle(d: LocalDate): Pair<String?, String> {
    val today = Clock.System.now().local().date
    val label = when (d) { today -> "Today"; today.minus(DatePeriod(days = 1)) -> "Yesterday"; else -> null }
    return label to "${pad(d.day)}/${pad(d.month.ordinal + 1)}/${d.year} ${DOW[d.dayOfWeek.ordinal]}"
}

@Composable
fun LogsCard(entries: List<LogEntry>, more: () -> Unit) = Card(padding = PaddingValues(horizontal = 22.dp, vertical = 20.dp)) {
    var open by remember { mutableStateOf(true) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
        T("Logs", 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = C.Sub)
    }
    if (!open) return@Card
    Gap(18.dp)
    if (entries.isEmpty()) T("No logs yet. Logs start from when Home Assistant began recording this device.", 14.sp, C.Faint, lh = 20.sp)
    entries.take(8).groupBy { it.at.local().date }.forEach { (day, list) -> DayLogs(day, list) }
    if (entries.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Box(Modifier.fillMaxWidth().clickable(onClick = more).padding(top = 18.dp), contentAlignment = Alignment.Center) { T("View More", 16.sp, weight = FontWeight.Medium) }
    }
}

@Composable
fun DayLogs(day: LocalDate, list: List<LogEntry>) {
    val (label, date) = dayTitle(day)
    if (label != null) T(label, 18.sp, weight = FontWeight.Medium)
    T(date, if (label != null) 15.sp else 16.sp, if (label != null) C.Text else C.Text)
    Gap(10.dp)
    list.forEachIndexed { i, e -> TimelineRow(hm(e.at), e.text, last = i == list.lastIndex) }
    Gap(12.dp)
}

@Composable
fun TimelineRow(time: String, text: String, last: Boolean) = Row(Modifier.fillMaxWidth()) {
    T(time, 13.sp, C.Text, modifier = Modifier.width(44.dp).padding(top = 1.dp))
    Box(Modifier.width(22.dp).height(if (last) 22.dp else 58.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            if (!last) {
                var y = 18f
                while (y < size.height) { drawLine(C.Dot, Offset(size.width / 2, y), Offset(size.width / 2, y + 4f), 2f); y += 9f }
            }
            drawCircle(C.Green, 7f, Offset(size.width / 2, 22f))
        }
    }
    T(text, 15.sp, modifier = Modifier.weight(1f).padding(bottom = 14.dp), lh = 20.sp)
}

@Composable
fun LogsScreen(d: Device, st: UiState) {
    val entries = if (d.isFeeder) st.logs.feeds[d.serial].orEmpty() else st.logs.drinks[d.serial].orEmpty()
    BigTitlePage("Logs", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (entries.isEmpty()) T("No logs yet. Logs start from when Home Assistant began recording this device.", 15.sp, C.Sub, modifier = Modifier.padding(24.dp))
            entries.groupBy { it.at.local().date }.forEach { (day, list) ->
                Gap(12.dp)
                var open by remember(day) { mutableStateOf(true) }
                Column(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 22.dp, vertical = 16.dp)) {
                    val (label, date) = dayTitle(day)
                    Row(Modifier.fillMaxWidth().clickable { open = !open }) {
                        Column(Modifier.weight(1f)) {
                            if (label != null) T(label, 18.sp, weight = FontWeight.Medium)
                            T(date, if (label != null) 15.sp else 16.sp)
                        }
                        Icon(if (open) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null, tint = C.Sub)
                    }
                    if (open) { Gap(10.dp); list.forEachIndexed { i, e -> TimelineRow(hm(e.at), e.text, i == list.lastIndex) } }
                }
            }
            Gap(24.dp)
        }
    }
}

// ---------- Feed now ----------

@Composable
fun FeedNowSheet(d: Device, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val step = d.gramsPerPortion.toInt().coerceAtLeast(1)
    var portions by remember { mutableStateOf(1) }
    AmountSheet("FEEDING AMOUNT", portions, step, onDismiss, { portions = it }) { onConfirm(portions * step) }
}

/** Cup picture, "10 g", and a −/slider/+ row, as in Petlibro's Feed Now and plan amount sheets. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AmountSheet(title: String, portions: Int, step: Int, onDismiss: () -> Unit, onChange: (Int) -> Unit, onConfirm: () -> Unit) =
    Sheet(onDismiss, title, onConfirm = onConfirm) {
        val max = 48
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Gap(8.dp)
            Canvas(Modifier.size(width = 90.dp, height = 90.dp)) {
                val w = size.width; val h = size.height
                val cup = Path().apply { moveTo(0f, 0f); lineTo(w, 0f); lineTo(w * .85f, h); lineTo(w * .15f, h); close() }
                drawPath(cup, C.Track2)
                val f = portions / max.toFloat()
                val top = h - (h * .1f + h * .9f * f)
                val inset = w * .15f * top / h // the cup's side at that height
                val fill = Path().apply { moveTo(inset, top); lineTo(w - inset, top); lineTo(w * .85f, h); lineTo(w * .15f, h); close() }
                drawPath(fill, Color(0xFFE2C3A3))
            }
            Gap(12.dp)
            T("${portions * step} g", 17.sp, weight = FontWeight.Medium)
            Gap(18.dp)
            Row(Modifier.fillMaxWidth().clip(CircleShape).background(C.Fill).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundStep(Icons.Default.Remove) { if (portions > 1) onChange(portions - 1) }
                Slider(portions.toFloat(), { onChange(it.toInt().coerceIn(1, max)) }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 1f..max.toFloat(), steps = max - 2,
                    colors = SliderDefaults.colors(activeTrackColor = C.Track2, inactiveTrackColor = C.Track2,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                    thumb = { Box(Modifier.size(26.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(C.Card)) },
                    track = { Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(C.Track2)) })
                RoundStep(Icons.Default.Add) { if (portions < max) onChange(portions + 1) }
            }
        }
    }

@Composable
private fun RoundStep(icon: ImageVector, onClick: () -> Unit) =
    Box(Modifier.size(46.dp).clip(CircleShape).background(C.Card).border(1.dp, C.Line, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(22.dp), tint = C.Text)
    }

// ---------- Schedule ----------

fun repeatText(days: List<Int>) = when {
    days.isEmpty() -> "Never"
    days.toSet() == (1..7).toSet() -> "Everyday"
    else -> days.sorted().joinToString(", ") { DOW[it - 1] }
}

@Composable
fun ScheduleScreen(d: Device) {
    val err = errorToast()
    BigTitlePage("Schedule", { Nav.back() }, bottom = {
        Box(Modifier.fillMaxWidth().background(C.Bg).navigationBarsPadding().padding(16.dp)) { DarkButton("Add Schedule") { Nav.go(Screen.PlanEdit(d.serial, null)) } }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Gap(12.dp)
            ToggleRow("Feeding Schedule", d.scheduleEnabled) { Repo.toggleSchedule(d, it, err) }
            d.plans.sortedBy { minutesOf(it.time) }.forEach { p ->
                Gap(12.dp)
                Column(Modifier.fillMaxWidth().background(C.Card).clickable { Nav.go(Screen.PlanEdit(d.serial, p.id)) }.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T(p.time, 26.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Toggle(p.enabled) { Repo.togglePlan(d, p, it, err) }
                    }
                    Gap(10.dp); Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line)); Gap(16.dp)
                    T("${fmt(p.grams)} g", 13.sp, C.Faint)
                    Gap(18.dp)
                    T(repeatText(p.days) + if (p.label.isNotBlank()) " · ${p.label}" else "", 13.sp, C.Faint)
                }
            }
            Gap(16.dp)
        }
    }
}

@Composable
fun PlanEditScreen(d: Device, plan: Plan?) {
    val err = errorToast()
    val step = d.gramsPerPortion.toInt().coerceAtLeast(1)
    var time by remember { mutableStateOf(plan?.time ?: "08:00") }
    var portions by remember { mutableStateOf(plan?.portions ?: 1) }
    var days by remember { mutableStateOf(plan?.days ?: (1..7).toList()) }
    var label by remember { mutableStateOf(plan?.label ?: "") }
    var sheet by remember { mutableStateOf<String?>(null) }
    BigTitlePage("Schedule", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Gap(12.dp)
            NavRow("*Feeding Time", time) { sheet = "time" }
            Divider()
            NavRow("*Feeding Amount", "${portions * step} g") { sheet = "amount" }
            Divider()
            NavRow("*Repeat", repeatText(days)) { sheet = "repeat" }
            Divider()
            NavRow("Label", label.ifBlank { "1–20 characters in length" }) { sheet = "label" }
            Gap(12.dp)
            Column(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 16.dp, vertical = 20.dp)) {
                DarkButton("Save") { Repo.setPlan(d, plan, time, portions, days, label, err); Nav.back() }
                if (plan != null) { Gap(12.dp); OutlineButton("DELETE", C.Red) { Repo.deletePlan(d, plan, err); Nav.back() } }
            }
        }
    }
    when (sheet) {
        "time" -> TimeSheet(time, { sheet = null }) { time = it; sheet = null }
        "amount" -> { var p by remember { mutableStateOf(portions) }; AmountSheet("FEEDING AMOUNT", p, step, { sheet = null }, { p = it }) { portions = p; sheet = null } }
        "repeat" -> RepeatSheet(days, { sheet = null }) { days = it; sheet = null }
        "label" -> {
            var t by remember { mutableStateOf(label) }
            Sheet({ sheet = null }, "LABEL", onConfirm = { label = t; sheet = null }) {
                // The integration rejects spaces in labels.
                OutlinedTextField(t, { v -> t = v.filterNot { it.isWhitespace() }.take(20) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { T("1–20 characters, no spaces", 14.sp, C.Faint) })
            }
        }
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 24.dp)) { Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line)) }

@Composable
fun RepeatSheet(days: List<Int>, onDismiss: () -> Unit, onPick: (List<Int>) -> Unit) {
    var sel by remember { mutableStateOf(days) }
    val mode = when { sel.isEmpty() -> 0; sel.toSet() == (1..7).toSet() -> 1; else -> 2 }
    Sheet(onDismiss, "REPEAT", onConfirm = { onPick(sel) }) {
        Column(Modifier.clip(RoundedCornerShape(8.dp)).background(C.Fill)) {
            listOf("Never", "Everyday", "Custom").forEachIndexed { i, s ->
                Row(Modifier.fillMaxWidth().clickable { sel = when (i) { 0 -> emptyList(); 1 -> (1..7).toList(); else -> if (mode == 2) sel else listOf(1, 2, 3, 4, 5) } }.padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    T(s, 17.sp, modifier = Modifier.weight(1f))
                    if (mode == i) Icon(Icons.Default.Check, null, tint = C.Green)
                }
                if (i < 2) Box(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 18.dp).background(C.Line))
            }
        }
        if (mode == 2) {
            Gap(14.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { day ->
                    val on = day in sel
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).background(if (on) C.Dark else C.Fill)
                        .clickable { sel = if (on) sel - day else sel + day }, contentAlignment = Alignment.Center) {
                        T(DOW[day - 1].take(2), 13.sp, if (on) C.OnDark else C.Text)
                    }
                }
            }
        }
    }
}

/** Two snapping wheels (hours, minutes) like Petlibro's Feeding Time picker. */
@Composable
fun TimeSheet(time: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val (h0, m0) = time.split(":").map { it.toInt() }
    var h by remember { mutableStateOf(h0) }
    var m by remember { mutableStateOf(m0) }
    Sheet(onDismiss, "Feeding Time", centeredTitle = true, onConfirm = { onPick("${pad(h)}:${pad(m)}") }) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(50.dp).clip(RoundedCornerShape(8.dp)).background(C.Fill))
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                Wheel(24, h, Modifier.width(60.dp)) { h = it }
                Wheel(60, m, Modifier.width(60.dp)) { m = it }
            }
        }
    }
}

@Composable
fun Wheel(count: Int, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val rows = 1000 * count
    val state = rememberLazyListState(initialFirstVisibleItemIndex = rows / 2 - (rows / 2) % count + value - 2)
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > 20) 3 else 2 }.distinctUntilChanged().collect { onChange(it % count) }
    }
    LazyColumn(modifier.height(200.dp), state = state, flingBehavior = rememberSnapFlingBehavior(state)) {
        items(rows) { i ->
            val v = i % count
            Box(Modifier.height(40.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                T(pad(v), if (v == value) 22.sp else 17.sp, if (v == value) C.Text else C.Sub, if (v == value) FontWeight.Medium else FontWeight.Normal)
            }
        }
    }
}

// ---------- Settings ----------

@Composable
fun SettingsHeader(d: Device) = Row(Modifier.fillMaxWidth().background(C.Card).clickable { Nav.go(Screen.About(d.serial)) }.padding(horizontal = 20.dp, vertical = 18.dp),
    verticalAlignment = Alignment.CenterVertically) {
    DeviceArt(d, Modifier.size(56.dp))
    Spacer(Modifier.width(14.dp))
    Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) { T(d.name, 17.sp, weight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); SmallIcon(Icons.Outlined.EditNote, C.Text, 18.dp) }
        if (d.room != null) T(d.room, 13.sp, C.Faint)
        T("SN: ${d.serial}", 13.sp, C.Faint)
    }
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = C.Faint)
}

@Composable
fun FeederSettingsScreen(d: Device) {
    val err = errorToast()
    var sheet by remember { mutableStateOf<String?>(null) }
    BigTitlePage("Settings", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Gap(12.dp)
            SettingsHeader(d)
            Gap(12.dp)
            NavRow("Measurement Unit", "g", chevron = false)
            Gap(12.dp)
            ToggleRow("Child Lock", d.state("child_lock_switch") == "True", enabled = false) {}
            Gap(12.dp)
            ToggleRow("Indicator Lights", d.e["light_switch"]?.on == true) { Repo.press(d, if (it) "light_on" else "light_off", err) }
            Gap(12.dp)
            NavRow("Desiccant Bag", d.num("remaining_desiccant")?.let { if (it <= 0) "${(-it).toInt()} day(s) overdue" else "${it.toInt()} day(s) left" }) { sheet = "desiccant" }
            Gap(12.dp)
            val home = Repo.state.value.home
            NavRow("Live Camera", home?.let { Repo.cameraFor(d, it)?.name } ?: "None") { sheet = "livecam" }
            Gap(12.dp)
            NavRow("Camera Settings", d.state("resolution")?.takeIf { it != "unknown" }) { sheet = "camera" }
            Gap(12.dp)
            NavRow("Firmware Settings", d.firmware?.let { "V$it" }) { sheet = "firmware" }
            Gap(24.dp)
        }
    }
    when (sheet) {
        "desiccant" -> Sheet({ sheet = null }, "DESICCANT BAG", confirm = "Reset (I replaced it)", onConfirm = { Repo.press(d, "desiccant_reset", err); sheet = null }) {
            T("Replace the desiccant bag every ${d.num("desiccant_frequency")?.toInt() ?: "--"} days to keep the food dry.", 15.sp, C.Sub, lh = 21.sp)
        }
        "camera" -> Sheet({ sheet = null }, "CAMERA SETTINGS", confirm = null) {
            listOf("Resolution" to "resolution", "Night Vision" to "night_vision", "Recording Mode" to "video_record_mode").forEach { (l, k) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) { T(l, 16.sp, modifier = Modifier.weight(1f)); T(d.state(k)?.takeIf { it != "unknown" } ?: "--", 15.sp, C.Sub) }
            }
            T("Camera settings are read-only through Home Assistant.", 13.sp, C.Faint); Gap(20.dp)
        }
        "firmware" -> FirmwareSheet(d) { sheet = null }
        "livecam" -> Sheet({ sheet = null }, "LIVE CAMERA", confirm = null) {
            val cams = Repo.state.value.home?.cameras.orEmpty()
            Radio(false, "Automatic (the camera in the feeder's area)") { Repo.setCamera(d, null); Repo.refresh(); sheet = null }
            cams.forEach { c -> Radio(false, c.name + (c.area?.let { " · $it" } ?: "")) { Repo.setCamera(d, c.id); Repo.refresh(); sheet = null } }
            Radio(false, "None") { Repo.setCamera(d, ""); Repo.refresh(); sheet = null }
            if (cams.isEmpty()) T("No cameras in Home Assistant yet. See LiberPet's docs/camera.md.", 13.sp, C.Faint, modifier = Modifier.padding(vertical = 8.dp))
            Gap(20.dp)
        }
    }
}

@Composable
fun FirmwareSheet(d: Device, onDismiss: () -> Unit) = Sheet(onDismiss, "Firmware Settings", centeredTitle = true, confirm = null) {
    val up = d.e["firmware"]?.on == true
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Gap(16.dp)
        Box(Modifier.size(64.dp).clip(CircleShape).background(C.Green), contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, Modifier.size(40.dp), tint = Color.White) }
        Gap(14.dp)
        T(if (up) "Update available" else "Newest Version", 20.sp, weight = FontWeight.Medium)
        T("V${d.firmware ?: "--"}", 14.sp, C.Sub)
        Gap(32.dp)
    }
}

@Composable
fun AboutScreen(d: Device) = Page("About Device", { Nav.back() }) {
    Column(Modifier.padding(16.dp)) {
        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.Fill)) {
            KV("Name", d.name); KV("Room", d.room ?: "--"); KV("Model", d.model)
        }
        Gap(12.dp)
        Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.Fill)) {
            KV("SN", d.serial); KV("Firmware Version", d.firmware ?: "--"); KV("Wi-Fi", d.state("wifi_ssid") ?: "--"); KV("Signal", d.state("wifi_rssi")?.let { "$it dBm" } ?: "--")
        }
        Gap(12.dp)
        T("Rename the device or set its room (area) in Home Assistant.", 13.sp, C.Faint)
    }
}

@Composable
fun KV(k: String, v: String) = Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
    T(k, 17.sp, modifier = Modifier.weight(1f)); T(v, 15.sp, C.Sub)
}
