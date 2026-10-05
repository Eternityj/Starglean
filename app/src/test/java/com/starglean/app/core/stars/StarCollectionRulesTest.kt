package com.starglean.app.core.stars

import com.starglean.app.core.model.GallerySamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StarCollectionRulesTest {
    @Test fun initialCollectionHasEightEmptyTypesWithoutASampleTargetOrHomeSelection() {
        val state = StarCollectionState.initial()
        assertEquals(8, state.instances.size)
        assertEquals(GallerySamples.items.map { it.id }, state.instances.map { it.id })
        assertTrue(state.instances.all { it.id == it.templateId && it.ordinal == 1 })
        assertNull(state.targetId)
        assertTrue(state.homeIds.isEmpty())
        assertTrue(state.placements.isEmpty())
        assertTrue(state.galleryItems().all { it.litStars == 0 && !it.completed && !it.shownOnHome })
        // Reading the real templates does not mutate fixtures used by the explicit demo.
        assertTrue(GallerySamples.items.any { it.completed && it.shownOnHome })
    }

    @Test fun realTemplatesRetainEveryApprovedPointLinkAndIllustration() {
        StarConstellationTemplates.items.zip(GallerySamples.items).forEach { (template, sample) ->
            assertEquals(sample.points, template.points)
            assertEquals(sample.links, template.links)
            assertEquals(sample.name, template.name)
            assertEquals(sample.figure, template.figure)
            assertEquals(sample.geometryIndex, template.geometryIndex)
            assertEquals(0, template.litStars)
            assertFalse(template.shownOnHome)
        }
    }

    @Test fun missingTargetLeavesASavedEntryUnplacedWithoutMutatingTheCollection() {
        val state = StarCollectionState.initial()
        val result = StarCollectionRules.placeDiary(state, id(1), setOf(id(1)))
        assertSame(state, result.state)
        assertNull(result.placement)
        assertFalse(result.newlyPlaced)
        assertFalse(result.completed)
    }

    @Test fun onlyRealSavedEntryIdentitiesAreAcceptedEvenWithoutATarget() {
        val state = StarCollectionState.initial()
        expectFailure { StarCollectionRules.placeDiary(state, "demo-own-1", setOf("demo-own-1")) }
        expectFailure { StarCollectionRules.placeDiary(state, id(1), emptySet()) }
        expectFailure { StarCollectionRules.placeDiary(state, "${id(1)} ", setOf("${id(1)} ")) }
        expectFailure { StarCollectionRules.placeDiary(state, "own-INVALID", setOf("own-INVALID")) }
    }

    @Test fun nextAvailablePointUsesTemplateIndexOrderAndDoesNotChangeTheTarget() {
        val state = StarCollectionRules.setTarget(StarCollectionState.initial(), "cygnus-1")
        val first = StarCollectionRules.placeDiary(state, id(1), saved(1, 2))
        val second = StarCollectionRules.placeDiary(first.state, id(2), saved(1, 2))
        assertEquals(DiaryStarPlacement(id(1), "cygnus-1", 0), first.placement)
        assertEquals(DiaryStarPlacement(id(2), "cygnus-1", 1), second.placement)
        assertEquals(id(1), second.state.diaryAt("cygnus-1", 0))
        assertEquals(id(2), second.state.diaryAt("cygnus-1", 1))
        assertNull(second.state.diaryAt("cygnus-1", 2))
        assertEquals("cygnus-1", second.state.targetId)
        assertTrue(second.newlyPlaced)
        assertFalse(second.completed)
    }

    @Test fun anExplicitInstanceDoesNotChangeTheChosenTarget() {
        val state = StarCollectionRules.setTarget(StarCollectionState.initial(), "lyra-1")
        val result = StarCollectionRules.placeDiary(state, id(1), saved(1), "orion-1")
        assertEquals("orion-1", result.placement?.instanceId)
        assertEquals("lyra-1", result.state.targetId)
        expectFailure { StarCollectionRules.placeDiary(state, id(1), saved(1), "unknown") }
    }

    @Test fun retryNeverMovesAnEntryAfterTargetOrExplicitDestinationChanges() {
        val first = StarCollectionRules.placeDiary(StarCollectionState.initial(), id(1), saved(1), "cygnus-1")
        val switched = StarCollectionRules.setTarget(first.state, "orion-1")
        val retry = StarCollectionRules.placeDiary(switched, id(1), saved(1), "lyra-1")
        assertSame(switched, retry.state)
        assertEquals(first.placement, retry.placement)
        assertEquals(1, retry.state.placements.size)
        assertFalse(retry.newlyPlaced)
    }

    @Test fun fillingAnInstanceDoesNotAutomaticallyPlaceItOnHomeOrPickAnotherTarget() {
        val full = fill(StarCollectionRules.setTarget(StarCollectionState.initial(), "lyra-1"), "lyra-1")
        assertEquals("lyra-1", full.targetId)
        assertTrue(full.galleryItems().single { it.id == "lyra-1" }.completed)
        assertTrue(full.homeIds.isEmpty())
        val retry = StarCollectionRules.placeDiary(full, id(1), full.placements.map { it.diaryId }.toSet())
        assertTrue(retry.completed)
        assertFalse(retry.newlyPlaced)
        val pending = StarCollectionRules.placeDiary(full, id(100), full.placements.map { it.diaryId }.toSet() + id(100))
        assertSame(full, pending.state)
        assertNull(pending.placement)
        assertFalse(pending.completed)
        assertFalse(pending.newlyPlaced)
    }

    @Test fun completionIsOnlyTrueOnTheFinalNewPointThenStaysTrueForAnIdempotentRetry() {
        var state = StarCollectionState.initial()
        val total = requireNotNull(StarConstellationTemplates.find("lyra-1")).totalStars
        repeat(total) { index ->
            val result = StarCollectionRules.placeDiary(state, id(index + 1), saved(*(1..total).toList().toIntArray()), "lyra-1")
            assertEquals(index == total - 1, result.completed)
            assertTrue(result.newlyPlaced)
            state = result.state
        }
    }

    @Test fun targetMayBeClearedButCannotBeUnknownOrAlreadyCompleted() {
        val initial = StarCollectionState.initial()
        assertSame(initial, StarCollectionRules.setTarget(initial, null))
        expectFailure { StarCollectionRules.setTarget(initial, "bad") }
        val selected = StarCollectionRules.setTarget(initial, "cygnus-1")
        assertSame(selected, StarCollectionRules.setTarget(selected, "cygnus-1"))
        assertNull(StarCollectionRules.setTarget(selected, null).targetId)
        expectFailure { StarCollectionRules.setTarget(fill(initial, "lyra-1"), "lyra-1") }
    }

    @Test fun newInstancesHaveStableContiguousIdsAndDoNotChangeExistingSelections() {
        val initial = StarCollectionRules.setTarget(StarCollectionState.initial(), "orion-1")
        var state = initial
        repeat(4) { state = StarCollectionRules.createInstance(state, "cygnus-1") }
        assertEquals(listOf(1, 2, 3, 4, 5), state.instances.filter { it.templateId == "cygnus-1" }.map { it.ordinal })
        assertEquals(listOf("cygnus-2", "cygnus-3", "cygnus-4", "cygnus-5"), state.instances.drop(8).map { it.id })
        assertEquals(initial.instances, state.instances.take(8))
        assertEquals("orion-1", state.targetId)
        assertTrue(state.homeIds.isEmpty())
        assertTrue(state.placements.isEmpty())
        expectFailure { StarCollectionRules.createInstance(state, "cygnus-1") }
        expectFailure { StarCollectionRules.createInstance(state, "cygnus") }
        expectFailure { StarCollectionRules.createInstance(state, "demo:cygnus-1") }
    }

    @Test fun completedAndUnfinishedInstancesBothCountTowardsTheFiveInstanceLimit() {
        var state = fill(StarCollectionState.initial(), "lyra-1")
        repeat(4) { state = StarCollectionRules.createInstance(state, "lyra-1") }
        expectFailure { StarCollectionRules.createInstance(state, "lyra-1") }
        assertEquals(5, state.instances.count { it.templateId == "lyra-1" })
    }

    @Test fun repeatedTypesKeepIndependentPointMappingsAndProgress() {
        var state = StarCollectionRules.createInstance(StarCollectionState.initial(), "lyra-1")
        state = StarCollectionRules.placeDiary(state, id(1), saved(1, 2), "lyra-1").state
        state = StarCollectionRules.placeDiary(state, id(2), saved(1, 2), "lyra-2").state
        assertEquals(id(1), state.diaryAt("lyra-1", 0))
        assertEquals(id(2), state.diaryAt("lyra-2", 0))
        assertEquals(1, state.galleryItems().single { it.id == "lyra-1" }.litStars)
        assertEquals(2, state.galleryItems().single { it.id == "lyra-2" }.ordinal)
        assertEquals(1, state.galleryItems().single { it.id == "lyra-2" }.litStars)
    }

    @Test fun homeSelectionAcceptsZeroThroughEightCompletedInstancesButNeverReplacesOne() {
        var state = StarCollectionRules.createInstance(StarCollectionState.initial(), "lyra-1")
        state.instances.forEach { state = fill(state, it.id) }
        val firstEight = state.instances.take(8).map { it.id }.toSet()
        val selected = StarCollectionRules.setHome(state, firstEight)
        assertEquals(8, selected.galleryItems().count { it.shownOnHome })
        expectFailure { StarCollectionRules.setHome(selected, firstEight + "lyra-2") }
        assertEquals(firstEight, selected.homeIds)
        val cleared = StarCollectionRules.setHome(selected, emptySet())
        assertTrue(cleared.homeIds.isEmpty())
        assertEquals(selected.instances, cleared.instances)
        assertEquals(selected.placements, cleared.placements)
        assertTrue(cleared.galleryItems().all { it.completed && !it.shownOnHome })
    }

    @Test fun homeOrderUsesCollectionOrderRatherThanTheCallersSetOrder() {
        var state = fill(StarCollectionState.initial(), "orion-1")
        state = fill(state, "cygnus-1")
        state = StarCollectionRules.setHome(state, linkedSetOf("orion-1", "cygnus-1"))
        assertEquals(listOf("cygnus-1", "orion-1"), state.galleryItems().filter { it.shownOnHome }.map { it.id })
    }

    @Test fun incompleteOrMissingInstancesCannotEnterHome() {
        val state = StarCollectionState.initial()
        expectFailure { StarCollectionRules.setHome(state, setOf("cygnus-1")) }
        expectFailure { StarCollectionRules.setHome(state, setOf("missing")) }
        assertSame(state, StarCollectionRules.setHome(state, emptySet()))
    }

    @Test fun holesAreFilledWithoutRenumberingExistingStars() {
        // The codec supports stable holes for a future coordinated deletion feature.
        val state = StarCollectionState.initial().copy(placements = listOf(DiaryStarPlacement(id(1), "lyra-1", 3)))
        val result = StarCollectionRules.placeDiary(state, id(2), saved(1, 2), "lyra-1")
        assertEquals(0, result.placement?.starIndex)
        assertEquals(id(1), result.state.diaryAt("lyra-1", 3))
        assertNull(result.state.diaryAt("lyra-1", 1))
    }

    @Test fun removingADiaryLeavesItsExactHoleAndNeverMovesOrDeletesAnInstance() {
        var state = StarCollectionRules.createInstance(StarCollectionState.initial(), "lyra-1")
        state = StarCollectionRules.placeDiary(state, id(1), saved(1, 2, 3), "lyra-2").state
        state = StarCollectionRules.placeDiary(state, id(2), saved(1, 2, 3), "lyra-2").state
        state = StarCollectionRules.placeDiary(state, id(3), saved(1, 2, 3), "lyra-2").state
        val instances = state.instances
        val result = StarCollectionRules.removeDiary(state, id(2))
        assertEquals(DiaryStarPlacement(id(2), "lyra-2", 1), result.placement)
        assertNull(result.state.diaryAt("lyra-2", 1))
        assertEquals(id(1), result.state.diaryAt("lyra-2", 0))
        assertEquals(id(3), result.state.diaryAt("lyra-2", 2))
        assertEquals(instances, result.state.instances)
        assertSame(result.state, StarCollectionRules.removeDiary(result.state, id(2)).state)
    }

    @Test fun removingFromACompletedHomeConstellationWithdrawsOnlyThatConstellation() {
        var state = fill(StarCollectionState.initial(), "lyra-1")
        state = fill(state, "orion-1")
        state = StarCollectionRules.setHome(state, setOf("lyra-1", "orion-1"))
        val target = requireNotNull(state.placements.firstOrNull { it.instanceId == "lyra-1" })
        val result = StarCollectionRules.removeDiary(state, target.diaryId)
        assertTrue(result.removedFromHome)
        assertEquals(setOf("orion-1"), result.state.homeIds)
        assertFalse(StarCollectionRules.isComplete(result.state, "lyra-1"))
        assertTrue(StarCollectionRules.isComplete(result.state, "orion-1"))
    }

    @Test fun duplicateDiaryOrPointIsRejected() {
        val state = StarCollectionState.initial()
        val first = DiaryStarPlacement(id(1), "lyra-1", 0)
        expectFailure { state.copy(placements = listOf(first, first.copy(instanceId = "cygnus-1"))) }
        expectFailure { state.copy(placements = listOf(first, first.copy(diaryId = id(2)))) }
    }

    @Test fun invalidSlotsDemoEntriesUnknownTargetsAndIncompleteHomeAreRejected() {
        val state = StarCollectionState.initial()
        listOf(-1, 5, Int.MAX_VALUE).forEach { index ->
            expectFailure { state.copy(placements = listOf(DiaryStarPlacement(id(1), "lyra-1", index))) }
        }
        expectFailure { state.copy(placements = listOf(DiaryStarPlacement("demo-own-1", "lyra-1", 0))) }
        expectFailure { state.copy(placements = listOf(DiaryStarPlacement(id(1), "lyra-2", 0))) }
        expectFailure { state.copy(targetId = "missing") }
        expectFailure { state.copy(homeIds = setOf("lyra-1")) }
    }

    @Test fun malformedCatalogCannotSilentlyDropOrReplaceAType() {
        val state = StarCollectionState.initial()
        expectFailure { state.copy(instances = state.instances.drop(1)) }
        expectFailure { state.copy(instances = state.instances + state.instances.first()) }
        expectFailure { state.copy(instances = state.instances + StarConstellationInstance("lyra-3", "lyra-1", 3)) }
        expectFailure { state.copy(instances = state.instances + StarConstellationInstance("lyra-2", "orion-1", 2)) }
        expectFailure { state.copy(instances = state.instances + StarConstellationInstance("missing-2", "missing-1", 2)) }
    }

    @Test fun missingSavedReferenceBlocksAllPlacementAndIsNotSilentlyErased() {
        val state = StarCollectionState.initial().copy(placements = listOf(DiaryStarPlacement(id(1), "lyra-1", 0)))
        expectFailure { StarCollectionRules.validateSavedReferences(state, saved(2)) }
        expectFailure { StarCollectionRules.placeDiary(state, id(2), saved(2), "lyra-1") }
        assertEquals(id(1), state.diaryAt("lyra-1", 0))
        StarCollectionRules.validateSavedReferences(state, saved(1))
    }

    @Test fun allFortyInstancesMayFillWithoutCapacityDiscardOrAutomaticNewCopies() {
        var state = StarCollectionState.initial()
        StarConstellationTemplates.items.forEach { template ->
            repeat(4) { state = StarCollectionRules.createInstance(state, template.id) }
        }
        state.instances.forEach { state = fill(state, it.id) }
        assertEquals(40, state.instances.size)
        assertEquals(285, state.placements.size)
        assertTrue(state.galleryItems().all { it.completed })
        val result = StarCollectionRules.placeDiary(state, id(1000), state.placements.map { it.diaryId }.toSet() + id(1000), "cygnus-1")
        assertNull(result.placement)
        assertSame(state, result.state)
    }

    private fun fill(initial: StarCollectionState, instanceId: String): StarCollectionState {
        var state = initial
        val remaining = state.galleryItems().single { it.id == instanceId }.let { it.totalStars - it.litStars }
        repeat(remaining) {
            val nextId = id(state.placements.size + 1)
            state = StarCollectionRules.placeDiary(state, nextId, state.placements.map { it.diaryId }.toSet() + nextId, instanceId).state
        }
        return state
    }

    private fun saved(vararg numbers: Int) = numbers.map(::id).toSet()
    private fun id(number: Int) = "own-00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
    private fun expectFailure(action: () -> Any) {
        try { action() } catch (_: StarCollectionDataException) { return }
        throw AssertionError("Expected a safe constellation failure")
    }
}
