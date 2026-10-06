package com.starglean.app.core.weather

import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WeatherResponseLimitTest {
    @Test fun normalUtf8ResponseIsReadExactly() {
        val json = "{\"current_units\":{\"temperature_2m\":\"°C\"}}"
        assertEquals(json, readBoundedWeatherResponse(ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))))
    }

    @Test fun responseAtExactly64KiBIsAllowed() {
        val bytes = ByteArray(WEATHER_RESPONSE_MAX_BYTES) { 'a'.code.toByte() }
        assertEquals(WEATHER_RESPONSE_MAX_BYTES, readBoundedWeatherResponse(ByteArrayInputStream(bytes)).length)
    }

    @Test fun oversizedResponseIsRejectedBeforeParsing() {
        expectInvalid { readBoundedWeatherResponse(ByteArrayInputStream(ByteArray(WEATHER_RESPONSE_MAX_BYTES + 1))) }
    }

    @Test fun emptyResponseIsRejectedRatherThanBecomingAnEmptyWeatherObject() {
        expectInvalid { readBoundedWeatherResponse(ByteArrayInputStream(byteArrayOf())) }
    }

    @Test fun cancellationIsCheckedDuringReadAndIsNeverMappedToWeatherFailure() {
        var checks = 0
        try {
            readBoundedWeatherResponse(ByteArrayInputStream(ByteArray(12_000))) {
                checks++
                if (checks == 2) throw CancellationException("test cancellation")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(2, checks)
        }
        assertTrue(checks > 0)
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected InvalidResponse")
        } catch (failure: WeatherFetchException) {
            assertEquals(WeatherFetchError.InvalidResponse, failure.code)
        }
    }
}
