package com.starglean.app.core.weather

import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.WeatherKind
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CurrentWritingWeatherTest {
    private val now = Instant.parse("2026-09-15T02:20:00Z")
    private val valid = OpenMeteoCurrentPayload(
        weatherCode = 0.0,
        isDay = 1.0,
        temperatureC = 18.6,
        observedEpochSeconds = Instant.parse("2026-09-15T02:15:00Z").epochSecond.toDouble(),
        timezone = "Australia/Melbourne",
        temperatureUnit = "°C",
        timeUnit = "unixtime",
    )

    @Test fun validCurrentConditionsPreserveServiceTimeAndDoNotClaimToBeDemo() {
        val result = mapOpenMeteoCurrent(valid, now)
        assertEquals(WeatherKind.Clear, result.snapshot.kind)
        assertEquals(Daylight.Day, result.snapshot.daylight)
        assertEquals(19, result.snapshot.temperatureC)
        assertFalse(result.snapshot.isDemo)
        assertEquals("2026-09-15T02:15:00Z", result.snapshot.capturedAt)
        assertEquals(Instant.parse("2026-09-15T02:15:00Z"), result.observedAt)
        assertEquals(now, result.fetchedAt)
        assertEquals(ZoneId.of("Australia/Melbourne"), result.zoneId)
        assertEquals(OPEN_METEO_ATTRIBUTION, result.attribution)
        assertNull(result.placeName)
    }

    @Test fun optionalPlaceMetadataPreservesWeatherAndIsNotAddedToTheDiarySnapshot() {
        val weather = mapOpenMeteoCurrent(valid, now)
        val location = WeatherQueryLocation.fromCoordinates(1.2, 3.4, "  示例镇  ")
        val named = weather.copy(placeName = location.placeName)
        assertEquals("示例镇", named.placeName)
        assertEquals(weather.snapshot, named.snapshot)
        assertEquals(weather.fetchedAt, named.fetchedAt)
        assertEquals(weather.observedAt, named.observedAt)
        assertEquals(weather.zoneId, named.zoneId)
        assertEquals(weather.attribution, named.attribution)
        assertTrue(named.isFresh(now))
        assertNotEquals(weather, named)
        assertEquals(weather, named.copy(placeName = null))
    }

    @Test fun missingOrMalformedPlaceMetadataDoesNotPreventValidWeatherMapping() {
        val expected = mapOpenMeteoCurrent(valid, now)
        listOf(null, "", " \u3000 ", "\uD800", "12.3, -45.6", "地\u202e名", "镇".repeat(101)).forEach { name ->
            val location = WeatherQueryLocation.fromCoordinates(1.2, 3.4, name)
            val weather = mapOpenMeteoCurrent(valid, now).copy(placeName = location.placeName)
            assertNull(weather.placeName)
            assertEquals(expected, weather)
        }
    }

    @Test fun existingConstructorRemainsCompatibleAndToStringNeverContainsPlaceMetadata() {
        val source = mapOpenMeteoCurrent(valid, now)
        val legacy = CurrentWritingWeather(source.snapshot, source.fetchedAt, source.observedAt,
            source.zoneId, source.attribution)
        assertEquals(source, legacy)
        val named = source.copy(placeName = "私密示例地名")
        assertEquals("CurrentWritingWeather(redacted)", named.toString())
        assertFalse(named.toString().contains("私密示例地名"))
        assertFalse(named.toString().contains(source.zoneId.id))
    }

    @Test fun unixTimestampIsUtcAndNotShiftedByLocationTimezone() {
        val melbourne = mapOpenMeteoCurrent(valid, now)
        val newYork = mapOpenMeteoCurrent(valid.copy(timezone = "America/New_York"), now)
        assertEquals(melbourne.observedAt, newYork.observedAt)
        assertEquals(melbourne.snapshot.capturedAt, newYork.snapshot.capturedAt)
        assertNotEquals(melbourne.zoneId, newYork.zoneId)
    }

    @Test fun currentDayNightComesOnlyFromServiceIsDay() {
        assertEquals(Daylight.Day, mapOpenMeteoCurrent(valid.copy(isDay = 1.0), now).snapshot.daylight)
        assertEquals(Daylight.Night, mapOpenMeteoCurrent(valid.copy(isDay = 0.0), now).snapshot.daylight)
        listOf(null, -1.0, 2.0, .5, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            expectFailure(WeatherFetchError.InvalidResponse) { mapOpenMeteoCurrent(valid.copy(isDay = value), now) }
        }
    }

    @Test fun wmoCodesMapToExactlyTheFiveRequestedWeatherCategories() {
        val groups = mapOf(
            WeatherKind.Clear to listOf(0, 1),
            WeatherKind.Cloudy to listOf(2, 3),
            WeatherKind.Fog to listOf(45, 48),
            WeatherKind.Rain to listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99),
            WeatherKind.Snow to listOf(71, 73, 75, 77, 85, 86),
        )
        groups.forEach { (kind, codes) ->
            codes.forEach { code ->
                assertEquals("WMO $code", kind, weatherKindFromWmo(code))
                assertEquals(kind, mapOpenMeteoCurrent(valid.copy(weatherCode = code.toDouble()), now).snapshot.kind)
            }
        }
        assertEquals(WeatherKind.entries.toSet() - WeatherKind.Unknown, groups.keys)
    }

    @Test fun unknownCodesRemainNeutralAndNeverBecomeClear() {
        listOf(-1, 4, 49, 70, 100, Int.MAX_VALUE).forEach { code ->
            assertEquals(WeatherKind.Unknown, weatherKindFromWmo(code))
        }
        listOf(4.0, 49.0, 100.0).forEach { code ->
            expectFailure(WeatherFetchError.UnsupportedWeather) { mapOpenMeteoCurrent(valid.copy(weatherCode = code), now) }
        }
    }

    @Test fun nullDecimalOrOverflowingCodesAreMalformedNotClear() {
        listOf(null, -1.0, .2, Double.NaN, Double.POSITIVE_INFINITY, 2147483648.0).forEach { code ->
            expectFailure(WeatherFetchError.InvalidResponse) { mapOpenMeteoCurrent(valid.copy(weatherCode = code), now) }
        }
    }

    @Test fun temperatureRoundingHandlesPositiveAndBelowFreezingValues() {
        assertEquals(19, mapOpenMeteoCurrent(valid.copy(temperatureC = 18.6), now).snapshot.temperatureC)
        assertEquals(-3, mapOpenMeteoCurrent(valid.copy(temperatureC = -2.6), now).snapshot.temperatureC)
        assertEquals(0, mapOpenMeteoCurrent(valid.copy(temperatureC = -.1), now).snapshot.temperatureC)
    }

    @Test fun missingNonFiniteOrImplausibleTemperatureDoesNotInventAValue() {
        listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, -100.1, 65.1).forEach { temperature ->
            expectFailure(WeatherFetchError.InvalidResponse) { mapOpenMeteoCurrent(valid.copy(temperatureC = temperature), now) }
        }
    }

    @Test fun missingOrUnexpectedUnitsAreRejected() {
        listOf(null, "", "°F", "Celsius").forEach { unit ->
            expectFailure(WeatherFetchError.InvalidResponse) { mapOpenMeteoCurrent(valid.copy(temperatureUnit = unit), now) }
        }
        listOf(null, "", "iso8601", "milliseconds").forEach { unit ->
            expectFailure(WeatherFetchError.InvalidResponse) { mapOpenMeteoCurrent(valid.copy(timeUnit = unit), now) }
        }
    }

    @Test fun missingOrInvalidTimezoneNeverUsesDeviceZoneSilently() {
        listOf(null, "", "   ", "Not/A_Zone", "Australia/Melbourne?debug=1").forEach { timezone ->
            expectFailure(WeatherFetchError.InvalidResponse) { mapOpenMeteoCurrent(valid.copy(timezone = timezone), now) }
        }
    }

    @Test fun malformedTimestampNeverUsesFetchTimeAsObservationTime() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 1.5, Long.MAX_VALUE.toDouble()).forEach { time ->
            expectFailure(WeatherFetchError.InvalidResponse) { mapOpenMeteoCurrent(valid.copy(observedEpochSeconds = time), now) }
        }
    }

    @Test fun ninetyMinuteAgeBoundaryIsInclusiveAndOlderCurrentIsRejected() {
        val boundary = now.minusSeconds(90 * 60)
        assertEquals(boundary, mapOpenMeteoCurrent(valid.copy(observedEpochSeconds = boundary.epochSecond.toDouble()), now).observedAt)
        expectFailure(WeatherFetchError.StaleObservation) {
            mapOpenMeteoCurrent(valid.copy(observedEpochSeconds = boundary.minusSeconds(1).epochSecond.toDouble()), now)
        }
    }

    @Test fun fifteenMinuteFutureBoundaryIsInclusiveAndLaterCurrentIsRejected() {
        val boundary = now.plusSeconds(15 * 60)
        assertEquals(boundary, mapOpenMeteoCurrent(valid.copy(observedEpochSeconds = boundary.epochSecond.toDouble()), now).observedAt)
        expectFailure(WeatherFetchError.StaleObservation) {
            mapOpenMeteoCurrent(valid.copy(observedEpochSeconds = boundary.plusSeconds(1).epochSecond.toDouble()), now)
        }
    }

    @Test fun freshnessIsElapsedTimeNotSameDateSoItWorksAcrossMidnight() {
        val justAfterMidnight = Instant.parse("2026-09-16T00:03:00Z")
        val shortlyBefore = Instant.parse("2026-09-15T23:55:00Z")
        assertTrue(isCurrentWeatherTimeFresh(shortlyBefore, justAfterMidnight))
        assertFalse(isCurrentWeatherTimeFresh(shortlyBefore.minusSeconds(24 * 60 * 60), justAfterMidnight))
    }

    @Test fun freshnessCanBeRecheckedAfterResumingWithoutTreatingCachedCurrentAsFresh() {
        val result = mapOpenMeteoCurrent(valid, now)
        assertTrue(result.isFresh(now))
        assertFalse(result.isFresh(now.plusSeconds(86 * 60)))
    }

    @Test fun errorsCarryOnlySafeFixedMessagesAndNoNestedRawFailure() {
        WeatherFetchError.entries.forEach { code ->
            val error = WeatherFetchException(code)
            assertEquals(code, error.code)
            assertTrue(!error.message.isNullOrBlank())
            assertFalse(error.message!!.contains("latitude"))
            assertFalse(error.message!!.contains("https://"))
            assertEquals(null, error.cause)
        }
    }

    private fun expectFailure(code: WeatherFetchError, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (failure: WeatherFetchException) {
            assertEquals(code, failure.code)
        }
    }
}
