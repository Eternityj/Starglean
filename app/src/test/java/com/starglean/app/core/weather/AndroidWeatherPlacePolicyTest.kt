package com.starglean.app.core.weather

import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Callback-policy tests only: these do not exercise a device's geocoder backend. */
class AndroidWeatherPlacePolicyTest {
    // Public synthetic fixture, never a user's observed location.
    private val query = WeatherQueryLocation.fromCoordinates(-37.813629, 144.963058)

    @Test fun systemGeocodingIsOptIn() {
        assertFalse(shouldResolveAndroidWeatherPlace(false))
        assertTrue(shouldResolveAndroidWeatherPlace(true))
        assertEquals(3_000L, ANDROID_WEATHER_PLACE_TIMEOUT_MILLIS)
    }

    @Test fun districtAndCityAreTrimmedJoinedAndDeduplicatedInPriorityOrder() {
        assertEquals("Carlton · Melbourne", androidWeatherPlaceName(" Melbourne ", " Carlton ", "Victoria", "Australia"))
        assertEquals("melbourne", androidWeatherPlaceName("Melbourne", " melbourne ", "Victoria", "Australia"))
    }

    @Test fun eitherPrimaryFieldPreventsBroadFallback() {
        assertEquals("Melbourne", androidWeatherPlaceName("Melbourne", null, "Victoria", "Australia"))
        assertEquals("Carlton", androidWeatherPlaceName(null, "Carlton", "Victoria", "Australia"))
    }

    @Test fun fallbackUsesOnlyRegionAndCountryWhenBothPrimaryFieldsAreMissing() {
        assertEquals("Victoria · Australia", androidWeatherPlaceName(null, " ", "Victoria", "Australia"))
        assertEquals("Australia", androidWeatherPlaceName(null, null, null, "Australia"))
        assertEquals("Singapore", androidWeatherPlaceName(null, null, "Singapore", "singapore"))
        assertNull(androidWeatherPlaceName(null, null, null, null))
    }

    @Test fun malformedOrCoordinateOnlyLabelsCannotReachTheUi() {
        assertNull(androidWeatherPlaceName("\u202eMelbourne", "144.96, -37.81", null, null))
        assertEquals("B".repeat(60), androidWeatherPlaceName("A".repeat(60), "B".repeat(60), null, null))
        assertNull(androidWeatherPlaceName("\uD800", null, null, null))
        assertEquals("Carlton", androidWeatherPlaceName("\nMelbourne", "Carlton", null, null))
    }

    @Test fun structuredStreetHasPriorityButNoStreetIsInventedWhenAbsent() {
        assertEquals("Lygon Street · Carlton · Melbourne",
            androidWeatherPlaceName("Melbourne", "Carlton", "Victoria", "Australia", " Lygon Street ", "123"))
        assertEquals("Carlton · Melbourne", androidWeatherPlaceName("Melbourne", "Carlton", "Victoria", "Australia"))
        assertEquals("Lygon Street", androidWeatherPlaceName(null, null, "Victoria", "Australia", "Lygon Street"))
    }

    @Test fun aClearlySeparatedKnownDoorIsRemovedWithoutChangingTheStreet() {
        listOf("123 Lygon Street", "Lygon Street 123").forEach { street ->
            assertEquals("Lygon Street · Carlton · Melbourne",
                androidWeatherPlaceName("Melbourne", "Carlton", null, null, street, "123"))
        }
        assertEquals("Lygon Street", androidWeatherPlaceName(null, null, null, null, "12A Lygon Street", "12A"))
        assertEquals("Lygon Street", androidWeatherPlaceName(null, null, null, null, "10–12 Lygon Street", "10–12"))
    }

    @Test fun ambiguousOrUnseparatedDoorAndUnitFieldsFallBackToAnArea() {
        listOf("123Lygon Street", "Unit 4 / 123 Lygon Street", "10–123 Lygon Street", "Lygon Street, 123 Carlton")
            .forEach { street ->
                assertEquals("Carlton · Melbourne", androidWeatherPlaceName("Melbourne", "Carlton", null, null, street, "123"))
            }
        assertEquals("Carlton", androidWeatherPlaceName(null, "Carlton", null, null, "123 Lygon Street"))
        assertEquals("Carlton", androidWeatherPlaceName(null, "Carlton", null, null, "123 Road", "123"))
        assertEquals("5th Avenue", androidWeatherPlaceName(null, null, null, null, "5th Avenue"))
    }

    @Test fun malformedHouseMetadataDoesNotEraseACleanStructuredStreet() {
        listOf("Unit #4", "x".repeat(33), "\n123").forEach { malformedHouse ->
            assertEquals("Lygon Street", weatherStreetWithoutDoor("Lygon Street", malformedHouse))
        }
        assertEquals("Carlton · Melbourne",
            androidWeatherPlaceName("Melbourne", "Carlton", null, null, "123Lygon Street", "123"))
    }

    @Test fun granularityComesFromStructuredFieldsRatherThanDisplayText() {
        assertEquals(WeatherPlaceGranularity.Street,
            androidWeatherPlace("Melbourne", "Carlton", null, null, "Lygon Street")?.granularity)
        assertEquals(WeatherPlaceGranularity.Area,
            androidWeatherPlace("Melbourne", "Carlton", null, null)?.granularity)
        assertEquals(WeatherPlaceGranularity.City,
            androidWeatherPlace("Melbourne", null, null, null)?.granularity)
        assertEquals(WeatherPlaceGranularity.Region,
            androidWeatherPlace(null, null, "Victoria", "Australia")?.granularity)
    }

    @Test fun candidateSelectionPrefersOnlyANearbyStreetAndFallsBackToArea() {
        val city = WeatherPlace("Carlton · Melbourne", WeatherPlaceGranularity.Area)
        val near = WeatherPlace("Lygon Street · Carlton · Melbourne", WeatherPlaceGranularity.Street)
        val far = WeatherPlace("Unrelated Road · Elsewhere", WeatherPlaceGranularity.Street)
        val selected = selectAndroidWeatherPlace(-37.8000, 144.9600, 20f, listOf(
            AndroidWeatherPlaceCandidate(city),
            AndroidWeatherPlaceCandidate(far, WeatherPlace("Elsewhere", WeatherPlaceGranularity.City), -37.79865, 144.9600),
            AndroidWeatherPlaceCandidate(near, city, -37.8003, 144.9600),
        ))
        assertEquals(near, selected)
        val noNearStreet = selectAndroidWeatherPlace(-37.8000, 144.9600, 20f, listOf(
            AndroidWeatherPlaceCandidate(far, city, -37.79865, 144.9600)))
        assertEquals(city, noNearStreet) // roughly 150 m is not accepted as "within 200 m"
    }

    @Test fun onlyTheFirstCoordinateLessCandidateMaySupplyAStreet() {
        val city = WeatherPlace("Melbourne", WeatherPlaceGranularity.City)
        val street = WeatherPlace("Lygon Street · Melbourne", WeatherPlaceGranularity.Street)
        assertEquals(city, selectAndroidWeatherPlace(-37.8, 144.96, 20f, listOf(
            AndroidWeatherPlaceCandidate(city), AndroidWeatherPlaceCandidate(street, city))))
        assertEquals(street, selectAndroidWeatherPlace(-37.8, 144.96, 20f, listOf(
            AndroidWeatherPlaceCandidate(street, city))))
    }

    @Test fun malformedOrPartialCandidateCoordinatesFailClosed() {
        val city = WeatherPlace("Melbourne", WeatherPlaceGranularity.City)
        val street = WeatherPlace("Lygon Street · Melbourne", WeatherPlaceGranularity.Street)
        listOf(
            AndroidWeatherPlaceCandidate(street, city, Double.NaN, 144.96),
            AndroidWeatherPlaceCandidate(street, city, -37.8, null),
            AndroidWeatherPlaceCandidate(street, city, null, 144.96),
            AndroidWeatherPlaceCandidate(street, city, 91.0, 144.96),
            AndroidWeatherPlaceCandidate(street, city, -37.8, 181.0),
        ).forEach { malformed ->
            assertNull(selectAndroidWeatherPlace(-37.8, 144.96, 20f, listOf(malformed)))
        }
        assertEquals(street, selectAndroidWeatherPlace(-37.8, 144.96, 20f,
            listOf(AndroidWeatherPlaceCandidate(street, city))))
    }

    @Test fun higherPriorityWholeNamesSurviveTheOneHundredCodePointBudget() {
        val street = "A".repeat(80)
        assertEquals("$street · City", androidWeatherPlaceName("City", "B".repeat(30), null, null, street))
        assertEquals("S".repeat(100), androidWeatherPlaceName("City", "District", null, null, "S".repeat(100)))
        assertEquals("District · City", androidWeatherPlaceName("City", "District", null, null, "S".repeat(101)))
        val supplementary = "\uD801\uDC00".repeat(60)
        assertEquals("$supplementary · City", androidWeatherPlaceName("City", null, null, null, supplementary))
        assertEquals("Lygon Street · Melbourne", androidWeatherPlaceName("Melbourne", "LYGON STREET", null, null, "Lygon Street"))
    }

    @Test fun reverseGeocodingRetainsTheOriginalAccuracyMetadataAndHttpIdentity() = runBlocking {
        val qualified = WeatherQueryLocation.fromCoordinates(query.latitude, query.longitude, accuracyMeters = 12f)
        val named = withOptionalAndroidWeatherPlace(qualified, WeatherPlaceRequestGate()) { _, _, receiver ->
            receiver.finish("Lygon Street · Carlton · Melbourne")
        }
        assertEquals(qualified.accuracyMeters, named.accuracyMeters)
        assertEquals(openMeteoCurrentUrl(qualified).toExternalForm(), openMeteoCurrentUrl(named).toExternalForm())
        assertEquals("WeatherQueryLocation(redacted)", named.toString())
        val unnamed = withOptionalAndroidWeatherPlace(qualified, WeatherPlaceRequestGate()) { _, _, receiver -> receiver.finish(null) }
        assertSame(qualified, unnamed)
        assertNull(query.accuracyMeters)
    }

    @Test fun exactQualifiedCoordinatesAreUsedAndPreservedWithTheReturnedName() = runBlocking {
        val named = withOptionalAndroidWeatherPlace(query, WeatherPlaceRequestGate()) { latitude, longitude, receiver ->
            assertEquals(query.latitude, latitude, 0.0)
            assertEquals(query.longitude, longitude, 0.0)
            receiver.finish("Melbourne · Carlton")
        }
        assertEquals(query.latitude, named.latitude, 0.0)
        assertEquals(query.longitude, named.longitude, 0.0)
        assertEquals("Melbourne · Carlton", named.placeName)
        assertEquals(openMeteoCurrentUrl(query).toExternalForm(), openMeteoCurrentUrl(named).toExternalForm())
        assertEquals("WeatherQueryLocation(redacted)", named.toString())
    }

    @Test fun missingOrInvalidNameReturnsTheOriginalValidQuery() = runBlocking {
        listOf(null, "", "-37.81, 144.96", "\nMelbourne").forEach { value ->
            val result = withOptionalAndroidWeatherPlace(query, WeatherPlaceRequestGate()) { _, _, receiver ->
                receiver.finish(value)
            }
            assertSame(query, result)
        }
    }

    @Test fun silentSystemRequestTimesOutWithoutWaitingForItsCallback() = runBlocking {
        val gate = WeatherPlaceRequestGate()
        lateinit var first: WeatherPlaceReceiver
        val result = withTimeout(2_000L) {
            withOptionalAndroidWeatherPlace(query, gate, timeoutMillis = 30L) { _, _, receiver -> first = receiver }
        }
        assertSame(query, result)
        assertFalse(first.isActive)
        var laterStarts = 0
        repeat(20) {
            assertSame(query, withOptionalAndroidWeatherPlace(query, gate) { _, _, _ -> laterStarts += 1 })
        }
        assertEquals(0, laterStarts)
        // A late result releases the OS slot but cannot retroactively label weather.
        first.finish("Late city")
        assertNull(result.placeName)
        val next = withOptionalAndroidWeatherPlace(query, gate) { _, _, receiver -> receiver.finish("Next city") }
        assertEquals("Next city", next.placeName)
    }

    @Test fun parentCancellationPropagatesAndLateCallbackCannotReviveIt() = runBlocking {
        val gate = WeatherPlaceRequestGate()
        lateinit var receiver: WeatherPlaceReceiver
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withOptionalAndroidWeatherPlace(query, gate) { _, _, active -> receiver = active }
        }
        assertTrue(receiver.isActive)
        result.cancelAndJoin()
        assertFalse(receiver.isActive)
        assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
        assertNull(gate.acquire {})
        receiver.finish("Late city")
        assertTrue(result.isCancelled)
        val next = gate.acquire {}
        assertNotNull(next)
        next!!.finish(null)
    }

    @Test fun secondCallerSkipsBusyBackendWithoutCancellingTheFirst() = runBlocking {
        val gate = WeatherPlaceRequestGate()
        lateinit var receiver: WeatherPlaceReceiver
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            withOptionalAndroidWeatherPlace(query, gate) { _, _, active -> receiver = active }
        }
        var secondStarted = false
        val second = withOptionalAndroidWeatherPlace(query, gate) { _, _, _ -> secondStarted = true }
        assertFalse(secondStarted)
        assertSame(query, second)
        assertTrue(receiver.isActive)
        receiver.finish("First city")
        assertEquals("First city", first.await().placeName)
    }

    @Test fun rejectedExecutorAndBackendErrorsKeepTheFixAndReleaseTheSlot() = runBlocking {
        val gate = WeatherPlaceRequestGate()
        val rejected = withOptionalAndroidWeatherPlace(query, gate) { _, _, _ -> throw RejectedExecutionException() }
        assertSame(query, rejected)
        val unavailable = withOptionalAndroidWeatherPlace(query, gate) { _, _, receiver -> receiver.finish(null) }
        assertSame(query, unavailable)
        val success = withOptionalAndroidWeatherPlace(query, gate) { _, _, receiver -> receiver.finish("City") }
        assertEquals("City", success.placeName)
    }

    @Test fun cancellationThrownByLauncherIsNotConvertedToMissingPlace() = runBlocking {
        val gate = WeatherPlaceRequestGate()
        val failure = runCatching {
            withOptionalAndroidWeatherPlace(query, gate) { _, _, _ -> throw CancellationException("cancelled") }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        val next = gate.acquire {}
        assertNotNull(next)
        next!!.finish(null)
    }

    @Test fun duplicateOldCompletionCannotReleaseANewerSystemRequest() {
        val gate = WeatherPlaceRequestGate()
        var deliveries = 0
        val first = gate.acquire { deliveries += 1 }!!
        first.finish("City")
        assertEquals(1, deliveries)
        val second = gate.acquire {}!!
        first.finish("Duplicate")
        assertEquals(1, deliveries)
        assertNull(gate.acquire {})
        second.finish(null)
        val third = gate.acquire {}
        assertNotNull(third)
        third!!.finish(null)
    }

    @Test fun detachedReceiverForgetsItsCallbackButDoesNotFreeTheUnderlyingCall() {
        val gate = WeatherPlaceRequestGate()
        var deliveries = 0
        val receiver = gate.acquire { deliveries += 1 }!!
        receiver.detach()
        assertFalse(receiver.isActive)
        assertNull(gate.acquire {})
        receiver.finish("Late city")
        assertEquals(0, deliveries)
        val next = gate.acquire {}
        assertNotNull(next)
        next!!.finish(null)
    }
}
