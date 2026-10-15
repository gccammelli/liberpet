package io.github.gccammelli.liberpet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.gccammelli.liberpet.Cats
import io.github.gccammelli.liberpet.Device
import io.github.gccammelli.liberpet.LABEL_ENTITY
import io.github.gccammelli.liberpet.NO_CAT
import io.github.gccammelli.liberpet.Pet
import io.github.gccammelli.liberpet.Repo
import io.github.gccammelli.liberpet.UiState
import io.github.gccammelli.liberpet.cats
import io.github.gccammelli.liberpet.label
import io.github.gccammelli.liberpet.recordingsFolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The feeder's pets (all pets if none are linked): the choices when labelling. */
fun feederPets(d: Device, st: UiState): List<Pet> =
    st.home?.pets.orEmpty().let { all -> all.filter { d.serial in it.linkedSerials }.ifEmpty { all } }

private fun progress(c: Cats): String {
    val n = c.labels.values.sum()
    val right = if (n >= 4) " · ${(c.accuracy * 100).toInt()}% right" else ""
    return when (c.state) {
        "ready" -> "Knows your cats$right"
        "learning" -> if (n == 0) "Label a few photos so it can tell your cats apart" else "Learning · $n photo${if (n == 1) "" else "s"} labelled$right"
        else -> "Waiting for the first clips with a cat"
    }
}

/** On the feeder page, when the recognizer is running: how far it's got, and the way into labelling. */
@Composable
fun CatsCard(d: Device) {
    var cats by remember { mutableStateOf<Cats?>(null) }
    LaunchedEffect(d.serial) { cats = runCatching { Repo.ha?.cats() }.getOrNull() }
    val c = cats ?: return
    Card(padding = PaddingValues(horizontal = 24.dp, vertical = 18.dp), onClick = { Nav.go(Screen.Teach(d.serial)) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                T("Cat Recognition", 18.sp, weight = FontWeight.Medium)
                Gap(4.dp)
                T(progress(c), 13.sp, if (c.state == "ready") C.Green else C.Sub)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(24.dp), tint = C.Faint)
        }
    }
    Gap(12.dp)
}

/**
 * Labelling: one bowl picture at a time, "who's eating here?". Each answer goes to the recognizer through
 * Home Assistant; the next picture is the one it's now least sure about, until it can tell the cats apart.
 */
@Composable
fun TeachScreen(d: Device, st: UiState) {
    val pets = feederPets(d, st)
    var cats by remember { mutableStateOf<Cats?>(null) }
    var folder by remember { mutableStateOf<String?>(null) }
    var skipped by remember { mutableStateOf(setOf<String>()) }
    var saving by remember { mutableStateOf(false) }
    var noHelper by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        runCatching { folder = Repo.ha!!.recordingsFolder(); cats = Repo.ha!!.cats() }.onFailure { error = it.message }
    }
    // New clips bring new pictures: look again now and then while the screen is open.
    LaunchedEffect(Unit) { while (true) { delay(20_000); if (!saving) runCatching { Repo.ha!!.cats() }.getOrNull()?.let { cats = it } } }

    fun answer(photo: String, pet: String) = scope.launch {
        saving = true
        runCatching {
            val before = cats?.labels?.values?.sum() ?: 0
            if (!Repo.ha!!.label(photo, pet)) { noHelper = true; return@runCatching }
            // Wait for the recognizer to take it (it checks every second), then show what it wants next.
            repeat(12) {
                delay(700)
                val c = Repo.ha!!.cats() ?: return@repeat
                if (c.labels.values.sum() > before) { cats = c; return@runCatching }
            }
            error = "The recognizer didn't answer. Is it running?"
        }.onFailure { error = it.message }
        saving = false
    }

    Page("Teach LiberPet", { Nav.back() }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            val c = cats
            when {
                noHelper -> Note("One step in Home Assistant first",
                    "Add a text helper so LiberPet can pass on your answers: Settings › Devices & services › Helpers › Create helper › Text, " +
                        "named \"LiberPet label\" (entity $LABEL_ENTITY), maximum length 255.")
                error != null && c == null -> Note("Can't reach the recognizer", error!!)
                c == null || folder == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.Green) }
                else -> {
                    Progress(c, pets)
                    Gap(16.dp)
                    val photo = c.toLabel.firstOrNull { it.id !in skipped }
                    if (photo == null) Note(if (c.state == "ready") "All caught up" else "No photos to label right now",
                        "New ones come with the cats' next visits to the feeder.")
                    else {
                        Card(padding = PaddingValues(0.dp)) {
                            Box(Modifier.fillMaxWidth().heightIn(min = 240.dp).background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
                                val img = rememberHaImage("/media/local/$folder/${photo.path}", 900)
                                if (img != null) Image(img, "The ${photo.side} bowl", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                                else CircularProgressIndicator(color = Color.White)
                                if (saving) Box(Modifier.matchParentSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            }
                        }
                        Gap(16.dp)
                        T("Who's at the ${photo.side} bowl?", 18.sp, weight = FontWeight.Medium)
                        T("The bright side of the picture", 13.sp, C.Sub)
                        photo.guess?.let { g -> (if (g == NO_CAT) "no cat" else pets.firstOrNull { it.id == g }?.name)?.let {
                            Gap(4.dp); T("LiberPet thinks: $it", 13.sp, C.Sub)
                        } }
                        Gap(12.dp)
                        pets.forEach { p ->
                            Choice(enabled = !saving, onClick = { answer(photo.id, p.id) }) {
                                PetAvatar(p.avatar, p.name, 36.dp); Spacer(Modifier.width(14.dp)); T(p.name, 16.sp, weight = FontWeight.Medium)
                            }
                            Gap(8.dp)
                        }
                        Choice(enabled = !saving, onClick = { answer(photo.id, NO_CAT) }) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Pets, null, tint = C.Faint) }
                            Spacer(Modifier.width(14.dp)); T("No cat at this bowl", 16.sp)
                        }
                        Gap(8.dp)
                        T("Skip", 15.sp, C.Sub, align = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !saving) { skipped = skipped + photo.id }.padding(12.dp))
                        error?.let { T(it, 13.sp, C.Red, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(c: Cats, pets: List<Pet>) = Card(padding = PaddingValues(16.dp)) {
    T(progress(c), 15.sp, if (c.state == "ready") C.Green else C.Text, FontWeight.Medium)
    Gap(10.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        pets.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                PetAvatar(p.avatar, p.name, 22.dp); Spacer(Modifier.width(6.dp)); T("${c.labels[p.id] ?: 0}", 14.sp, C.Sub)
            }
        }
        T("No cat: ${c.labels[NO_CAT] ?: 0}", 14.sp, C.Sub)
    }
    if (c.state != "ready") { Gap(8.dp); T("It asks about the photos it's least sure of. About 10 per cat is usually enough.", 12.sp, C.Faint, lh = 16.sp) }
}

@Composable
private fun Choice(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Card).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { content() }

@Composable
private fun Note(title: String, body: String) = Card(padding = PaddingValues(20.dp)) {
    T(title, 16.sp, weight = FontWeight.Medium); Gap(6.dp); T(body, 14.sp, C.Sub, lh = 20.sp)
}
