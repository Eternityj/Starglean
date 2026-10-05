package com.starglean.app.core.sensor

import org.junit.Assert.*
import org.junit.Test

class MovementWindowTest {
    private fun sample(index: Int, latitude: Double = 0.0, accuracy: Float = 1f, stepMillis: Long = 4_000) =
        MovementSample(latitude, 0.0, accuracy, 100_000L + index * stepMillis)

    @Test fun oneFixNeverClaimsActivity() {
        assertEquals(MovementAssessment.Collecting, MovementWindow().add(sample(0), 100_000))
    }
    @Test fun fourAccurateStillSamplesSupportSlowOrStill() {
        val window = MovementWindow()
        repeat(3) { assertEquals(MovementAssessment.Collecting, window.add(sample(it), sample(it).atMillis)) }
        assertEquals(MovementAssessment.SlowOrStill, window.add(sample(3), sample(3).atMillis))
    }
    @Test fun clearFreshFastDisplacementBlocksImmediately() {
        val window = MovementWindow()
        repeat(3) { window.add(sample(it, it * .001), sample(it).atMillis) }
        assertEquals(MovementAssessment.FastMoving, window.add(sample(3, .003), sample(3).atMillis))
    }
    @Test fun broadAccuracyDoesNotMeanStationary() {
        val window = MovementWindow()
        repeat(3) { window.add(sample(it, accuracy = 45f), sample(it).atMillis) }
        assertEquals(MovementAssessment.Collecting, window.add(sample(3, accuracy = 45f), sample(3).atMillis))
    }
    @Test fun stationaryOneHertzFixesWithOrdinaryAccuracySettleAcrossALongBaseline() {
        listOf(5f, 10f, 15f).forEach { accuracy ->
            val window = MovementWindow()
            repeat(10) { index ->
                assertEquals(MovementAssessment.Collecting,
                    window.add(sample(index, accuracy = accuracy, stepMillis = 1_000),
                        sample(index, stepMillis = 1_000).atMillis))
            }
            assertEquals(MovementAssessment.SlowOrStill,
                window.add(sample(10, accuracy = accuracy, stepMillis = 1_000),
                    sample(10, stepMillis = 1_000).atMillis))
        }
    }
    @Test fun threeHertzSamplingIsNotEvictedBeforeTenSeconds() {
        val window = MovementWindow()
        repeat(30) { index ->
            assertEquals(MovementAssessment.Collecting,
                window.add(sample(index, accuracy = 10f, stepMillis = 334),
                    sample(index, stepMillis = 334).atMillis))
        }
        val final = sample(30, accuracy = 10f, stepMillis = 334)
        assertEquals(MovementAssessment.SlowOrStill, window.add(final, final.atMillis))
    }
    @Test fun equivalentRepeatedTimestampIsIgnoredWithoutDiscardingTheWindow() {
        val window = MovementWindow()
        window.add(sample(0), 100_000)
        assertEquals(MovementAssessment.Collecting, window.add(sample(0), 104_000))
        assertTrue(window.lastAddWasEquivalentDuplicate)
        window.add(sample(1), sample(1).atMillis)
        window.add(sample(2), sample(2).atMillis)
        assertEquals(MovementAssessment.SlowOrStill, window.add(sample(3), sample(3).atMillis))
    }
    @Test fun conflictingRepeatedTimestampFailsClosed() {
        val window = MovementWindow()
        window.add(sample(0), 100_000)
        assertEquals(MovementAssessment.Uncertain,
            window.add(sample(0, latitude = .001), 100_000))
        assertFalse(window.lastAddWasEquivalentDuplicate)
    }
    @Test fun LongGapsCannotEstablishActivity() {
        val window = MovementWindow()
        repeat(4) { index ->
            val fix = sample(index).copy(atMillis = 100_000L + index * 12_000)
            val result = window.add(fix, fix.atMillis)
            if (index == 3) assertEquals(MovementAssessment.Uncertain, result)
        }
    }
    @Test fun suddenOutlierAndFastMovementFailClosed() {
        val outlierWindow = MovementWindow()
        repeat(10) { index ->
            val fix = sample(index, accuracy = 15f, stepMillis = 1_000)
            outlierWindow.add(fix, fix.atMillis)
        }
        val outlier = sample(17, latitude = 65.0 / METERS_PER_LATITUDE_DEGREE,
            accuracy = 15f, stepMillis = 1_000)
        assertEquals(MovementAssessment.Uncertain, outlierWindow.add(outlier, outlier.atMillis))

        val fastWindow = MovementWindow()
        val first = sample(0, accuracy = .5f, stepMillis = 1_000)
        fastWindow.add(first, first.atMillis)
        val fast = sample(1, latitude = .0001, accuracy = .5f, stepMillis = 1_000)
        assertEquals(MovementAssessment.FastMoving, fastWindow.add(fast, fast.atMillis))
    }
    @Test fun recentObservableAccelerationCannotBeDilutedByALongSlowBaseline() {
        val window = MovementWindow()
        repeat(22) { index ->
            val fix = walkingSample(index, 1_000L, 1.4, 15f)
            window.add(fix, fix.atMillis)
        }
        val previousMeters = 21 * 1.4
        val accelerated = sample(22,
            latitude = (previousMeters + 40.0) / METERS_PER_LATITUDE_DEGREE,
            accuracy = 15f, stepMillis = 1_000)
        assertEquals(MovementAssessment.FastMoving, window.add(accelerated, accelerated.atMillis))
    }
    @Test fun ordinaryWalkingWithPhoneAccuracyConvergesOnALongerBoundedBaseline() {
        listOf(1_000L, 500L, 334L).forEach { stepMillis ->
            listOf(5f, 10f, 15f).forEach { accuracy ->
                val window = MovementWindow()
                var assessment = MovementAssessment.Collecting
                val count = (24_000L / stepMillis).toInt()
                repeat(count) { index ->
                    val fix = walkingSample(index, stepMillis, 1.4, accuracy)
                    assessment = window.add(fix, fix.atMillis)
                    assertTrue(assessment != MovementAssessment.FastMoving)
                }
                assertEquals("step=$stepMillis accuracy=$accuracy", MovementAssessment.SlowOrStill, assessment)
            }
        }
    }
    @Test fun realisticStationaryJitterCanConvergeWithoutClearingAtTenSeconds() {
        listOf(5f, 10f, 15f).forEach { accuracy ->
            val window = MovementWindow()
            var assessment = MovementAssessment.Collecting
            repeat(24) { index ->
                val jitterMeters = if (index % 2 == 0) -3.0 else 3.0
                val fix = sample(index, latitude = jitterMeters / METERS_PER_LATITUDE_DEGREE,
                    accuracy = accuracy, stepMillis = 1_000)
                assessment = window.add(fix, fix.atMillis)
                assertTrue(assessment != MovementAssessment.FastMoving)
            }
            assertEquals(MovementAssessment.SlowOrStill, assessment)
        }
    }
    @Test fun carSpeedIsCaughtWithinTwoSecondsWithoutWaitingForTheLongBaseline() {
        val window = MovementWindow()
        val first = walkingSample(0, 1_000L, 15.0, 5f)
        assertEquals(MovementAssessment.Collecting, window.add(first, first.atMillis))
        val second = walkingSample(1, 1_000L, 15.0, 5f)
        assertNotEquals(MovementAssessment.FastMoving, window.add(second, second.atMillis))
        val third = walkingSample(2, 1_000L, 15.0, 5f)
        assertEquals(MovementAssessment.FastMoving, window.add(third, third.atMillis))
    }
    @Test fun InvalidSamplesAreUncertain() {
        listOf(sample(0).copy(latitude = Double.NaN), sample(0).copy(longitude = 181.0),
            sample(0).copy(accuracyMeters = 0f), sample(0).copy(accuracyMeters = 51f),
            sample(0).copy(atMillis = 89_999), sample(0).copy(atMillis = 100_001)).forEach {
            assertEquals(MovementAssessment.Uncertain, MovementWindow().add(it, 100_000))
        }
    }
    @Test fun ToStringDoesNotExposeCoordinates() {
        assertEquals("MovementSample(location=redacted)", sample(0).toString())
    }

    private fun walkingSample(index: Int, stepMillis: Long, metersPerSecond: Double, accuracy: Float): MovementSample {
        val elapsedSeconds = index * stepMillis / 1_000.0
        return sample(index, latitude = elapsedSeconds * metersPerSecond / METERS_PER_LATITUDE_DEGREE,
            accuracy = accuracy, stepMillis = stepMillis)
    }

    private companion object {
        const val METERS_PER_LATITUDE_DEGREE = 111_195.0
    }
}
