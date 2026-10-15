package io.github.gccammelli.liberpet

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/** Small key-value settings store (SharedPreferences on Android). */
interface KeyValue {
    fun getString(key: String): String?
    fun putString(key: String, value: String?) // null removes the key
    fun clear()
}

/** The Home Assistant token, encrypted with the Android Keystore. */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String?) // null removes
}

/** Private app files (the Home header picture). */
interface AppFiles {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun delete(name: String)
}

class PlatformStorage(val settings: KeyValue, val secrets: SecretStore, val files: AppFiles)

/** UI-side platform actions, provided by MainActivity via [LocalPlatform]. */
interface PlatformUi {
    val appVersion: String
    fun openUrl(url: String)
    fun toast(message: String)
    /** Light or dark status/navigation bar icons to match the app's theme. */
    fun setDarkBars(dark: Boolean)
    /** Let the user pick a photo (system photo picker); [done] gets its bytes, or null if cancelled. */
    fun pickImage(done: (ByteArray?) -> Unit)
}

val LocalPlatform = staticCompositionLocalOf<PlatformUi> { error("No platform provided") }

/** Decodes an image, downscaled so its longest side is at most [maxSide] pixels. */
expect fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap?

/** Plays an HLS or MP4 address (from Home Assistant). [onError] gets a short message if playback fails. */
@androidx.compose.runtime.Composable
expect fun VideoPlayer(url: String, muted: Boolean, modifier: androidx.compose.ui.Modifier, onError: (String) -> Unit)
