package com.starglean.app.core.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyMovementGateTest {
    @Test fun oneOrFourStationarySamplesDoNotPrematurelyClaimSettled() {
        val gate = NearbyMovementGate()
        repeat(4) { index ->
            assertEquals(NearbyMovementStatus.Collecting, gate.update(sample(index), now(index)))
        }
        assertEquals(NearbyMovementStatus.Settled, gate.update(sample(4), now(4)))
    }

    @Test fun ordinaryWalkingIsNotTreatedAsFastTravel() {
        val gate = NearbyMovementGate()
        repeat(4) { index ->
            assertFalse(gate.update(sample(index, latitude = index * .00005, accuracy = .5), now(index)) ==
                NearbyMovementStatus.FastMovement)
        }
        assertEquals(NearbyMovementStatus.Settled,
            gate.update(sample(4, latitude = 4 * .00005, accuracy = .5), now(4)))
    }

    @Test fun consistentFastDisplacementBlocksWithoutCallingItDriving() {
        val gate = NearbyMovementGate()
        repeat(3) { index -> gate.update(sample(index, latitude = index * .001), now(index)) }
        assertEquals(NearbyMovementStatus.FastMovement,
            gate.update(sample(3, latitude = .003), now(3)))
        assertEquals(NearbyMovementStatus.FastMovement,
            gate.update(sample(4, latitude = .004), now(4)))
    }

    @Test fun noisyInvalidMockFutureAndStaleSamplesFailClosed() {
        listOf(
            sample(0, accuracy = 35.01), sample(0).copyForTest(latitude = Double.NaN),
            sample(0).copyForTest(longitude = 181.0), sample(0).copyForTest(isMock = true),
            sample(0).copyForTest(sampledAtNanos = now(0) + 1),
            sample(0).copyForTest(sampledAtNanos = now(0) - 8_000_000_000L),
        ).forEach { bad ->
            assertEquals(NearbyMovementStatus.Uncertain, NearbyMovementGate().update(bad, now(0)))
        }
        val noisy = NearbyMovementGate()
        repeat(3) { index -> noisy.update(sample(index, accuracy = 20.0), now(index)) }
        assertEquals(NearbyMovementStatus.Collecting, noisy.update(sample(3, accuracy = 20.0), now(3)))
    }

    @Test fun evidenceExpiresAtTheExactBoundaryAndClockRegressionStaysClosed() {
        val gate = settledGate()
        assertEquals(NearbyMovementStatus.Settled, gate.statusAt(now(4) + 7_999_999_999L))
        assertEquals(NearbyMovementStatus.Uncertain, gate.statusAt(now(4) + 8_000_000_000L))
        assertEquals(NearbyMovementStatus.Uncertain, gate.statusAt(now(4)))
        assertEquals(NearbyMovementStatus.Uncertain, gate.update(sample(10), now(10)))
    }

    @Test fun fastOrUncertainEvidenceRequiresAFullFreshSettledRecovery() {
        val gate = NearbyMovementGate()
        repeat(4) { index -> gate.update(sample(index, latitude = index * .001), now(index)) }
        assertEquals(NearbyMovementStatus.FastMovement, gate.statusAt(now(3)))
        val base = 10
        repeat(4) { offset ->
            assertFalse(gate.update(sample(base + offset, latitude = .003), now(base + offset)) == NearbyMovementStatus.Settled)
        }
        assertFalse(gate.update(sample(base + 4, latitude = .003), now(base + 4)) == NearbyMovementStatus.Settled)
        assertEquals(NearbyMovementStatus.Settled,
            gate.update(sample(base + 5, latitude = .003), now(base + 5)))
    }

    @Test fun oneHertzOrdinaryAccuracyAndThreeHertzProvidersCanSettle() {
        listOf(5.0, 10.0, 15.0).forEach { accuracy ->
            val gate = NearbyMovementGate()
            repeat(11) { index ->
                assertFalse(gate.update(sampleAt(index, 1_000L, accuracy = accuracy),
                    nowAt(index, 1_000L)) == NearbyMovementStatus.Settled)
            }
            assertEquals(NearbyMovementStatus.Settled,
                gate.update(sampleAt(11, 1_000L, accuracy = accuracy), nowAt(11, 1_000L)))
        }

        val highRateGate = NearbyMovementGate()
        repeat(31) { index ->
            assertFalse(highRateGate.update(sampleAt(index, 334L, accuracy = 10.0),
                nowAt(index, 334L)) == NearbyMovementStatus.Settled)
        }
        assertEquals(NearbyMovementStatus.Settled,
            highRateGate.update(sampleAt(31, 334L, accuracy = 10.0), nowAt(31, 334L)))
    }

    @Test fun diagnosticsNeverExposeCoordinates() {
        assertEquals("NearbyReaderLocationSample(redacted)", sample(0).toString())
    }

    @Test fun equivalentDuplicateDoesNotConfirmOrClearAndMultiProviderStreamSettles() {
        val gate = NearbyMovementGate()
        repeat(3) { index ->
            val original = sample(index)
            val beforeDuplicate = gate.update(original, now(index))
            assertEquals(beforeDuplicate, gate.update(original, now(index) + 1))
        }
        assertEquals(NearbyMovementStatus.Collecting, gate.statusAt(now(2) + 1))
        val firstConfirmation = gate.update(sample(3), now(3))
        assertEquals(NearbyMovementStatus.Collecting, firstConfirmation)
        assertEquals(firstConfirmation, gate.update(sample(3), now(3) + 1))
        assertEquals(NearbyMovementStatus.Settled, gate.update(sample(4), now(4)))
    }

    @Test fun threeHertzEquivalentProviderDuplicatesDoNotPreventSettling() {
        val gate = NearbyMovementGate()
        repeat(32) { index ->
            val original = sampleAt(index, 334L, accuracy = 10.0)
            val first = gate.update(original, nowAt(index, 334L))
            assertEquals(first, gate.update(original, nowAt(index, 334L) + 1))
        }
        assertEquals(NearbyMovementStatus.Settled, gate.statusAt(nowAt(31, 334L) + 1))
    }

    @Test fun conflictingSameTimeFixRevokesSettledAndRequiresFreshRecovery() {
        val gate = NearbyMovementGate()
        repeat(12) { index -> gate.update(sampleAt(index, 1_000L, accuracy = 10.0), nowAt(index, 1_000L)) }
        assertEquals(NearbyMovementStatus.Settled, gate.statusAt(nowAt(11, 1_000L)))
        val conflict = sampleAt(11, 1_000L, latitude = .001, accuracy = 10.0)
        assertEquals(NearbyMovementStatus.Uncertain, gate.update(conflict, nowAt(11, 1_000L) + 1))
        repeat(11) { offset ->
            assertFalse(gate.update(sampleAt(12 + offset, 1_000L, accuracy = 10.0),
                nowAt(12 + offset, 1_000L)) == NearbyMovementStatus.Settled)
        }
        assertEquals(NearbyMovementStatus.Settled,
            gate.update(sampleAt(23, 1_000L, accuracy = 10.0), nowAt(23, 1_000L)))
    }

    @Test fun stopAfterFastMovementRequiresFreshTimeSpanningEvidence() {
        val gate = NearbyMovementGate()
        gate.update(sample(0, accuracy = .5), now(0))
        assertEquals(NearbyMovementStatus.FastMovement,
            gate.update(sample(1, latitude = .001, accuracy = .5), now(1)))
        val base = 4
        repeat(4) { offset ->
            assertFalse(gate.update(sample(base + offset, latitude = .001, accuracy = 10.0),
                now(base + offset)) == NearbyMovementStatus.Settled)
        }
        assertFalse(gate.update(sample(base + 4, latitude = .001, accuracy = 10.0),
            now(base + 4)) == NearbyMovementStatus.Settled)
        assertEquals(NearbyMovementStatus.Settled,
            gate.update(sample(base + 5, latitude = .001, accuracy = 10.0), now(base + 5)))
    }

    @Test fun settledAuthorizationIsImmediatelyRevokedByFastOutlierOrMockEvidence() {
        val fast = settledGate()
        assertEquals(NearbyMovementStatus.FastMovement,
            fast.update(sample(5, latitude = .001, accuracy = .5), now(5)))

        val outlier = settledGate()
        assertEquals(NearbyMovementStatus.Uncertain,
            outlier.update(sample(6, latitude = .000405, accuracy = 15.0), now(6)))

        val mock = settledGate()
        assertEquals(NearbyMovementStatus.Uncertain,
            mock.update(sample(5).copyForTest(isMock = true), now(5)))
    }

    @Test fun ordinaryWalkingAndRealisticJitterAtPhoneAccuracyCanSettleWithoutEverClaimingFast() {
        listOf(1_000L, 500L, 334L).forEach { stepMillis ->
            listOf(5.0, 10.0, 15.0).forEach { accuracy ->
                val walking = NearbyMovementGate()
                var status = NearbyMovementStatus.Collecting
                val count = (24_000L / stepMillis).toInt()
                repeat(count) { index ->
                    val elapsedSeconds = index * stepMillis / 1_000.0
                    status = walking.update(sampleAt(index, stepMillis,
                        latitude = elapsedSeconds * 1.4 / METERS_PER_LATITUDE_DEGREE, accuracy = accuracy),
                        nowAt(index, stepMillis))
                    assertTrue(status != NearbyMovementStatus.FastMovement)
                }
                assertEquals("step=$stepMillis accuracy=$accuracy", NearbyMovementStatus.Settled, status)

                val jitter = NearbyMovementGate()
                repeat(count) { index ->
                    val offsetMeters = if (index % 2 == 0) -3.0 else 3.0
                    status = jitter.update(sampleAt(index, stepMillis,
                        latitude = offsetMeters / METERS_PER_LATITUDE_DEGREE, accuracy = accuracy),
                        nowAt(index, stepMillis))
                    assertTrue(status != NearbyMovementStatus.FastMovement)
                }
                assertEquals("jitter step=$stepMillis accuracy=$accuracy", NearbyMovementStatus.Settled, status)
            }
        }
    }

    @Test fun threeHertzProviderDuplicatesWithNormalJitterSettleButRecentFastEvidenceRevokes() {
        val gate = NearbyMovementGate()
        repeat(72) { index ->
            val offsetMeters = if (index % 2 == 0) -3.0 else 3.0
            val original = sampleAt(index, 334L,
                latitude = offsetMeters / METERS_PER_LATITUDE_DEGREE, accuracy = 15.0)
            val status = gate.update(original, nowAt(index, 334L))
            assertTrue(status != NearbyMovementStatus.FastMovement)
            assertEquals(status, gate.update(original, nowAt(index, 334L) + 1))
        }
        assertEquals(NearbyMovementStatus.Settled, gate.statusAt(nowAt(71, 334L) + 1))

        val fast = sampleAt(72, 334L,
            latitude = 43.0 / METERS_PER_LATITUDE_DEGREE, accuracy = 15.0)
        assertEquals(NearbyMovementStatus.FastMovement, gate.update(fast, nowAt(72, 334L)))
    }

    private fun settledGate() = NearbyMovementGate().also { gate ->
        repeat(5) { index -> gate.update(sample(index), now(index)) }
    }

    private fun sample(index: Int, latitude: Double = 0.0, accuracy: Double = 1.0) =
        NearbyReaderLocationSample(latitude, 0.0, accuracy, now(index), false)

    private fun sampleAt(index: Int, stepMillis: Long, latitude: Double = 0.0, accuracy: Double = 1.0) =
        NearbyReaderLocationSample(latitude, 0.0, accuracy, nowAt(index, stepMillis), false)

    private fun now(index: Int) = (100_000L + index * 4_000L) * 1_000_000L
    private fun nowAt(index: Int, stepMillis: Long) = (100_000L + index * stepMillis) * 1_000_000L

    private fun NearbyReaderLocationSample.copyForTest(
        latitude: Double = this.latitude,
        longitude: Double = this.longitude,
        sampledAtNanos: Long = this.sampledAtElapsedNanos,
        isMock: Boolean = this.isMock,
    ) = NearbyReaderLocationSample(latitude, longitude, accuracyMeters, sampledAtNanos, isMock)

    private companion object {
        const val METERS_PER_LATITUDE_DEGREE = 111_195.0
    }
}
