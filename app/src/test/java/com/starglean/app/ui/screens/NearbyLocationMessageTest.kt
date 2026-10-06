package com.starglean.app.ui.screens

import com.starglean.app.core.discovery.NearbyLocationIssue
import com.starglean.app.core.discovery.NearbyLocationProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyLocationMessageTest {
    @Test fun collectingHasAUsefulNonErrorMessage() {
        assertEquals("Collecting a fresh location fix…", unreliableLocationMessage(null))
    }

    @Test fun coarseAccuracyIsRoundedUpWithoutClaimingAnErrorBound() {
        val message = unreliableLocationMessage(
            NearbyLocationIssue(NearbyLocationProblem.AccuracyTooWide, 84.01),
        )
        assertEquals(
            "Its reported accuracy is about 85 m. Starglean needs a reported accuracy of 35 m or better.",
            message,
        )
        assertFalse(message.contains("within"))
        assertFalse(message.contains("coordinate"))
    }

    @Test fun nonFiniteAccuracyNeverLeaksANumericArtifact() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, null).forEach { accuracy ->
            val message = unreliableLocationMessage(
                NearbyLocationIssue(NearbyLocationProblem.AccuracyTooWide, accuracy),
            )
            assertEquals("Starglean needs a reported accuracy of 35 m or better.", message)
            assertFalse(message.contains("NaN"))
            assertFalse(message.contains("Infinity"))
        }
    }

    @Test fun eachUnreliableCauseHasSpecificGuidance() {
        val expectedPhrases = mapOf(
            NearbyLocationProblem.AccuracyMissing to "does not report accuracy",
            NearbyLocationProblem.InvalidCoordinates to "invalid coordinates",
            NearbyLocationProblem.InvalidTimestamp to "invalid timestamp",
            NearbyLocationProblem.MockLocation to "Simulated locations",
            NearbyLocationProblem.StaleSample to "too old",
        )
        val messages = expectedPhrases.mapValues { (problem, _) ->
            unreliableLocationMessage(NearbyLocationIssue(problem))
        }

        messages.forEach { (problem, message) ->
            assertTrue("Missing explanation for $problem", message.contains(checkNotNull(expectedPhrases[problem])))
        }
        assertEquals(messages.values.size, messages.values.toSet().size)
    }
}
