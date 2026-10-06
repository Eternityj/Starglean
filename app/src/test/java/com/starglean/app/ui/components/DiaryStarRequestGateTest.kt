package com.starglean.app.ui.components

import org.junit.Assert.*
import org.junit.Test

class DiaryStarRequestGateTest {
    @Test fun rapidManualTapsCannotClaimTwoPlacements() {
        val gate = DiaryStarRequestGate()
        val first = checkNotNull(gate.tryBegin())
        assertNull(gate.tryBegin())
        assertTrue(gate.owns(first))
        assertTrue(gate.busy)
    }

    @Test fun cancelledManualFinallyCannotUnlockTheNewRefresh() {
        val gate = DiaryStarRequestGate()
        val manual = checkNotNull(gate.tryBegin())
        gate.invalidate()
        val refresh = gate.replace()
        assertFalse(gate.finish(manual))
        assertTrue(gate.owns(refresh))
        assertNull(gate.tryBegin())
    }

    @Test fun staleRefreshFinallyCannotClearTheNewManualJob() {
        val gate = DiaryStarRequestGate()
        val oldRefresh = gate.replace()
        assertTrue(gate.finish(oldRefresh))
        val manual = checkNotNull(gate.tryBegin())
        assertFalse(gate.finish(oldRefresh))
        assertTrue(gate.owns(manual))
        assertTrue(gate.busy)
    }

    @Test fun onlyTheCurrentOwnerMayReleaseTheGate() {
        val gate = DiaryStarRequestGate()
        val old = gate.replace()
        val current = gate.replace()
        assertFalse(gate.owns(old))
        assertFalse(gate.finish(old))
        assertTrue(gate.finish(current))
        assertFalse(gate.busy)
        assertFalse(gate.finish(current))
    }

    @Test fun leavingInvalidatesLateResultsWithoutReusingTheirIdentity() {
        val gate = DiaryStarRequestGate()
        val old = gate.replace()
        gate.invalidate()
        assertFalse(gate.owns(old))
        assertFalse(gate.finish(old))
        val next = checkNotNull(gate.tryBegin())
        assertNotEquals(old, next)
    }
}
