package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.gccammelli.liberpet.LocalPlatform
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** "system", "light" or "dark"; the app's choice, saved by Repo. */
object ThemeMode {
    var mode by mutableStateOf("system")
    /** What's on screen now (set by [AppTheme]). */
    var dark by mutableStateOf(false)
}

/** Colours: light ones sampled from the Petlibro app; dark ones are LiberPet's own (Petlibro has no dark mode). */
object C {
    private fun c(light: Long, dark: Long) = Color(if (ThemeMode.dark) dark else light)
    val Bg get() = c(0xFFF5F5F5, 0xFF121212)
    val Card get() = c(0xFFFFFFFF, 0xFF1E1E1E)
    val Text get() = c(0xFF33383D, 0xFFE6E6E6)
    val Sub get() = c(0xFF999999, 0xFFA3A3A3)
    val Faint get() = c(0xFFAEAEAE, 0xFF7C7C7C)
    val Green get() = c(0xFF315800, 0xFF7FA84A)
    val Brown get() = c(0xFF966E5A, 0xFFC09078)
    val Tan get() = c(0xFFD6B79B, 0xFFC9A27E)
    val Red get() = c(0xFFF5494C, 0xFFFF6B6B)
    val RedBg get() = c(0xFFFFF4F4, 0xFF3A1E1F)
    /** Primary buttons, checked switches; [OnDark] is the text on them. */
    val Dark get() = c(0xFF2A2A2A, 0xFFE6E6E6)
    val OnDark get() = c(0xFFFFFFFF, 0xFF1A1A1A)
    val Thumb get() = c(0xFFFFFFFF, 0xFFBDBDBD)
    val Track get() = c(0xFFDBDBDB, 0xFF3A3A3A)
    val Track2 get() = c(0xFFE8E8E8, 0xFF333333)
    val Fill get() = c(0xFFF7F7F7, 0xFF262626)
    val Line get() = c(0xFFEDEDED, 0xFF2C2C2C)
    val Dot get() = c(0xFFBDBDBD, 0xFF555555)
    val Nav get() = c(0xFFF8F8F8, 0xFF1A1A1A)
    val HomeBg get() = c(0xFFECECEC, 0xFF161616)
    val Dialog get() = c(0xFFE6E8EA, 0xFF2A2A2A)
    val Blue get() = c(0xFF1677FF, 0xFF5AA2FF)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    ThemeMode.dark = when (ThemeMode.mode) { "light" -> false; "dark" -> true; else -> system }
    val p = LocalPlatform.current
    LaunchedEffect(ThemeMode.dark) { p.setDarkBars(ThemeMode.dark) }
    MaterialTheme(
        colorScheme = (if (ThemeMode.dark) darkColorScheme() else lightColorScheme())
            .copy(primary = C.Dark, onPrimary = C.OnDark, background = C.Bg, surface = C.Card, onSurface = C.Text, onBackground = C.Text, onSurfaceVariant = C.Sub),
        content = content,
    )
}

@Composable
fun T(text: String, size: TextUnit = 16.sp, color: Color = C.Text, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, align: TextAlign? = null, lh: TextUnit = TextUnit.Unspecified) =
    Text(text, modifier, color = color, style = TextStyle(fontSize = size, fontWeight = weight, lineHeight = lh), textAlign = align)

/** White screen with a centred title and a back chevron, as on Petlibro's detail pages. */
@Composable
fun Page(title: String, onBack: (() -> Unit)?, bg: Color = C.Bg, action: (@Composable () -> Unit)? = null, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(bg)) {
        Box(Modifier.fillMaxWidth().background(C.Card).statusBarsPadding().height(56.dp)) {
            if (onBack != null) BackChevron(onBack, Modifier.align(Alignment.CenterStart))
            T(title, 18.sp, weight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp), align = TextAlign.Center)
            if (action != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { action() }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (bottom != null) bottom()
    }
}

/** Back chevron plus a large bold title under it ("Schedule", "Settings", "Logs"). */
@Composable
fun BigTitlePage(title: String, onBack: () -> Unit, bg: Color = C.Bg, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxWidth().background(C.Card).statusBarsPadding()) {
            Box(Modifier.height(56.dp)) { BackChevron(onBack, Modifier.align(Alignment.CenterStart)) }
            T(title, 32.sp, weight = FontWeight.Bold, modifier = Modifier.padding(start = 26.dp, bottom = 14.dp))
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        if (bottom != null) bottom()
    }
}

@Composable
fun BackChevron(onBack: () -> Unit, modifier: Modifier = Modifier) =
    Box(modifier.size(56.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Filled.ArrowBackIos, "Back", Modifier.size(22.dp).padding(start = 4.dp), tint = C.Text)
    }

@Composable
fun Card(modifier: Modifier = Modifier, radius: Dp = 12.dp, padding: PaddingValues = PaddingValues(16.dp), onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(radius)).background(C.Card)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(padding), content = content)

/** A settings row: label, grey value, chevron. */
@Composable
fun NavRow(label: String, value: String? = null, labelColor: Color = C.Text, chevron: Boolean = true, leading: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().background(C.Card).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 24.dp, vertical = 22.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
        if (label.startsWith("*")) T("*", 17.sp, C.Red) // required field, as Petlibro marks it
        T(label.removePrefix("*"), 17.sp, labelColor, modifier = Modifier.weight(1f))
        if (value != null) T(value, 14.sp, C.Sub)
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = C.Faint)
    }

@Composable
fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().background(C.Card).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, 17.sp, modifier = Modifier.weight(1f))
        Toggle(checked, enabled, onChange)
    }

@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = Switch(checked, onChange, enabled = enabled,
    colors = SwitchDefaults.colors(checkedTrackColor = C.Dark, checkedThumbColor = C.OnDark, uncheckedTrackColor = C.Track2,
        uncheckedThumbColor = C.OnDark, uncheckedBorderColor = C.Track2, checkedBorderColor = C.Dark,
        disabledCheckedTrackColor = C.Dark.copy(alpha = .4f), disabledUncheckedTrackColor = C.Fill))

/** The grey gap between grouped white sections. */
@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))

@Composable
fun DarkButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Box(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).background(if (enabled) C.Dark else C.Track)
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        T(text, 17.sp, if (enabled) C.OnDark else C.Sub) // disabled: muted, but readable in both themes
    }

@Composable
fun OutlineButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) =
    Box(modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, color, RoundedCornerShape(8.dp)).background(C.Card)
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        T(text, 16.sp, color)
    }

/** Petlibro's bottom sheet: close X top right, an optional caps title, content, then a dark Confirm. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, title: String? = null, centeredTitle: Boolean = false, confirm: String? = "Confirm", onConfirm: () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = C.Card,
        dragHandle = null, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Box(Modifier.fillMaxWidth().height(64.dp)) {
                if (title != null && centeredTitle) T(title, 17.sp, weight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
                Icon(Icons.Default.Close, "Close", Modifier.align(Alignment.CenterEnd).size(28.dp).clickable(onClick = onDismiss), tint = C.Text)
            }
            if (title != null && !centeredTitle) T(title, 18.sp, weight = FontWeight.Medium, modifier = Modifier.padding(bottom = 12.dp))
            content()
            if (confirm != null) { Gap(24.dp); DarkButton(confirm, onClick = onConfirm); Gap(20.dp) }
        }
    }
}

/** A thin rounded progress bar (Status card). */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, track: Color = C.Line, height: Dp = 5.dp) =
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }

/** The open ring gauge (Hydration History / Water Intake): 270° arc with a gap at the bottom. */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier, stroke: Dp = 7.dp, track: Color = Color.Transparent, content: @Composable () -> Unit = {}) =
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(size.width - w, size.height - w)
            if (track != Color.Transparent) drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * fraction.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }

@Composable
fun SmallIcon(v: ImageVector, tint: Color = C.Text, size: Dp = 20.dp) = Icon(v, null, Modifier.size(size), tint = tint)

@Composable
fun RowScope.Fill() = Spacer(Modifier.weight(1f))

@Composable
fun Radio(selected: Boolean, label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().background(C.Card).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(18.dp).clip(CircleShape).border(1.dp, if (selected) C.Tan else C.Faint, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(C.Tan))
        }
        T(label, 16.sp, if (selected && enabled) C.Text else C.Faint)
    }
