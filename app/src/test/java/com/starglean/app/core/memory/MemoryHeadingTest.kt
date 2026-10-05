package com.starglean.app.core.memory

import kotlin.math.abs
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHeadingTest {
    @Test fun firstReliableSampleIsRealNotAnInventedZero() {
        val filter = MemoryHeadingFilter()
        assertEquals(271.0, requireNotNull(filter.update(271.0, 1_000L)), 0.0)
        filter.reset()
        assertEquals(80.0, requireNotNull(filter.update(80.0, 2_000L)), 0.0)
    }

    @Test fun filteringAcrossNorthTakesTheShortArcInBothDirections() {
        val clockwise = MemoryHeadingFilter()
        clockwise.update(359.0, 1_000L)
        val next = requireNotNull(clockwise.update(1.0, 1_180L))
        assertTrue(abs(MemoryCompassMath.signedDeltaDegrees(next, 0.0)) < 1.0)
        val counterclockwise = MemoryHeadingFilter()
        counterclockwise.update(1.0, 1_000L)
        val reverse = requireNotNull(counterclockwise.update(359.0, 1_180L))
        assertTrue(abs(MemoryCompassMath.signedDeltaDegrees(reverse, 0.0)) < 1.0)
        assertEquals(0.0, MemoryCompassMath.signedDeltaDegrees(next, 0.0) + MemoryCompassMath.signedDeltaDegrees(reverse, 0.0), 1e-9)
    }

    @Test fun lowPassStepIsTimeAwareAndNeverOvershoots() {
        val oneStep = MemoryHeadingFilter()
        val manySteps = MemoryHeadingFilter()
        oneStep.update(0.0, 1_000L)
        manySteps.update(0.0, 1_000L)
        val whole = requireNotNull(oneStep.update(90.0, 1_180L))
        var incremental = 0.0
        repeat(6) { incremental = requireNotNull(manySteps.update(90.0, 1_030L + it * 30L)) }
        assertEquals(whole, incremental, 1e-8)
        assertTrue(whole in 0.0..90.0)
        assertTrue(whole > 50.0 && whole < 60.0)
    }

    @Test fun invalidReversedOrOldReadingsNeverContinueAStaleHeading() {
        val filter = MemoryHeadingFilter()
        filter.update(45.0, 1_000L)
        assertNull(filter.update(Double.NaN, 1_100L))
        assertEquals(270.0, requireNotNull(filter.update(270.0, 1_200L)), 0.0)
        assertNull(filter.update(20.0, 1_199L))
        assertEquals(90.0, requireNotNull(filter.update(90.0, 1_300L)), 0.0)
        assertEquals(180.0, requireNotNull(filter.update(180.0, 3_000L)), 0.0)
        assertEquals(180.0, requireNotNull(filter.update(20.0, 3_000L)), 0.0)
        assertNull(filter.update(20.0, -1L))
    }

    @Test fun readingRequiresNullDegreesWheneverItIsNotReliable() {
        HeadingReliability.entries.filter { it != HeadingReliability.Reliable }.forEach { quality ->
            MemoryHeadingReading(null, quality, 0L)
            assertTrue(runCatching { MemoryHeadingReading(90.0, quality, 1L) }.isFailure)
        }
        assertTrue(runCatching { MemoryHeadingReading(null, HeadingReliability.Reliable, 1L) }.isFailure)
        assertTrue(runCatching { MemoryHeadingReading(Double.NaN, HeadingReliability.Reliable, 1L) }.isFailure)
        assertTrue(runCatching { MemoryHeadingReading(360.0, HeadingReliability.Reliable, 1L) }.isFailure)
        assertTrue(runCatching { MemoryHeadingReading(90.0, HeadingReliability.Reliable, -1L) }.isFailure)
    }

    @Test fun magneticReferenceRecentDataAndMediumAccuracyAreRequired() {
        assertEquals(HeadingReliability.Reliable, quality())
        assertEquals(HeadingReliability.Unavailable, quality(hasMagnetometer = false))
        assertEquals(HeadingReliability.Unavailable, quality(hasRecentSample = false))
        assertEquals(HeadingReliability.NeedsCalibration, quality(sensorAccuracy = 1))
        assertEquals(HeadingReliability.NeedsCalibration, quality(magneticAccuracy = 0))
        assertEquals(HeadingReliability.NeedsCalibration, quality(field = 120.0))
        assertEquals(HeadingReliability.NeedsCalibration, quality(field = 0.0))
        assertEquals(HeadingReliability.NeedsCalibration, quality(field = Double.NaN))
    }

    @Test fun steepOrFaceDownPhonesDoNotPublishANorthHeading() {
        assertEquals(HeadingReliability.Reliable, quality(normalZ = cos(Math.toRadians(59.0))))
        assertEquals(HeadingReliability.TooSteep, quality(normalZ = cos(Math.toRadians(61.0))))
        assertEquals(HeadingReliability.TooSteep, quality(normalZ = -1.0))
        assertEquals(HeadingReliability.Unavailable, quality(normalZ = Double.NaN))
    }

    @Test fun reportedHeadingUncertaintyCanOverrideSensorHighAccuracy() {
        assertEquals(HeadingReliability.Reliable, quality(error = Math.toRadians(20.0)))
        assertEquals(HeadingReliability.NeedsCalibration, quality(error = Math.toRadians(26.0)))
        assertEquals(HeadingReliability.NeedsCalibration, quality(error = Double.NaN))
    }

    private fun quality(
        hasMagnetometer: Boolean = true,
        hasRecentSample: Boolean = true,
        sensorAccuracy: Int = 3,
        magneticAccuracy: Int = 3,
        field: Double = 50.0,
        normalZ: Double = 1.0,
        error: Double? = null,
    ) = memoryHeadingReliability(hasMagnetometer, hasRecentSample, sensorAccuracy, magneticAccuracy, field, normalZ, error)
}
