package io.github.gccammelli.petlibre

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

class PlatformStorage(val settings: KeyValue, val secrets: SecretStore)

/** UI-side platform actions, provided by MainActivity via [LocalPlatform]. */
interface PlatformUi {
    val appVersion: String
    fun openUrl(url: String)
    fun toast(message: String)
}

val LocalPlatform = staticCompositionLocalOf<PlatformUi> { error("No platform provided") }

/** Decodes an image, downscaled so its longest side is at most [maxSide] pixels. */
expect fun decodeImage(bytes: ByteArray, maxSide: Int): ImageBitmap?
