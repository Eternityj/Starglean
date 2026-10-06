package com.starglean.app.core.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherLocationValidationTest {
    private val now = 500_000_000_000L

    private fun reliable(
        latitude: Double = -37.8,
        longitude: Double = 145.0,
        hasAccuracy: Boolean = true,
        accuracyMeters: Float = 30f,
        fixNanos: Long = now - 1_000_000_000L,
        nowNanos: Long = now,
    ): Boolean = isWeatherLocationReliable(
        latitude, longitude, hasAccuracy, accuracyMeters, fixNanos, nowNanos,
    )

    @Test
    fun acceptsRecentPreciseLocationAtTheInclusiveHundredMeterBoundary() {
        assertTrue(reliable())
        assertEquals(100f, WEATHER_LOCATION_MAX_ACCURACY_METERS, 0f)
        assertTrue(reliable(accuracyMeters = Math.nextDown(100f)))
        assertTrue(reliable(accuracyMeters = 100f))
        assertFalse(reliable(accuracyMeters = Math.nextUp(100f)))
    }

    @Test
    fun rejectsNonFiniteCoordinates() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertFalse(reliable(latitude = invalid))
            assertFalse(reliable(longitude = invalid))
        }
    }

    @Test
    fun enforcesCoordinateRangesIncludingPolesAndDateLine() {
        assertTrue(reliable(latitude = 90.0, longitude = 180.0))
        assertTrue(reliable(latitude = -90.0, longitude = -180.0))
        assertFalse(reliable(latitude = 90.00001))
        assertFalse(reliable(latitude = -90.00001))
        assertFalse(reliable(longitude = 180.00001))
        assertFalse(reliable(longitude = -180.00001))
    }

    @Test
    fun rejectsMissingOrInvalidAccuracy() {
        assertFalse(reliable(hasAccuracy = false))
        listOf(0f, -0.01f, 100.1f, 3_000f, 25_000f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertFalse(reliable(accuracyMeters = it))
        }
    }

    @Test
    fun twoMinuteAgeBoundaryIsInclusive() {
        assertTrue(reliable(fixNanos = now))
        assertTrue(reliable(fixNanos = now - WEATHER_LOCATION_MAX_AGE_NANOS))
        assertFalse(reliable(fixNanos = now - WEATHER_LOCATION_MAX_AGE_NANOS - 1L))
    }

    @Test
    fun allowsOnlyFiveSecondsOfFutureTimestampTolerance() {
        assertTrue(reliable(fixNanos = now + WEATHER_LOCATION_FUTURE_TOLERANCE_NANOS))
        assertFalse(reliable(fixNanos = now + WEATHER_LOCATION_FUTURE_TOLERANCE_NANOS + 1L))
    }

    @Test
    fun rejectsInvalidMonotonicClockValues() {
        assertFalse(reliable(fixNanos = -1L))
        assertFalse(reliable(nowNanos = -1L))
        assertFalse(reliable(fixNanos = Long.MIN_VALUE))
        assertFalse(reliable(nowNanos = Long.MIN_VALUE))
    }

    @Test
    fun monotonicAgeArithmeticCannotWrapIntoAValidFix() {
        assertFalse(reliable(fixNanos = 0L, nowNanos = Long.MAX_VALUE))
        assertFalse(reliable(fixNanos = Long.MAX_VALUE, nowNanos = 0L))
        assertTrue(reliable(fixNanos = Long.MAX_VALUE, nowNanos = Long.MAX_VALUE))
        assertTrue(reliable(fixNanos = 0L, nowNanos = 0L))
    }

    @Test
    fun failuresExposeOnlySafeChineseMessagesAndTypedCodes() {
        WeatherLocationFailure.entries.forEach { code ->
            val failure = WeatherLocationException(code)
            assertEquals(code, failure.code)
            assertTrue(failure.message.orEmpty().isNotBlank())
            assertFalse(failure.message.orEmpty().contains("latitude"))
            assertFalse(failure.message.orEmpty().contains("longitude"))
            assertNull(failure.cause)
        }
    }
}
