package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.CatStats
import io.github.gccammelli.liberpet.Pet
import io.github.gccammelli.liberpet.CatDay
import io.github.gccammelli.liberpet.catStats
import io.github.gccammelli.liberpet.local
import kotlin.time.Clock
import kotlinx.datetime.minus

private val PERIODS = listOf(1 to "Today", 7 to "7 days", 30 to "30 days")

/**
 * Cat Insights (GitHub #3, #4), in the Care tab: one cat's visits to the feeder, from what the recognizer saw in the
 * recordings, for today, 7 or 30 days. [daily] is the recognizer's daily statistics ([catDays]).
 */
@Composable
fun CatInsights(p: Pet, daily: Map<String, Map<String, CatDay>>) {
    var days by remember { mutableStateOf(7) }
    val today = Clock.System.now().local().date.toString()
    Row(verticalAlignment = Alignment.CenterVertically) {
        T("Insights", 18.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
        PERIODS.forEach { (n, label) ->
            val on = n == days
            Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(16.dp)).background(if (on) C.Dark else C.Card).clickable { days = n }
                .padding(horizontal = 12.dp, vertical = 6.dp)) { T(label, 13.sp, if (on) C.OnDark else C.Text) }
        }
    }
    Gap(12.dp)
    // Per-day averages over the days that have recordings, not days before recording started.
    val first = kotlinx.datetime.LocalDate.parse(today).minus(kotlinx.datetime.DatePeriod(days = days - 1)).toString()
    val covered = daily.keys.count { it in first..today }.coerceAtLeast(1)
    CatCard(catStats(daily, p.id, today, days), days, covered, today)
    Gap(8.dp)
    T("From the feeder camera's recordings; visits the recognizer isn't sure about aren't counted.", 12.sp, C.Faint, lh = 16.sp)
}

@Composable
private fun CatCard(s: CatStats, days: Int, covered: Int, today: String) = Card(padding = PaddingValues(16.dp)) {
    s.last?.let { T("Last visit ${lastVisit(it, today)}", 13.sp, C.Sub); Gap(10.dp) }
    if (s.visits == 0) { T(if (days == 1) "No visits seen today" else "No visits seen", 14.sp, C.Sub); return@Card }
    Row {
        Stat("${s.visits}", if (days == 1) "visits" else "visits (${fmt1(s.visits.toDouble() / covered)}/day)", Modifier.weight(1f))
        Stat(minSec(s.seconds), "eating", Modifier.weight(1f))
        Stat(minSec(s.typicalSeconds), "per visit", Modifier.weight(1f))
    }
    Gap(14.dp)
    val left = (s.leftShare * 100).toInt()
    T("Left bowl $left% · Right bowl ${100 - left}%", 13.sp, C.Sub)
    Gap(6.dp)
    val green = C.Green; val track = C.Line
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(track, cornerRadius = CornerRadius(4.dp.toPx()))
        drawRoundRect(green, size = Size(size.width * s.leftShare.toFloat(), size.height), cornerRadius = CornerRadius(4.dp.toPx()))
    }
    Gap(14.dp)
    T("When", 13.sp, C.Sub)
    Gap(6.dp)
    val bar = C.Brown; val max = (s.byHour.maxOrNull() ?: 1).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val w = size.width / 24
        s.byHour.forEachIndexed { h, n ->
            if (n > 0) {
                val bh = size.height * n / max
                drawRoundRect(bar, Offset(h * w + w * 0.15f, size.height - bh), Size(w * 0.7f, bh), CornerRadius(2.dp.toPx()))
            }
        }
    }
    Row(Modifier.fillMaxWidth()) { listOf("00", "06", "12", "18", "24").forEachIndexed { i, l ->
        T(l, 10.sp, C.Faint, modifier = Modifier.weight(1f), align = if (i == 4) TextAlign.End else TextAlign.Start)
    } }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) = Column(modifier) {
    T(value, 20.sp, weight = FontWeight.Bold); T(label, 12.sp, C.Sub)
}

private fun minSec(seconds: Int) = if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
private fun fmt1(v: Double) = ((v * 10).toInt() / 10.0).toString()

/** "08:49", "yesterday 20:31" or "14/10 07:00" for a stay's local time. */
private fun lastVisit(at: String, today: String): String {
    val time = at.substring(11, 16)
    val day = at.take(10)
    val yesterday = kotlinx.datetime.LocalDate.parse(today).let { it.minus(kotlinx.datetime.DatePeriod(days = 1)) }.toString()
    return when (day) { today -> time; yesterday -> "yesterday $time"; else -> "${day.substring(8, 10)}/${day.substring(5, 7)} $time" }
}
