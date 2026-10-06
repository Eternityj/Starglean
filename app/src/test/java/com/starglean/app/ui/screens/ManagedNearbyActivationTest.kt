package com.starglean.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedNearbyActivationTest {
    @Test fun `composition access must be gate managed as well as allowed`() {
        assertTrue(isManagedLocationAllowed(managed = true, allowed = true))
        assertFalse(isManagedLocationAllowed(managed = false, allowed = true))
        assertFalse(isManagedLocationAllowed(managed = true, allowed = false))
    }

    @Test fun `starts only with foreground global access and precise permission`() {
        assertTrue(shouldStartManagedNearby(true, true, true, false, false))
        assertFalse(shouldStartManagedNearby(false, true, true, false, false))
        assertFalse(shouldStartManagedNearby(true, false, true, false, false))
        assertFalse(shouldStartManagedNearby(true, true, false, false, false))
    }

    @Test fun `session stop and an existing source prevent automatic restart`() {
        assertFalse(shouldStartManagedNearby(true, true, true, true, false))
        assertFalse(shouldStartManagedNearby(true, true, true, false, true))
    }
}
