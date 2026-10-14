package io.github.gccammelli.petlibre.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.petlibre.Device
import io.github.gccammelli.petlibre.INTEGRATION_URL
import io.github.gccammelli.petlibre.LocalPlatform
import io.github.gccammelli.petlibre.Repo
import io.github.gccammelli.petlibre.UiState
import io.github.gccammelli.petlibre.fmt
import io.github.gccammelli.petlibre.localHm
import io.github.gccammelli.petlibre.localMinutes
import io.github.gccammelli.petlibre.plans
import kotlin.time.Clock
import io.github.gccammelli.petlibre.local
import io.github.gccammelli.petlibre.pad
import kotlinx.coroutines.launch
import kotlin.time.Instant

sealed interface Screen {
    data class Feeder(val sn: String) : Screen
    data class Schedule(val sn: String) : Screen
    data class PlanEdit(val sn: String, val planId: Long?) : Screen
    data class FeederSettings(val sn: String) : Screen
    data class Logs(val sn: String) : Screen
    data class Fountain(val sn: String) : Screen
    data class WaterIntake(val sn: String) : Screen
    data class FountainSettings(val sn: String) : Screen
    data class Goal(val sn: String) : Screen
    data class About(val sn: String) : Screen
    data class Pet(val id: String) : Screen
    data object Settings : Screen
}

object Nav {
    val stack = mutableStateListOf<Screen>()
    var tab by mutableStateOf(0)
    fun go(s: Screen) { stack += s }
    /** False when there's nothing to go back to (the system then closes the app). */
    fun back(): Boolean = stack.removeLastOrNull() != null
}

@Composable
fun Root() {
    val st by Repo.state.collectAsState()
    if (!st.on) return Connect()
    val home = st.home
    if (home == null) return Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
        if (st.error != null) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            T(st.error!!, 15.sp, C.Sub); Gap(16.dp); DarkButton("Retry") { Repo.refresh() }
        } else CircularProgressIndicator(color = C.Green)
    }
    fun dev(sn: String) = home.devices.firstOrNull { it.serial == sn }
    when (val s = Nav.stack.lastOrNull()) {
        null -> Tabs(st)
        is Screen.Feeder -> dev(s.sn)?.let { FeederScreen(it, st) }
        is Screen.Schedule -> dev(s.sn)?.let { ScheduleScreen(it) }
        is Screen.PlanEdit -> dev(s.sn)?.let { PlanEditScreen(it, it.plansById(s.planId)) }
        is Screen.FeederSettings -> dev(s.sn)?.let { FeederSettingsScreen(it) }
        is Screen.Logs -> dev(s.sn)?.let { LogsScreen(it, st) }
        is Screen.Fountain -> dev(s.sn)?.let { FountainScreen(it, st) }
        is Screen.WaterIntake -> dev(s.sn)?.let { WaterIntakeScreen(it, st) }
        is Screen.FountainSettings -> dev(s.sn)?.let { FountainSettingsScreen(it, st) }
        is Screen.Goal -> dev(s.sn)?.let { GoalScreen(it, st) }
        is Screen.About -> dev(s.sn)?.let { AboutScreen(it) }
        is Screen.Pet -> home.pets.firstOrNull { it.id == s.id }?.let { PetScreen(it, st) }
        Screen.Settings -> SettingsScreen()
    } ?: Nav.back()
}

private fun Device.plansById(id: Long?) = id?.let { i -> plans.firstOrNull { it.id == i } }

// ---------- Tabs ----------

@Composable
fun Tabs(st: UiState) = Column(Modifier.fillMaxSize().background(C.Bg)) {
    Box(Modifier.weight(1f)) {
        when (Nav.tab) {
            0 -> HomeTab(st)
            1 -> CareTab(st)
            2 -> ServicesTab()
            else -> MeTab(st)
        }
    }
    Row(Modifier.fillMaxWidth().background(C.Nav).navigationBarsPadding().height(64.dp)) {
        listOf("Device" to Icons.Default.Smartphone, "Care" to Icons.Default.Pets, "Services" to Icons.Default.ShoppingBag, "Me" to Icons.Default.Person)
            .forEachIndexed { i, (label, icon) ->
                val sel = Nav.tab == i
                Column(Modifier.weight(1f).fillMaxSize().clickable { Nav.tab = i }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, label, Modifier.size(28.dp), tint = if (sel) C.Dark else Color(0xFFB3B3B3))
                    T(label, 11.sp, if (sel) C.Dark else Color(0xFF8A8A8A), if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
    }
}

@Composable
fun HomeTab(st: UiState) {
    val home = st.home ?: return
    Column(Modifier.fillMaxSize().background(Color(0xFFECECEC)).verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().height(300.dp).background(Brush.verticalGradient(listOf(Color(0xFFB9B6B3), Color(0xFF9D9A97), Color(0xFFC9C5C0))))) {
            Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 10.dp, end = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                HeaderCircle(if (st.loading) Icons.Default.Refresh else Icons.Outlined.NotificationsNone) { Repo.refresh() }
                HeaderCircle(Icons.Default.Add) { Nav.tab = 3 }
            }
            Row(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                T("All", 28.sp, Color.White, FontWeight.Bold)
                Icon(Icons.Default.KeyboardArrowDown, null, tint = Color.White)
            }
        }
        Column(Modifier.offset(y = (-40).dp).padding(horizontal = 17.dp)) {
            home.devices.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) {
                    pair.forEach { DeviceCard(it, st, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Gap(13.dp)
            }
            if (st.error != null) T(st.error, 13.sp, C.Red, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
private fun HeaderCircle(icon: ImageVector, onClick: () -> Unit) =
    Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = .35f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(26.dp), tint = Color.White)
    }

val Orange = Color(0xFFF26B3A)

/** Fountain problems as the app lists them: filter and cleaning overdue, water low, offline. */
fun Device.irregularities(): List<String> = buildList {
    if (!online) add("Offline")
    if ((num("remaining_filter_days") ?: 1.0) <= 0) add("Filter")
    if ((num("remaining_cleaning_days") ?: 1.0) <= 0) add("Cleaning")
    if (isFountain && (num("weight_percent") ?: 100.0) < 20) add("Water")
}

@Composable
fun DeviceCard(d: Device, st: UiState, modifier: Modifier) = Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color.White)
    .clickable { Nav.go(if (d.isFeeder) Screen.Feeder(d.serial) else Screen.Fountain(d.serial)) }) {
    Box(Modifier.fillMaxWidth().aspectRatio(.98f).background(Color(0xFFF8F8F8))) {
        DeviceArt(d, Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 40.dp))
        T(d.room ?: "", 13.sp, C.Faint, modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
    }
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        StatusIcons(d)
        Gap(8.dp)
        T(d.name, 16.sp, weight = FontWeight.Bold, lh = 20.sp)
        Gap(8.dp)
        val irr = d.irregularities()
        if (d.isFountain && irr.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(15.dp).clip(CircleShape).background(C.Red), contentAlignment = Alignment.Center) { Icon(Icons.Default.PriorityHigh, null, Modifier.size(11.dp), tint = Color.White) }
            Spacer(Modifier.width(6.dp)); T("${irr.size} Irregularities Detected", 13.sp, C.Red)
        }
        Gap(10.dp)
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
        Gap(10.dp)
        if (d.isFeeder) {
            T("Next Feeding:", 13.sp)
            T(nextFeeding(d), 13.sp)
        } else {
            T("Today's water intake:", 13.sp)
            T("${fmt(d.num("today_drinking_amount") ?: 0.0)} ml", 13.sp)
        }
    }
}

fun nextFeeding(d: Device): String {
    val g = d.num("next_feed_quantity_weight") ?: 0.0
    val now = Clock.System.now().local().time.let { it.hour * 60 + it.minute }
    val p = d.plans.filter { it.enabled }.minByOrNull { (localMinutes(it.utc) - now + 1439) % 1440 } ?: return "--"
    return "${localHm(p.utc)} (${fmt(if (g > 0) g else p.grams)} g)"
}

@Composable
fun StatusIcons(d: Device) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    SmallIcon(if (d.online) Icons.Outlined.Wifi else Icons.Outlined.WifiOff, C.Faint, 18.dp)
    if (d.isFountain) {
        SmallIcon(Icons.Outlined.WaterDrop, if ((d.num("weight_percent") ?: 100.0) < 20) Orange else C.Faint, 18.dp)
        SmallIcon(Icons.Outlined.FilterAlt, if ((d.num("remaining_filter_days") ?: 1.0) <= 0) Orange else C.Faint, 18.dp)
        SmallIcon(Icons.Outlined.CleaningServices, if ((d.num("remaining_cleaning_days") ?: 1.0) <= 0) Orange else C.Faint, 18.dp)
    } else if (d.e["light_switch"]?.on == false) SmallIcon(Icons.Outlined.VolumeOff, C.Faint, 18.dp)
}

@Composable
fun ServicesTab() = Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding()) {
    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { T("SERVICES", 17.sp, weight = FontWeight.Medium) }
    Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Bg).padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.ShoppingBag, null, Modifier.size(32.dp), tint = C.Dark)
        Gap(16.dp)
        T("Service coming soon...", 20.sp)
    }
}

// ---------- Connect ----------

@Composable
fun Connect() {
    var url by remember { mutableStateOf("http://") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val p = LocalPlatform.current
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Gap(32.dp)
        T("Petlibre", 32.sp, weight = FontWeight.Bold)
        Gap(8.dp)
        T("Your Petlibro feeder and fountain, through your own Home Assistant. Home Assistant keeps the Petlibro login; this app only talks to Home Assistant.", 15.sp, C.Sub, lh = 21.sp)
        Gap(28.dp)
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { T("Home Assistant address", 14.sp, C.Sub) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Gap(12.dp)
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { T("Long-lived access token", 14.sp, C.Sub) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation())
        Gap(10.dp)
        T("Create the token in Home Assistant: your profile → Security → Long-lived access tokens.", 13.sp, C.Sub, lh = 18.sp)
        if (err != null) { Gap(12.dp); T(err!!, 14.sp, C.Red) }
        Gap(24.dp)
        DarkButton(if (busy) "Connecting…" else "Connect", enabled = !busy && url.length > 8 && token.isNotBlank()) {
            busy = true; err = null
            scope.launch { runCatching { Repo.connect(url, token) }.onFailure { err = it.message ?: it.toString() }; busy = false }
        }
        Gap(20.dp)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, C.Line, RoundedCornerShape(8.dp)).clickable { p.openUrl(INTEGRATION_URL) }.padding(16.dp)) {
            T("Needs the PETLIBRO integration in Home Assistant (install it from HACS and sign in there).", 13.sp, C.Blue, lh = 18.sp)
        }
    }
}

fun hm(i: Instant) = i.local().time.let { "${pad(it.hour)}:${pad(it.minute)}" }
