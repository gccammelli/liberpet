package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.Device
import io.github.gccammelli.liberpet.decodeImage
import io.github.gccammelli.liberpet.Repo

/*
 * Simple drawings of the devices, in the spirit of Petlibro's product photos (which are theirs, so not used here).
 */

@Composable
fun DeviceArt(d: Device, modifier: Modifier) = if (d.isFeeder) FeederArt(modifier) else FountainArt(modifier)

private val shade = Color(0xFFE6E6E6)

@Composable
fun FountainArt(modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val bw = w * .78f; val x = (w - bw) / 2
    // wooden base
    drawRoundRect(Color(0xFFD9B48A), Offset(x - w * .03f, h * .74f), Size(bw + w * .06f, h * .1f), CornerRadius(h * .05f))
    drawRoundRect(Color(0xFFEFEFEF), Offset(x - w * .03f, h * .7f), Size(bw + w * .06f, h * .06f), CornerRadius(h * .03f))
    // body
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFF2F2F2), Color.White, Color(0xFFE9E9E9)), x, x + bw), Offset(x, h * .3f), Size(bw, h * .42f), CornerRadius(w * .1f))
    // steel rim
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFBDBDBD), Color(0xFF8E8E8E))), Offset(x + bw * .03f, h * .27f), Size(bw * .94f, h * .07f), CornerRadius(h * .03f))
    // spout
    drawRoundRect(Color.White, Offset(w * .4f, h * .12f), Size(w * .2f, h * .18f), CornerRadius(w * .04f))
    drawOval(Color(0xFFF7F7F7), Offset(w * .36f, h * .09f), Size(w * .28f, h * .07f))
    drawRoundRect(shade, Offset(x + bw * .08f, h * .4f), Size(bw * .84f, h * .02f), CornerRadius(4f))
}

@Composable
fun FeederArt(modifier: Modifier) = Canvas(modifier) {
    val w = size.width; val h = size.height
    val bw = w * .38f; val x = (w - bw) / 2
    // tray
    drawRoundRect(Color(0xFFF0F0F0), Offset(w * .22f, h * .8f), Size(w * .56f, h * .08f), CornerRadius(h * .04f))
    drawOval(Color(0xFFE3E3E3), Offset(w * .24f, h * .74f), Size(w * .3f, h * .09f))
    // body
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFFEDEDED), Color.White, Color(0xFFE5E5E5)), x, x + bw), Offset(x, h * .1f), Size(bw, h * .74f), CornerRadius(w * .06f))
    // lid seam and window
    drawLine(shade, Offset(x, h * .22f), Offset(x + bw, h * .22f), 2f)
    drawRoundRect(Color(0xFF8C6A55), Offset(w * .48f, h * .32f), Size(w * .04f, h * .2f), CornerRadius(w * .02f))
    // camera dot
    drawCircle(Color(0xFF3A3A3A), w * .025f, Offset(w * .5f, h * .6f))
}

/** A picture from Home Assistant ([path] with the token), cached for the session; null while loading or if it fails. */
@Composable
fun rememberHaImage(path: String?, maxSide: Int): ImageBitmap? {
    var img by remember(path) { mutableStateOf(path?.let { imageCache[it] }) }
    LaunchedEffect(path) {
        val ha = Repo.ha
        if (path != null && img == null && ha != null)
            img = runCatching { decodeImage(ha.bytes(path), maxSide) }.getOrNull()?.also { imageCache[path] = it }
    }
    return img
}

/** Pet photo through Home Assistant's image proxy ([path] from [io.github.gccammelli.liberpet.Pet.avatar]); initial on grey while loading. */
@Composable
fun PetAvatar(path: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    val img = rememberHaImage(path, 256)
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFFE6E6E6)), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, name, Modifier.size(size), contentScale = ContentScale.Crop)
        else T(name.take(1), (size.value * .4f).sp, C.Sub)
    }
}

private val imageCache = mutableStateMapOf<String, ImageBitmap>()


/** The default Home header: a grey cat by a bowl of kibble in a warm room (a darker room in dark mode). */
@Composable
fun HeaderArt(modifier: Modifier) {
    val dark = ThemeMode.dark
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val u = h / 100f // one unit = 1% of the height
        val floorY = h * .78f
        // wall and floor
        drawRect(Brush.verticalGradient(if (dark) listOf(Color(0xFF2E2925), Color(0xFF231F1C)) else listOf(Color(0xFFF4EADF), Color(0xFFE6D5C3)), 0f, floorY), size = Size(w, floorY))
        drawRect(if (dark) Color(0xFF3B2F25) else Color(0xFFD7B994), Offset(0f, floorY), Size(w, h - floorY))
        drawLine(if (dark) Color(0xFF4A3B2E) else Color(0xFFC9A77F), Offset(0f, floorY), Offset(w, floorY), 1.2f * u)
        var x = -w * .1f
        while (x < w) { drawLine(if (dark) Color(0xFF34291F) else Color(0xFFCDAE88), Offset(x, floorY + 6 * u), Offset(x + 30 * u, h), .6f * u); x += 34 * u }
        // window light on the wall
        val glass = if (dark) Color(0x22FFE9C7) else Color(0x66FFFFFF)
        val frame = if (dark) Color(0xFF3A332D) else Color(0xFFE2CDB6)
        drawRoundRect(glass, Offset(w * .08f, 14 * u), Size(30 * u, 36 * u), CornerRadius(3 * u))
        drawLine(frame, Offset(w * .08f + 15 * u, 14 * u), Offset(w * .08f + 15 * u, 50 * u), 1.2f * u)
        drawLine(frame, Offset(w * .08f, 32 * u), Offset(w * .08f + 30 * u, 32 * u), 1.2f * u)

        val fur = if (dark) Color(0xFF7D8894) else Color(0xFF8F9BA8)
        val belly = if (dark) Color(0xFFA5AFB9) else Color(0xFFC3CBD3)
        val ink = Color(0xFF2B2B2B)
        val cx = w * .5f // cat centre
        scale(.72f, Offset(w * .58f, floorY)) {
        // tail
        drawPath(Path().apply { moveTo(cx + 14 * u, floorY - 4 * u); cubicTo(cx + 34 * u, floorY - 2 * u, cx + 34 * u, floorY - 26 * u, cx + 24 * u, floorY - 30 * u) },
            fur, style = Stroke(5 * u, cap = StrokeCap.Round))
        // body and belly
        drawOval(fur, Offset(cx - 16 * u, floorY - 34 * u), Size(32 * u, 36 * u))
        drawOval(belly, Offset(cx - 8 * u, floorY - 24 * u), Size(16 * u, 22 * u))
        // front paws
        drawOval(belly, Offset(cx - 11 * u, floorY - 5 * u), Size(9 * u, 6 * u))
        drawOval(belly, Offset(cx + 2 * u, floorY - 5 * u), Size(9 * u, 6 * u))
        // head and ears
        val hy = floorY - 44 * u
        drawPath(Path().apply { moveTo(cx - 15 * u, hy - 4 * u); lineTo(cx - 13 * u, hy - 21 * u); lineTo(cx - 3 * u, hy - 11 * u); close() }, fur)
        drawPath(Path().apply { moveTo(cx + 15 * u, hy - 4 * u); lineTo(cx + 13 * u, hy - 21 * u); lineTo(cx + 3 * u, hy - 11 * u); close() }, fur)
        drawPath(Path().apply { moveTo(cx - 12.5f * u, hy - 8 * u); lineTo(cx - 12 * u, hy - 16 * u); lineTo(cx - 6 * u, hy - 11 * u); close() }, Color(0xFFE9B4B8))
        drawPath(Path().apply { moveTo(cx + 12.5f * u, hy - 8 * u); lineTo(cx + 12 * u, hy - 16 * u); lineTo(cx + 6 * u, hy - 11 * u); close() }, Color(0xFFE9B4B8))
        drawOval(fur, Offset(cx - 16 * u, hy - 13 * u), Size(32 * u, 26 * u))
        // face
        drawOval(ink, Offset(cx - 9 * u, hy - 4 * u), Size(5 * u, 6 * u))
        drawOval(ink, Offset(cx + 4 * u, hy - 4 * u), Size(5 * u, 6 * u))
        drawCircle(Color.White, 1.1f * u, Offset(cx - 5.8f * u, hy - 2.2f * u))
        drawCircle(Color.White, 1.1f * u, Offset(cx + 7.2f * u, hy - 2.2f * u))
        drawCircle(Color(0x66F28B95), 2.6f * u, Offset(cx - 10 * u, hy + 4 * u))
        drawCircle(Color(0x66F28B95), 2.6f * u, Offset(cx + 10 * u, hy + 4 * u))
        drawPath(Path().apply { moveTo(cx - 1.6f * u, hy + 2.5f * u); lineTo(cx + 1.6f * u, hy + 2.5f * u); lineTo(cx, hy + 4.3f * u); close() }, Color(0xFFE48A94))
        drawPath(Path().apply { moveTo(cx - 3 * u, hy + 6 * u); quadraticTo(cx - 1.5f * u, hy + 7.5f * u, cx, hy + 5 * u); quadraticTo(cx + 1.5f * u, hy + 7.5f * u, cx + 3 * u, hy + 6 * u) },
            ink, style = Stroke(.7f * u, cap = StrokeCap.Round))
        for (s in listOf(-1f, 1f)) for (dy in listOf(-1f, 1.2f)) {
            drawLine(if (dark) Color(0x99FFFFFF) else Color(0x99FFFFFF), Offset(cx + s * 6 * u, hy + 4 * u + dy * u), Offset(cx + s * 17 * u, hy + 3 * u + dy * 2.2f * u), .45f * u)
        }
        // bowl with kibble
        val bx = cx + 40 * u
        for ((dx, dy) in listOf(-5f to -1f, 0f to -2.5f, 5f to -1f, -2.5f to 1f, 2.5f to 1f)) drawCircle(Color(0xFFC98B4E), 2.2f * u, Offset(bx + dx * u, floorY - 9 * u + dy * u))
        drawPath(Path().apply { moveTo(bx - 13 * u, floorY - 9 * u); cubicTo(bx - 11 * u, floorY + u, bx + 11 * u, floorY + u, bx + 13 * u, floorY - 9 * u); close() }, Color(0xFF2E6E62))
        drawLine(Color(0xFF3F8A7C), Offset(bx - 13 * u, floorY - 9 * u), Offset(bx + 13 * u, floorY - 9 * u), 1.4f * u, cap = StrokeCap.Round)
        }
    }
}
