package com.starglean.app.ui.screens

import com.starglean.app.ui.components.EncounterArrivalLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyReadingPresentationTest {
    @Test fun `unknown eligibility keeps shell only after readable content was presented`() {
        assertEquals(NearbyReadingPresentation.Discovery,
            nearbyReadingPresentation(reading = true, readableNow = false, presentedThisEncounter = false))
        assertEquals(NearbyReadingPresentation.Readable,
            nearbyReadingPresentation(reading = true, readableNow = true, presentedThisEncounter = false))
        assertEquals(NearbyReadingPresentation.Checking,
            nearbyReadingPresentation(reading = true, readableNow = false, presentedThisEncounter = true))
    }

    @Test fun `session end always removes the reading shell`() {
        assertEquals(NearbyReadingPresentation.Discovery,
            nearbyReadingPresentation(reading = false, readableNow = true, presentedThisEncounter = true))
    }

    @Test fun `renewed eligibility restores content in the same shell`() {
        assertEquals(NearbyReadingPresentation.Checking,
            nearbyReadingPresentation(reading = true, readableNow = false, presentedThisEncounter = true))
        assertEquals(NearbyReadingPresentation.Readable,
            nearbyReadingPresentation(reading = true, readableNow = true, presentedThisEncounter = true))
    }

    @Test fun `restored eligibility does not replay encounter arrival`() {
        val arrival = EncounterArrivalLatch()
        assertTrue(arrival.take(encounter = 7, readable = true, active = true))
        assertFalse(arrival.take(encounter = 7, readable = false, active = true))
        assertFalse(arrival.take(encounter = 7, readable = true, active = true))
    }
}
