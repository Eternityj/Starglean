package com.starglean.app.ui.screens

import com.starglean.app.core.network.NetworkResult
import com.starglean.app.core.network.PreparedOpen
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDiscoveryAttemptStateTest {
    @Test fun `only successful no eligible response is classified as empty`() {
        val empty = PreparedOpen("NO_ELIGIBLE_CONTENT", null, null, null)

        assertTrue(NetworkResult.Success(empty).isVerifiedNoEligibleContent())
        assertFalse(NetworkResult.ServiceFailure("NO_ELIGIBLE_CONTENT").isVerifiedNoEligibleContent())
        assertFalse(NetworkResult.HttpFailure(503).isVerifiedNoEligibleContent())
        assertFalse(NetworkResult.TransportFailure(IllegalStateException("offline")).isVerifiedNoEligibleContent())
        assertFalse(NetworkResult.InvalidResponse("NO_ELIGIBLE_CONTENT").isVerifiedNoEligibleContent())
    }

    @Test fun `successful non-empty and error outcomes are not classified as empty`() {
        val staged = PreparedOpen(
            "CONTENT_STAGED",
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            4L,
            Instant.parse("2026-09-28T10:00:00Z"),
        )

        assertFalse(NetworkResult.Success(staged).isVerifiedNoEligibleContent())
        assertFalse(
            NetworkResult.Success(PreparedOpen("DAILY_LIMIT_REACHED", null, null, null))
                .isVerifiedNoEligibleContent(),
        )
    }

    @Test fun `shake acceptance requires every readiness guard and a fix`() {
        assertTrue(acceptsDiscoveryShake(false, false, true, true, true))
        assertFalse(acceptsDiscoveryShake(true, false, true, true, true))
        assertFalse(acceptsDiscoveryShake(false, true, true, true, true))
        assertFalse(acceptsDiscoveryShake(false, false, false, true, true))
        assertFalse(acceptsDiscoveryShake(false, false, true, false, true))
        assertFalse(acceptsDiscoveryShake(false, false, true, true, false))
    }

    @Test fun `ignored shake does not advance feedback sequence or clear current outcome`() {
        val current = DiscoveryAttemptState(acceptedShakeSequence = 8L, noEligibleContent = true)

        assertTrue(current === current.onShakeEvaluated(accepted = false))
    }

    @Test fun `accepted shake advances once and resets prior empty outcome`() {
        val next = DiscoveryAttemptState(acceptedShakeSequence = 8L, noEligibleContent = true)
            .onShakeEvaluated(accepted = true)

        assertTrue(next.acceptedShakeSequence == 9L)
        assertFalse(next.noEligibleContent)
    }

    @Test fun `prepare completion replaces rather than latches empty outcome`() {
        val empty = NetworkResult.Success(PreparedOpen("NO_ELIGIBLE_CONTENT", null, null, null))
        val limited = NetworkResult.Success(PreparedOpen("DAILY_LIMIT_REACHED", null, null, null))

        val afterEmpty = DiscoveryAttemptState(acceptedShakeSequence = 3L).onPrepareCompleted(empty)
        val afterLimited = afterEmpty.onPrepareCompleted(limited)

        assertTrue(afterEmpty.noEligibleContent)
        assertFalse(afterLimited.noEligibleContent)
        assertTrue(afterLimited.acceptedShakeSequence == 3L)
    }

    @Test fun `close reset clears empty outcome without changing feedback sequence`() {
        val reset = DiscoveryAttemptState(acceptedShakeSequence = 5L, noEligibleContent = true).resetOutcome()

        assertTrue(reset.acceptedShakeSequence == 5L)
        assertFalse(reset.noEligibleContent)
    }
}
