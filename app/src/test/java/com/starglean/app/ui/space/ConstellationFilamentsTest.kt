package com.starglean.app.ui.space

import com.starglean.app.ui.space.CelestialGeometry.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationFilamentsTest {
    @Test(timeout = 2000)
    fun everyChartRebuildsDeterministicallyWithinSmallParticleBudgets() {
        for (index in 0..4) {
            assertEquals(ConstellationFilaments.motes(index), ConstellationFilaments.createMotes(index))
            assertEquals(ConstellationFilaments.flecks(index), ConstellationFilaments.createFlecks(index))
            assertTrue(ConstellationFilaments.motes(index).size in 200..600)
            assertTrue(ConstellationFilaments.flecks(index).size in 60..180)
            assertSame(ConstellationFilaments.motes(index), ConstellationFilaments.motes(index))
            assertSame(ConstellationFilaments.flecks(index), ConstellationFilaments.flecks(index))
        }
        assertTrue(ConstellationFilaments.motes(-1).isEmpty())
        assertTrue(ConstellationFilaments.flecks(5).isEmpty())
    }

    @Test
    fun allDustIsFiniteShallowAndSecondaryToEveryRealStar() {
        for (index in 0..4) {
            val figure = CelestialGeometry.constellations[index]
            ConstellationFilaments.motes(index).forEach { mote ->
                assertFinite(mote.position)
                assertTrue(abs(mote.position.z) < .026f)
                assertTrue(mote.intensity in .13f.. .38f)
                assertTrue(mote.scale in .25f.. .60f)
                figure.points.forEach { anchor -> assertTrue(planarDistance(mote.position, anchor) >= .015f) }
            }
            ConstellationFilaments.flecks(index).forEach { mote ->
                assertFinite(mote.position)
                assertTrue(abs(mote.position.z) < .06f)
                assertTrue(mote.intensity in .25f.. .60f)
                assertTrue(mote.scale in .22f.. .46f)
                figure.points.forEach { anchor -> assertTrue(planarDistance(mote.position, anchor) >= .045f) }
            }
            figure.points.indices.forEach { starIndex ->
                val star = ConstellationFilaments.star(index, starIndex)
                assertTrue(star.intensity in 1.08f..1.35f)
                assertTrue(star.scale in 1.10f..1.40f)
                assertTrue(star.intensity >= ConstellationFilaments.flecks(index).maxOf { it.intensity } * 1.8f)
                assertTrue(star.scale >= ConstellationFilaments.flecks(index).maxOf { it.scale } * 2.3f)
            }
        }
    }

    @Test
    fun eachOriginalConnectionHasDustAndFlankingGlintsWithTrueLocalDepth() {
        for (index in 0..4) {
            val figure = CelestialGeometry.constellations[index]
            val motes = ConstellationFilaments.motes(index)
            val flecks = ConstellationFilaments.flecks(index)
            assertTrue(motes.count { it.position.z > .005f } > 25)
            assertTrue(motes.count { it.position.z < -.005f } > 25)
            assertTrue(flecks.count { it.position.z > .01f } > 8)
            assertTrue(flecks.count { it.position.z < -.01f } > 8)
            figure.links.forEach { (a, b) ->
                val start = figure.points[a]
                val end = figure.points[b]
                val onEdge = motes.filter {
                    val (t, side) = coordinates(it.position, start, end)
                    t in .05f.. .95f && abs(side) < .03f
                }
                assertTrue(onEdge.size >= 6)
                val onSides = flecks.map { coordinates(it.position, start, end) }
                    .filter { (t, side) -> t in .07f.. .93f && abs(side) in .0249f.. .1001f }
                assertTrue(onSides.count { it.second > 0f } >= 2)
                assertTrue(onSides.count { it.second < 0f } >= 2)
                val spacings = onEdge.map { coordinates(it.position, start, end).first }.sorted()
                    .zipWithNext { left, right -> right - left }
                assertTrue(spacings.maxOrNull()!! > spacings.minOrNull()!! * 4f)
            }
            motes.forEach { mote ->
                assertTrue(figure.links.any { (a, b) ->
                    val (t, side) = coordinates(mote.position, figure.points[a], figure.points[b])
                    t in 0f..1f && abs(side) < .03f
                })
            }
        }
    }

    @Test
    fun shortOrionBeltLinksDoNotInheritAFixedLongSegmentParticleCount() {
        val figure = CelestialGeometry.constellations[1]
        for ((a, b) in listOf(3 to 4, 4 to 5)) {
            val grains = ConstellationFilaments.motes(1).count {
                val (t, side) = coordinates(it.position, figure.points[a], figure.points[b])
                t in .10f.. .90f && abs(side) < .029f
            }
            assertTrue("Short belt segment should not receive 64 grains: $grains", grains in 10..35)
            val glints = ConstellationFilaments.flecks(1).count {
                val (t, side) = coordinates(it.position, figure.points[a], figure.points[b])
                t in .2f.. .8f && abs(side) < .1001f
            }
            assertTrue("Belt stars need breathing room: $glints", glints in 2..18)
        }
    }

    @Test
    fun originalPrimaryStarsRemainPrimaryWithoutInventingUnknownAnchors() {
        for ((index, primary) in listOf(0 to 0, 1 to 7, 2 to 0, 3 to 1, 4 to 2)) {
            val stars = CelestialGeometry.constellations[index].points.indices
                .map { ConstellationFilaments.star(index, it) }
            assertEquals(primary, stars.indices.maxByOrNull { stars[it].intensity })
            assertEquals(1.35f, stars[primary].intensity, .000001f)
            assertEquals(1.40f, stars[primary].scale, .000001f)
            assertEquals(ConstellationStyle.star(index, -1), ConstellationFilaments.star(index, -1))
        }
        assertEquals(ConstellationStyle.star(5, 0), ConstellationFilaments.star(5, 0))
    }

    private fun assertFinite(point: Vec3) {
        assertTrue(point.x.isFinite())
        assertTrue(point.y.isFinite())
        assertTrue(point.z.isFinite())
    }

    private fun planarDistance(a: Vec3, b: Vec3): Float =
        sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    /** Along-segment fraction and signed perpendicular distance in the chart. */
    private fun coordinates(point: Vec3, start: Vec3, end: Vec3): Pair<Float, Float> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        return ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared to
            ((point.x - start.x) * -dy + (point.y - start.y) * dx) / sqrt(lengthSquared)
    }
}
