package com.starglean.app.core.location

import com.starglean.app.core.memory.isMemoryLocationReliable
import com.starglean.app.core.weather.WeatherQueryLocation
import com.starglean.app.core.weather.isWeatherLocationReliable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualifiedLocationRequestTest {
    @Test fun budgetsAreSharedWindowsNotPerProviderSlices() {
        assertEquals(30_000L, WEATHER_LOCATION_REQUEST_TIMEOUT_MILLIS)
        assertEquals(45_000L, MEMORY_LOCATION_REQUEST_TIMEOUT_MILLIS)
    }

    @Test fun everySourceStartsBeforeEvenAnImmediateCandidateIsAccepted() = runBlocking {
        val sources = listOf(FakeSource(listOf(7)), FakeSource(), FakeSource())
        val result = awaitQualifiedLocation(sources, 2_000L, {}) { candidate ->
            assertTrue(sources.all { it.starts == 1 })
            candidate
        }
        assertEquals(7, result)
        sources.forEach { assertEquals(1, it.closes) }
    }

    @Test fun silentFirstTwoSourcesDoNotDelayTheThirdSource() = runBlocking {
        val network = FakeSource()
        val fused = FakeSource()
        val gps = FakeSource(listOf(12))
        val result = withTimeout(2_000L) {
            awaitQualifiedLocation(listOf(network, fused, gps), 45_000L, {}, ::positive)
        }
        assertEquals(12, result)
        listOf(network, fused, gps).forEach { assertEquals(1, it.closes) }
    }

    @Test fun rejectedFirstFixKeepsEverySourceAliveUntilABetterFixArrives() = runBlocking {
        val first = FakeSource()
        val second = FakeSource()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            awaitQualifiedLocation(listOf(first, second), 2_000L, {}, ::positive)
        }
        first.emit(-1)
        yield()
        assertTrue(result.isActive)
        assertEquals(0, first.closes)
        assertEquals(0, second.closes)
        first.emit(42)
        assertEquals(42, result.await())
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test fun providerSecurityAndRuntimeFailuresDoNotPreventAnotherSource() = runBlocking {
        val coarseOnlyGps = LocationUpdateSource<Int> { throw SecurityException("private platform details") }
        val brokenProvider = LocationUpdateSource<Int> { throw IllegalStateException("private platform details") }
        val healthy = FakeSource(listOf(5))
        assertEquals(5, awaitQualifiedLocation(listOf(coarseOnlyGps, brokenProvider, healthy), 2_000L, {}, ::positive))
        assertEquals(1, healthy.closes)
    }

    @Test fun providerThatBecomesAvailableDuringTheSameRequestIsSubscribedWithoutExtendingDeadline() = runBlocking {
        var attempts = 0
        var callback: ((Int) -> Unit)? = null
        var closes = 0
        val source = LocationUpdateSource<Int> { receiver ->
            attempts += 1
            if (attempts == 1) null else {
                callback = receiver
                LocationUpdateSubscription { closes += 1 }
            }
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            awaitQualifiedLocation(listOf(source), 2_500L, {}, ::positive)
        }

        kotlinx.coroutines.delay(1_100L)
        callback?.invoke(7)

        assertEquals(7, result.await())
        assertTrue(attempts >= 2)
        assertEquals(1, closes)
    }

    @Test fun noCandidateTimesOutAndClosesAllSources() = runBlocking {
        val sources = listOf(FakeSource(), FakeSource(), FakeSource())
        val failure = failureOf { awaitQualifiedLocation(sources, 60L, {}, ::positive) }
        assertEquals(ForegroundLocationFailure.Timeout, failure.code)
        sources.forEach { assertEquals(1, it.closes) }
    }

    @Test fun onlyRejectedCandidatesProduceUnreliableAtTheDeadline() = runBlocking {
        val source = FakeSource(listOf(-1, -2))
        val failure = failureOf { awaitQualifiedLocation(listOf(source), 60L, {}, ::positive) }
        assertEquals(ForegroundLocationFailure.Unreliable, failure.code)
        assertEquals(1, source.closes)
    }

    @Test fun parentCancellationIsNotConvertedToALocationFailureAndClosesAllSources() = runBlocking {
        val sources = listOf(FakeSource(), FakeSource(), FakeSource())
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            awaitQualifiedLocation(sources, 45_000L, {}, ::positive)
        }
        assertTrue(sources.all { it.starts == 1 })
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
        sources.forEach { assertEquals(1, it.closes) }
        assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
        // OEM callbacks already queued on the looper cannot revive a finished request.
        sources.forEach { it.emit(9) }
        sources.forEach { assertEquals(1, it.closes) }
    }

    @Test fun qualificationExceptionStillClosesEverySource() = runBlocking {
        val sources = listOf(FakeSource(listOf(4)), FakeSource())
        val failure = runCatching {
            awaitQualifiedLocation<Int, Int>(sources, 2_000L, {}) { throw IllegalArgumentException("invalid candidate") }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        sources.forEach { assertEquals(1, it.closes) }
    }

    @Test fun revokedPermissionAfterCandidateValidationPreventsReturningTheFix() = runBlocking {
        val source = FakeSource(listOf(3))
        var allowed = true
        val failure = failureOf {
            awaitQualifiedLocation(listOf(source), 2_000L, {
                if (!allowed) throw ForegroundLocationException(ForegroundLocationFailure.PermissionRequired)
            }) {
                allowed = false
                it
            }
        }
        assertEquals(ForegroundLocationFailure.PermissionRequired, failure.code)
        assertEquals(1, source.closes)
    }

    @Test fun silentProviderStillObservesSystemServiceDisablement() = runBlocking {
        val source = FakeSource()
        var enabled = true
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            failureOf {
                awaitQualifiedLocation(listOf(source), 4_000L, {
                    if (!enabled) throw ForegroundLocationException(ForegroundLocationFailure.ServicesDisabled)
                }, ::positive)
            }
        }
        enabled = false
        val failure = withTimeout(3_000L) { result.await() }
        assertEquals(ForegroundLocationFailure.ServicesDisabled, failure.code)
        assertEquals(1, source.closes)
    }

    @Test fun permissionLossDuringRegistrationCleansEarlierSourcesAndDoesNotStartLaterOnes() = runBlocking {
        val first = FakeSource()
        val last = FakeSource()
        var allowed = true
        val denied = LocationUpdateSource<Int> {
            allowed = false
            throw SecurityException("do not expose platform data")
        }
        val failure = failureOf {
            awaitQualifiedLocation(listOf(first, denied, last), 2_000L, {
                if (!allowed) throw ForegroundLocationException(ForegroundLocationFailure.PermissionRequired)
            }, ::positive)
        }
        assertEquals(ForegroundLocationFailure.PermissionRequired, failure.code)
        assertEquals(1, first.closes)
        assertEquals(0, last.starts)
    }

    @Test fun oneCleanupFailureDoesNotPreventOtherSubscriptionsFromClosing() = runBlocking {
        val first = FakeSource(listOf(8))
        val broken = FakeSource(throwOnClose = true)
        val last = FakeSource()
        assertEquals(8, awaitQualifiedLocation(listOf(first, broken, last), 2_000L, {}, ::positive))
        listOf(first, broken, last).forEach { assertEquals(1, it.closes) }
    }

    @Test fun cancelledBeforeEntryNeverSubscribes() = runBlocking {
        val source = FakeSource()
        val result = async(start = CoroutineStart.LAZY) {
            awaitQualifiedLocation(listOf(source), 45_000L, {}, ::positive)
        }
        result.cancelAndJoin()
        assertEquals(0, source.starts)
    }

    @Test fun retryLoopPreservesMemoryFreshnessAndHundredMeterAccuracyThreshold() = runBlocking {
        val now = 1_700_000_000_000L
        val elapsed = 200_000_000_000L
        // Public, synthetic test candidates, not Android/device location data.
        val candidates = listOf(
            Fix(accuracy = 100.1f),
            Fix(ageMillis = 45_001L),
            Fix(accuracy = 20f, ageMillis = 1_000L),
        )
        val source = LocationUpdateSource<Fix> { callback ->
            candidates.forEach(callback)
            LocationUpdateSubscription {}
        }
        var examined = 0
        val accepted = awaitQualifiedLocation(listOf(source), 2_000L, {}) { candidate ->
            examined += 1
            candidate.takeIf {
                isMemoryLocationReliable(-37.8136123, 144.9631987, true, it.accuracy,
                    now - it.ageMillis, now, elapsed - it.ageMillis * 1_000_000L, elapsed)
            }
        }
        assertEquals(3, examined)
        assertEquals(candidates.last(), accepted)
    }

    @Test fun retryLoopPreservesWeatherFreshnessAndOnlyPassesAcceptedExactCoordinates() = runBlocking {
        val elapsed = 200_000_000_000L
        val candidates = listOf(Fix(ageMillis = 120_001L), Fix(accuracy = 100.1f), Fix(accuracy = 30f))
        val source = LocationUpdateSource<Fix> { callback ->
            candidates.forEach(callback)
            LocationUpdateSubscription {}
        }
        var examined = 0
        val accepted = awaitQualifiedLocation(listOf(source), 2_000L, {}) {
            examined += 1
            if (isWeatherLocationReliable(-37.8136123, 144.9631987, true, it.accuracy,
                    elapsed - it.ageMillis * 1_000_000L, elapsed)
            ) WeatherQueryLocation.fromCoordinates(-37.8136123, 144.9631987) else null
        }
        assertEquals(3, examined)
        assertEquals(-37.8136123, accepted.latitude, 0.0)
        assertEquals(144.9631987, accepted.longitude, 0.0)
        assertFalse(accepted.toString().contains("37.8136123"))
    }

    private data class Fix(val accuracy: Float = 10f, val ageMillis: Long = 0L)

    private class FakeSource(
        private val initial: List<Int> = emptyList(),
        private val throwOnClose: Boolean = false,
    ) : LocationUpdateSource<Int> {
        var starts = 0
        var closes = 0
        private var callback: ((Int) -> Unit)? = null

        override fun subscribe(onLocation: (Int) -> Unit): LocationUpdateSubscription {
            starts += 1
            callback = onLocation
            initial.forEach(onLocation)
            return LocationUpdateSubscription {
                closes += 1
                if (throwOnClose) throw IllegalStateException("private OEM cleanup details")
            }
        }

        fun emit(candidate: Int) { checkNotNull(callback)(candidate) }
    }

    private fun positive(value: Int): Int? = value.takeIf { it > 0 }

    private suspend fun failureOf(block: suspend () -> Any): ForegroundLocationException {
        try {
            block()
        } catch (failure: ForegroundLocationException) {
            return failure
        }
        throw AssertionError("Expected a safe location failure")
    }
}
