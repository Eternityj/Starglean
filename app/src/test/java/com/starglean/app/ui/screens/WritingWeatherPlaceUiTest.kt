package com.starglean.app.ui.screens

import com.starglean.app.core.weather.WeatherPlaceGranularity
import com.starglean.app.core.weather.WeatherQueryLocation
import org.junit.Assert.*
import org.junit.Test

class WritingWeatherPlaceUiTest {
    @Test fun providedCityAndDistrictAreDisplayedWithoutGuessing() {
        assertEquals("测试城市 · 测试城区", writingWeatherPlaceLabel(
            "  测试城市 · 测试城区  ", placeLoading = false, placeLookupEnabled = true, weatherEnabled = true))
    }

    @Test fun streetNameIsShownOnlyWhenSupplied() {
        assertEquals("Example Street · Sample District · Sample City", writingWeatherPlaceLabel(
            "Example Street · Sample District · Sample City", false, true, true))
        assertEquals("Melbourne", writingWeatherPlaceLabel("Melbourne", false, true, true))
    }

    @Test fun addressDetailExplainsMissingStreetWithoutChangingThePlaceName() {
        assertEquals("Near this street · area and city from Android",
            writingWeatherPlaceDetail(WeatherPlaceGranularity.Street, true))
        assertEquals("Street unavailable · area and city shown",
            writingWeatherPlaceDetail(WeatherPlaceGranularity.Area, true))
        assertEquals("Street unavailable · only city available",
            writingWeatherPlaceDetail(WeatherPlaceGranularity.City, true))
        assertNull(writingWeatherPlaceDetail(WeatherPlaceGranularity.Unknown, true))
        assertNull(writingWeatherPlaceDetail(WeatherPlaceGranularity.City, false))
    }

    @Test fun nonStreetAndroidResultsGetOneImprovement() {
        assertTrue(shouldRetryWritingWeatherPlace(WeatherPlaceGranularity.City))
        assertTrue(shouldRetryWritingWeatherPlace(WeatherPlaceGranularity.Region))
        assertTrue(shouldRetryWritingWeatherPlace(WeatherPlaceGranularity.Area))
        assertTrue(shouldRetryWritingWeatherPlace(WeatherPlaceGranularity.Unknown))
        assertFalse(shouldRetryWritingWeatherPlace(WeatherPlaceGranularity.Street))
    }

    @Test fun improvementMustBeAStreetFromASeparateFixWithinOneHundredMeters() {
        val original = WeatherQueryLocation.fromCoordinates(-37.8, 144.96, "Melbourne", 20f,
            WeatherPlaceGranularity.City)
        val near = WeatherQueryLocation.fromCoordinates(-37.8003, 144.96, "Lygon Street · Melbourne", 20f,
            WeatherPlaceGranularity.Street)
        val far = WeatherQueryLocation.fromCoordinates(-37.79865, 144.96, "Other Road · Melbourne", 20f,
            WeatherPlaceGranularity.Street)
        assertTrue(canApplyWritingWeatherPlaceImprovement(original, near))
        assertFalse(canApplyWritingWeatherPlaceImprovement(original, far))
        assertFalse(canApplyWritingWeatherPlaceImprovement(original,
            near.let { WeatherQueryLocation.fromCoordinates(-37.8003, 144.96, "Carlton", 20f,
                WeatherPlaceGranularity.Area) }))
        val missingAccuracy = WeatherQueryLocation.fromCoordinates(-37.8003, 144.96,
            "Lygon Street · Melbourne", null, WeatherPlaceGranularity.Street)
        assertFalse(canApplyWritingWeatherPlaceImprovement(original, missingAccuracy))
        assertFalse(canApplyWritingWeatherPlaceImprovement(
            WeatherQueryLocation.fromCoordinates(-37.8, 144.96, "Melbourne", null,
                WeatherPlaceGranularity.City), near))
    }

    @Test fun cancelledDisabledBackgroundAndStaleEpochResultsAreRejected() {
        assertTrue(isWritingWeatherRequestCurrent(7, 7, consentEnabled = true, foreground = true))
        assertFalse(isWritingWeatherRequestCurrent(7, 8, consentEnabled = true, foreground = true))
        assertFalse(isWritingWeatherRequestCurrent(7, 7, consentEnabled = false, foreground = true))
        assertFalse(isWritingWeatherRequestCurrent(7, 7, consentEnabled = true, foreground = false))
    }

    @Test fun weatherAccuracyIsAnUpwardRoundedEstimateNotAnAccessGrant() {
        val state = WritingWeatherUiState(enabled = true, precisePermissionGranted = true, locationAccuracyMeters = 18.2f)
        assertEquals("Weather location · estimated accuracy 19 m", writingWeatherAccuracyLabel(state))
        assertEquals("Weather location · estimated accuracy 1 m", writingWeatherAccuracyLabel(state.copy(locationAccuracyMeters = .5f)))
        assertFalse(checkNotNull(writingWeatherAccuracyLabel(state)).contains("200"))
        assertFalse(checkNotNull(writingWeatherAccuracyLabel(state)).contains("IN_RANGE"))
    }

    @Test fun absentInvalidDisabledAndPendingAccuracyIsNotMisleadinglyDisplayed() {
        val state = WritingWeatherUiState(enabled = true, precisePermissionGranted = true, locationAccuracyMeters = 20f)
        listOf(null, Float.NaN, 0f, -1f, 101f, Float.POSITIVE_INFINITY).forEach {
            assertNull(writingWeatherAccuracyLabel(state.copy(locationAccuracyMeters = it)))
        }
        assertNull(writingWeatherAccuracyLabel(state.copy(enabled = false)))
        assertNull(writingWeatherAccuracyLabel(state.copy(precisePermissionGranted = false)))
        assertNull(writingWeatherAccuracyLabel(state.copy(foreground = false)))
        assertNull(writingWeatherAccuracyLabel(state.copy(loading = true)))
        assertNull(writingWeatherAccuracyLabel(state.copy(locationFailure = com.starglean.app.core.weather.WeatherLocationFailure.Unreliable)))
    }

    @Test fun failedOrEmptyLookupDoesNotInventAPlace() {
        listOf(null, "", "  ", "\n\t").forEach { name ->
            assertEquals("Place name unavailable", writingWeatherPlaceLabel(
                name, placeLoading = false, placeLookupEnabled = true, weatherEnabled = true))
        }
    }

    @Test fun lookupInProgressDoesNotShowThePreviousPlaceAsCurrent() {
        assertEquals("Finding the place name…", writingWeatherPlaceLabel(
            "上一次的城市", placeLoading = true, placeLookupEnabled = true, weatherEnabled = true))
    }

    @Test fun lookupNotEnabledIsNotMisreportedAsAnError() {
        assertEquals("Place names are off", writingWeatherPlaceLabel(
            null, placeLoading = false, placeLookupEnabled = false, weatherEnabled = true))
    }

    @Test fun disabledLookupNeverExposesAStaleName() {
        assertEquals("Place names are off", writingWeatherPlaceLabel(
            "上一次的城市", placeLoading = true, placeLookupEnabled = false, weatherEnabled = true))
    }

    @Test fun disabledWeatherDoesNotPretendAPlaceRequestHasFailed() {
        assertEquals("Local weather", writingWeatherPlaceLabel(
            "上一次的城市", placeLoading = true, placeLookupEnabled = true, weatherEnabled = false))
    }

    @Test fun androidDisclosureStatesTheDisplayBoundary() {
        val disclosure = writingPlaceDisclosure()
        assertFalse(disclosure.contains("Apple"))
        assertTrue(disclosure.contains("Android"))
        assertTrue(disclosure.contains("Precise coordinates"))
        assertTrue(disclosure.contains("Your writing is not sent"))
        assertTrue(disclosure.contains("street, district and city"))
        assertTrue(disclosure.contains("If street details are unavailable"))
        assertTrue(disclosure.contains("not shared with anonymous readers"))
    }
}
