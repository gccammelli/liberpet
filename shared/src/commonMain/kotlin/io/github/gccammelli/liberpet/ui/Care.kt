package io.github.gccammelli.liberpet.ui

import androidx.compose.runtime.LaunchedEffect
import io.github.gccammelli.liberpet.catDays
import io.github.gccammelli.liberpet.CatDay
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.INTEGRATION_URL
import io.github.gccammelli.liberpet.LocalPlatform
import io.github.gccammelli.liberpet.Pet
import io.github.gccammelli.liberpet.Repo
import io.github.gccammelli.liberpet.UiState
import io.github.gccammelli.liberpet.fmt
import io.github.gccammelli.liberpet.local
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlin.time.Clock

fun Pet.ageText() = ageYears?.let { y -> if (y > 0) "$y year${if (y > 1) "s" else ""} old" else "${ageMonths ?: 0} months old" } ?: "--"

@Composable
fun CareTab(st: UiState) {
    val pets = st.home?.pets.orEmpty()
    var sel by remember { mutableStateOf(0) }
    var trend by remember { mutableStateOf<String?>(null) }
    val pet = pets.getOrNull(sel)
    // Cat Insights (GitHub #4): per-cat statistics from the recognizer, when it's running.
    var daily by remember { mutableStateOf<Map<String, Map<String, CatDay>>?>(null) }
    LaunchedEffect(Unit) { daily = runCatching { Repo.ha?.catDays() }.getOrNull() }
    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Row(Modifier.fillMaxWidth().background(C.Card).statusBarsPadding().height(64.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T("Dashboard", 20.sp, weight = FontWeight.Medium)
                Gap(6.dp); Box(Modifier.width(92.dp).height(3.dp).background(C.Green))
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Gap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pets.forEachIndexed { i, p ->
                    Column(Modifier.clickable { sel = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(62.dp).clip(CircleShape).border(2.dp, if (i == sel) C.Tan else Color.Transparent, CircleShape).padding(3.dp)) {
                            PetAvatar(p.avatar, p.name, 56.dp, Modifier.then(if (i == sel) Modifier else Modifier.background(Color.White.copy(alpha = .5f))))
                        }
                        Gap(4.dp); T(p.name, 15.sp, if (i == sel) C.Text else C.Faint)
                    }
                }
            }
            if (pet == null) { Gap(24.dp); T("No pets in Home Assistant yet.", 15.sp, C.Sub); return@Column }
            Gap(18.dp)
            Card(padding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), onClick = { Nav.go(Screen.Pet(pet.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(pet.name, 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f)); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = C.Text)
                }
                Gap(14.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallIcon(Icons.Outlined.Cake, C.Text, 22.dp); Spacer(Modifier.width(8.dp)); T(pet.ageText(), 16.sp, modifier = Modifier.weight(1f))
                    Box(Modifier.width(1.dp).height(22.dp).background(C.Line)); Spacer(Modifier.width(24.dp))
                    SmallIcon(Icons.Outlined.Pets, C.Text, 22.dp); Spacer(Modifier.width(8.dp)); T(pet.breed ?: "--", 16.sp, modifier = Modifier.weight(1f))
                }
            }
            Gap(22.dp)
            val feeders = st.home?.devices.orEmpty().filter { it.isFeeder && it.serial in pet.linkedSerials }
            val fountains = st.home?.devices.orEmpty().filter { it.isFountain && it.serial in pet.linkedSerials }
            val stats = daily.takeIf { io.github.gccammelli.liberpet.Feature.CAT_INSIGHTS.on() }
            if (stats != null && feeders.isNotEmpty()) { CatInsights(pet, stats); Gap(22.dp) }
            T("Overview", 18.sp, weight = FontWeight.Medium)
            Gap(12.dp)
            if (stats == null || feeders.isEmpty()) {
                OverviewCard("Food Intake", "Meal", "${feeders.sumOf { it.num("today_feeding_times") ?: 0.0 }.toInt()}", "times", "Total", fmt(feeders.sumOf { it.num("today_feeding_quantity_weight") ?: 0.0 }), "g") { trend = "food" }
                Gap(12.dp)
            }
            OverviewCard("Water Intake", "Total Visits", "${fountains.sumOf { it.num("today_drinking_count") ?: 0.0 }.toInt()}", "times", "Total", fmt(fountains.sumOf { it.num("today_drinking_amount") ?: 0.0 }), "ml") { trend = "water" }
            Gap(12.dp)
            Card(padding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
                T("Weight", 18.sp, weight = FontWeight.Medium)
                Gap(8.dp)
                Row(verticalAlignment = Alignment.Bottom) { T(fmt(pet.num("weight") ?: 0.0), 30.sp, weight = FontWeight.Medium); T(" kg", 15.sp, modifier = Modifier.padding(bottom = 4.dp)) }
            }
            Gap(24.dp)
            T(if (stats != null) "Water is the total of the fountains linked to ${pet.name}; Petlibro only splits it per pet with RFID collars."
              else "Food and water are the totals of the devices linked to ${pet.name}; Petlibro only splits them per pet with RFID collars.", 12.sp, C.Faint, lh = 17.sp)
            Gap(24.dp)
            val ts = trend
            if (ts != null) {
                val devs = if (ts == "food") feeders else fountains
                val today = Clock.System.now().local().date
                val days = (6 downTo 0).map { today.minus(DatePeriod(days = it)) }
                val values = days.map { day -> devs.sumOf { d -> if (day == today) d.num(if (ts == "food") "today_feeding_quantity_weight" else "today_drinking_amount") ?: 0.0 else st.logs.daily[d.serial]?.get(day) ?: 0.0 } }
                Sheet({ trend = null }, if (ts == "food") "FOOD INTAKE · 7 DAYS" else "WATER INTAKE · 7 DAYS", confirm = null) {
                    Chart(values, days.map { "${it.day}" })
                    Gap(10.dp); T("Average ${fmt(values.average())} ${if (ts == "food") "g" else "ml"} a day", 14.sp, C.Sub); Gap(24.dp)
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(title: String, l1: String, v1: String, u1: String, l2: String, v2: String, u2: String, onTrend: () -> Unit) =
    Card(padding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(title, 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Row(Modifier.clickable(onClick = onTrend), verticalAlignment = Alignment.CenterVertically) {
                T("View 7-Day Trend", 12.sp, C.Sub); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = C.Sub)
            }
        }
        Gap(10.dp)
        Row {
            Column(Modifier.width(120.dp)) {
                T(l1, 14.sp, C.Sub)
                Row(verticalAlignment = Alignment.Bottom) { T(v1, 26.sp, weight = FontWeight.Medium); T(" $u1", 15.sp, modifier = Modifier.padding(bottom = 3.dp)) }
            }
            Column {
                T(l2, 14.sp, C.Sub)
                Row(verticalAlignment = Alignment.Bottom) { T(v2, 26.sp, weight = FontWeight.Medium); T(" $u2", 15.sp, modifier = Modifier.padding(bottom = 3.dp)) }
            }
        }
    }

@Composable
fun PetScreen(p: Pet, st: UiState) {
    val err = errorToast()
    var weight by remember { mutableStateOf<Double?>(null) }
    Page("Profile", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Gap(20.dp)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PetAvatar(p.avatar, p.name, 100.dp) }
            Gap(24.dp)
            T("Basic", 13.sp, C.Sub, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            Group {
                ProfileRow("Name", p.name, true); ProfileRow("Sex", p.sex?.replaceFirstChar { it.uppercase() } ?: "--", true)
                ProfileRow("Age", listOfNotNull(p.ageYears?.let { "$it yrs" }, p.ageMonths?.let { "$it mos" }).joinToString(" ").ifBlank { "--" }, true)
                ProfileRow("Breed", p.breed ?: "--", false)
            }
            Gap(18.dp)
            T("Health", 13.sp, C.Sub, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            Group {
                ProfileRow("Neutering", when (p.neutered) { true -> "Neutered"; false -> "Not neutered"; null -> "--" }, true)
                ProfileRow("Weight", p.num("weight")?.let { "${fmt(it)} kg" } ?: "--", true) { weight = p.num("weight") ?: 4.0 }
            }
            Gap(18.dp)
            T("Device(s) Linked", 13.sp, C.Sub, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
            Group {
                st.home?.devices.orEmpty().filter { it.serial in p.linkedSerials }.forEach { d -> ProfileRow(d.name, d.room ?: "", false) { Nav.go(if (d.isFeeder) Screen.Feeder(d.serial) else Screen.Fountain(d.serial)) } }
            }
            Gap(16.dp)
            T("Change name, sex, age and breed in Home Assistant or the pet's Petlibro profile.", 12.sp, C.Faint, lh = 17.sp)
            Gap(24.dp)
        }
    }
    weight?.let { w ->
        Sheet({ weight = null }, "WEIGHT", onConfirm = { p.e["weight"]?.let { Repo.setNumber(null, it.id, w, err) }; weight = null }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StepCircle(Icons.Default.Remove, C.Dark, 44.dp) { weight = ((w - .1) * 10).toLong() / 10.0 }
                T("${fmt(w)} kg", 28.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f), align = androidx.compose.ui.text.style.TextAlign.Center)
                StepCircle(Icons.Default.Add, C.Dark, 44.dp) { weight = ((w + .1) * 10 + .5).toLong() / 10.0 }
            }
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) = Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(C.Card)) { content() }

@Composable
private fun ProfileRow(label: String, value: String, required: Boolean, onClick: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 17.sp); if (required) T("*", 17.sp, C.Red)
        Spacer(Modifier.weight(1f))
        T(value, 15.sp, C.Sub)
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = C.Faint)
    }

@Composable
fun MeTab(st: UiState) {
    val p = LocalPlatform.current
    val pets = st.home?.pets.orEmpty()
    Column(Modifier.fillMaxSize().background(C.Bg).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().background(C.Card).statusBarsPadding()) {
            Box(Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Outlined.Settings, "Settings", Modifier.align(Alignment.CenterEnd).padding(end = 20.dp).size(26.dp).clickable { Nav.go(Screen.Settings) }, tint = C.Text)
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PetAvatar(null, st.home?.owner ?: "?", 58.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    T(st.home?.owner ?: "Home Assistant", 20.sp, weight = FontWeight.Medium)
                    T("${st.home?.devices?.size ?: 0} devices", 13.sp, C.Faint)
                }
            }
            Gap(20.dp)
        }
        Gap(12.dp)
        Column(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 20.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallIcon(Icons.Outlined.Pets, C.Text, 20.dp); Spacer(Modifier.width(8.dp))
                T("My Pet", 17.sp); T(" (${pets.size})", 15.sp, C.Faint); Spacer(Modifier.weight(1f))
            }
            Gap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                pets.forEach { pet ->
                    Column(Modifier.clickable { Nav.go(Screen.Pet(pet.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        PetAvatar(pet.avatar, pet.name, 50.dp); Gap(4.dp); T(pet.name, 15.sp)
                    }
                }
            }
        }
        Gap(12.dp)
        NavRow("Home Assistant", Repo.haUrl?.removePrefix("http://")?.removePrefix("https://"), leading = { SmallIcon(Icons.Outlined.Home, C.Text, 22.dp) }) { Repo.haUrl?.let(p::openUrl) }
        Gap(12.dp)
        NavRow("PETLIBRO integration", leading = { SmallIcon(Icons.Outlined.Extension, C.Text, 22.dp) }) { p.openUrl(INTEGRATION_URL) }
        Gap(12.dp)
        var pick by remember { mutableStateOf(false) }
        val modes = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
        NavRow("Appearance", modes.first { it.first == ThemeMode.mode }.second, leading = { SmallIcon(Icons.Outlined.DarkMode, C.Text, 22.dp) }) { pick = true }
        if (pick) Sheet({ pick = false }, "APPEARANCE", confirm = null) {
            modes.forEach { (k, label) -> Radio(ThemeMode.mode == k, label) { Repo.setTheme(k); pick = false } }
            Gap(24.dp)
        }
        Gap(12.dp)
        NavRow("About", "V${p.appVersion}", leading = { SmallIcon(Icons.Outlined.Info, C.Text, 22.dp) }, chevron = false)
    }
}

@Composable
fun SettingsScreen() = BigTitlePage("Settings", { Nav.back() }) {
    Gap(12.dp)
    NavRow("Refresh", chevron = false) { Repo.refresh() }
    // Optional parts of LiberPet, from the registry (Feature); all off until switched on.
    io.github.gccammelli.liberpet.Feature.entries.groupBy { it.group }.forEach { (group, features) ->
        Gap(24.dp)
        T(group.uppercase(), 13.sp, C.Sub, modifier = Modifier.padding(horizontal = 16.dp))
        Gap(8.dp)
        features.forEach { f ->
            ToggleRow(f.title, f.on()) { on ->
                Repo.setFeature(f, on)
                if (f == io.github.gccammelli.liberpet.Feature.CARE_TAB) Nav.tab = if (on) 2 else 1 // stay on Me, whose position just moved
            }
            T(f.about + (f.needs?.let { " Needs: $it." } ?: ""), 12.sp, C.Faint, lh = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
    }
    Gap(24.dp)
    NavRow(if (Repo.demo) "Leave the demo" else "Log Out", labelColor = C.Red) { Repo.logout(); Nav.stack.clear(); Nav.tab = 0 }
}
