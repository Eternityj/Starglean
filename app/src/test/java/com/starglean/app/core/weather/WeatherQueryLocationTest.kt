package com.starglean.app.core.weather

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeatherQueryLocationTest {
    @Test fun accuracyEstimateIsDisplayOnlyAndNotPartOfWeatherRequest() {
        val plain = WeatherQueryLocation.fromCoordinates(1.2, 3.4)
        val located = WeatherQueryLocation.fromCoordinates(1.2, 3.4, "Example Street", 18.2f)
        assertEquals(18.2f, checkNotNull(located.accuracyMeters), 0f)
        assertEquals(openMeteoCurrentUrl(plain).toExternalForm(), openMeteoCurrentUrl(located).toExternalForm())
        assertEquals("WeatherQueryLocation(redacted)", located.toString())
        assertEquals(located, WeatherQueryLocation.fromCoordinates(1.2, 3.4, "Example Street", 18.2f))
        assertEquals(located.hashCode(), WeatherQueryLocation.fromCoordinates(1.2, 3.4, "Example Street", 18.2f).hashCode())
        assertNotEquals(located, WeatherQueryLocation.fromCoordinates(1.2, 3.4, "Example Street", 20f))
    }

    @Test fun invalidOrAbsentAccuracyIsNeverShownAsZeroPrecision() {
        listOf(null, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f, 0f, 101f).forEach {
            val query = WeatherQueryLocation.fromCoordinates(1.2, 3.4, accuracyMeters = it)
            assertNull(query.accuracyMeters)
            assertEquals(1.2, query.latitude, 0.0)
        }
        assertEquals(100f, checkNotNull(WeatherQueryLocation.fromCoordinates(1.2, 3.4, accuracyMeters = 100f).accuracyMeters), 0f)
    }

    @Test fun authorizedCoordinatesArePreservedExactlyWithoutGridRounding() {
        val location = WeatherQueryLocation.fromCoordinates(-37.813629, 144.963058)
        assertEquals(-37.813629, location.latitude, 0.0)
        assertEquals(144.963058, location.longitude, 0.0)
        val url = openMeteoCurrentUrl(location).toExternalForm()
        assertTrue(url.contains("latitude=-37.813629&longitude=144.963058"))
        assertFalse(url.contains("latitude=-37.8&longitude=145.0"))
    }

    @Test fun requestIncludesOnlyCurrentFieldsAndUsesHttpsUtcAndAutomaticZone() {
        val url = openMeteoCurrentUrl(WeatherQueryLocation.fromCoordinates(1.2, 3.4))
        assertEquals("https", url.protocol)
        assertEquals("api.open-meteo.com", url.host)
        assertEquals("/v1/forecast", url.path)
        assertTrue(url.query.contains("current=temperature_2m,weather_code,is_day"))
        assertTrue(url.query.contains("temperature_unit=celsius"))
        assertTrue(url.query.contains("timeformat=unixtime"))
        assertTrue(url.query.contains("timezone=auto"))
        listOf("hourly=", "daily=", "apikey=", "start_date=", "past_days=").forEach { assertFalse(url.query.contains(it)) }
    }

    @Test fun decimalFormattingDoesNotChangeWithDeviceLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val url = openMeteoCurrentUrl(WeatherQueryLocation.fromCoordinates(52.52, 13.41)).toExternalForm()
            assertTrue(url.contains("latitude=52.52&longitude=13.41"))
            assertFalse(url.contains("52,52"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun nonFiniteAndOutOfBoundsCoordinatesAreRejected() {
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -90.01, 90.01).forEach { latitude ->
            expectInvalid { WeatherQueryLocation.fromCoordinates(latitude, 0.0) }
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -180.01, 180.01).forEach { longitude ->
            expectInvalid { WeatherQueryLocation.fromCoordinates(0.0, longitude) }
        }
    }

    @Test fun validWorldBoundsArePreservedWithoutRounding() {
        assertEquals(90.0, WeatherQueryLocation.fromCoordinates(90.0, 180.0).latitude, 0.0)
        assertEquals(180.0, WeatherQueryLocation.fromCoordinates(90.0, 180.0).longitude, 0.0)
        assertEquals(-90.0, WeatherQueryLocation.fromCoordinates(-90.0, -180.0).latitude, 0.0)
        assertEquals(-180.0, WeatherQueryLocation.fromCoordinates(-90.0, -180.0).longitude, 0.0)
    }

    @Test fun onlyIdenticalCoordinatesCompareEqualAndToStringRedactsThePosition() {
        val first = WeatherQueryLocation.fromCoordinates(-37.813629, 144.963058)
        val second = WeatherQueryLocation.fromCoordinates(-37.822345, 144.978765)
        val same = WeatherQueryLocation.fromCoordinates(-37.813629, 144.963058)
        assertNotEquals(first, second)
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertEquals("WeatherQueryLocation(redacted)", first.toString())
    }

    @Test fun negativeZeroIsNormalizedSoExactEqualityAndHashCodesAgree() {
        val positive = WeatherQueryLocation.fromCoordinates(0.0, 0.0)
        val negative = WeatherQueryLocation.fromCoordinates(-0.0, -0.0)
        assertEquals(positive, negative)
        assertEquals(positive.hashCode(), negative.hashCode())
        assertTrue(openMeteoCurrentUrl(negative).query.startsWith("latitude=0.0&longitude=0.0"))
    }

    @Test fun tinyAndHighPrecisionCoordinatesRoundTripFromTheUrlWithoutLostDigits() {
        val pairs = listOf(0.000000123456789 to -0.000000987654321,
            12.123456789012345 to -45.987654321098765)
        pairs.forEach { (latitude, longitude) ->
            val location = WeatherQueryLocation.fromCoordinates(latitude, longitude)
            val parameters = openMeteoCurrentUrl(location).query.split('&').associate {
                val pair = it.split('=', limit = 2)
                pair[0] to pair[1]
            }
            assertEquals(latitude, checkNotNull(parameters["latitude"]).toDouble(), 0.0)
            assertEquals(longitude, checkNotNull(parameters["longitude"]).toDouble(), 0.0)
        }
    }

    @Test fun optionalPlaceNameDefaultsToAbsentAndOnlyTrimsOuterSpaces() {
        assertNull(WeatherQueryLocation.fromCoordinates(1.2, 3.4).placeName)
        assertNull(normalizeWeatherPlaceName(null))
        assertNull(normalizeWeatherPlaceName(""))
        assertNull(normalizeWeatherPlaceName(" \u00a0\u3000 "))
        assertEquals("示例镇  北区", normalizeWeatherPlaceName(" \u00a0示例镇  北区\u3000 "))
        assertEquals("Étoile-sur-Mer", normalizeWeatherPlaceName(" Étoile-sur-Mer "))
    }

    @Test fun placeNameLimitCountsUnicodeCodePointsNotUtf16Units() {
        val supplementaryLetter = "\uD840\uDC00"
        assertEquals("镇".repeat(100), normalizeWeatherPlaceName("镇".repeat(100)))
        assertNull(normalizeWeatherPlaceName("镇".repeat(101)))
        assertEquals(supplementaryLetter.repeat(100), normalizeWeatherPlaceName(supplementaryLetter.repeat(100)))
        assertNull(normalizeWeatherPlaceName(supplementaryLetter.repeat(101)))
    }

    @Test fun malformedUtf16NamesAreDiscardedWithoutInvalidatingCoordinates() {
        listOf("\uD800", "\uDC00", "地\uD800名", "\uDC00\uD800", "\uD800x\uDC00").forEach { malformed ->
            val location = WeatherQueryLocation.fromCoordinates(12.123456789, -45.987654321, malformed)
            assertNull(location.placeName)
            assertEquals(12.123456789, location.latitude, 0.0)
            assertEquals(-45.987654321, location.longitude, 0.0)
        }
    }

    @Test fun controlsDirectionFormattingAndLineSeparatorsAreRejectedEvenAtEdges() {
        listOf("\u0000", "\t", "\n", "\r", "\u007f", "\u0085", "\u00ad", "\u200b",
            "\u200e", "\u200f", "\u202e", "\u2066", "\u2069", "\ufeff", "\u2028", "\u2029",
            "\uDB40\uDC01").forEach { forbidden ->
            assertNull(normalizeWeatherPlaceName("示例${forbidden}镇"))
            assertNull(normalizeWeatherPlaceName("${forbidden}示例镇${forbidden}"))
        }
    }

    @Test fun decimalAndDmsCoordinatesAreNotAcceptedAsPlaceNames() {
        listOf("12.3, -45.6", "[12.3; -45.6]", "12.3，45.6", "-3.78136E1 1.44963E2",
            "12.3° N, 45.6° E", "12°34′56″N 45°12′30″E", "latitude=12.3 longitude=-45.6",
            "geo:12.3,-45.6", "北纬12.3度，东经45.6度", "12.3 North, 45.6 West", "12345")
            .forEach { coordinates -> assertNull(normalizeWeatherPlaceName(coordinates)) }
        assertEquals("示例新区3", normalizeWeatherPlaceName("示例新区3"))
        assertEquals("Area 51", normalizeWeatherPlaceName("Area 51"))
    }

    @Test fun placeMetadataDoesNotEnterUrlOrAlterExactCoordinates() {
        val plain = WeatherQueryLocation.fromCoordinates(12.123456789012345, -45.987654321098765)
        val named = WeatherQueryLocation.fromCoordinates(plain.latitude, plain.longitude, "示例镇 & Place=北区")
        assertEquals(plain.latitude, named.latitude, 0.0)
        assertEquals(plain.longitude, named.longitude, 0.0)
        assertEquals(openMeteoCurrentUrl(plain).toExternalForm(), openMeteoCurrentUrl(named).toExternalForm())
        assertFalse(openMeteoCurrentUrl(named).toExternalForm().contains("示例镇"))
        assertEquals("WeatherQueryLocation(redacted)", named.toString())
    }

    @Test fun displayMetadataParticipatesInEqualityButNotHttpRequestIdentity() {
        val first = WeatherQueryLocation.fromCoordinates(1.2, 3.4, "示例甲镇")
        val same = WeatherQueryLocation.fromCoordinates(1.2, 3.4, " 示例甲镇 ")
        val changed = WeatherQueryLocation.fromCoordinates(1.2, 3.4, "示例乙镇")
        val absent = WeatherQueryLocation.fromCoordinates(1.2, 3.4)
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, changed)
        assertNotEquals(first, absent)
        assertEquals(openMeteoCurrentUrl(first).toExternalForm(), openMeteoCurrentUrl(changed).toExternalForm())
        assertEquals(absent, WeatherQueryLocation.fromCoordinates(1.2, 3.4, "\u202e坏地名"))
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected InvalidLocation")
        } catch (failure: WeatherFetchException) {
            assertEquals(WeatherFetchError.InvalidLocation, failure.code)
        }
    }
}
