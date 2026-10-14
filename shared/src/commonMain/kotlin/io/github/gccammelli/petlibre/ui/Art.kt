package io.github.gccammelli.petlibre.ui

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.petlibre.Device
import io.github.gccammelli.petlibre.decodeImage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes

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

/** Pet photo from its avatar URL (Petlibro's image host), cached for the session; initial on grey while loading. */
@Composable
fun PetAvatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier) {
    var img by remember(url) { mutableStateOf(url?.let { avatarCache[it] }) }
    LaunchedEffect(url) {
        if (url != null && img == null) img = runCatching { decodeImage(http.get(url).readRawBytes(), 256) }.getOrNull()?.also { avatarCache[url] = it }
    }
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFFE6E6E6)), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, name, Modifier.size(size), contentScale = ContentScale.Crop)
        else T(name.take(1), (size.value * .4f).sp, C.Sub)
    }
}

private val avatarCache = mutableStateMapOf<String, ImageBitmap>()
private val http by lazy { HttpClient() }

