package com.starglean.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingPreviewSourceTest {
    @Test fun ordinaryOwnDraftAllowsRealOrUnavailableWeather() {
        val origin = writingPreviewOrigin(demoWeather = false, fixedTestLocation = false)
        assertEquals(WritingPreviewOrigin.OwnDraft, origin)
        assertTrue(writingPreviewCanSave(origin, weatherIsDemo = false))
    }

    @Test fun demoWeatherPreviewCannotEnterRealLibraryEvenIfSnapshotFlagChanges() {
        val origin = writingPreviewOrigin(demoWeather = true, fixedTestLocation = false)
        assertEquals(WritingPreviewOrigin.DemoWeather, origin)
        assertFalse(writingPreviewCanSave(origin, weatherIsDemo = true))
        assertFalse(writingPreviewCanSave(origin, weatherIsDemo = false))
    }

    @Test fun fixedLocationQueryRemainsATestEvenWhenItsWeatherIsLive() {
        val origin = writingPreviewOrigin(demoWeather = false, fixedTestLocation = true)
        assertEquals(WritingPreviewOrigin.FixedTestLocation, origin)
        assertFalse(writingPreviewCanSave(origin, weatherIsDemo = false))
        assertFalse(writingPreviewCanSave(origin, weatherIsDemo = true))
    }

    @Test fun combinedDebugFlagsNeverFallBackToAnOwnDraft() {
        val origin = writingPreviewOrigin(demoWeather = true, fixedTestLocation = true)
        assertEquals(WritingPreviewOrigin.FixedTestLocation, origin)
        assertFalse(writingPreviewCanSave(origin, weatherIsDemo = false))
        assertFalse(writingPreviewCanSave(origin, weatherIsDemo = true))
    }

    @Test fun demoSnapshotIsRejectedEvenIfCallerMislabelsItsOrigin() {
        assertFalse(writingPreviewCanSave(WritingPreviewOrigin.OwnDraft, weatherIsDemo = true))
    }

    @Test fun starPlacementRequiresAConfirmedRealDiarySave() {
        WritingPreviewOrigin.entries.forEach { origin ->
            assertFalse(writingPreviewCanPlaceStar(origin, weatherIsDemo = false, saveConfirmed = false))
            assertFalse(writingPreviewCanPlaceStar(origin, weatherIsDemo = true, saveConfirmed = true))
            assertEquals(origin == WritingPreviewOrigin.OwnDraft,
                writingPreviewCanPlaceStar(origin, weatherIsDemo = false, saveConfirmed = true))
        }
    }
}
