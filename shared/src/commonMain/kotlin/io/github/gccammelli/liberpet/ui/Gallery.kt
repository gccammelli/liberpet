package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.Clip
import io.github.gccammelli.liberpet.Device
import io.github.gccammelli.liberpet.Repo
import io.github.gccammelli.liberpet.UiState
import io.github.gccammelli.liberpet.VideoPlayer
import io.github.gccammelli.liberpet.clips
import io.github.gccammelli.liberpet.local
import io.github.gccammelli.liberpet.pad
import io.github.gccammelli.liberpet.recordingDays
import io.github.gccammelli.liberpet.recordingsFolder
import io.github.gccammelli.liberpet.resolveMedia
import kotlinx.datetime.LocalDate

/** Why a clip was recorded, as the recorder names it. */
private val REASONS = mapOf(
    "feed" to (Icons.Default.Restaurant to "Feeding"),
    "motion" to (Icons.AutoMirrored.Outlined.DirectionsRun to "Motion"),
    "manual" to (Icons.Outlined.TouchApp to "Manual"),
    "continuous" to (Icons.Outlined.Videocam to "Continuous"),
)

/** The recorder's clips, by day: a timeline of the day with feedings marked, then the clips as thumbnails. */
@Composable
fun GalleryScreen(d: Device, st: UiState) {
    var days by remember { mutableStateOf<List<String>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var day by remember { mutableStateOf<String?>(null) }
    var clips by remember(day) { mutableStateOf<List<Clip>?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            val f = Repo.ha!!.recordingsFolder() ?: error("no recordings folder")
            folder = f
            Repo.ha!!.recordingDays(f)
        }.onSuccess { days = it; day = it.firstOrNull() }.onFailure { missing = true }
    }
    LaunchedEffect(day) { val f = folder; day?.let { dd -> if (f != null) clips = runCatching { Repo.ha!!.clips(dd, f) }.getOrDefault(emptyList()) } }
    Page("Recordings", { Nav.back() }) {
        when {
            missing -> Empty("No recordings in Home Assistant yet",
                "Set up LiberPet's recorder and share its folder to Home Assistant as media storage named \"liberpet\". See docs/camera.md in LiberPet's repository.")
            days == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Green) }
            days!!.isEmpty() -> Empty("No clips yet", "Clips appear here when the camera sees motion or the feeder dispenses.")
            else -> {
                val sel = day!!
                val feeds = st.logs.feeds[d.serial].orEmpty().filter { it.at.local().date.toString() == sel }
                LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(span = { GridItemSpan(2) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(days!!) { dd ->
                                val on = dd == sel
                                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) C.Dark else C.Card).clickable { day = dd }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)) { T(dayChip(dd), 14.sp, if (on) C.OnDark else C.Text) }
                            }
                        }
                    }
                    item(span = { GridItemSpan(2) }) { DayTimeline(clips.orEmpty(), feeds.map { it.at.local().time.let { t -> t.hour * 60 + t.minute } }) }
                    when {
                        clips == null -> item(span = { GridItemSpan(2) }) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Green) }
                        }
                        clips!!.isEmpty() -> item(span = { GridItemSpan(2) }) { T("No clips on this day", 15.sp, C.Sub, modifier = Modifier.padding(24.dp)) }
                        else -> items(clips!!) { c -> ClipCard(c) { Nav.go(Screen.Clip(d.serial, c.video, "${dayChip(c.day)} · ${c.time.take(5)}")) } }
                    }
                }
            }
        }
    }
}

private fun dayChip(day: String): String {
    val date = LocalDate.parse(day)
    val (label, full) = dayTitle(date)
    return label ?: (full.substringAfterLast(' ') + " " + full.take(5)) // "Mon 13/10"
}

@Composable
private fun Empty(title: String, body: String) = Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
    horizontalAlignment = Alignment.CenterHorizontally) {
    Icon(Icons.Outlined.Videocam, null, Modifier.size(48.dp), tint = C.Faint)
    Gap(12.dp)
    T(title, 18.sp, weight = FontWeight.Medium, align = TextAlign.Center)
    Gap(8.dp)
    T(body, 14.sp, C.Sub, align = TextAlign.Center, lh = 20.sp)
}

/** The day at a glance: clips as bars, feedings as green dots, over 24 hours. */
@Composable
private fun DayTimeline(clips: List<Clip>, feedMinutes: List<Int>) = Card(padding = PaddingValues(16.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("${clips.size} clip${if (clips.size == 1) "" else "s"}", 15.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (feedMinutes.isNotEmpty()) T("${feedMinutes.size} feeding${if (feedMinutes.size == 1) "" else "s"}", 13.sp, C.Green)
    }
    Gap(12.dp)
    val track = C.Line; val clipColor = C.Brown; val feedColor = C.Green
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val w = size.width; val mid = size.height / 2
        drawLine(track, Offset(0f, mid), Offset(w, mid), 4.dp.toPx())
        clips.forEach { c ->
            val (h, m, s) = c.time.split(":").map { it.toInt() }
            val x0 = (h * 3600 + m * 60 + s) / 86400f * w
            val x1 = x0 + ((c.seconds ?: 30) / 86400f * w).coerceAtLeast(3.dp.toPx())
            drawLine(clipColor, Offset(x0, mid), Offset(x1, mid), 10.dp.toPx())
        }
        feedMinutes.forEach { drawCircle(feedColor, 4.dp.toPx(), Offset(it / 1440f * w, mid)) }
    }
    Row(Modifier.fillMaxWidth()) { listOf("00", "06", "12", "18", "24").forEachIndexed { i, l ->
        T(l, 10.sp, C.Faint, modifier = Modifier.weight(1f), align = if (i == 4) TextAlign.End else TextAlign.Start)
    } }
}

@Composable
private fun ClipCard(c: Clip, onClick: () -> Unit) = Column(Modifier.clip(RoundedCornerShape(10.dp)).background(C.Card).clickable(onClick = onClick)) {
    Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).background(Color(0xFF1E1E1E))) {
        val img = rememberHaImage(c.thumb, 480)
        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0x99000000)))))
        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            c.reasons.mapNotNull { REASONS[it] }.forEach { (icon, label) -> Badge(icon, label) }
        }
        c.seconds?.let { T("${it / 60}:${pad(it % 60)}", 11.sp, Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
    }
    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        T(c.time.take(5), 15.sp, weight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        T(c.reasons.mapNotNull { REASONS[it]?.second }.joinToString(" · "), 12.sp, C.Sub)
    }
}

@Composable
private fun Badge(icon: ImageVector, label: String) =
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(14.dp), tint = Color.White)
    }

/** One clip, full screen, with play/pause and a scrubber. The address comes from Home Assistant on opening. */
@Composable
fun ClipScreen(id: String, title: String) {
    var url by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { runCatching { Repo.ha!!.resolveMedia(id) }.onSuccess { url = it }.onFailure { error = it.message } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val u = url
        when {
            error != null -> T(error!!, 14.sp, Color.White, modifier = Modifier.align(Alignment.Center).padding(24.dp), align = TextAlign.Center)
            u == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
            else -> VideoPlayer(u, muted = false, modifier = Modifier.fillMaxSize(), onError = { error = "The recording couldn't be played ($it)" }, controls = true)
        }
        Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clickable { Nav.back() }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
            T(title, 16.sp, Color.White)
        }
    }
}
