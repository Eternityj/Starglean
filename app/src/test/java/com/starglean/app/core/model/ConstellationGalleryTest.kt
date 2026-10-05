package com.starglean.app.core.model

import com.starglean.app.ui.space.CelestialGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationGalleryTest {
    private val cygnus = GallerySamples.items[0]
    private val orion = GallerySamples.items[1]
    private val lyra = GallerySamples.items[2]

    @Test fun sampleGeometryExactlyMatchesTheApprovedHomeStarsAndLinks() {
        GallerySamples.items.filter { it.geometryIndex != null }.forEach { item ->
            val geometry = CelestialGeometry.constellations[requireNotNull(item.geometryIndex)]
            assertEquals(geometry.name, item.name)
            assertEquals(geometry.points.map { GalleryPoint(it.x, it.y) }, item.points)
            assertEquals(geometry.links, item.links)
        }
    }

    @Test fun samplesCountOnlyExplicitMainStarsNotDecorativeDust() {
        assertEquals(listOf(6, 9, 5, 5, 9, 10, 8, 5), GallerySamples.items.map { it.totalStars })
        assertEquals(listOf(6, 9, 4, 5, 6, 10, 4, 5), GallerySamples.items.map { it.litStars })
        assertEquals(
            listOf(true, true, false, true, false, true, false, true),
            GallerySamples.items.map { it.completed },
        )
        assertEquals(setOf(cygnus.id, orion.id), GallerySamples.initialHomeIds)
        assertTrue(GallerySamples.items.all { it.hasIllustration })
    }

    @Test fun eightUniqueFiguresHaveStableLocalInstanceIdentities() {
        assertEquals(
            listOf(
                "cygnus-1", "orion-1", "lyra-1", "cassiopeia-1",
                "leo-1", "scorpius-1", "pegasus-1", "delphinus-1",
            ),
            GallerySamples.items.map { it.id },
        )
        assertEquals(8, GallerySamples.items.map { it.id }.toSet().size)
        assertEquals(GalleryFigure.values().toSet(), GallerySamples.items.mapNotNull { it.figure }.toSet())
        assertTrue(GallerySamples.items.all { it.ordinal == 1 })
        assertTrue(GallerySamples.items.drop(3).none { it.shownOnHome })
        assertEquals(listOf(0, 1, 2, 3, null, null, null, null), GallerySamples.items.map { it.geometryIndex })
    }

    @Test fun addingFiguresPreservesTheThreeApprovedFixtures() {
        val displayNames = listOf("Cygnus", "Orion", "Lyra")
        assertEquals(displayNames, GallerySamples.items.take(3).map { it.name })
        assertEquals(listOf("Cygnus", "Orion", "Lyra"), GallerySamples.items.take(3).map { it.latinName })
        assertEquals(listOf(6, 9, 5), GallerySamples.items.take(3).map { it.totalStars })
        assertEquals(
            listOf((0..5).toSet(), (0..8).toSet(), (0..3).toSet()),
            GallerySamples.items.take(3).map { it.litStarIndices },
        )
        assertEquals("cygnus-1", GallerySamples.initialFocusedId)
        assertEquals("lyra-1", GallerySamples.initialTargetId)
        assertEquals(setOf("cygnus-1", "orion-1"), GallerySamples.initialHomeIds)
    }

    @Test fun illustrationAvailabilityFollowsFigureIdentityInsteadOfHomeGeometry() {
        val leo = GallerySamples.items.single { it.figure == GalleryFigure.Leo }
        assertNull(leo.geometryIndex)
        assertTrue(leo.hasIllustration)
        assertFalse(cygnus.copy(figure = null).hasIllustration)
    }

    @Test fun everyFigureHasDistinctInFrameAnchorsAndOneConnectedStarGraph() {
        GallerySamples.items.forEach { item ->
            assertTrue(item.totalStars in 5..10)
            assertEquals(item.totalStars, item.points.toSet().size)
            item.points.forEach { point ->
                val u = .50f + .68f * point.x
                val v = .52f - .68f * point.y
                assertTrue("${item.id}: horizontal image anchor is outside the safe frame", u in 0.08f..0.92f)
                assertTrue("${item.id}: vertical image anchor is outside the safe frame", v in 0.08f..0.92f)
            }
            val undirectedEdges = item.links.map { (a, b) -> minOf(a, b) to maxOf(a, b) }
            assertEquals("${item.id}: duplicate link", item.links.size, undirectedEdges.toSet().size)
            val connected = mutableSetOf(0)
            repeat(item.totalStars) {
                item.links.forEach { (a, b) ->
                    if (a in connected || b in connected) {
                        connected += a
                        connected += b
                    }
                }
            }
            assertEquals("${item.id}: disconnected main stars", item.points.indices.toSet(), connected)
        }
    }

    @Test fun allNewDiariesRemainExplicitLocalFixturesWithNoExtraIllustrationSlots() {
        GallerySamples.items.forEach { item ->
            item.points.indices.forEach { index ->
                assertEquals("demo:${item.id}:star:${index + 1}", item.starId(index))
                if (index in item.litStarIndices) {
                    assertEquals("demo:${item.id}:diary:${index + 1}", item.diaryIdForStar(index))
                } else {
                    assertNull(item.diaryIdForStar(index))
                }
            }
        }
    }

    @Test fun starAndDiaryIdentitiesAreStableAcrossTargetAndDisplayChanges() {
        val updated = cygnus.copy(shownOnHome = false, name = "Cygnus · Collected")
        cygnus.points.indices.forEach { index ->
            assertEquals(cygnus.starId(index), updated.starId(index))
            assertEquals(cygnus.diaryIdForStar(index), updated.diaryIdForStar(index))
        }
        assertNotEquals(cygnus.starId(0), orion.starId(0))
        assertNotEquals(cygnus.starId(0), cygnus.starId(1))
        assertEquals("demo:cygnus-1:star:1", cygnus.starId(0))
        assertEquals("demo:lyra-1:diary:4", lyra.diaryIdForStar(3))
        assertNull(lyra.diaryIdForStar(4))
    }

    @Test fun initialFocusIsCygnusButTheNextDiaryStillTargetsLyra() {
        var state = GalleryBrowsingState(GallerySamples.initialFocusedId, GallerySamples.initialTargetId)
        state = state.browse(orion)
        assertEquals(orion.id, state.focusedId)
        assertEquals(lyra.id, state.targetId)
        state = state.browse(cygnus)
        assertEquals(cygnus.id, state.focusedId)
        assertEquals(lyra.id, state.targetId)
    }

    @Test fun allEightDisplayNamesMatchTheEditionWithoutChangingTheLatinIdentityUsedByArt() {
        val names = listOf("Cygnus", "Orion", "Lyra", "Cassiopeia", "Leo", "Scorpius", "Pegasus", "Delphinus")
        val displayNames = names
        assertEquals(displayNames, GallerySamples.items.map { it.name })
        assertEquals(names, GallerySamples.items.map { it.latinName })
        assertEquals(names, GallerySamples.items.map { it.figure?.name })
    }

    @Test fun completedInstancesCannotBecomeCurrentTargets() {
        assertEquals(lyra.id, GallerySelectionPolicy.selectTarget(lyra.id, cygnus))
        assertNull(GallerySelectionPolicy.selectTarget(null, orion))
        assertEquals(lyra.id, GallerySelectionPolicy.selectTarget(null, lyra))
    }

    @Test fun newFiguresUseTheSameExplicitTargetAndHomeSelectionRules() {
        GallerySamples.items.drop(3).forEach { item ->
            val browsed = GalleryBrowsingState(cygnus.id, lyra.id).browse(item)
            assertEquals(item.id, browsed.focusedId)
            assertEquals(lyra.id, browsed.targetId)
            if (item.completed) {
                assertEquals(lyra.id, browsed.setTarget(item).targetId)
                assertEquals(setOf(item.id), GallerySelectionPolicy.toggleHomeDraft(emptySet(), item))
                assertEquals(setOf(item.id), GallerySelectionPolicy.confirmHomeDraft(setOf(item.id), GallerySamples.items))
            } else {
                assertEquals(item.id, browsed.setTarget(item).targetId)
                assertTrue(GallerySelectionPolicy.toggleHomeDraft(emptySet(), item).isEmpty())
                assertNull(GallerySelectionPolicy.confirmHomeDraft(setOf(item.id), GallerySamples.items))
            }
        }
    }

    @Test fun explicitlySelectingAnIncompleteTargetDoesNotMoveFocusOrOldDiaries() {
        val another = lyra.copy(id = "lyra-2", ordinal = 2, litStarIndices = emptySet())
        val state = GalleryBrowsingState(cygnus.id, lyra.id).setTarget(another)
        assertEquals(cygnus.id, state.focusedId)
        assertEquals(another.id, state.targetId)
        assertEquals("demo:lyra-1:diary:1", lyra.diaryIdForStar(0))
    }

    @Test fun editingHomeSelectionDoesNotCommitUntilConfirm() {
        val initial = GalleryHomeSelection(GallerySamples.initialHomeIds)
        val edited = initial.begin().toggle(cygnus)
        assertEquals(initial.committedIds, edited.committedIds)
        assertEquals(setOf(orion.id), edited.draftIds)
        val confirmed = edited.confirm(GallerySamples.items)
        assertEquals(setOf(orion.id), confirmed.committedIds)
        assertEquals(confirmed.committedIds, confirmed.draftIds)
        assertEquals(GallerySamples.initialHomeIds, initial.committedIds)
    }

    @Test fun cancelAndReopenDiscardUnconfirmedEdits() {
        val initial = GalleryHomeSelection(GallerySamples.initialHomeIds)
        val edited = initial.toggle(cygnus)
        assertEquals(initial, edited.cancel())
        assertEquals(initial, edited.begin())
    }

    @Test fun zeroHomeSelectionsAreAllowedWithoutDeletingAnyConstellation() {
        val cleared = GalleryHomeSelection(GallerySamples.initialHomeIds)
            .toggle(cygnus).toggle(orion).confirm(GallerySamples.items)
        assertTrue(cleared.committedIds.isEmpty())
        assertEquals(8, GallerySamples.items.size)
        assertEquals(6, cygnus.litStars)
    }

    @Test fun incompleteInstancesCannotBeAddedToHomeDrafts() {
        val draft = GallerySamples.initialHomeIds
        assertEquals(draft, GallerySelectionPolicy.toggleHomeDraft(draft, lyra))
        assertNull(GallerySelectionPolicy.confirmHomeDraft(setOf(lyra.id), GallerySamples.items))
    }

    @Test fun ninthHomeSelectionIsBlockedButRemovalAndReplacementWork() {
        val items = (1..9).map { number -> cygnus.copy(id = "completed-$number", ordinal = number) }
        val eight = items.take(8).map { it.id }.toSet()
        assertEquals(eight, GallerySelectionPolicy.toggleHomeDraft(eight, items[8]))
        val seven = GallerySelectionPolicy.toggleHomeDraft(eight, items[0])
        val replaced = GallerySelectionPolicy.toggleHomeDraft(seven, items[8])
        assertEquals(8, replaced.size)
        assertTrue(items[8].id in replaced)
        assertFalse(items[0].id in replaced)
        assertEquals(replaced, GallerySelectionPolicy.confirmHomeDraft(replaced, items))
    }

    @Test fun confirmationRechecksTheLimitAndCatalog() {
        val items = (1..9).map { number -> cygnus.copy(id = "completed-$number", ordinal = number) }
        assertNull(GallerySelectionPolicy.confirmHomeDraft(items.map { it.id }.toSet(), items))
        assertNull(GallerySelectionPolicy.confirmHomeDraft(setOf("missing-id"), items))
        assertNull(GallerySelectionPolicy.confirmHomeDraft(setOf(cygnus.id), listOf(cygnus, cygnus)))
        val invalid = GalleryHomeSelection(GallerySamples.initialHomeIds, setOf("missing-id"))
        assertEquals(invalid, invalid.confirm(GallerySamples.items))
    }

    @Test fun fiveInstanceTypeLimitCountsCompleteAndIncompleteTogether() {
        val complete = (1..4).map { number -> cygnus.copy(id = "cygnus-$number", ordinal = number) }
        val unfinished = cygnus.copy(id = "cygnus-5", ordinal = 5, litStarIndices = setOf(0), shownOnHome = false)
        assertTrue(GallerySelectionPolicy.canAddInstance(complete, "Cygnus"))
        assertFalse(GallerySelectionPolicy.canAddInstance(complete + unfinished, "Cygnus"))
        assertFalse(GallerySelectionPolicy.canAddInstance(complete + unfinished, "  CYGNUS  "))
        assertTrue(GallerySelectionPolicy.canAddInstance(complete + unfinished, "Lyra"))
        assertFalse(GallerySelectionPolicy.canAddInstance(complete, " "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedNonFinitePointsAreRejected() {
        GalleryPoint(Float.NaN, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedOutOfBoundsLitStarIsRejected() {
        cygnus.copy(litStarIndices = setOf(99), shownOnHome = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedOutOfBoundsLinkIsRejected() {
        cygnus.copy(links = listOf(0 to 99))
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedSelfLinkIsRejected() {
        cygnus.copy(links = listOf(1 to 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteModelCannotClaimItIsOnHome() {
        lyra.copy(shownOnHome = true)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decorativeIndicesDoNotReceiveStableDiarySlots() {
        cygnus.diaryIdForStar(cygnus.totalStars)
    }
}
