package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.Device
import io.github.gccammelli.liberpet.LocalPlatform
import io.github.gccammelli.liberpet.MOTION_COLS
import io.github.gccammelli.liberpet.MOTION_ROWS
import io.github.gccammelli.liberpet.MotionSettings
import io.github.gccammelli.liberpet.Repo
import io.github.gccammelli.liberpet.decodeImage
import io.github.gccammelli.liberpet.motionSettings
import io.github.gccammelli.liberpet.setMotionSettings
import io.github.gccammelli.liberpet.snapshot
import kotlinx.coroutines.launch

/**
 * The recorder's motion detection: which parts of the picture count (a grid over a fresh snapshot) and how sensitive
 * it is. Saved in Home Assistant, where the recorder picks it up within seconds.
 */
@Composable
fun MotionScreen(d: Device) {
    val ctx = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var m by remember { mutableStateOf<MotionSettings?>(null) }
    var missing by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) { runCatching { Repo.ha!!.motionSettings() }.onSuccess { if (it == null) missing = true else m = it } }
    LaunchedEffect(Unit) {
        val cam = Repo.state.value.home?.let { Repo.cameraFor(d, it) } ?: return@LaunchedEffect
        picture = runCatching { decodeImage(Repo.ha!!.snapshot(cam.id), 1280) }.getOrNull()
    }
    Page("Motion Detection", { Nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            when {
                missing -> SetupNote("To set motion detection from here, add a Home Assistant text helper named \"LiberPet recorder\" " +
                    "(input_text.liberpet_recorder) and update the recorder.", "tools/recorder/README.md#motion-detection")
                m == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Green) }
                else -> {
                    val cur = m!!
                    T("Tap or drag over the picture to choose where movement counts. Dimmed squares are ignored: leave out " +
                        "things that move or flicker, like a window, a TV or a busy floor.", 15.sp, C.Sub, lh = 21.sp)
                    Gap(14.dp)
                    MotionGrid(picture, cur.cells) { m = cur.copy(cells = it) }
                    Gap(10.dp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlineButton("Watch all", C.Text, Modifier.weight(1f)) { m = cur.copy(cells = List(cur.cells.size) { true }) }
                        OutlineButton("Watch none", C.Text, Modifier.weight(1f)) { m = cur.copy(cells = List(cur.cells.size) { false }) }
                    }
                    Gap(24.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        T("Sensitivity", 16.sp, weight = FontWeight.Medium); Fill(); T("${cur.sensitivity}", 16.sp, C.Sub)
                    }
                    Slider(cur.sensitivity.toFloat(), { m = cur.copy(sensitivity = it.toInt()) }, valueRange = 1f..100f,
                        colors = SliderDefaults.colors(thumbColor = C.Green, activeTrackColor = C.Green))
                    T("Lower: only clear movement starts a clip. Higher: smaller movements count too. 50 is the default.", 14.sp, C.Faint, lh = 19.sp)
                    Gap(24.dp)
                    DarkButton("Save", Modifier.fillMaxWidth(), enabled = cur.cells.any { it }) {
                        scope.launch {
                            runCatching { Repo.ha!!.setMotionSettings(cur) }
                                .onSuccess { ctx.toast("Saved: the recorder uses it within a few seconds"); Nav.back() }
                                .onFailure { ctx.toast("Couldn't save: ${it.message}") }
                        }
                    }
                    if (cur.cells.none { it }) { Gap(8.dp); T("Watch at least one square, or motion recording stops.", 13.sp, C.Red) }
                }
            }
        }
    }
}

/** The camera picture under a grid; a drag paints every square it crosses the way its first square flips. */
@Composable
private fun MotionGrid(picture: ImageBitmap?, cells: List<Boolean>, onChange: (List<Boolean>) -> Unit) {
    var paint by remember { mutableStateOf(true) }
    var current by remember { mutableStateOf(cells) }
    current = cells
    fun cellAt(o: Offset, size: Size): Int? {
        val c = (o.x / size.width * MOTION_COLS).toInt(); val r = (o.y / size.height * MOTION_ROWS).toInt()
        return if (c in 0 until MOTION_COLS && r in 0 until MOTION_ROWS) r * MOTION_COLS + c else null
    }
    fun set(i: Int, on: Boolean) { if (current[i] != on) { current = current.toMutableList().also { it[i] = on }; onChange(current) } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
        picture?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            ?: T("Loading the camera picture…", 14.sp, Color.White, modifier = Modifier.align(Alignment.Center))
        Canvas(Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { o -> cellAt(o, Size(size.width.toFloat(), size.height.toFloat()))?.let { set(it, !current[it]) } }
            }
            .pointerInput(Unit) {
                val s = Size(size.width.toFloat(), size.height.toFloat())
                detectDragGestures(onDragStart = { o -> cellAt(o, s)?.let { paint = !current[it]; set(it, paint) } }) { change, _ ->
                    cellAt(change.position, s)?.let { set(it, paint) }
                }
            }) {
            val cw = size.width / MOTION_COLS; val ch = size.height / MOTION_ROWS
            for (i in cells.indices) {
                val tl = Offset(i % MOTION_COLS * cw, i / MOTION_COLS * ch)
                if (!cells[i]) drawRect(Color.Black.copy(alpha = 0.6f), tl, Size(cw, ch))
                else drawRect(C.Green.copy(alpha = 0.15f), tl, Size(cw, ch))
            }
            for (c in 1 until MOTION_COLS) drawLine(Color.White.copy(alpha = 0.35f), Offset(c * cw, 0f), Offset(c * cw, size.height), 1f)
            for (r in 1 until MOTION_ROWS) drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, r * ch), Offset(size.width, r * ch), 1f)
        }
    }
}
