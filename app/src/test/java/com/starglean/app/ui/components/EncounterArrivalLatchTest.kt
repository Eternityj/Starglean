package com.starglean.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class EncounterArrivalLatchTest {
    @Test fun pendingAndInactiveNeverConsumeAnArrival() {
        val latch = EncounterArrivalLatch()
        assertFalse(latch.take(1, false, true))
        assertFalse(latch.take(1, true, false))
        assertFalse(latch.hasConsumed(1))
        assertTrue(latch.take(1, true, true))
        assertTrue(latch.hasConsumed(1))
    }

    @Test fun RenewalOrLocationRecoveryNeverReplaysSoundAndVibration() {
        val latch = EncounterArrivalLatch()
        assertTrue(latch.take(1, true, true))
        repeat(100) { assertFalse(latch.take(1, true, true)) }
        assertFalse(latch.take(1, false, true))
        assertFalse(latch.take(1, true, true))
        assertFalse(latch.take(1, true, false))
        assertFalse(latch.take(1, true, true))
        assertTrue(latch.take(2, true, true))
        assertTrue(latch.hasConsumed(2))
        assertFalse(latch.hasConsumed(1))
        assertFalse(latch.take(2, true, true))
    }
}
