package com.starglean.app.core.feedback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEffectOwnershipTest {
    @Test fun `old completion cannot cancel replacement run`() {
        val ownership = RunEffectOwnership()
        ownership.started(1)
        ownership.started(2)

        assertFalse(ownership.takeForCancellation(expectedRun = 1))
        assertTrue(ownership.takeForCancellation(expectedRun = 2))
        assertFalse(ownership.takeForCancellation())
    }

    @Test fun `lifecycle stop can cancel whichever run owns hardware`() {
        val ownership = RunEffectOwnership()
        ownership.started(7)
        assertTrue(ownership.takeForCancellation())
        assertFalse(ownership.takeForCancellation(expectedRun = 7))
    }
}

