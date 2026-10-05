package com.starglean.app.ui.components

import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GalleryPoint
import com.starglean.app.ui.space.CelestialGeometry
import com.starglean.app.ui.space.CelestialGeometry.Vec3
import com.starglean.app.ui.space.ConstellationFilaments
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryStarDustTest {
    @Test
    fun existingHomeChartsDelegateWithoutRearrangingAnyGrain() {
        CelestialGeometry.constellations.indices.forEach { index ->
            val item = fixture.copy(geometryIndex = index)
            assertSame(ConstellationFilaments.motes(index), GalleryStarDust.motes(item))
            assertSame(ConstellationFilaments.flecks(index), GalleryStarDust.flecks(item))
        }
        val unsupported = fixture.copy(geometryIndex = 999)
        assertTrue(GalleryStarDust.motes(unsupported).isEmpty())
        assertTrue(GalleryStarDust.flecks(unsupported).isEmpty())
    }

    @Test(timeout = 2000)
    fun galleryOnlyGeometryRebuildsDeterministicallyInTheSameParticleBudget() {
        val motes = GalleryStarDust.motes(fixture)
        val flecks = GalleryStarDust.flecks(fixture)
        repeat(5) {
            assertEquals(motes, GalleryStarDust.motes(fixture))
            assertEquals(flecks, GalleryStarDust.flecks(fixture))
        }
        assertTrue(motes.size in 200..500)
        assertTrue(flecks.size in 60..160)
        // Ownership/progress does not reshuffle the apparent constellation material.
        val secondInstance = fixture.copy(id = "dust-test-2", ordinal = 2, litStarIndices = setOf(0))
        assertEquals(motes, GalleryStarDust.motes(secondInstance))
        assertEquals(flecks, GalleryStarDust.flecks(secondInstance))
        val anotherType = fixture.copy(latinName = "AnotherDustFigure")
        assertNotEquals(motes, GalleryStarDust.motes(anotherType))
        assertNotEquals(flecks, GalleryStarDust.flecks(anotherType))
    }

    @Test
    fun grainsStayFiniteShallowDimAndClearOfEveryDiaryAnchor() {
        GalleryStarDust.motes(fixture).forEach { mote ->
            assertFinite(mote.position)
            assertTrue(abs(mote.position.z) < .026f)
            assertTrue(mote.intensity in .13f.. .38f)
            assertTrue(mote.scale in .25f.. .60f)
            fixture.points.forEach { anchor ->
                assertTrue(planarDistance(mote.position, anchor) >= .015f)
            }
        }
        GalleryStarDust.flecks(fixture).forEach { mote ->
            assertFinite(mote.position)
            assertTrue(abs(mote.position.z) < .06f)
            assertTrue(mote.intensity in .25f.. .60f)
            assertTrue(mote.scale in .22f.. .46f)
            fixture.points.forEach { anchor ->
                assertTrue(planarDistance(mote.position, anchor) >= .045f)
            }
        }
        assertEquals(5, fixture.points.size)
        assertEquals(5, fixture.totalStars)
        assertEquals(0, fixture.litStars)
    }

    @Test
    fun eachConnectionHasIrregularDustAndSeparateGlintsOnBothSides() {
        val motes = GalleryStarDust.motes(fixture)
        val flecks = GalleryStarDust.flecks(fixture)
        assertTrue(motes.any { it.position.z > .005f })
        assertTrue(motes.any { it.position.z < -.005f })
        assertTrue(flecks.any { it.position.z > .01f })
        assertTrue(flecks.any { it.position.z < -.01f })
        fixture.links.forEach { (a, b) ->
            val start = fixture.points[a]
            val end = fixture.points[b]
            val onEdge = motes.map { coordinates(it.position, start, end) }
                .filter { (t, side) -> t in .05f.. .95f && abs(side) < .03f }
            assertTrue(onEdge.size >= 6)
            val onSides = flecks.map { coordinates(it.position, start, end) }
                .filter { (t, side) -> t in .07f.. .93f && abs(side) in .0249f.. .1001f }
            assertTrue(onSides.count { it.second > 0f } >= 2)
            assertTrue(onSides.count { it.second < 0f } >= 2)
            val spacings = onEdge.map { it.first }.sorted()
                .zipWithNext { left, right -> right - left }
            assertTrue(spacings.maxOrNull()!! > spacings.minOrNull()!! * 4f)
        }
        motes.forEach { mote ->
            assertTrue(fixture.links.any { (a, b) ->
                val (t, side) = coordinates(mote.position, fixture.points[a], fixture.points[b])
                t in 0f..1f && abs(side) < .03f
            })
        }
    }

    @Test(timeout = 2000)
    fun emptyCoincidentShortAndOverflowingSegmentsRemainSafeAndBounded() {
        assertTrue(GalleryStarDust.motes(fixture.copy(links = emptyList())).isEmpty())
        assertTrue(GalleryStarDust.flecks(fixture.copy(links = emptyList())).isEmpty())
        val coincident = twoPointFixture(GalleryPoint(0f, 0f), GalleryPoint(0f, 0f))
        assertTrue(GalleryStarDust.motes(coincident).isEmpty())
        assertTrue(GalleryStarDust.flecks(coincident).isEmpty())
        val tiny = twoPointFixture(GalleryPoint(0f, 0f), GalleryPoint(.03f, 0f))
        assertTrue(GalleryStarDust.motes(tiny).isEmpty())
        assertTrue(GalleryStarDust.flecks(tiny).isEmpty())
        val short = twoPointFixture(GalleryPoint(0f, 0f), GalleryPoint(.08f, 0f))
        assertTrue(GalleryStarDust.motes(short).size in 1..10)
        assertTrue(GalleryStarDust.flecks(short).isEmpty())
        val overflow = twoPointFixture(
            GalleryPoint(-Float.MAX_VALUE, 0f), GalleryPoint(Float.MAX_VALUE, 0f),
        )
        assertTrue(GalleryStarDust.motes(overflow).isEmpty())
        assertTrue(GalleryStarDust.flecks(overflow).isEmpty())
    }

    private fun twoPointFixture(start: GalleryPoint, end: GalleryPoint): GalleryConstellation =
        fixture.copy(points = listOf(start, end), links = listOf(0 to 1))

    private fun assertFinite(point: Vec3) {
        assertTrue(point.x.isFinite())
        assertTrue(point.y.isFinite())
        assertTrue(point.z.isFinite())
    }

    private fun planarDistance(point: Vec3, anchor: GalleryPoint): Float =
        sqrt((point.x - anchor.x) * (point.x - anchor.x) +
            (point.y - anchor.y) * (point.y - anchor.y))

    private fun coordinates(point: Vec3, start: GalleryPoint, end: GalleryPoint): Pair<Float, Float> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        return ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared to
            ((point.x - start.x) * -dy + (point.y - start.y) * dx) / sqrt(lengthSquared)
    }

    private val fixture = GalleryConstellation(
        id = "dust-test-1", name = "测试星座", latinName = "DustTestFigure", ordinal = 1,
        geometryIndex = null,
        points = listOf(
            GalleryPoint(-.45f, .30f), GalleryPoint(-.25f, -.20f), GalleryPoint(0f, .16f),
            GalleryPoint(.25f, -.20f), GalleryPoint(.45f, .35f),
        ),
        links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4),
        litStarIndices = emptySet(), shownOnHome = false,
    )
}
