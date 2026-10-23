package io.github.gccammelli.liberpet

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.ui.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiKitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val platform = object : PlatformUi {
        override val appVersion = "test"
        override fun openUrl(url: String) = Unit
        override fun toast(message: String) = Unit
        override fun setDarkBars(dark: Boolean) = Unit
        override fun pickImage(done: (ByteArray?) -> Unit) = done(null)
        override fun saveImage(jpeg: ByteArray, name: String, done: (Throwable?) -> Unit) = done(null)
    }
    private fun render(content: @Composable () -> Unit) {
        ThemeMode.mode = "system"
        compose.setContent { CompositionLocalProvider(LocalPlatform provides platform) { AppTheme(content) } }
        compose.waitForIdle()
        compose.runOnIdle {
            android.view.inspector.WindowInspector.getGlobalWindowViews().forEach { view ->
                if (view.width > 0 && view.height > 0) view.draw(Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)))
            }
        }
    }

    @Test fun textAndProgressPrimitivesRenderWithDefaultsAndExplicitStyles() {
        render {
            Column {
                T("Default text")
                T("Explicit text", 18.sp, C.Red, FontWeight.Bold, Modifier.fillMaxWidth(), TextAlign.End, 24.sp)
                Gap(); Gap(4.dp)
                SmallIcon(Icons.Default.Add)
                SmallIcon(Icons.Default.Add, C.Blue, 24.dp)
                Bar(-1f, C.Green)
                Bar(2f, C.Red, Modifier.width(100.dp), C.Track, 8.dp)
                Ring(.5f, C.Green, Modifier.size(64.dp))
                Ring(2f, C.Red, Modifier.size(64.dp), 5.dp, C.Track) { T("Complete") }
                Chart(emptyList(), emptyList())
            }
        }
        compose.onNodeWithText("Default text").assertExists()
        compose.onNodeWithText("Explicit text").assertExists()
        compose.onNodeWithText("Complete").assertExists()
    }

    @Test fun navigationRowsOnlyInvokeActionsForInteractiveRows() {
        var clicks = 0
        render {
            Column {
                NavRow("Read only", chevron = false)
                NavRow("*Editable", "Value", C.Red, true, { T("Leading") }) { clicks++ }
                Card { T("Static card") }
                Card(Modifier, 4.dp, PaddingValues(8.dp), { clicks++ }) { T("Interactive card") }
                BackChevron({ clicks++ })
                Radio(true, "Disabled selection", enabled = false) { clicks++ }
                Radio(false, "Choose", enabled = true) { clicks++ }
            }
        }
        compose.onNodeWithText("Read only").assertHasNoClickAction()
        compose.onNodeWithText("Editable").performClick()
        compose.onNodeWithText("Interactive card").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Disabled selection").performClick()
        assertEquals(3, clicks)
        compose.onNodeWithText("Choose").performClick()
        assertEquals(4, clicks)
    }

    @Test fun pageOptionalActionsAndBottomContentFollowConfiguration() {
        var configured by mutableStateOf(false)
        var backs = 0
        render {
            if (configured) Page("Configured", { backs++ }, C.Fill, { T("Action") }, { T("Bottom") }) { T("Body") }
            else Page("Plain", null) { T("Body") }
        }
        compose.onNodeWithText("Body").assertExists()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
        compose.runOnIdle { configured = true }
        compose.onNodeWithText("Action").assertExists(); compose.onNodeWithText("Bottom").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun sheetSupportsNoTitleCenteredTitleAndExplicitConfirmation() {
        var mode by mutableStateOf(0)
        var confirmations = 0
        render {
            key(mode) {
                when (mode) {
                    0 -> Sheet({ mode++ }) { T("Body") }
                    1 -> Sheet({ mode++ }, "Centered", true, "Apply", { confirmations++; mode++ }) { T("Body") }
                    else -> BigTitlePage("Returned", {}, C.Fill, { T("Footer") }) { T("Body") }
                }
            }
        }
        compose.onNodeWithText("Body").assertExists()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Centered").assertExists()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithText("Returned").assertExists()
        compose.onNodeWithText("Footer").assertExists()
        assertEquals(1, confirmations)
    }
}
