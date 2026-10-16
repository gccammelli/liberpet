package io.github.gccammelli.liberpet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * LiberPet's optional parts. Each one is switched on in Settings (all off by default) and its screens check [on].
 * To add one: an entry here, then `if (Feature.X.on()) …` where it shows. To remove one: delete the entry and its
 * screens; the settings list and storage follow by themselves. [needs] tells people what has to be set up first.
 */
enum class Feature(val title: String, val about: String, val needs: String?, val group: String, val oldKey: String? = null) {
    CARE_TAB("Care tab", "Each pet's profile, weight and water, and Cat Insights if that's on.", null, "Tabs", oldKey = "show_care"),
    RECORDINGS("Recordings", "Play the feeder camera's recorded clips, and record a clip on demand.",
        "LiberPet's recorder on a home server (tools/recorder)", "Camera"),
    CAT_RECOGNITION("Cat Recognition", "Teach LiberPet your cats from short clips, so recordings say who ate at each bowl.",
        "Recordings, plus LiberPet's recognizer (tools/recognizer)", "Beta", oldKey = "beta_recognition"),
    CAT_INSIGHTS("Cat Insights", "Each cat's visits, time eating and favourite bowl, in the Care tab.",
        "Cat Recognition", "Beta", oldKey = "beta_insights");

    internal val key get() = "feature.${name.lowercase()}"
    internal val flow = MutableStateFlow(false)

    /** Whether it's switched on (recomposes when it changes). */
    @Composable
    fun on(): Boolean = flow.collectAsState().value

    /** The same, outside composition. */
    val isOn get() = flow.value

    companion object {
        /** Reads the switches; settings from before the registry (beta_recognition, show_care…) carry over. */
        internal fun load(settings: KeyValue) = entries.forEach { f ->
            f.flow.value = (settings.getString(f.key) ?: f.oldKey?.let { settings.getString(it) }) == "true"
        }

        internal fun set(settings: KeyValue, f: Feature, on: Boolean) {
            f.flow.value = on
            settings.putString(f.key, on.toString())
        }
    }
}
