package com.starglean.app.ui.screens

import org.junit.Assert.*
import org.junit.Test

class WritingWeatherConsentTest {
    @Test fun unifiedGateRequiresManagementDisclosureAndPrecisePermission() {
        assertTrue(canUseWritingLocation(managed = true, consented = true, precisePermissionGranted = true))
        assertFalse(canUseWritingLocation(managed = false, consented = true, precisePermissionGranted = true))
        assertFalse(canUseWritingLocation(managed = true, consented = false, precisePermissionGranted = true))
        assertFalse(canUseWritingLocation(managed = true, consented = true, precisePermissionGranted = false))
    }

    @Test fun fixedDebugWeatherCanRunWithoutGrantingProductionLocation() {
        assertTrue(canUseWritingLocation(false, false, false, fixedDebugLocation = true))
        assertFalse(canUseWritingLocation(false, false, false, fixedDebugLocation = false))
    }

    @Test fun initialStateHasNoInventedPlaceOrUnrequestedGeocoding() {
        val state = WritingWeatherUiState()
        assertNull(state.placeName)
        assertFalse(state.placeLoading)
        assertFalse(state.placeLookupEnabled)
    }
}
