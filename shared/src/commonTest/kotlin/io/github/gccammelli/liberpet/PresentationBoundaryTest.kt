package io.github.gccammelli.liberpet

import io.github.gccammelli.liberpet.ui.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class PresentationBoundaryTest {
    private fun entity(state: String, attrs: String = "{}") = Entity("sensor.test", state, json.parseToJsonElement(attrs).jsonObject)
    private fun device(model: String = "PLWF105", entities: Map<String, Entity> = emptyMap()) = Device("id", "serial", "Device", model, null, null, entities)

    @Test fun fountainAlertsRespectThresholdsAndNeverApplyWaterAlertsToFeeders() {
        assertTrue(device().irregularities().isEmpty())
        val faults = mapOf("online" to entity("off"), "remaining_filter_days" to entity("0"), "remaining_cleaning_days" to entity("-1"), "weight_percent" to entity("19"))
        assertEquals(listOf("Offline", "Filter", "Cleaning", "Water"), device(entities = faults).irregularities())
        assertEquals(listOf("Offline", "Filter", "Cleaning"), device("PLAF203", faults).irregularities())
        assertTrue(device(entities = mapOf("weight_percent" to entity("20"))).irregularities().isEmpty())
    }

    @Test fun agesUseReadableGrammarAndKeepMissingAgeExplicit() {
        val pet = Pet("ha", "1", "Cat", emptyMap())
        assertEquals("--", pet.ageText())
        for ((attrs, expected) in listOf("""{"years":0}""" to "0 months old", """{"years":0,"months":8}""" to "8 months old", """{"years":1}""" to "1 year old", """{"years":2}""" to "2 years old")) {
            assertEquals(expected, pet.copy(e = mapOf("age" to entity("on", attrs))).ageText())
        }
    }

    @Test fun petLinksAreScopedToTheDeviceAndRecognitionFallsBackToAllPets() {
        val pet = Pet("ha", "1", "Cat", mapOf("boundDeviceNums" to entity("on", """{"serial":true}""")))
        val other = Pet("ha2", "2", "Other", emptyMap())
        val state = UiState(home = Home(emptyList(), listOf(pet, other), null))
        assertEquals(listOf(pet), linkedPets(device(), state))
        assertEquals(listOf(pet), feederPets(device(), state))
        assertEquals(listOf(pet, other), feederPets(device().copy(serial = "other"), state))
        assertTrue(feederPets(device(), UiState()).isEmpty()); assertTrue(linkedPets(device(), UiState()).isEmpty())
        assertEquals(0.0, goal(device(), state)); assertEquals("01m 05s", mmss(65.0))
    }

    @Test fun nextFeedUsesPlanWeightUnlessTheIntegrationReportsAnUpcomingWeight() {
        assertEquals("--", nextFeeding(device("PLAF203")))
        val schedule = entity("on", """{"schedule":[{"id":1,"time":"08:00","enabled":true,"amount_g":10},{"id":2,"time":"09:00","enabled":false,"amount_g":20}]}""")
        val d = device("PLAF203", mapOf("feeding_schedule" to schedule)).copy(tz = zone)
        assertEquals("08:00 (10 g)", nextFeeding(d))
        assertEquals("08:00 (30 g)", nextFeeding(d.copy(e = d.e + ("next_feed_quantity_weight" to entity("30")))))
        assertEquals("08:00 (10 g)", nextFeeding(d.copy(e = d.e + ("next_feed_quantity_weight" to entity("0")))))
    }

    @Test fun chartRangesAggregateOnlyTheChosenPeriodAndIgnoreUnparseableLogAmounts() {
        val instant = Instant.parse("2026-01-15T12:00:00Z")
        val today = instant.local().date
        val daily = mapOf(today to 12.0, LocalDate(2025, 1, 1) to 99.0, LocalDate(2026, 2, 1) to 7.0)
        val drinks = listOf(LogEntry(instant, "Your pet(s) drank 5 ml water"), LogEntry(instant, "Unknown amount"), LogEntry(instant, "drank .. ml"), LogEntry(Instant.parse("2026-01-01T12:00:00Z"), "drank 99 ml"))
        val hourly = historyBars(0, today, daily, drinks)
        assertEquals(5.0, hourly.first.sum()); assertEquals(24, hourly.first.size)
        assertEquals(5.0, hourly.first[instant.local().hour])
        assertEquals(12.0, historyBars(1, today, daily, drinks).first.sum())
        assertEquals(12.0, historyBars(2, today, daily, drinks).first.sum())
        assertEquals(31, historyBars(2, today, daily, drinks).first.size)
        val yearly = historyBars(3, today, daily, drinks).first
        assertEquals(12.0, yearly[0]); assertEquals(7.0, yearly[1]); assertEquals(19.0, yearly.sum())
    }

    @Test fun feedingStatusDistinguishesPastPendingFailedAndFuturePlans() {
        val plan = Plan(1, "08:30", 10.0, 1, true, emptyList(), "", null)
        for ((state, expected) in listOf("dispensed" to "Completed", "skipped" to "Skipped", "to_be_skipped" to "Skipped", "pending" to null, "error" to "Failed", null to "Failed")) {
            assertEquals(expected, pastStatus(plan.copy(todayState = state), 9 * 60))
        }
        assertNull(pastStatus(plan.copy(todayState = "dispensed"), 8 * 60))
        assertEquals("Completed", pastStatus(plan.copy(todayState = "dispensed"), 8 * 60 + 30))
    }

    @Test fun clipNamesOnlyUseKnownPetsAndKeepLeftAndRightIdentitiesSeparate() {
        val clip = Clip("2026-01-01", "08:30:00", listOf("motion"), 30, "media/clip.mp4", null)
        val left = Pet("a", "1", "First", emptyMap()); val right = Pet("b", "2", "Second", emptyMap())
        val state = UiState(home = Home(emptyList(), listOf(left, right), null))
        fun cats(sides: Map<String, String>) = Cats("ready", 1.0, emptyMap(), emptyList(), mapOf("2026-01-01/clip" to sides))
        assertNull(whoAte(clip, null, state))
        assertNull(whoAte(clip, cats(emptyMap()), state))
        assertEquals("First", whoAte(clip, cats(mapOf("left" to "1")), state))
        assertEquals("Second", whoAte(clip, cats(mapOf("right" to "2")), state))
        assertEquals("First", whoAte(clip, cats(mapOf("left" to "1", "right" to "1")), state))
        assertEquals("Left: First · Right: Second", whoAte(clip, cats(mapOf("left" to "1", "right" to "2")), state))
        assertNull(whoAte(clip, cats(mapOf("left" to "none", "right" to "removed")), state))
        assertNull(whoAte(clip, cats(mapOf("left" to "1")), UiState()))
        assertNull(whoAte(clip, cats(mapOf("left" to "1")), state.copy(home = state.home!!.copy(pets = emptyList()))))
    }
}
