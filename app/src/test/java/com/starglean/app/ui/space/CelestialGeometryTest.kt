package com.starglean.app.ui.space

import com.starglean.app.ui.space.CelestialGeometry.Vec3
import kotlin.math.PI
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CelestialGeometryTest {
    @Test
    fun homeDisplayNamesMatchTheEditionGalleryNamesAndBigDipperAsterism() {
        val displayNames = listOf("Cygnus", "Orion", "Lyra", "Cassiopeia", "Big Dipper")
        assertEquals(
            displayNames,
            CelestialGeometry.constellations.map { it.name },
        )
    }

    @Test
    fun occlusionUsesTheCameraToStarSegmentRatherThanDepthAlone() {
        assertTrue(CelestialGeometry.isOccluded(Vec3(0f, 0f, -2f)))
        assertFalse(CelestialGeometry.isOccluded(Vec3(0f, 0f, 2f)))
        assertFalse(CelestialGeometry.isOccluded(Vec3(0f, 0f, 1f)))
        assertFalse(CelestialGeometry.isOccluded(Vec3(2f, 0f, -2f)))
        assertFalse(CelestialGeometry.isOccluded(Vec3(0f, 0f, 8f)))
        assertFalse(CelestialGeometry.isOccluded(Vec3(0f, 0f, 6.4f)))
        assertTrue(CelestialGeometry.isOccluded(Vec3(-2f, 0f, 0f), Vec3(6.4f, 0f, 0f)))
        assertFalse(CelestialGeometry.isOccluded(Vec3(2f, 0f, 0f), Vec3(6.4f, 0f, 0f)))
    }

    @Test
    fun aFullOrbitReturnsEveryStarAndCentreToItsStartingPosition() {
        CelestialGeometry.constellations.indices.forEach { index ->
            val phase = .71f
            val start = CelestialGeometry.constellationPoints(index, phase)
            val end = CelestialGeometry.constellationPoints(index, phase + FULL_TURN)
            start.zip(end).forEach { (a, b) -> assertVecEquals(a, b) }
            assertVecEquals(
                CelestialGeometry.constellationCenter(index, phase),
                CelestialGeometry.constellationCenter(index, phase + FULL_TURN),
            )

            // An animation wrapping from 2π to zero must not visibly jump.
            val beforeWrap = CelestialGeometry.constellationPoints(index, FULL_TURN - .001f)
            val afterWrap = CelestialGeometry.constellationPoints(index, .001f)
            beforeWrap.zip(afterWrap).forEach { (a, b) -> assertTrue(distance(a, b) < .005f) }
        }
    }

    @Test
    fun everyStarAndConnectingSegmentStaysOutsideThePlanetThroughoutTheOrbit() {
        CelestialGeometry.constellations.forEachIndexed { index, constellation ->
            repeat(72) { step ->
                val phase = FULL_TURN * step / 72
                val points = CelestialGeometry.constellationPoints(index, phase)
                val centre = CelestialGeometry.constellationCenter(index, phase)
                assertEquals(CelestialGeometry.ORBIT_RADIUS, length(centre), EPSILON)
                points.forEach { point ->
                    assertTrue(length(point) >= CelestialGeometry.ORBIT_RADIUS - EPSILON)
                    assertTrue(length(point) < 2.2f)
                }
                constellation.links.forEach { (from, to) ->
                    assertTrue(from in points.indices && to in points.indices && from != to)
                    // Both endpoints share a tangent plane, so even the interiors of
                    // connecting lines cannot accidentally intersect the unit sphere.
                    repeat(11) { sample ->
                        val t = sample / 10f
                        val a = points[from]
                        val b = points[to]
                        val p = Vec3(
                            a.x + (b.x - a.x) * t,
                            a.y + (b.y - a.y) * t,
                            a.z + (b.z - a.z) * t,
                        )
                        assertTrue(length(p) >= CelestialGeometry.ORBIT_RADIUS - EPSILON)
                    }
                }
            }
        }
    }

    @Test
    fun figuresRetainTheirShapeWhileTheirStarsMoveInDepth() {
        CelestialGeometry.constellations.forEachIndexed { index, constellation ->
            val frontPhase = -constellation.phaseOffset
            val front = CelestialGeometry.constellationPoints(index, frontPhase)
            val side = CelestialGeometry.constellationPoints(index, frontPhase + PI.toFloat() / 2)
            val backCentre = CelestialGeometry.constellationCenter(index, frontPhase + PI.toFloat())
            val frontCentre = CelestialGeometry.constellationCenter(index, frontPhase)
            assertTrue(frontCentre.z > 0f)
            assertTrue(backCentre.z < 0f)
            assertTrue(front.zip(side).any { (a, b) -> kotlin.math.abs(a.z - b.z) > .5f })
            for (a in front.indices) {
                for (b in a + 1 until front.size) {
                    assertEquals(distance(front[a], front[b]), distance(side[a], side[b]), EPSILON)
                }
            }
            // These points rotate as a real tangent plane. A camera-facing billboard
            // would keep its width at the side and fail this perspective check.
            assertTrue(projectedWidth(side) < projectedWidth(front) * .65f)
        }
    }

    private fun projectedWidth(points: List<Vec3>): Float {
        val xs = points.map { it.x / (6.2f - it.z) }
        return xs.max() - xs.min()
    }

    private fun assertVecEquals(a: Vec3, b: Vec3) {
        assertEquals(a.x, b.x, EPSILON)
        assertEquals(a.y, b.y, EPSILON)
        assertEquals(a.z, b.z, EPSILON)
    }

    private fun length(p: Vec3): Float = sqrt(p.x * p.x + p.y * p.y + p.z * p.z)

    private fun distance(a: Vec3, b: Vec3): Float = length(Vec3(a.x - b.x, a.y - b.y, a.z - b.z))

    private companion object {
        const val EPSILON = .00001f
        val FULL_TURN = 2f * PI.toFloat()
    }
}
