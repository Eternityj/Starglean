package com.starglean.app.core.location

import org.junit.Assert.*
import org.junit.Test

class AppLocationConsentTest {
    @Test fun noChoiceWrongVersionOrInvalidChoiceRequiresOneDisclosure() {
        assertEquals(AppLocationConsent.NotAsked, decodeAppLocationConsent(0, null))
        assertEquals(AppLocationConsent.NotAsked, decodeAppLocationConsent(2, "Enabled"))
        assertEquals(AppLocationConsent.NotAsked, decodeAppLocationConsent(1, "bad"))
    }

    @Test fun choicesSurviveRestartWithoutReaskingAfterDenial() {
        listOf(AppLocationConsent.Enabled, AppLocationConsent.Declined).forEach { choice ->
            assertEquals(choice, decodeAppLocationConsent(appLocationConsentVersion(), choice.name))
        }
    }

    @Test fun consentAloneAndOsPermissionAloneNeverAuthorizeLocation() {
        AppLocationConsent.entries.forEach { consent ->
            assertFalse(mayUseAppLocation(consent, false))
        }
        assertFalse(mayUseAppLocation(AppLocationConsent.Declined, true))
        assertFalse(mayUseAppLocation(AppLocationConsent.NotAsked, true))
        assertTrue(mayUseAppLocation(AppLocationConsent.Enabled, true))
    }
}
