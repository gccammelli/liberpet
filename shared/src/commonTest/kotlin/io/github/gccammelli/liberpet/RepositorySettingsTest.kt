package io.github.gccammelli.liberpet

import kotlin.test.*

class RepositorySettingsTest {
    private class Settings : KeyValue {
        val data = mutableMapOf<String, String>()
        override fun getString(key: String) = data[key]
        override fun putString(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun clear() = data.clear()
    }

    @Test fun featureMigrationPrefersNewKeysAndPersistsExplicitChoices() {
        val settings = Settings()
        try {
            Feature.load(settings)
            assertTrue(Feature.entries.none { it.isOn })
            settings.putString("show_care", "true")
            settings.putString("beta_recognition", "true")
            settings.putString("feature.cat_recognition", "false")
            Feature.load(settings)
            assertTrue(Feature.CARE_TAB.isOn)
            assertFalse(Feature.CAT_RECOGNITION.isOn)
            Feature.set(settings, Feature.CARE_TAB, false)
            Feature.load(settings)
            assertFalse(Feature.CARE_TAB.isOn)
            assertEquals("false", settings.getString("feature.care_tab"))
            Feature.set(settings, Feature.CAT_INSIGHTS, true)
            assertTrue(Feature.CAT_INSIGHTS.isOn)
        } finally { Feature.load(Settings()) }
    }

    @Test fun orderCameraHeaderAndLogoutPreserveUserChoices() {
        val settings = Settings()
        val tokens = mutableMapOf<String, String>()
        val files = mutableMapOf<String, ByteArray>()
        Repo.init(PlatformStorage(settings, object : SecretStore {
            override fun get(key: String) = tokens[key]
            override fun set(key: String, value: String?) { if (value == null) tokens.remove(key) else tokens[key] = value }
        }, object : AppFiles {
            override fun read(name: String) = files[name]
            override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
            override fun delete(name: String) { files.remove(name) }
        }))
        fun device(serial: String, room: String? = "Kitchen") = Device(serial, serial, serial, "PLAF", room, null, emptyMap())
        val a = device("a"); val b = device("b"); val c = device("c")
        Repo.saveOrder(listOf("b", "a"))
        assertEquals(listOf(b, a, c), Repo.ordered(listOf(a, c, b)))
        val camera = Camera("camera.kitchen", "Kitchen", "Kitchen")
        val other = Camera("camera.hall", "Hall", "Hall")
        fun home(vararg cams: Camera) = Home(emptyList(), emptyList(), null, cams.toList())
        assertEquals(camera, Repo.cameraFor(a, home(camera, other)))
        assertNull(Repo.cameraFor(device("no-room", null), home(camera)))
        assertNull(Repo.cameraFor(a, home(camera, camera.copy(id = "camera.second"))))
        Repo.setCamera(a, "")
        assertNull(Repo.cameraFor(a, home(camera)))
        Repo.setCamera(a, other.id)
        assertEquals(other, Repo.cameraFor(a, home(camera, other)))
        Repo.setCamera(a, "camera.removed")
        assertEquals(camera, Repo.cameraFor(a, home(camera)))
        Repo.setCamera(a, null)
        assertEquals(camera, Repo.cameraFor(a, home(camera)))
        val picture = byteArrayOf(1, 2, 3)
        Repo.setHeader(picture)
        assertContentEquals(picture, Repo.header.value)
        assertContentEquals(picture, files["header.img"])
        Repo.setHeader(null)
        assertNull(Repo.header.value); assertFalse(files.containsKey("header.img"))
        Repo.setFeature(Feature.CARE_TAB, true)
        Repo.setTheme("dark")
        tokens["ha_token"] = "saved-token"
        Repo.logout()
        assertFalse(Repo.connected)
        assertNull(tokens["ha_token"])
        assertEquals(mapOf("theme" to "dark"), settings.data)
        assertFalse(Feature.CARE_TAB.isOn)
        assertEquals(UiState(), Repo.state.value)
    }
}
