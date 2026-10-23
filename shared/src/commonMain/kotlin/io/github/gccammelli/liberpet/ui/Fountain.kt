package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.gccammelli.liberpet.Device
import io.github.gccammelli.liberpet.Pet
import io.github.gccammelli.liberpet.Repo
import io.github.gccammelli.liberpet.UiState
import io.github.gccammelli.liberpet.dispensingModes
import io.github.gccammelli.liberpet.dur
import io.github.gccammelli.liberpet.fmt
import io.github.gccammelli.liberpet.local
import io.github.gccammelli.liberpet.pad
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Clock

fun linkedPets(d: Device, st: UiState): List<Pet> = st.home?.pets.orEmpty().filter { d.serial in it.linkedSerials }
fun goal(d: Device, st: UiState): Double = linkedPets(d, st).sumOf { it.num("drinkingGoal") ?: 0.0 }
fun mmss(s: Double) = "${pad((s / 60).toInt())}m ${pad((s % 60).toInt())}s"

@Composable
fun FountainScreen(d: Device, st: UiState) {
    var flow by remember { mutableStateOf(false) }
    var remind by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    Page(d.name, { Nav.back() }, action = { GearButton { Nav.go(Screen.FountainSettings(d.serial)) } }, bottom = {
        BottomActions(listOf(Triple("Flow Type", Icons.Default.WaterDrop) { flow = true }, Triple("Hydration History", Icons.Default.BarChart) { Nav.go(Screen.WaterIntake(d.serial)) }))
    }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(C.Card)) {
                RoomLine(d)
                val irr = d.irregularities()
                if (irr.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).border(1.dp, C.Red, RoundedCornerShape(6.dp))
                    .background(C.RedBg).clickable { remind = true }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(15.dp).clip(CircleShape).background(C.Red), contentAlignment = Alignment.Center) { Icon(Icons.Default.PriorityHigh, null, Modifier.size(11.dp), tint = Color.White) }
                    Spacer(Modifier.width(6.dp)); T("${irr.size} Irregularities Detected", 13.sp, C.Red, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = C.Red)
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { FountainArt(Modifier.size(150.dp)) }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Gap(16.dp)
                Card(padding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), onClick = { Nav.go(Screen.WaterIntake(d.serial)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("Hydration History", 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Faint)
                    }
                    Gap(16.dp)
                    val ml = d.num("today_drinking_amount") ?: 0.0
                    val g = goal(d, st)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Ring(if (g > 0) (ml / g).toFloat() else 1f, C.Green, Modifier.size(150.dp), stroke = 9.dp) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.Bottom) { T(fmt(ml), 30.sp, weight = FontWeight.Medium); T("ml", 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
                                T("Goal: ${if (g > 0) fmt(g) else "--"}ml", 14.sp, C.Sub)
                            }
                        }
                        Spacer(Modifier.width(22.dp))
                        Column {
                            T("Today's Drinking Data", 13.sp, C.Faint)
                            Gap(8.dp)
                            Row(verticalAlignment = Alignment.Bottom) { T("${d.num("today_drinking_count")?.toInt() ?: 0}", 22.sp, weight = FontWeight.Medium); T("times", 13.sp, C.Sub, modifier = Modifier.padding(bottom = 3.dp)) }
                            Gap(8.dp)
                            val s = d.num("today_drinking_time") ?: 0.0
                            Row(verticalAlignment = Alignment.Bottom) {
                                T(pad((s / 60).toInt()), 22.sp, weight = FontWeight.Medium); T("m ", 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                                T(pad((s % 60).toInt()), 22.sp, weight = FontWeight.Medium); T("s", 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                            }
                        }
                    }
                    Gap(16.dp)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
                    Row(Modifier.fillMaxWidth().clickable { Nav.go(Screen.Goal(d.serial)) }.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        T("Customize daily hydration goal", 15.sp, modifier = Modifier.weight(1f))
                        val pets = linkedPets(d, st)
                        pets.forEachIndexed { i, p -> PetAvatar(p.avatar, p.name, 26.dp, Modifier.offset(x = (-6 * i).dp)) }
                        Box(Modifier.offset(x = (-6 * pets.size).dp).size(26.dp).clip(CircleShape).background(C.Green), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = Color.White)
                        }
                    }
                }
                Gap(12.dp)
                Card(padding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
                    T("Status", 18.sp, weight = FontWeight.Medium)
                    Gap(14.dp)
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { SmallIcon(Icons.Outlined.WaterDrop, C.Text, 22.dp); Spacer(Modifier.width(10.dp)); T("Water", 16.sp) }
                    Gap(10.dp)
                    Bar(((d.num("weight_percent") ?: 0.0) / 100).toFloat(), C.Green, Modifier.padding(start = 32.dp))
                    Gap(18.dp)
                    StatusLine(Icons.Outlined.FilterAlt, "Filter", d.num("remaining_filter_days"), d.num("filter_cycle")) { sheet = "filter" }
                    Gap(10.dp)
                    StatusLine(Icons.Outlined.CleaningServices, "Cleaning", d.num("remaining_cleaning_days"), d.num("cleaning_cycle")) { sheet = "cleaning" }
                }
                Gap(12.dp)
                LogsCard(st.logs.drinks[d.serial].orEmpty()) { Nav.go(Screen.Logs(d.serial)) }
                Gap(16.dp)
            }
        }
    }
    if (flow) FlowTypeSheet(d) { flow = false }
    if (remind) ReminderDialog { remind = false }
    sheet?.let { MaintenanceSheet(d, it) { sheet = null } }
}

/** Filter or cleaning: when it's due, how often, and the reset after doing it. */
@Composable
fun MaintenanceSheet(d: Device, what: String, onDismiss: () -> Unit) {
    val err = errorToast()
    val filter = what == "filter"
    val cycleKey = if (filter) "filter_cycle" else "cleaning_cycle"
    var cycle by remember { mutableStateOf(d.num(cycleKey)?.toInt() ?: 14) }
    Sheet(onDismiss, if (filter) "FILTER" else "CLEANING", confirm = if (filter) "Reset (I replaced the filter)" else "Reset (I cleaned it)", onConfirm = {
        if (cycle != d.num(cycleKey)?.toInt()) d.e[cycleKey]?.let { Repo.setNumber(d, it.id, cycle.toDouble(), err) }
        Repo.press(d, if (filter) "filter_reset" else "cleaning_reset", err); onDismiss()
    }) {
        T(if (filter) "Please replace the filter on time to ensure your pet is drinking clean water. Reset the filter cycle after replacing the filter."
          else "Please clean it in time to keep your pet's drinking water healthy.", 15.sp, C.Sub, lh = 21.sp)
        Gap(18.dp)
        Stepper("Every", cycle, "days", 1, 60) { cycle = it }
    }
}

@Composable
fun Stepper(label: String, value: Int, unit: String, min: Int, max: Int, onChange: (Int) -> Unit) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    T(label, 16.sp, modifier = Modifier.weight(1f))
    StepCircle(Icons.Default.Remove, C.Dark) { if (value > min) onChange(value - 1) }
    T("$value $unit", 17.sp, weight = FontWeight.Medium, modifier = Modifier.width(96.dp), align = TextAlign.Center)
    StepCircle(Icons.Default.Add, C.Dark) { if (value < max) onChange(value + 1) }
}

@Composable
fun StepCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, size: androidx.compose.ui.unit.Dp = 36.dp, onClick: () -> Unit) =
    Box(Modifier.size(size).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, if (icon == Icons.Default.Add) "Increase" else "Decrease", Modifier.size(size * .6f), tint = Color.White)
    }

@Composable
fun ReminderDialog(onDismiss: () -> Unit) = Dialog(onDismiss) {
    Column(Modifier.clip(RoundedCornerShape(6.dp)).background(C.Dialog).padding(24.dp)) {
        T("Important Reminder", 18.sp, weight = FontWeight.Medium, modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
        Gap(14.dp); Box(Modifier.fillMaxWidth().height(1.dp).background(C.Track)); Gap(18.dp)
        listOf(Icons.Outlined.CleaningServices to ("Cleaning reminder" to "Please clean it in time to keep your pet's drinking water healthy."),
            Icons.Outlined.FilterAlt to ("Replacement reminder" to "Please replace the filter on time to ensure your pet is drinking clean water. Reset the filter cycle after replacing the filter."))
            .forEach { (icon, tb) ->
                Row {
                    SmallIcon(icon, C.Red, 26.dp); Spacer(Modifier.width(16.dp))
                    Column { T(tb.first, 16.sp, weight = FontWeight.Medium); Gap(6.dp); T(tb.second, 15.sp, lh = 21.sp) }
                }
                Gap(18.dp)
            }
        Gap(8.dp)
        Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(4.dp)).background(C.Dark).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
            T("Previous Step", 17.sp, C.OnDark)
        }
    }
}

@Composable
fun FlowTypeSheet(d: Device, onDismiss: () -> Unit) {
    val err = errorToast()
    val modes = d.dispensingModes.ifEmpty { listOf("Flowing Water (Constant)", "Intermittent Water (Scheduled)") }
    var sel by remember { mutableStateOf(modes.indexOf(d.state("water_dispensing_mode")).coerceAtLeast(0)) }
    var interval by remember { mutableStateOf(d.num("water_interval")?.toInt() ?: 15) }
    var duration by remember { mutableStateOf(d.num("water_dispensing_duration")?.toInt() ?: 15) }
    Sheet(onDismiss, "Choose Flow Type", centeredTitle = true, onConfirm = {
        if (modes[sel] != d.state("water_dispensing_mode")) Repo.select(d, "water_dispensing_mode", modes[sel], err)
        if (sel == 1) {
            if (interval != d.num("water_interval")?.toInt()) d.e["water_interval"]?.let { Repo.setNumber(d, it.id, interval.toDouble(), err) }
            if (duration != d.num("water_dispensing_duration")?.toInt()) d.e["water_dispensing_duration"]?.let { Repo.setNumber(d, it.id, duration.toDouble(), err) }
        }
        onDismiss()
    }) {
        FlowOption("Continuous Flow", "Steady stream all day", sel == 0, false) { sel = 0 }
        Gap(10.dp)
        FlowOption("Timed Flow", "Customize water output or pause", sel == 1, true) { sel = 1 }
        if (sel == 1) {
            Gap(16.dp)
            Stepper("Pause", interval, "min", 1, 180) { interval = it }
            Gap(10.dp)
            Stepper("Flow", duration, "min", 1, 180) { duration = it }
        }
    }
}

@Composable
private fun FlowOption(title: String, sub: String, selected: Boolean, chevron: Boolean, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).border(if (selected) 2.dp else 1.dp, if (selected) C.Green else C.Line, RoundedCornerShape(4.dp))
        .clickable(onClick = onClick).padding(vertical = 18.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (selected) C.Green else C.Track2), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.InvertColors, null, Modifier.size(22.dp), tint = Color.White)
            }
            Gap(10.dp)
            T(title, 15.sp, weight = FontWeight.Medium)
            T(sub, 13.sp, C.Sub)
        }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.align(Alignment.CenterEnd).padding(end = 12.dp), tint = C.Faint)
    }

// ---------- Water intake ----------

@Composable
fun WaterIntakeScreen(d: Device, st: UiState) {
    val today = Clock.System.now().local().date
    val daily = st.logs.daily[d.serial].orEmpty() + (today to (d.num("today_drinking_amount") ?: 0.0))
    val g = goal(d, st)
    var range by remember { mutableStateOf(0) }
    var anchor by remember { mutableStateOf(today) }
    Page("LiberPet", { Nav.back() }, bg = C.Bg) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          T("WATER INTAKE", 30.sp, weight = FontWeight.Bold, modifier = Modifier.fillMaxWidth().background(C.Card).padding(start = 16.dp, top = 10.dp, bottom = 22.dp))
          Gap(14.dp)
          Column(Modifier.padding(horizontal = 16.dp)) {
            Caps("TODAY")
            Card(padding = PaddingValues(20.dp)) {
                val ml = daily[today] ?: 0.0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ring(if (g > 0) (ml / g).toFloat() else 1f, C.Brown, Modifier.size(130.dp), stroke = 8.dp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.Bottom) { T(fmt(ml), 26.sp, weight = FontWeight.Medium); T("ml", 13.sp, modifier = Modifier.padding(bottom = 3.dp)) }
                            T("Goal: ${if (g > 0) fmt(g) else "- -"} ml", 11.sp, C.Faint)
                        }
                    }
                    Spacer(Modifier.width(36.dp))
                    Column {
                        T("Times", 12.sp, C.Faint); T("${d.num("today_drinking_count")?.toInt() ?: 0}", 18.sp, weight = FontWeight.Medium)
                        Gap(14.dp)
                        T("Length of Time", 12.sp, C.Faint); T(dur(d.num("today_drinking_time") ?: 0.0), 18.sp, weight = FontWeight.Medium)
                    }
                }
                Gap(18.dp); Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line)); Gap(14.dp)
                val y = daily[today.minus(DatePeriod(days = 1))] ?: d.num("yesterday_drinking_amount") ?: 0.0
                val top = maxOf(ml, y, 1.0)
                DayBar("Today", ml, top); Gap(12.dp); DayBar("Yesterday", y, top)
            }
            Gap(18.dp)
            Row(verticalAlignment = Alignment.CenterVertically) { Caps("ACHIEVE THE GOAL", Modifier.weight(1f)) }
            Card(padding = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    (7 downTo 1).forEach { back ->
                        val day = today.minus(DatePeriod(days = back))
                        val v = daily[day] ?: 0.0
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Ring(if (g > 0) (v / g).toFloat() else 0f, C.Brown, Modifier.size(34.dp), stroke = 4.dp, track = C.Track2)
                            Gap(4.dp); T("${pad(day.day)}/${pad(day.month.ordinal + 1)}", 11.sp, C.Faint)
                        }
                    }
                }
                Gap(14.dp)
                Row(Modifier.fillMaxWidth().clickable { Nav.go(Screen.Goal(d.serial)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    T("Goal Setting", 14.sp, modifier = Modifier.weight(1f)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Text)
                }
            }
            Gap(18.dp)
            Caps("HISTORY")
            Card(padding = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(C.Fill).padding(3.dp)) {
                    listOf("td.", "wk.", "mth.", "yr.").forEachIndexed { i, s ->
                        Box(Modifier.weight(1f).height(32.dp).clip(RoundedCornerShape(5.dp)).background(if (range == i) C.Card else Color.Transparent)
                            .clickable { range = i; anchor = today }, contentAlignment = Alignment.Center) { T(s, 14.sp) }
                    }
                }
                Gap(12.dp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    T("${pad(anchor.day)}/${pad(anchor.month.ordinal + 1)}/${anchor.year}", 13.sp); Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                }
                val bars = historyBars(range, anchor, daily, st.logs.drinks[d.serial].orEmpty())
                Chart(bars.first, bars.second)
                Gap(12.dp); Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line)); Gap(12.dp)
                Row { T("Total Water Intake", 13.sp, modifier = Modifier.weight(1f)); T("${fmt(bars.first.sum())} ml", 13.sp, C.Sub) }
            }
            Gap(24.dp)
          }
        }
    }
}

@Composable
fun Caps(s: String, modifier: Modifier = Modifier) = T(s, 15.sp, weight = FontWeight.Medium, modifier = modifier.padding(bottom = 10.dp))

@Composable
private fun DayBar(label: String, v: Double, top: Double) = Column {
    Row { T(label, 13.sp); T(" (${fmt(v)} ml)", 13.sp, C.Faint) }
    Gap(4.dp)
    Bar((v / top).toFloat(), C.Brown, track = Color.Transparent, height = 3.dp)
}

/** Bars and axis labels for td. (hours, from the drink log), wk. (7 days), mth. (days), yr. (months). */
fun historyBars(range: Int, anchor: LocalDate, daily: Map<LocalDate, Double>, drinks: List<io.github.gccammelli.liberpet.LogEntry>): Pair<List<Double>, List<String>> = when (range) {
    0 -> {
        val ml = Regex("""drank ([\d.]+) ml""")
        val byHour = DoubleArray(24)
        drinks.filter { it.at.local().date == anchor }.forEach { e -> byHour[e.at.local().hour] += ml.find(e.text)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
        byHour.toList() to (0..23).map { if (it % 6 == 0) pad(it) else "" }
    }
    1 -> (6 downTo 0).map { daily[anchor.minus(DatePeriod(days = it))] ?: 0.0 } to (6 downTo 0).map { anchor.minus(DatePeriod(days = it)).let { d -> "${pad(d.day)}" } }
    2 -> {
        val first = LocalDate(anchor.year, anchor.month, 1)
        val days = generateSequence(first) { it.plus(DatePeriod(days = 1)) }.takeWhile { it.month == anchor.month }.toList()
        days.map { daily[it] ?: 0.0 } to days.map { if (it.day % 7 == 1) "${it.day}" else "" }
    }
    else -> (1..12).map { m -> daily.filterKeys { it.year == anchor.year && it.month.ordinal + 1 == m }.values.sum() } to (1..12).map { if (it % 3 == 1) "$it" else "" }
}

@Composable
fun Chart(values: List<Double>, labels: List<String>) {
    val top = maxOf(values.maxOrNull() ?: 0.0, 1.0)
    Row(Modifier.fillMaxWidth().height(120.dp)) {
        Canvas(Modifier.weight(1f).fillMaxSize()) {
            val w = size.width / values.size
            var x = 0f
            while (x < size.width) { drawCircle(C.Dot, 1.5f, Offset(x, size.height - 1)); x += 12f }
            values.forEachIndexed { i, v ->
                if (v > 0) {
                    val h = (v / top * (size.height - 12)).toFloat()
                    drawLine(Color(0xFFD9BFA8), Offset(i * w + w / 2, size.height), Offset(i * w + w / 2, size.height - h), (w * .5f).coerceIn(3f, 18f))
                }
            }
        }
        Column(Modifier.width(26.dp).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
            T(fmt(top), 10.sp, C.Faint); T("0", 10.sp, C.Faint)
        }
    }
    Row(Modifier.fillMaxWidth().padding(end = 26.dp)) { labels.forEach { T(it, 10.sp, C.Faint, modifier = Modifier.weight(1f)) } }
}

// ---------- Settings, goal, calibration ----------

@Composable
fun FountainSettingsScreen(d: Device, st: UiState) {
    val err = errorToast()
    var sheet by remember { mutableStateOf<String?>(null) }
    BigTitlePage("Settings", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Gap(12.dp)
            SettingsHeader(d)
            Gap(12.dp)
            Row(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 24.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                T("Measurement Unit", 17.sp, modifier = Modifier.weight(1f))
                T("○ oz   ", 13.sp, C.Faint); T("◉ ml", 13.sp, C.Tan)
            }
            Gap(12.dp)
            NavRow("Goal Setting") { Nav.go(Screen.Goal(d.serial)) }
            Gap(12.dp)
            NavRow("Calibration") { sheet = "calibration" }
            Gap(12.dp)
            ToggleRow("Indicator Lights", d.e["light_switch"]?.on == true) { Repo.press(d, if (it) "light_on" else "light_off", err) }
            Gap(12.dp)
            NavRow("Filter", d.num("remaining_filter_days")?.let { if (it <= 0) "${(-it).toInt()} day(s) overdue" else "${it.toInt()} day(s) left" }) { sheet = "filter" }
            Gap(12.dp)
            NavRow("Cleaning", d.num("remaining_cleaning_days")?.let { if (it <= 0) "${(-it).toInt()} day(s) overdue" else "${it.toInt()} day(s) left" }) { sheet = "cleaning" }
            Gap(12.dp)
            NavRow("Firmware Settings", d.firmware?.let { "V$it" }) { sheet = "firmware" }
            Gap(24.dp)
        }
    }
    when (sheet) {
        "filter", "cleaning" -> MaintenanceSheet(d, sheet!!) { sheet = null }
        "firmware" -> FirmwareSheet(d) { sheet = null }
        "calibration" -> Sheet({ sheet = null }, "CALIBRATION", confirm = "CONFIRM", onConfirm = { Repo.press(d, "calibrate_weight", err); sheet = null }) {
            listOf("Remove the water tank and place the base on a flat and level surface.", "Do not place any objects on the base.", "Do not let the base touch any other objects.")
                .forEach { T("· $it", 14.sp, lh = 20.sp, modifier = Modifier.padding(vertical = 6.dp)) }
            if (d.e["weight_calibration_error"]?.on == true) { Gap(8.dp); T("The last calibration failed.", 14.sp, C.Red) }
        }
    }
}

@Composable
fun GoalScreen(d: Device, st: UiState) {
    val err = errorToast()
    val pets = linkedPets(d, st)
    var total by remember { mutableStateOf(goal(d, st).toInt()) }
    Page("", { Nav.back() }, bg = C.Card) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            T("Daily Hydration Goal", 28.sp, weight = FontWeight.Bold)
            Gap(6.dp); T("Modify your device's daily goal for all pets.", 14.sp, C.Sub)
            Gap(48.dp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StepCircle(Icons.Default.Remove, C.Green, 48.dp) { total = (total - 10).coerceAtLeast(0) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    T("$total", 56.sp, weight = FontWeight.Medium); T("ml / day", 17.sp)
                }
                StepCircle(Icons.Default.Add, C.Green, 48.dp) { total += 10 }
            }
            Gap(40.dp)
            Column(Modifier.clip(RoundedCornerShape(8.dp)).background(C.Fill)) {
                Row(Modifier.fillMaxWidth().background(C.Fill).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    T("Linked Pets", 12.sp, modifier = Modifier.weight(1f)); T("Drinking Goal", 12.sp)
                }
                pets.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PetAvatar(p.avatar, p.name, 30.dp); Spacer(Modifier.width(8.dp)); T(p.name, 15.sp, modifier = Modifier.weight(1f))
                        T(if (pets.isNotEmpty()) "${total / pets.size} ml" else "--", 14.sp, C.Sub)
                    }
                }
            }
            Gap(32.dp)
            DarkButton("Confirm") {
                // The goal lives on each pet in Petlibro's cloud; split the device's total between its pets.
                pets.forEach { p -> p.e["drinkingGoal"]?.let { Repo.setNumber(null, it.id, (total / pets.size).toDouble(), err) } }
                Nav.back()
            }
            Gap(24.dp)
        }
    }
}
