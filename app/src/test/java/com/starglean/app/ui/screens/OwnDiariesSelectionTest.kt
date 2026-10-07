package com.starglean.app.ui.screens

import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.stars.StarCollectionRules
import com.starglean.app.core.stars.StarCollectionState
import org.junit.Assert.*
import org.junit.Test

class OwnDiariesSelectionTest {
    private val firstId = "own-12345678-1234-4234-8234-123456789abc"
    private val secondId = "own-12345678-1234-4234-8234-123456789abd"
    private fun entry(id: String) = OwnDiaryRecord(
        DiaryDemo(id, "My exact title", "My writing stays exactly as I saved it.", "16 Sep · 10:00",
            DiaryWeatherSnapshot.Unavailable, "No location saved", PaperStyle.WarmWhite, DiaryFont.Clear),
        place = null, savedAtMillis = 100L,
    )

    @Test fun noFilterPreservesTheActualRecordsAndTheirOrder() {
        val records = listOf(entry(secondId), entry(firstId))
        assertSame(records, filterOwnDiaries(records, null))
    }

    @Test fun searchAndMonthIntersectWithoutChangingSavedRecords() {
        val first = entry(firstId).copy(savedAtMillis = java.time.Instant.parse("2026-09-10T12:00:00Z").toEpochMilli())
        val second = entry(secondId).copy(savedAtMillis = java.time.Instant.parse("2026-08-10T12:00:00Z").toEpochMilli())
        val records = listOf(first, second)
        assertEquals(listOf(first), filterOwnDiaries(records, null, "EXACT", "2026-09", java.time.ZoneId.of("UTC")))
        assertTrue(filterOwnDiaries(records, setOf(secondId), "exact", "2026-09", java.time.ZoneId.of("UTC")).isEmpty())
        assertEquals(records, filterOwnDiaries(records, null, "writing"))
    }

    @Test fun explicitEmptySelectionDoesNotExposeAllEntries() {
        assertTrue(filterOwnDiaries(listOf(entry(firstId)), emptySet()).isEmpty())
    }

    @Test fun selectedRecordRetainsItsExactContentWeatherAndIdentity() {
        val first = entry(firstId)
        val selected = filterOwnDiaries(listOf(first, entry(secondId)), setOf(firstId))
        assertEquals(listOf(first), selected)
        assertSame(first, selected.single())
    }

    @Test fun missingOrDecorativeStarIdsNeverProduceSampleEntries() {
        assertTrue(filterOwnDiaries(listOf(entry(firstId)), setOf("demo:lyra-1:diary:1", "missing")).isEmpty())
    }

    @Test fun starLabelUsesTheVerifiedRealPlacementNotTemplateExampleProgress() {
        val initial = StarCollectionState.initial()
        val waiting = "Waiting for a constellation"
        assertEquals(waiting, ownDiaryStarLabel(initial, firstId))
        val target = StarCollectionRules.setTarget(initial, "lyra-1")
        val placed = StarCollectionRules.placeDiary(target, firstId, setOf(firstId)).state
        assertEquals("Lyra 1 · Star 1", ownDiaryStarLabel(placed, firstId))
        assertEquals(waiting, ownDiaryStarLabel(placed, secondId))
    }

    @Test fun secondInstanceKeepsItsOwnOrdinalAndStarIndex() {
        val second = StarCollectionRules.createInstance(StarCollectionState.initial(), "lyra-1")
        val firstPlaced = StarCollectionRules.placeDiary(second, firstId, setOf(firstId, secondId), "lyra-2").state
        val both = StarCollectionRules.placeDiary(firstPlaced, secondId, setOf(firstId, secondId), "lyra-2").state
        assertEquals("Lyra 2 · Star 2", ownDiaryStarLabel(both, secondId))
    }

    @Test fun deleteWarningStatesIrreversibilityWithoutInventingAStar() {
        val message = ownDiaryDeleteMessage(StarCollectionState.initial(), firstId)
        assertTrue(message.contains("cannot be undone"))
        assertFalse(message.contains("star will go dark"))
        assertTrue(message.contains("Photos used only by this entry will be removed"))
        assertTrue(message.contains("still used by another saved entry or draft will be kept"))
    }

    @Test fun deleteWarningExplainsStarAndHomeWithdrawalForACompletedHomeInstance() {
        var state = StarCollectionState.initial()
        val total = state.galleryItems().single { it.id == "lyra-1" }.totalStars
        repeat(total) { index ->
            val id = if (index == 0) firstId else "own-00000000-0000-0000-0000-${index.toString().padStart(12, '0')}"
            val saved = state.placements.map { it.diaryId }.toSet() + id
            state = StarCollectionRules.placeDiary(state, id, saved, "lyra-1").state
        }
        state = StarCollectionRules.setHome(state, setOf("lyra-1"))
        val message = ownDiaryDeleteMessage(state, firstId)
        assertTrue(message.contains("star will go dark"))
        assertTrue(message.contains("Lyra 1"))
        assertTrue(message.contains("removed from your home sky"))
    }
}
