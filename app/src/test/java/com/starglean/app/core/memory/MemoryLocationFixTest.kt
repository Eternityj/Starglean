package com.starglean.app.core.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryLocationFixTest {
    private val now = 1_700_000_000_000L
    private val elapsed = 90_000_000_000L

    private fun reliable(
        latitude: Double = -37.8136,
        longitude: Double = 144.9631,
        hasAccuracy: Boolean = true,
        accuracy: Float = 12f,
        capturedAt: Long = now,
        fixElapsed: Long = elapsed,
        currentElapsed: Long = elapsed,
    ): Boolean = isMemoryLocationReliable(latitude, longitude, hasAccuracy, accuracy, capturedAt, now, fixElapsed, currentElapsed)

    @Test fun locationRetainsRawLocalCoordinatesButRedactsItsDebugString() {
        val fix = MemoryLocationFix(-37.813612345, 144.963198765, 12f, now)
        assertEquals(-37.813612345, fix.latitude, 0.0)
        assertEquals(144.963198765, fix.longitude, 0.0)
        assertFalse(fix.toString().contains("-37.813612345"))
        assertFalse(fix.toString().contains("144.963198765"))
        assertTrue(fix.toString().contains("redacted"))
    }

    @Test fun dataValidationRejectsMalformedCoordinatesAccuracyAndTimeWithoutEchoingValues() {
        val invalid = listOf<() -> MemoryLocationFix>(
            { MemoryLocationFix(Double.NaN, 1.0, 1f, now) },
            { MemoryLocationFix(90.01, 1.0, 1f, now) },
            { MemoryLocationFix(1.0, Double.POSITIVE_INFINITY, 1f, now) },
            { MemoryLocationFix(1.0, -180.01, 1f, now) },
            { MemoryLocationFix(1.0, 1.0, 0f, now) },
            { MemoryLocationFix(1.0, 1.0, -1f, now) },
            { MemoryLocationFix(1.0, 1.0, Float.NaN, now) },
            { MemoryLocationFix(1.0, 1.0, Float.POSITIVE_INFINITY, now) },
            { MemoryLocationFix(1.0, 1.0, 1f, 0L) },
            { MemoryLocationFix(1.0, 1.0, 1f, Long.MAX_VALUE) },
        )
        invalid.forEach { build ->
            val error = runCatching(build).exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertFalse(error?.message.orEmpty().contains("NaN"))
            assertFalse(error?.message.orEmpty().contains("180.01"))
        }
        MemoryLocationFix(-90.0, -180.0, 1f, 1L)
        MemoryLocationFix(90.0, 180.0, 1f, 253_402_300_799_999L)
    }

    @Test fun freshAccurateFixesAndBoundaryAccuracyAreAccepted() {
        assertTrue(reliable())
        assertEquals(100f, MEMORY_LOCATION_MAX_ACCURACY_METERS, 0f)
        assertTrue(reliable(accuracy = Math.nextDown(100f)))
        assertTrue(reliable(accuracy = 100f))
        assertFalse(reliable(accuracy = Math.nextUp(100f)))
        assertTrue(reliable(capturedAt = now - 45_000L, fixElapsed = elapsed - 45_000_000_000L))
        assertTrue(reliable(capturedAt = now + 5_000L, fixElapsed = elapsed + 5_000_000_000L))
    }

    @Test fun missingOrPoorAccuracyNeverProducesAFalsePreciseDirection() {
        assertFalse(reliable(hasAccuracy = false))
        assertFalse(reliable(accuracy = 100.1f))
        assertFalse(reliable(accuracy = 2_000f))
        assertFalse(reliable(accuracy = 0f))
        assertFalse(reliable(accuracy = Float.NaN))
        assertFalse(reliable(latitude = 100.0))
        assertFalse(reliable(longitude = Double.NaN))
    }

    @Test fun oldStoredLocationRemainsReadableButCannotQualifyAsANewPreciseFix() {
        // Tightening current capture quality must not corrupt previously saved diary records.
        val old = MemoryLocationFix(-37.8, 145.0, 2_000f, now)
        assertEquals(2_000f, old.accuracyMeters, 0f)
        assertFalse(reliable(accuracy = old.accuracyMeters))
    }

    @Test fun monotonicStalenessAndFutureTimestampsAreBothRejected() {
        assertFalse(reliable(fixElapsed = elapsed - 45_000_000_001L))
        assertFalse(reliable(fixElapsed = elapsed + 5_000_000_001L))
        assertFalse(reliable(fixElapsed = 0L))
        assertFalse(reliable(currentElapsed = -1L))
        assertFalse(reliable(fixElapsed = Long.MAX_VALUE))
        assertFalse(reliable(currentElapsed = Long.MAX_VALUE))
    }

    @Test fun invalidWallTimeCannotBeSavedEvenIfMonotonicAgeLooksFresh() {
        assertFalse(reliable(capturedAt = 0L))
        assertFalse(reliable(capturedAt = now - 45_001L))
        assertFalse(reliable(capturedAt = now + 5_001L))
        assertFalse(reliable(capturedAt = Long.MAX_VALUE))
    }

    @Test fun allLocationErrorsHaveSafeMessagesAndNoRetainedProviderCause() {
        MemoryLocationFailure.entries.forEach { failure ->
            val error = MemoryLocationException(failure)
            assertEquals(failure, error.code)
            assertTrue(error.message.orEmpty().isNotBlank())
            assertFalse(error.message.orEmpty().contains("latitude", ignoreCase = true))
            assertFalse(error.message.orEmpty().contains("longitude", ignoreCase = true))
            assertNull(error.cause)
        }
    }
}
