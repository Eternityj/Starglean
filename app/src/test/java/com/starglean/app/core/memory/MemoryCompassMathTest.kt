package com.starglean.app.core.memory

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCompassMathTest {
    private fun fix(latitude: Double, longitude: Double, accuracy: Float = 5f) =
        MemoryLocationFix(latitude, longitude, accuracy, 1_700_000_000_000L)

    @Test fun distanceIsSymmetricAndKnownEquatorialArcHasExpectedLength() {
        val a = fix(0.0, 0.0)
        val b = fix(0.0, 1.0)
        assertEquals(0.0, MemoryCompassMath.distanceMeters(a, a), 0.0)
        assertEquals(111_195.08, MemoryCompassMath.distanceMeters(a, b), .02)
        assertEquals(MemoryCompassMath.distanceMeters(a, b), MemoryCompassMath.distanceMeters(b, a), 1e-6)
    }

    @Test fun theFourCardinalBearingsPointFromCurrentPositionToTheDiary() {
        val origin = fix(0.0, 0.0)
        assertEquals(0.0, requireNotNull(MemoryCompassMath.bearingDegrees(origin, fix(1.0, 0.0))), 1e-8)
        assertEquals(90.0, requireNotNull(MemoryCompassMath.bearingDegrees(origin, fix(0.0, 1.0))), 1e-8)
        assertEquals(180.0, requireNotNull(MemoryCompassMath.bearingDegrees(origin, fix(-1.0, 0.0))), 1e-8)
        assertEquals(270.0, requireNotNull(MemoryCompassMath.bearingDegrees(origin, fix(0.0, -1.0))), 1e-8)
    }

    @Test fun antimeridianCrossingUsesTheShortDirection() {
        val west = fix(0.0, 179.9)
        val east = fix(0.0, -179.9)
        assertEquals(22_239.016, MemoryCompassMath.distanceMeters(west, east), .02)
        assertEquals(90.0, requireNotNull(MemoryCompassMath.bearingDegrees(west, east)), 1e-8)
        assertEquals(270.0, requireNotNull(MemoryCompassMath.bearingDegrees(east, west)), 1e-8)
    }

    @Test fun samePlaceNearbyAndOverlappingAccuracyNeverInventABearing() {
        val origin = fix(0.0, 0.0)
        assertNull(MemoryCompassMath.bearingDegrees(origin, origin))
        assertNull(MemoryCompassMath.bearingDegrees(origin, fix(.0002, 0.0)))
        assertNull(MemoryCompassMath.bearingDegrees(fix(0.0, 0.0, 100f), fix(.001, 0.0, 50f)))
        assertEquals(0.0, requireNotNull(MemoryCompassMath.bearingDegrees(origin, fix(.001, 0.0))), 1e-8)
    }

    @Test fun antipodesNearAntipodesAndStartingPolesHaveNoUniqueBearing() {
        val origin = fix(0.0, 0.0)
        val antipode = fix(0.0, 180.0)
        assertTrue(MemoryCompassMath.distanceMeters(origin, antipode).isFinite())
        assertNull(MemoryCompassMath.bearingDegrees(origin, antipode))
        assertNull(MemoryCompassMath.bearingDegrees(origin, fix(.0001, 180.0)))
        assertNull(MemoryCompassMath.bearingDegrees(origin, fix(.0003, 180.0)))
        assertNull(MemoryCompassMath.bearingDegrees(fix(90.0, 123.0), fix(45.0, 40.0)))
        assertNull(MemoryCompassMath.bearingDegrees(fix(-90.0, -30.0), fix(-45.0, 40.0)))
    }

    @Test fun coarsePositionCannotClaimAlignmentWhenRelativeAngularUncertaintyIsLarge() {
        val current = fix(0.0, 0.0, 1_000f)
        val nearbyDiary = fix(.0098925, 0.0, 10f)
        assertTrue(MemoryCompassMath.distanceMeters(current, nearbyDiary) in 1_090.0..1_110.0)
        assertNull(MemoryCompassMath.bearingDegrees(current, nearbyDiary))
        val distantDiary = fix(.20, 0.0, 10f)
        assertEquals(0.0, requireNotNull(MemoryCompassMath.bearingDegrees(current, distantDiary)), 1e-8)
        assertEquals(90.0, requireNotNull(MemoryCompassMath.bearingDegrees(current, fix(0.0, .20, 1_500f))), 1e-8)
    }

    @Test fun normalizationAndDeltaRemainStableAcrossNorthAndHugeValues() {
        assertEquals(0.0, MemoryCompassMath.normalizeDegrees(360.0), 0.0)
        assertEquals(359.0, MemoryCompassMath.normalizeDegrees(-1.0), 0.0)
        assertEquals(2.0, MemoryCompassMath.signedDeltaDegrees(1.0, 359.0), 0.0)
        assertEquals(-2.0, MemoryCompassMath.signedDeltaDegrees(359.0, 1.0), 0.0)
        assertEquals(-180.0, MemoryCompassMath.signedDeltaDegrees(180.0, 0.0), 0.0)
        val enormous = MemoryCompassMath.signedDeltaDegrees(Double.MAX_VALUE, -Double.MAX_VALUE)
        assertTrue(enormous.isFinite() && enormous >= -180.0 && enormous < 180.0)
    }

    @Test fun eightDirectionNamesUseHalfSectorBoundaries() {
        val expectedDirections = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        expectedDirections.forEachIndexed { index, name ->
            assertEquals(name, MemoryCompassMath.directionName(index * 45.0))
        }
        assertEquals("north", MemoryCompassMath.directionName(359.9))
        assertEquals("north", MemoryCompassMath.directionName(22.4999))
        assertEquals("northeast", MemoryCompassMath.directionName(22.5))
        assertEquals("north", MemoryCompassMath.directionName(337.5))
    }

    @Test fun alignmentIsSmoothSymmetricAndFadesOutWithoutHardJumping() {
        assertEquals(1f, MemoryCompassMath.alignmentStrength(0.0), 0f)
        assertEquals(1f, MemoryCompassMath.alignmentStrength(10.0), 0f)
        assertEquals(.5f, MemoryCompassMath.alignmentStrength(20.0), .000001f)
        assertEquals(0f, MemoryCompassMath.alignmentStrength(30.0), 0f)
        assertEquals(0f, MemoryCompassMath.alignmentStrength(180.0), 0f)
        assertEquals(1f, MemoryCompassMath.alignmentStrength(359.0), 0f)
        var previous = 1f
        for (step in 0..600) {
            val angle = step / 20.0
            val strength = MemoryCompassMath.alignmentStrength(angle)
            assertTrue(strength in 0f..1f && strength <= previous)
            assertEquals(strength, MemoryCompassMath.alignmentStrength(-angle), .000001f)
            previous = strength
        }
        assertTrue(abs(MemoryCompassMath.alignmentStrength(10.001) - 1f) < .00001f)
        assertTrue(MemoryCompassMath.alignmentStrength(29.999) < .00001f)
        assertEquals(0f, MemoryCompassMath.alignmentStrength(Double.NaN), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteDirectionDoesNotPretendToBeNorth() {
        MemoryCompassMath.normalizeDegrees(Double.NaN)
    }
}
