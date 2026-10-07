package com.starglean.app.ui.screens

import com.starglean.app.core.memory.MemoryLocationFailure
import com.starglean.app.core.weather.WeatherLocationFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingLocationRecoveryTest {
    @Test fun weatherProgressDistinguishesPositionFromNetworkRequest() {
        assertEquals("Finding your location…",
            writingWeatherProgressLabel(WritingWeatherStage.Locating))
        assertEquals("Fetching weather…",
            writingWeatherProgressLabel(WritingWeatherStage.Fetching))
        assertNotEquals(writingWeatherProgressLabel(WritingWeatherStage.Locating),
            writingWeatherProgressLabel(WritingWeatherStage.Fetching))
        assertEquals("Weather unavailable · Quiet background",
            writingWeatherProgressLabel(WritingWeatherStage.Idle))
        assertFalse(writingWeatherProgressLabel(WritingWeatherStage.Idle).contains("Finding"))
    }

    @Test fun initialWeatherStateDoesNotInventLocationOrPermission() {
        val state = WritingWeatherUiState()
        assertEquals(WritingWeatherStage.Idle, state.stage)
        assertFalse(state.loading)
        assertFalse(state.permissionGranted)
        assertFalse(state.precisePermissionGranted)
        assertNull(state.current)
        assertNull(state.locationFailure)
        assertNull(state.fetchFailure)
    }

    @Test fun weatherPrecisionIsOfferedOnlyAfterAnActualFixFailure() {
        val eligible = setOf(WeatherLocationFailure.Unavailable, WeatherLocationFailure.Timeout,
            WeatherLocationFailure.Unreliable)
        WeatherLocationFailure.entries.forEach { failure ->
            assertEquals(failure in eligible, canImproveWeatherLocation(failure, preciseGranted = false))
        }
        assertFalse(canImproveWeatherLocation(null, preciseGranted = false))
    }

    @Test fun weatherNeverSuggestsIncreasingPermissionAlreadyGranted() {
        WeatherLocationFailure.entries.forEach { failure ->
            assertFalse(canImproveWeatherLocation(failure, preciseGranted = true))
        }
    }

    @Test fun diaryPrecisionRecoveryIsOnlyOfferedAfterAnActualFixFailure() {
        val eligible = setOf(MemoryLocationFailure.Unavailable, MemoryLocationFailure.Timeout,
            MemoryLocationFailure.Unreliable)
        MemoryLocationFailure.entries.forEach { failure ->
            assertEquals(failure in eligible, canImproveDiaryLocation(failure, preciseGranted = false))
        }
        assertFalse(canImproveDiaryLocation(null, preciseGranted = false))
    }

    @Test fun diaryNeverSuggestsIncreasingPermissionAlreadyGranted() {
        MemoryLocationFailure.entries.forEach { failure ->
            assertFalse(canImproveDiaryLocation(failure, preciseGranted = true))
        }
    }

    @Test fun allDiaryLocationFailuresClearlySayNothingWasSaved() {
        MemoryLocationFailure.entries.forEach { failure ->
            assertTrue(writingLocationFailureMessage(failure).contains("Your entry is not saved"))
        }
        assertTrue(writingLocationFailureMessage(MemoryLocationFailure.ServicesDisabled).contains("Location services are off"))
        assertTrue(writingLocationFailureMessage(MemoryLocationFailure.PermissionRequired).contains("Settings"))
        assertTrue(writingLocationFailureMessage(MemoryLocationFailure.Unreliable).contains("too old or inaccurate"))
    }

    @Test fun cancelWaitCannotPretendToCancelAnAtomicWrite() {
        assertTrue(canCancelWritingLocation(LocalSavePhase.Locating))
        assertFalse(canCancelWritingLocation(LocalSavePhase.Idle))
        assertFalse(canCancelWritingLocation(LocalSavePhase.PreparingDraft))
        assertFalse(canCancelWritingLocation(LocalSavePhase.Writing))
    }
}
