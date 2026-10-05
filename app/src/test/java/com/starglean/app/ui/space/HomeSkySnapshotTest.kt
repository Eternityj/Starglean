package com.starglean.app.ui.space

import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.stars.StarCollectionState
import com.starglean.app.core.stars.StarConstellationTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSkySnapshotTest {
    private fun completed(item: GalleryConstellation): GalleryConstellation =
        item.copy(litStarIndices = item.points.indices.toSet(), shownOnHome = true)

    private fun entryId(instance: String, star: Int): String = "test-entry:$instance:$star"

    @Test fun emptyRealCollectionHasNoSampleConstellationsOrDiaryAnchors() {
        val state = StarCollectionState.initial()
        val snapshot = HomeSkySnapshot.fromGallery(state.galleryItems(), state::diaryAt)
        assertTrue(snapshot.figures.isEmpty())
        assertNull(snapshot.indexOf("cygnus-1"))
        assertNull(snapshot.diaryAt("cygnus-1", 0))
    }

    @Test fun allEightTemplatesKeepExactAnchorOrderAndLinks() {
        val items = StarConstellationTemplates.items.map(::completed)
        val snapshot = HomeSkySnapshot.fromGallery(items, ::entryId)
        assertEquals(8, snapshot.figures.size)
        snapshot.figures.forEachIndexed { index, figure ->
            val item = items[index]
            assertEquals(item.id, figure.instanceId)
            assertEquals(item.links, figure.geometry.links)
            assertEquals(item.points.size, figure.geometry.points.size)
            item.points.forEachIndexed { star, point ->
                assertEquals(CelestialGeometry.Vec3(point.x, point.y, 0f), figure.geometry.points[star])
                assertEquals(entryId(item.id, star), snapshot.diaryAt(item.id, star))
            }
            assertTrue(figure.filaments.isNotEmpty())
            assertTrue(figure.flecks.isNotEmpty())
            assertEquals(item.points.size, figure.stars.size)
            assertTrue(figure.stars.all { it.intensity in 1f..1.5f && it.scale in 1f..1.5f })
        }
    }

    @Test fun incompleteHiddenAndUnassignedInstancesCannotAppearOnHome() {
        val first = completed(StarConstellationTemplates.items[0])
        val hidden = completed(StarConstellationTemplates.items[1]).copy(shownOnHome = false)
        val incomplete = StarConstellationTemplates.items[2]
        val missingAssignment = completed(StarConstellationTemplates.items[3])
        val snapshot = HomeSkySnapshot.fromGallery(listOf(first, hidden, incomplete, missingAssignment)) { instance, star ->
            if (instance == missingAssignment.id && star == 2) null else entryId(instance, star)
        }
        assertEquals(listOf(first.id), snapshot.figures.map { it.instanceId })
    }

    @Test fun repeatedTypesKeepDistinctInstanceAndDiaryIdentities() {
        val first = completed(StarConstellationTemplates.items[0])
        val second = first.copy(id = "cygnus-2", ordinal = 2)
        val snapshot = HomeSkySnapshot.fromGallery(listOf(first, second), ::entryId)
        assertEquals(2, snapshot.figures.size)
        assertEquals("Cygnus · 1", snapshot.figures[0].name)
        assertEquals("Cygnus · 2", snapshot.figures[1].name)
        assertEquals(entryId(first.id, 3), snapshot.diaryAt(first.id, 3))
        assertEquals(entryId(second.id, 3), snapshot.diaryAt(second.id, 3))
        assertTrue(snapshot.figures.all { it.isCygnus })
        assertTrue(snapshot.figures[0].geometry.phaseOffset != snapshot.figures[1].geometry.phaseOffset)
    }

    @Test fun staleOrOutOfRangeSelectionNeverFallsBackToAnotherDiary() {
        val item = completed(StarConstellationTemplates.items[0])
        val before = HomeSkySnapshot.fromGallery(listOf(item), ::entryId)
        val after = HomeSkySnapshot.fromGallery(emptyList(), ::entryId)
        assertEquals(0, before.indexOf(item.id))
        assertNull(after.indexOf(item.id))
        assertNull(after.diaryAt(item.id, 0))
        assertNull(before.diaryAt(item.id, -1))
        assertNull(before.diaryAt(item.id, item.points.size))
        assertNull(before.diaryAt("other-instance", 0))
    }

    @Test fun stableSourceOrderAndEightInstanceLimitDoNotCollapseRepeatedTypes() {
        val all = StarConstellationTemplates.items.map(::completed)
        val ninth = all[0].copy(id = "cygnus-2", ordinal = 2)
        val snapshot = HomeSkySnapshot.fromGallery(listOf(ninth) + all, ::entryId)
        assertEquals(8, snapshot.figures.size)
        assertEquals(ninth.id, snapshot.figures.first().instanceId)
        assertEquals(all.take(7).map { it.id }, snapshot.figures.drop(1).map { it.instanceId })
        assertEquals(8, snapshot.figures.map { it.geometry.phaseOffset }.toSet().size)
        snapshot.figures.forEach { figure ->
            assertTrue(figure.geometry.phaseOffset.isFinite())
            assertTrue(figure.geometry.inclination.isFinite())
            homeConstellationPoints(figure.geometry, 2.1f).forEach {
                assertTrue(it.x.isFinite() && it.y.isFinite() && it.z.isFinite())
            }
        }
    }

    @Test fun snapshotCopiesInputAndExposesUnmodifiableCollectionsAcrossThreads() {
        val source = completed(StarConstellationTemplates.items[0])
        val points = source.points.toMutableList()
        val links = source.links.toMutableList()
        val supplied = source.copy(points = points, links = links)
        val snapshot = HomeSkySnapshot.fromGallery(listOf(supplied), ::entryId)
        points.clear()
        links.clear()
        assertEquals(source.points.size, snapshot.figures[0].geometry.points.size)
        assertEquals(source.links, snapshot.figures[0].geometry.links)
        assertTrue(runCatching { (snapshot.figures as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (snapshot.figures[0].geometry.points as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertTrue(runCatching { (snapshot.figures[0].diaryIds as MutableList<*>).clear() }.exceptionOrNull() is UnsupportedOperationException)
        assertFalse(snapshot.figures[0].toString().contains("test-entry"))
    }

    @Test fun approvedExistingDustAndDemoGeometryAreUnchanged() {
        val items = StarConstellationTemplates.items.take(4).map(::completed)
        val real = HomeSkySnapshot.fromGallery(items, ::entryId)
        real.figures.forEachIndexed { index, figure ->
            assertEquals(ConstellationFilaments.motes(index), figure.filaments)
            assertEquals(ConstellationFilaments.flecks(index), figure.flecks)
            figure.stars.forEachIndexed { star, style -> assertEquals(ConstellationFilaments.star(index, star), style) }
        }
        val demo = HomeSkySnapshot.Demo
        assertEquals(5, demo.figures.size)
        demo.figures.forEachIndexed { index, figure ->
            assertEquals(CelestialGeometry.constellations[index], figure.geometry)
            listOf(0f, .39f, 1.9f).forEach { phase ->
                assertEquals(CelestialGeometry.constellationPoints(index, phase), homeConstellationPoints(figure.geometry, phase))
                assertEquals(CelestialGeometry.constellationCenter(index, phase), homeConstellationCenter(figure.geometry, phase))
            }
        }
    }

    @Test fun selectedHitReturnsTheActualStarIndexNotAnArbitraryEntry() {
        val hits = listOf(
            HomeStarHit(0, 0, 20f, 20f, 0f, false),
            HomeStarHit(0, 4, 80f, 40f, 0f, false),
            HomeStarHit(1, 2, 81f, 40f, 0f, false),
        )
        val picked = nearestHomeStar(hits, 81f, 40f, selectedIndex = 0, radius = 30f)
        assertNotNull(picked)
        assertEquals(0, picked!!.index)
        assertEquals(4, picked.starIndex)
        assertEquals(2, nearestHomeStar(hits, 81f, 40f, selectedIndex = null, radius = 30f)?.starIndex)
    }

    @Test fun hiddenClippedAndDistantStarsCannotStealATap() {
        val hits = listOf(
            HomeStarHit(0, 0, 20f, 20f, 0f, true),
            HomeStarHit(0, 1, 20f, 20f, 1.01f, false),
            HomeStarHit(0, 2, 21f, 20f, 0f, false),
        )
        assertEquals(2, nearestHomeStar(hits, 20f, 20f, null, 30f)?.starIndex)
        assertNull(nearestHomeStar(hits, 200f, 200f, null, 30f))
        assertNull(nearestHomeStar(hits, Float.NaN, 20f, null, 30f))
        assertNull(nearestHomeStar(hits, 20f, 20f, 7, 30f))
    }
}
