package com.starglean.app.core.discovery

import com.starglean.app.core.location.LocationUpdateSource
import com.starglean.app.core.location.LocationUpdateSubscription
import com.starglean.app.core.model.ReadingEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyReaderLocationMonitorTest {
    @Test fun constructionDoesNotStartOrRequestAnything() {
        val fixture = Fixture()
        assertFalse(fixture.monitor.isRunning)
        assertEquals(0, fixture.source.starts)
        assertTrue(fixture.updates.isEmpty())
    }

    @Test fun everyInitialBlockerPreventsProviderRegistration() {
        listOf(
            NearbyReaderLocationStatus.ConsentRequired,
            NearbyReaderLocationStatus.NotForeground,
            NearbyReaderLocationStatus.PrecisePermissionRequired,
            NearbyReaderLocationStatus.ServicesDisabled,
            NearbyReaderLocationStatus.NoProvider,
        ).forEach { reason ->
            val fixture = Fixture()
            fixture.blocked = reason
            fixture.monitor.start()
            assertEquals(reason, fixture.latest.status)
            assertNull(fixture.latest.fix)
            assertFalse(fixture.monitor.isRunning)
            assertEquals(0, fixture.source.starts)
        }
    }

    @Test fun startsAllProvidersImmediatelyAndKeepsThemOpenAcrossFreshFixes() {
        val first = Source()
        val second = Source()
        val third = Source()
        val fixture = Fixture(listOf(first, second, third))
        fixture.monitor.start()
        assertEquals(NearbyReaderLocationStatus.WaitingForFix, fixture.latest.status)
        listOf(first, second, third).forEach { assertEquals(1, it.starts) }
        first.emit(fixture.sample())
        fixture.now += 1_000_000_000L
        second.emit(fixture.sample())
        fixture.now += 1_000_000_000L
        third.emit(fixture.sample())
        assertEquals(3, fixture.updates.count { it.status == NearbyReaderLocationStatus.Ready })
        listOf(first, second, third).forEach { assertEquals(0, it.closes) }
    }

    @Test fun successfulFixPreservesCoordinatesAndRealMonotonicTimestamp() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.now += 7_000_123L
        fixture.source.emit(fixture.sample(latitude = 41.25, longitude = -72.75, accuracy = 12.5))
        val fix = fixture.latest.fix!!
        assertEquals(41.25, fix.latitude, 0.0)
        assertEquals(-72.75, fix.longitude, 0.0)
        assertEquals(fixture.now, fix.sampledAtElapsedNanos)
        assertEquals(fixture.now / 1_000_000L, fix.quality.sampledAtElapsedMillis)
        assertEquals(12.5, fix.quality.horizontalAccuracyMeters, 0.0)
        assertFalse(fix.quality.isMock)
    }

    @Test fun sameUnchangedFixKeepsTheExactQualityInstanceAcrossHeartbeats() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.source.emit(fixture.sample())
        val fix = fixture.latest.fix!!
        val count = fixture.updates.size
        fixture.now += 2_000_000_000L
        fixture.monitor.tick()
        assertEquals(count, fixture.updates.size)
        assertSame(fix, fixture.latest.fix)
        assertSame(fix.quality, fixture.latest.fix!!.quality)
    }

    @Test fun differentOriginalFixGetsNewQualityIdentityEvenWithEqualQualityNumbers() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.source.emit(fixture.sample(latitude = 41.25))
        val first = fixture.latest.fix!!
        fixture.source.emit(fixture.sample(latitude = 41.26))
        val second = fixture.latest.fix!!
        assertNotSame(first.quality, second.quality)
        assertEquals(first.quality.sampledAtElapsedMillis, second.quality.sampledAtElapsedMillis)
        assertEquals(first.quality.horizontalAccuracyMeters, second.quality.horizontalAccuracyMeters, 0.0)
    }

    @Test fun preStartCachedSampleIsNeverAcceptedEvenWhenRecentAndPrecise() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.source.emit(fixture.sample(timestamp = fixture.now - 1L))
        assertEquals(NearbyReaderLocationStatus.WaitingForFix, fixture.latest.status)
        assertNull(fixture.latest.fix)
        assertTrue(fixture.monitor.isRunning)
    }

    @Test fun lateOlderProviderSampleDoesNotReplaceCurrentFix() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.now += 2_000_000_000L
        fixture.source.emit(fixture.sample())
        val newest = fixture.latest.fix!!
        fixture.source.emit(fixture.sample(latitude = 0.0, timestamp = fixture.now - 1_000_000_000L))
        assertSame(newest, fixture.latest.fix)
    }

    @Test fun illegalCoordinatesClearCurrentFixWithoutStoppingOtherProviders() {
        listOf(
            Double.NaN to 0.0, Double.POSITIVE_INFINITY to 0.0, 90.0001 to 0.0,
            -90.0001 to 0.0, 0.0 to Double.NaN, 0.0 to Double.NEGATIVE_INFINITY,
            0.0 to 180.0001, 0.0 to -180.0001,
        ).forEach { (latitude, longitude) ->
            val fixture = startedWithFix()
            fixture.source.emit(fixture.sample(latitude = latitude, longitude = longitude))
            assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
            assertNull(fixture.latest.fix)
            assertTrue(fixture.monitor.isRunning)
            assertEquals(0, fixture.source.closes)
        }
    }

    @Test fun olderQueuedFixCannotReviveAJustInvalidatedNewerSample() {
        val fixture = startedWithFix()
        // The original fix has expired, so the coarse sample below genuinely invalidates.
        fixture.now += 8_000_000_000L
        fixture.source.emit(fixture.sample(accuracy = 40.0))
        fixture.source.emit(fixture.sample(timestamp = fixture.now - 1_000_000_000L))
        assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
        assertNull(fixture.latest.fix)
        fixture.source.emit(fixture.sample())
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
    }

    @Test fun coarseFirstFromOneSourceAllowsSlightlyOlderPreciseInitialFixFromAnother() {
        listOf(NearbyLocationPurpose.Reading, NearbyLocationPurpose.Publishing).forEach { purpose ->
            val coarse = Source()
            val precise = Source()
            val fixture = Fixture(listOf(coarse, precise), purpose)
            fixture.monitor.start()
            fixture.now += 1_000_000_000L
            coarse.emit(fixture.sample(accuracy = 150.0))
            precise.emit(fixture.sample(accuracy = 10.0, timestamp = fixture.now - 100_000_000L))
            assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
            assertEquals(10.0, fixture.latest.fix!!.quality.horizontalAccuracyMeters, 0.0)
        }
    }

    @Test fun preciseFirstThenNewerCoarseKeepsTheOriginalReadingFix() {
        val precise = Source()
        val coarse = Source()
        val fixture = Fixture(listOf(precise, coarse))
        fixture.monitor.start()
        fixture.now += 1_000_000_000L
        precise.emit(fixture.sample(accuracy = 10.0, timestamp = fixture.now - 100_000_000L))
        val accepted = fixture.latest.fix
        // A coarse network fix says nothing about the precise one, so it must not reset reading.
        coarse.emit(fixture.sample(latitude = 0.0, longitude = 0.0, accuracy = 150.0))
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
        assertSame(accepted, fixture.latest.fix)
    }

    @Test fun readingIgnoredCoarseUpdateCannotRenewOriginalDeadline() {
        val fixture = startedWithFix()
        fixture.now += 7_999_000_000L
        fixture.source.emit(fixture.sample(accuracy = 100.0))
        fixture.now += 1_000_000L
        fixture.monitor.tick()
        assertEquals(NearbyReaderLocationStatus.FixExpired, fixture.latest.status)
        assertNull(fixture.latest.fix)
    }

    @Test fun preciseFirstThenNewerCoarsePreservesOnlyTheOriginalPublishingFix() {
        val precise = Source()
        val coarse = Source()
        val fixture = Fixture(listOf(precise, coarse), NearbyLocationPurpose.Publishing)
        fixture.monitor.start()
        fixture.now += 1_000_000_000L
        precise.emit(fixture.sample(accuracy = 10.0, timestamp = fixture.now - 100_000_000L))
        val accepted = fixture.latest.fix
        coarse.emit(fixture.sample(latitude = 0.0, longitude = 0.0, accuracy = 150.0))
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
        assertSame(accepted, fixture.latest.fix)
    }

    @Test fun newerMockFromAnotherSourceCannotBeClearedByHistoricalPreciseFix() {
        listOf(NearbyLocationPurpose.Reading, NearbyLocationPurpose.Publishing).forEach { purpose ->
            val risky = Source()
            val delayed = Source()
            val fixture = Fixture(listOf(risky, delayed), purpose)
            fixture.monitor.start()
            fixture.now += 1_000_000_000L
            risky.emit(fixture.sample(mock = true))
            delayed.emit(fixture.sample(timestamp = fixture.now - 100_000_000L))
            assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
            assertEquals(NearbyLocationProblem.MockLocation, fixture.latest.issue?.problem)
            assertNull(fixture.latest.fix)
        }
    }

    @Test fun newerInvalidCoordinatesCannotBeClearedByHistoricalCrossSourceFix() {
        val risky = Source()
        val delayed = Source()
        val fixture = Fixture(listOf(risky, delayed))
        fixture.monitor.start()
        fixture.now += 1_000_000_000L
        risky.emit(fixture.sample(latitude = 91.0))
        delayed.emit(fixture.sample(timestamp = fixture.now - 100_000_000L))
        assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
        assertEquals(NearbyLocationProblem.InvalidCoordinates, fixture.latest.issue?.problem)
        assertNull(fixture.latest.fix)
    }

    @Test fun acceptedNewerFixCannotBeReplacedByHistoricalCrossSourceFix() {
        val newest = Source()
        val delayed = Source()
        val fixture = Fixture(listOf(newest, delayed))
        fixture.monitor.start()
        fixture.now += 1_000_000_000L
        newest.emit(fixture.sample(latitude = 42.0))
        val accepted = fixture.latest.fix
        delayed.emit(fixture.sample(latitude = 0.0, timestamp = fixture.now - 100_000_000L))
        assertSame(accepted, fixture.latest.fix)
    }

    @Test fun legalBoundaryCoordinatesAreAcceptedWithoutTreatingZeroAsMissing() {
        listOf(-90.0 to -180.0, 90.0 to 180.0, 0.0 to 0.0).forEach { (latitude, longitude) ->
            val fixture = Fixture()
            fixture.monitor.start()
            fixture.source.emit(fixture.sample(latitude = latitude, longitude = longitude))
            assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
        }
    }

    @Test fun missingInvalidOrTooWideAccuracyIsNeverUsedForReading() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 35.0001).forEach { accuracy ->
            val withoutFix = Fixture().apply { monitor.start() }
            withoutFix.source.emit(withoutFix.sample(accuracy = accuracy))
            assertEquals(NearbyReaderLocationStatus.UnreliableFix, withoutFix.latest.status)
            assertNull(withoutFix.latest.fix)

            val withFix = startedWithFix()
            val accepted = withFix.latest.fix
            withFix.now += 1_000_000_000L
            withFix.source.emit(withFix.sample(latitude = 0.0, longitude = 0.0, accuracy = accuracy))
            assertSame(accepted, withFix.latest.fix)
        }
    }

    @Test fun readingQualityFailuresReportDistinctRedactedMetadata() {
        data class Case(
            val sample: Fixture.() -> NearbyReaderLocationSample,
            val problem: NearbyLocationProblem,
            val reportedAccuracy: Double?,
        )
        val cases = listOf(
            Case({ sample(accuracy = null) }, NearbyLocationProblem.AccuracyMissing, null),
            Case({ sample(accuracy = Double.NaN) }, NearbyLocationProblem.AccuracyMissing, null),
            Case({ sample(accuracy = 40.0) }, NearbyLocationProblem.AccuracyTooWide, 40.0),
            Case({ sample(latitude = 91.0) }, NearbyLocationProblem.InvalidCoordinates, 12.5),
            Case({ sample(timestamp = now + 1L) }, NearbyLocationProblem.InvalidTimestamp, null),
            Case({ sample(mock = true) }, NearbyLocationProblem.MockLocation, 12.5),
            Case({ val old = sample(); now += 8_000_000_000L; old }, NearbyLocationProblem.StaleSample, 12.5),
        )
        cases.forEach { case ->
            val fixture = Fixture()
            fixture.monitor.start()
            fixture.source.emit(case.sample(fixture))
            assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
            assertEquals(case.problem, fixture.latest.issue?.problem)
            assertEquals(case.reportedAccuracy, fixture.latest.issue?.accuracyMeters)
            val diagnostic = fixture.latest.issue.toString()
            assertFalse(diagnostic.contains("41.25"))
            assertFalse(diagnostic.contains("-72.75"))
        }
    }

    @Test fun publishingCoarseOrMissingUpdateKeepsExactFreshFixWithoutEmittingEvidence() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 35.0001).forEach { accuracy ->
            val fixture = startedPublishingWithFix()
            val accepted = fixture.latest.fix!!
            val updateCount = fixture.updates.size
            fixture.now += 2_000_000_000L
            fixture.source.emit(fixture.sample(latitude = 0.0, longitude = 0.0, accuracy = accuracy))
            assertEquals(updateCount, fixture.updates.size)
            assertSame(accepted, fixture.latest.fix)
            assertSame(accepted.quality, fixture.latest.fix!!.quality)
            assertNull(fixture.latest.issue)
        }
    }

    @Test fun publishingIgnoredCoarseTimestampDoesNotBlockARealQualifiedRecovery() {
        val fixture = startedPublishingWithFix()
        val original = fixture.latest.fix!!
        fixture.now += 2_000_000_000L
        fixture.source.emit(fixture.sample(latitude = 0.0, accuracy = 100.0))
        fixture.source.emit(fixture.sample(latitude = 42.0, timestamp = fixture.now - 1_000_000_000L))
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
        assertEquals(42.0, fixture.latest.fix!!.latitude, 0.0)
        assertNotSame(original, fixture.latest.fix)
    }

    @Test fun publishingIgnoredCoarseUpdateCannotRenewOriginalDeadline() {
        val fixture = startedPublishingWithFix()
        val accepted = fixture.latest.fix!!
        fixture.now += 7_999_000_000L
        fixture.source.emit(fixture.sample(accuracy = 100.0))
        assertSame(accepted, fixture.latest.fix)
        fixture.now += 1_000_000L
        fixture.monitor.tick()
        assertEquals(NearbyReaderLocationStatus.FixExpired, fixture.latest.status)
        assertNull(fixture.latest.fix)
    }

    @Test fun publishingDoesNotPreserveFixForCoarseSampleAtEightSecondBoundary() {
        val fixture = startedPublishingWithFix()
        fixture.now += 8_000_000_000L
        fixture.source.emit(fixture.sample(accuracy = 100.0))
        assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
        assertNull(fixture.latest.fix)
        assertEquals(NearbyLocationProblem.AccuracyTooWide, fixture.latest.issue?.problem)
    }

    @Test fun publishingStillClearsFixForSecurityIntegrityAndFreshnessFailures() {
        data class Case(val sample: Fixture.() -> NearbyReaderLocationSample, val problem: NearbyLocationProblem)
        val cases = listOf(
            Case({ sample(mock = true) }, NearbyLocationProblem.MockLocation),
            Case({ sample(timestamp = now + 1L) }, NearbyLocationProblem.InvalidTimestamp),
            Case({ sample(latitude = 91.0) }, NearbyLocationProblem.InvalidCoordinates),
            Case({ val old = sample(); now += 8_000_000_000L; old }, NearbyLocationProblem.StaleSample),
        )
        cases.forEach { case ->
            val fixture = startedPublishingWithFix()
            fixture.source.emit(case.sample(fixture))
            assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
            assertNull(fixture.latest.fix)
            assertEquals(case.problem, fixture.latest.issue?.problem)
        }
    }

    @Test fun publishingLifecycleLossStillStopsAndClearsPreservedFix() {
        val fixture = startedPublishingWithFix()
        fixture.now += 1_000_000_000L
        fixture.source.emit(fixture.sample(accuracy = 100.0))
        fixture.blocked = NearbyReaderLocationStatus.NotForeground
        fixture.monitor.tick()
        assertEquals(NearbyReaderLocationStatus.NotForeground, fixture.latest.status)
        assertNull(fixture.latest.fix)
        assertNull(fixture.latest.issue)
        assertFalse(fixture.monitor.isRunning)
        assertEquals(1, fixture.source.closes)
    }

    @Test fun thirtyFiveMeterBoundaryIsAccepted() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.source.emit(fixture.sample(accuracy = 35.0))
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
    }

    @Test fun mockFlagClearsAnExistingRealFixImmediately() {
        val fixture = startedWithFix()
        fixture.source.emit(fixture.sample(mock = true))
        assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
        assertNull(fixture.latest.fix)
    }

    @Test fun futureAndNegativeTimestampsAreRejectedWithoutTolerance() {
        listOf(-1L, START_NANOS + 1L, Long.MAX_VALUE).forEach { time ->
            val fixture = startedWithFix()
            fixture.source.emit(fixture.sample(timestamp = time))
            assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
            assertNull(fixture.latest.fix)
        }
    }

    @Test fun delayedEightSecondOldSampleNeverBecomesCurrent() {
        val fixture = Fixture()
        fixture.monitor.start()
        val sampled = fixture.sample()
        fixture.now += 8_000_000_000L
        fixture.source.emit(sampled)
        assertEquals(NearbyReaderLocationStatus.UnreliableFix, fixture.latest.status)
        assertNull(fixture.latest.fix)
    }

    @Test fun fixExpiresExactlyAtGateDeadlineEvenWithoutProviderCallbacks() {
        val fixture = startedWithFix()
        fixture.now += 7_999_000_000L
        fixture.monitor.tick()
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
        assertEquals(1L, fixture.monitor.nextPollDelayMillis())
        fixture.now += 1_000_000L
        fixture.monitor.tick()
        assertEquals(NearbyReaderLocationStatus.FixExpired, fixture.latest.status)
        assertNull(fixture.latest.fix)
        assertTrue(fixture.monitor.isRunning)
        assertEquals(0, fixture.source.closes)
    }

    @Test fun expiryUsesConservativeGateMillisecondsRatherThanRoundingLate() {
        val fixture = Fixture()
        fixture.monitor.start()
        fixture.now += 999_999L
        fixture.source.emit(fixture.sample())
        fixture.now = START_NANOS + 8_000_000_000L
        fixture.monitor.tick()
        assertEquals(NearbyReaderLocationStatus.FixExpired, fixture.latest.status)
    }

    @Test fun aNewValidFixRecoversAfterInvalidOrExpiredSample() {
        val fixture = startedWithFix()
        fixture.source.emit(fixture.sample(mock = true))
        fixture.now += 1_000_000_000L
        fixture.source.emit(fixture.sample())
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
        fixture.now += 8_000_000_000L
        fixture.monitor.tick()
        fixture.source.emit(fixture.sample())
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
    }

    @Test fun pauseDestroyConsentOrPermissionLossStopsAndClearsAllProviders() {
        listOf(
            NearbyReaderLocationStatus.NotForeground,
            NearbyReaderLocationStatus.ConsentRequired,
            NearbyReaderLocationStatus.PrecisePermissionRequired,
            NearbyReaderLocationStatus.ServicesDisabled,
        ).forEach { reason ->
            val first = Source()
            val second = Source()
            val fixture = Fixture(listOf(first, second))
            fixture.monitor.start()
            first.emit(fixture.sample())
            fixture.blocked = reason
            fixture.monitor.tick()
            assertEquals(reason, fixture.latest.status)
            assertNull(fixture.latest.fix)
            assertFalse(fixture.monitor.isRunning)
            assertEquals(1, first.closes)
            assertEquals(1, second.closes)
            fixture.blocked = null
            first.emit(fixture.sample())
            fixture.monitor.start()
            assertEquals(reason, fixture.latest.status)
            assertEquals(1, first.starts)
        }
    }

    @Test fun revokedPermissionIsCheckedAgainBeforeProcessingCandidate() {
        val fixture = startedWithFix()
        fixture.blocked = NearbyReaderLocationStatus.PrecisePermissionRequired
        fixture.source.emit(fixture.sample())
        assertEquals(NearbyReaderLocationStatus.PrecisePermissionRequired, fixture.latest.status)
        assertNull(fixture.latest.fix)
        assertEquals(1, fixture.source.closes)
    }

    @Test fun explicitStopIsIdempotentAndLateCallbacksCannotRevive() {
        val fixture = startedWithFix()
        fixture.monitor.stop()
        val count = fixture.updates.size
        fixture.monitor.stop()
        fixture.source.emit(fixture.sample())
        fixture.monitor.tick()
        fixture.monitor.start()
        assertEquals(NearbyReaderLocationStatus.Stopped, fixture.latest.status)
        assertNull(fixture.latest.fix)
        assertEquals(count, fixture.updates.size)
        assertEquals(1, fixture.source.closes)
        assertEquals(1, fixture.source.starts)
    }

    @Test fun clockRollbackClearsFixAndStopsPermanently() {
        val fixture = startedWithFix()
        fixture.now -= 1L
        fixture.monitor.tick()
        assertEquals(NearbyReaderLocationStatus.ClockInvalid, fixture.latest.status)
        assertNull(fixture.latest.fix)
        assertFalse(fixture.monitor.isRunning)
        assertEquals(1, fixture.source.closes)
    }

    @Test fun negativeClockNeverStartsProvider() {
        val fixture = Fixture()
        fixture.now = -1L
        fixture.monitor.start()
        assertEquals(NearbyReaderLocationStatus.ClockInvalid, fixture.latest.status)
        assertEquals(0, fixture.source.starts)
    }

    @Test fun providerThatBecomesAvailableIsSubscribedOnHeartbeatWithinTheSameSession() {
        val recovering = Source(absent = true)
        val fixture = Fixture(listOf(recovering))
        fixture.monitor.start()
        assertEquals(NearbyReaderLocationStatus.WaitingForFix, fixture.latest.status)
        assertTrue(fixture.monitor.isRunning)
        assertEquals(1, recovering.starts)

        recovering.absent = false
        fixture.monitor.tick()
        recovering.emit(fixture.sample())

        assertEquals(2, recovering.starts)
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
    }

    @Test fun oneFailingProviderDoesNotStarveHealthyProviders() {
        val broken = Source(failRegistration = true)
        val healthy = Source()
        val fixture = Fixture(listOf(broken, healthy))
        fixture.monitor.start()
        healthy.emit(fixture.sample())
        assertEquals(NearbyReaderLocationStatus.Ready, fixture.latest.status)
        assertEquals(1, healthy.starts)
    }

    @Test fun revocationDuringRegistrationCleansEarlierSourcesAndSkipsLaterOnes() {
        val first = Source()
        val last = Source()
        lateinit var fixture: Fixture
        val middle = LocationUpdateSource<NearbyReaderLocationSample> {
            fixture.blocked = NearbyReaderLocationStatus.PrecisePermissionRequired
            throw SecurityException("private platform details")
        }
        fixture = Fixture(listOf(first, middle, last))
        fixture.monitor.start()
        assertEquals(NearbyReaderLocationStatus.PrecisePermissionRequired, fixture.latest.status)
        assertEquals(1, first.closes)
        assertEquals(0, last.starts)
    }

    @Test fun failingCleanupDoesNotPreventOtherSourcesClosing() {
        val first = Source()
        val second = Source(failCleanup = true)
        val third = Source()
        val fixture = Fixture(listOf(first, second, third))
        fixture.monitor.start()
        fixture.monitor.stop()
        listOf(first, second, third).forEach { assertEquals(1, it.closes) }
    }

    @Test fun callbackStoppingDuringRegistrationAlsoClosesTheJustCreatedSubscription() {
        val source = Source(immediate = sampleAtStart())
        val last = Source()
        lateinit var fixture: Fixture
        fixture = Fixture(listOf(source, last)) { update ->
            if (update.status == NearbyReaderLocationStatus.Ready) fixture.monitor.stop()
        }
        fixture.monitor.start()
        assertFalse(fixture.monitor.isRunning)
        assertEquals(1, source.closes)
        assertEquals(0, last.starts)
    }

    @Test fun consumerExceptionClosesRegisteredSourcesAndIgnoresLateFixes() {
        val source = Source()
        val fixture = Fixture(listOf(source)) { update ->
            if (update.status == NearbyReaderLocationStatus.Ready) throw IllegalStateException("consumer failed")
        }
        fixture.monitor.start()
        val failure = runCatching { source.emit(fixture.sample()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(fixture.monitor.isRunning)
        assertEquals(1, source.closes)
        val count = fixture.updates.size
        source.emit(fixture.sample())
        assertEquals(count, fixture.updates.size)
    }

    @Test fun redactedStringsDoNotExposeCoordinatesOrAccuracy() {
        val fixture = startedWithFix()
        val sample = fixture.sample()
        val fix = fixture.latest.fix!!
        listOf(sample.toString(), fix.toString(), fixture.latest.toString()).forEach { text ->
            assertFalse(text.contains("41.25"))
            assertFalse(text.contains("-72.75"))
            assertTrue(text.contains("redacted"))
        }
    }

    @Test fun noLocationStatusByItselfFabricatesReadingEligibility() {
        val fixture = startedWithFix()
        val gate = NearbyReadingGate("reader-session")
        val now = fixture.now / 1_000_000L
        assertEquals(ReadingEligibility.UNKNOWN, gate.updateEnvironment(
            foreground = true, precisePermission = true, networkAvailable = true,
            readerFix = fixture.latest.fix!!.quality, nowElapsedMillis = now,
        ))
        assertSame(fixture.latest.fix!!.quality, gate.beginCheck(now)!!.readerFix)
    }

    private fun startedWithFix() = Fixture().apply {
        monitor.start()
        source.emit(sample())
        assertEquals(NearbyReaderLocationStatus.Ready, latest.status)
    }

    private fun startedPublishingWithFix() = Fixture(purpose = NearbyLocationPurpose.Publishing).apply {
        monitor.start()
        source.emit(sample())
        assertEquals(NearbyReaderLocationStatus.Ready, latest.status)
    }

    private class Fixture(
        sources: List<LocationUpdateSource<NearbyReaderLocationSample>> = listOf(Source()),
        purpose: NearbyLocationPurpose = NearbyLocationPurpose.Reading,
        observer: (NearbyReaderLocationUpdate) -> Unit = {},
    ) {
        var now = START_NANOS
        var blocked: NearbyReaderLocationStatus? = null
        val source: Source get() = sourcesForTest.first() as Source
        private val sourcesForTest = sources
        val updates = mutableListOf<NearbyReaderLocationUpdate>()
        val latest: NearbyReaderLocationUpdate get() = updates.last()
        val monitor = NearbyReaderLocationMonitor(sources, { now }, { blocked }, purpose) {
            updates += it
            observer(it)
        }
        fun sample(
            latitude: Double = 41.25,
            longitude: Double = -72.75,
            accuracy: Double? = 12.5,
            timestamp: Long = now,
            mock: Boolean = false,
        ) = NearbyReaderLocationSample(latitude, longitude, accuracy, timestamp, mock)
    }

    private class Source(
        var absent: Boolean = false,
        private val failRegistration: Boolean = false,
        private val failCleanup: Boolean = false,
        private val immediate: NearbyReaderLocationSample? = null,
    ) : LocationUpdateSource<NearbyReaderLocationSample> {
        var starts = 0
        var closes = 0
        private var callback: ((NearbyReaderLocationSample) -> Unit)? = null
        override fun subscribe(onLocation: (NearbyReaderLocationSample) -> Unit): LocationUpdateSubscription? {
            starts += 1
            if (absent) return null
            if (failRegistration) throw IllegalStateException("private provider details")
            callback = onLocation
            immediate?.let(onLocation)
            return LocationUpdateSubscription {
                closes += 1
                if (failCleanup) throw IllegalStateException("private cleanup details")
            }
        }
        fun emit(sample: NearbyReaderLocationSample) { callback?.invoke(sample) }
    }

    private companion object {
        const val START_NANOS = 100_000_000_000L
        fun sampleAtStart() = NearbyReaderLocationSample(41.25, -72.75, 12.5, START_NANOS, false)
    }
}
