package com.starglean.app.ui.screens

import com.starglean.app.ui.screens.DiscoveryShakeRejection.AccountNotReady
import com.starglean.app.ui.screens.DiscoveryShakeRejection.LocationNotReady
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoveryShakeHintTest {
    @Test fun rejectedShakeNamesTheFirstMissingRequirement() {
        assertEquals(AccountNotReady, discoveryShakeRejection(busy = false, reading = false, accountReady = false, locationReady = false))
        assertEquals(LocationNotReady, discoveryShakeRejection(busy = false, reading = false, accountReady = true, locationReady = false))
        assertNull(discoveryShakeRejection(busy = false, reading = false, accountReady = true, locationReady = true))
    }

    @Test fun shakesWhileSearchingOrReadingStayQuiet() {
        assertNull(discoveryShakeRejection(busy = true, reading = false, accountReady = false, locationReady = false))
        assertNull(discoveryShakeRejection(busy = false, reading = true, accountReady = false, locationReady = false))
    }

    @Test fun hintDisappearsOnceItsReasonIsResolved() {
        assertNotNull(discoveryShakeHint(LocationNotReady, accountReady = true, locationReady = false))
        assertNull(discoveryShakeHint(LocationNotReady, accountReady = true, locationReady = true))
        assertNotNull(discoveryShakeHint(AccountNotReady, accountReady = false, locationReady = true))
        assertNull(discoveryShakeHint(AccountNotReady, accountReady = true, locationReady = false))
        assertNull(discoveryShakeHint(null, accountReady = false, locationReady = false))
    }
}
