package com.starglean.app.core.discovery

import com.starglean.app.core.model.ReadingEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyReadingGateTest {
    @Test fun newSessionHasNoPermissionToReadOrSendARequest() {
        val gate = NearbyReadingGate(SESSION)
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(1_000L))
        assertNull(gate.beginCheck(1_000L))
        assertNull(gate.visibilityDeadlineAt(1_000L))
    }

    @Test fun strictMovementModeCannotBeAccidentallyBypassedByTheLegacyOverload() {
        val gate = NearbyReadingGate(SESSION, requireMovementEvidence = true)
        gate.updateEnvironment(true, true, true, BASE_FIX, 1_000L)
        assertNull(gate.beginCheck(1_000L))
        gate.updateEnvironment(true, true, true, BASE_FIX, 1_000L, NearbyMovementStatus.Settled)
        assertNotNull(gate.beginCheck(1_000L))
    }

    @Test fun readingStartsWithTheFirstPreciseFixWhileMovementIsStillBeingChecked() {
        val gate = NearbyReadingGate(SESSION, requireMovementEvidence = true)
        update(gate, movement = NearbyMovementStatus.Collecting)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, gate.beginCheck(1_000L)!!))
        assertEquals(ReadingEligibility.UNKNOWN, update(gate, movement = NearbyMovementStatus.FastMovement))
    }

    @Test fun localLocationAloneNeverGrantsReading() {
        val gate = readyGate()
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(1_000L))
        val check = gate.beginCheck(1_000L)!!
        assertEquals(SESSION, check.sessionId)
        assertEquals(1L, check.sequence)
        assertSame(BASE_FIX, check.readerFix)
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(1_000L))
    }

    @Test fun validResponseGrantsOnlyUntilDispatchBasedDeadline() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, check, now = 2_000L))
        assertEquals(6_000L, gate.visibilityDeadlineAt(2_000L))
        assertEquals(ReadingEligibility.IN_RANGE, gate.eligibilityAt(5_999L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(6_000L))
        assertNull(gate.visibilityDeadlineAt(6_000L))
    }

    @Test fun shorterServerLeaseIsNotExtendedToLocalMaximum() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, check, 1_100L, lease = 500L))
        assertEquals(1_500L, gate.visibilityDeadlineAt(1_100L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(1_500L))
    }

    @Test fun leaseIsFurtherBoundedByLocationFreshness() {
        val gate = readyGate(now = 8_000L, quality = fix(at = 1_000L))
        val check = gate.beginCheck(8_000L)!!
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, check, now = 8_100L))
        assertEquals(9_000L, gate.visibilityDeadlineAt(8_100L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(9_000L))
        assertNull(gate.beginCheck(9_000L))
    }

    @Test fun excessiveZeroAndNegativeLeasesNeverGrant() {
        listOf(-1L, 0L, 5_001L, Long.MAX_VALUE).forEach { lease ->
            val gate = readyGate()
            val check = gate.beginCheck(1_000L)!!
            assertEquals(ReadingEligibility.UNKNOWN, grant(gate, check, lease = lease))
            assertEquals(ReadingEligibility.UNKNOWN, grant(gate, check, lease = 5_000L))
        }
    }

    @Test fun lateResponseCannotRestartAFullLeaseAtArrival() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, check, now = 6_000L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(6_001L))
    }

    @Test fun responseArrivingAfterItsShorterLeaseAlsoCannotGrant() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, check, now = 2_000L, lease = 1_000L))
    }

    @Test fun accuracyBoundaryIsInclusiveAndFreshnessDeadlineExclusive() {
        val gate = readyGate(now = 8_999L, quality = fix(at = 1_000L, accuracy = 35.0))
        assertNotNull(gate.beginCheck(8_999L))
        assertNull(gate.beginCheck(9_000L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(9_000L))
    }

    @Test fun inaccurateNonfiniteNonpositiveMockFutureAndMissingFixesAreRejected() {
        listOf(
            fix(accuracy = 35.01), fix(accuracy = Double.NaN), fix(accuracy = Double.POSITIVE_INFINITY),
            fix(accuracy = Double.NEGATIVE_INFINITY), fix(accuracy = 0.0), fix(accuracy = -1.0),
            fix(mock = true), fix(at = 1_001L), fix(at = -1L), null,
        ).forEach { quality ->
            val gate = readyGate(quality = quality)
            assertNull(gate.beginCheck(1_000L))
            assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(1_000L))
        }
    }

    @Test fun qualifiedNewFixDoesNotMutateOrRejectTheAlreadyDispatchedRequest() {
        val gate = readyGate()
        val old = gate.beginCheck(1_000L)!!
        grant(gate, old)
        val renewal = gate.beginCheck(1_001L)!!
        assertEquals(ReadingEligibility.IN_RANGE, update(gate, now = 1_100L, quality = fix(at = 1_100L)))
        assertSame(BASE_FIX, renewal.readerFix)
        assertTrue(gate.isPending(renewal))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, renewal, now = 1_100L))
        val current = gate.beginCheck(1_100L)!!
        assertTrue(current.sequence > renewal.sequence)
        assertEquals(1_100L, current.readerFix.sampledAtElapsedMillis)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, current, now = 1_100L))
    }

    @Test fun degradedFixImmediatelyMasksAnExistingGrant() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        assertEquals(ReadingEligibility.UNKNOWN, update(gate, quality = fix(accuracy = 70.0)))
        assertNull(gate.visibilityDeadlineAt(1_000L))
        assertNull(gate.beginCheck(1_000L))
    }

    @Test fun fastOrUncertainMovementMasksLocallyWithoutBecomingServerAuthorisation() {
        listOf(NearbyMovementStatus.FastMovement, NearbyMovementStatus.Uncertain).forEach { movement ->
            val gate = readyGate()
            grant(gate, gate.beginCheck(1_000L)!!)
            assertEquals(ReadingEligibility.UNKNOWN, update(gate, movement = movement))
            assertNull(gate.beginCheck(1_000L))
            assertNull(gate.visibilityDeadlineAt(1_000L))
            assertEquals(ReadingEligibility.UNKNOWN, update(gate, movement = NearbyMovementStatus.Settled))
            val check = gate.beginCheck(1_000L)!!
            assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(1_000L))
            assertEquals(ReadingEligibility.IN_RANGE, grant(gate, check))
        }
    }

    @Test fun sameTimestampQualifiedReplacementDoesNotRewriteTheOldTicket() {
        val gate = readyGate()
        val old = gate.beginCheck(1_000L)!!
        val replacement = fix(accuracy = 15.0)
        update(gate, quality = replacement)
        assertSame(BASE_FIX, old.readerFix)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, old))
        assertSame(replacement, gate.beginCheck(1_000L)!!.readerFix)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, gate.beginCheck(1_000L)!!))
    }

    @Test fun qualifiedReplacementFixPreservesUnexpiredGrantAndInFlightTicketIdentity() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        val pending = gate.beginCheck(1_000L)!!
        val otherOriginalFix = fix()
        assertEquals(BASE_FIX.sampledAtElapsedMillis, otherOriginalFix.sampledAtElapsedMillis)
        assertEquals(BASE_FIX.horizontalAccuracyMeters, otherOriginalFix.horizontalAccuracyMeters, 0.0)
        assertFalse(BASE_FIX === otherOriginalFix)
        assertEquals(ReadingEligibility.IN_RANGE, update(gate, quality = otherOriginalFix))
        assertTrue(gate.isPending(pending))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, pending))
        val current = gate.beginCheck(1_000L)!!
        assertSame(otherOriginalFix, current.readerFix)
        assertTrue(current.sequence > pending.sequence)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, current))
    }

    @Test fun replacementFixCannotExtendOldLeaseWithoutItsOwnAcceptedCheck() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!, lease = 1_000L)
        update(gate, now = 1_500L, quality = fix(at = 1_500L))
        assertEquals(2_000L, gate.visibilityDeadlineAt(1_500L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(2_000L))
    }

    @Test fun continuousOneHertzFixesWithSlowResponsesKeepReadingForSixtySeconds() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        var requestAt = 1_000L
        var nextFixAt = 2_000L
        var cycle = 0
        while (requestAt < 60_000L) {
            val ticket = gate.beginCheck(requestAt)!!
            val responseAt = requestAt + if (cycle++ % 2 == 0) 1_500L else 2_500L
            while (nextFixAt <= responseAt) {
                assertEquals(ReadingEligibility.IN_RANGE,
                    update(gate, now = nextFixAt, quality = fix(at = nextFixAt)))
                assertTrue(gate.isPending(ticket))
                nextFixAt += 1_000L
            }
            assertEquals(ReadingEligibility.IN_RANGE, grant(gate, ticket, now = responseAt))
            requestAt = responseAt
        }
        assertEquals(ReadingEligibility.IN_RANGE, gate.eligibilityAt(requestAt))
    }

    @Test fun currentReplacementFixCanRenewBeforeThePreservedLeaseExpires() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        update(gate, now = 2_000L, quality = fix(at = 2_000L))
        val renewal = gate.beginCheck(2_000L)!!
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, renewal, now = 2_500L))
        assertEquals(7_000L, gate.visibilityDeadlineAt(2_500L))
    }

    @Test fun verifiedOutOfRangeOrEndedAlwaysTerminatesEvenAfterFixSupersedesTicket() {
        listOf(ReadingEligibility.OUT_OF_RANGE, ReadingEligibility.ENDED).forEach { terminal ->
            val gate = readyGate()
            grant(gate, gate.beginCheck(1_000L)!!)
            val old = gate.beginCheck(1_100L)!!
            update(gate, now = 1_200L, quality = fix(at = 1_200L))
            assertTrue(gate.isPending(old))
            assertEquals(terminal, gate.acceptVerifiedTerminal(terminal, 1_300L))
            assertNull(gate.beginCheck(1_300L))
            assertNull(gate.visibilityDeadlineAt(1_300L))
        }
    }

    @Test fun verifiedTerminalFromExplicitlySupersededRequestStillEndsWholeSession() {
        listOf(ReadingEligibility.OUT_OF_RANGE, ReadingEligibility.ENDED).forEach { terminal ->
            val gate = readyGate()
            grant(gate, gate.beginCheck(1_000L)!!)
            val first = gate.beginCheck(1_100L)!!
            val second = gate.beginCheck(1_200L)!!
            assertFalse(gate.isPending(first))
            assertTrue(gate.isPending(second))
            // Transport has authenticated and exactly matched the first request's envelope.
            assertEquals(terminal, gate.acceptVerifiedTerminal(terminal, 1_300L))
            assertFalse(gate.isPending(second))
            assertNull(gate.beginCheck(1_300L))
            assertNull(gate.visibilityDeadlineAt(1_300L))
        }
    }

    @Test fun replacementFixDoesNotPreserveGrantAcrossAnyRealQualificationLoss() {
        data class Case(
            val quality: NearbyReaderFixQuality? = fix(at = 1_100L),
            val permission: Boolean = true,
            val network: Boolean = true,
            val movement: NearbyMovementStatus = NearbyMovementStatus.Settled,
        )
        listOf(
            Case(quality = null),
            Case(quality = fix(at = 1_100L, accuracy = 35.0001)),
            Case(quality = fix(at = 1_100L, mock = true)),
            Case(permission = false),
            Case(network = false),
            Case(movement = NearbyMovementStatus.FastMovement),
            Case(movement = NearbyMovementStatus.Uncertain),
        ).forEach { case ->
            val gate = readyGate()
            grant(gate, gate.beginCheck(1_000L)!!)
            assertEquals(ReadingEligibility.UNKNOWN, update(gate, now = 1_100L,
                quality = case.quality, permission = case.permission, network = case.network,
                movement = case.movement))
            assertNull(gate.visibilityDeadlineAt(1_100L))
        }
    }

    @Test fun identicalEnvironmentUpdateDoesNotDiscardTheMatchingCheck() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        update(gate, now = 1_100L)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, check, now = 1_100L))
    }

    @Test fun olderFixCannotReplaceALaterAcceptedFixAfterNetworkRecovery() {
        val gate = readyGate(now = 2_000L, quality = fix(at = 2_000L))
        grant(gate, gate.beginCheck(2_000L)!!, now = 2_000L)
        update(gate, now = 2_100L, network = false)
        update(gate, now = 2_200L, quality = fix(at = 1_000L))
        assertNull(gate.beginCheck(2_200L))
        update(gate, now = 2_200L, quality = fix(at = 2_000L))
        assertNotNull(gate.beginCheck(2_200L))
    }

    @Test fun twoRequestsResolveOnlyTheLatestAndSequencesIncrease() {
        val gate = readyGate()
        val first = gate.beginCheck(1_000L)!!
        val second = gate.beginCheck(1_010L)!!
        assertTrue(second.sequence > first.sequence)
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, first, now = 1_020L))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, second, now = 1_030L))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, first, now = 1_040L))
        assertEquals(6_010L, gate.visibilityDeadlineAt(1_040L))
    }

    @Test fun aCopiedTicketIsNotTheLocallyPendingRequest() {
        val gate = readyGate()
        val original = gate.beginCheck(1_000L)!!
        val copy = NearbyReadingCheck(
            original.sessionId, original.generation, original.sequence,
            original.requestedAtElapsedMillis, original.readerFix, original.deadlineElapsedMillis,
        )
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, copy))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, original))
    }

    @Test fun sameSessionIdDoesNotLetARequestFromAnotherGateAuthoriseThisOne() {
        val first = readyGate()
        val second = readyGate()
        val firstCheck = first.beginCheck(1_000L)!!
        val secondCheck = second.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.UNKNOWN, grant(second, firstCheck))
        assertEquals(ReadingEligibility.IN_RANGE, grant(second, secondCheck))
    }

    @Test fun wrongResponseSessionMasksAndConsumesTheCurrentRequest() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        val check = gate.beginCheck(1_001L)!!
        assertEquals(
            ReadingEligibility.UNKNOWN,
            gate.accept(check, "other-session", ReadingEligibility.IN_RANGE, 5_000L, 1_001L),
        )
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, check, now = 1_001L))
    }

    @Test fun duplicateResponseCannotExtendAGrant() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        grant(gate, check, lease = 1_000L)
        grant(gate, check, now = 1_500L, lease = 5_000L)
        assertEquals(2_000L, gate.visibilityDeadlineAt(1_500L))
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, check, now = 2_000L))
    }

    @Test fun sameFixRenewalMayMaintainAnExistingUnexpiredLease() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        val renewal = gate.beginCheck(2_000L)!!
        assertEquals(ReadingEligibility.IN_RANGE, gate.eligibilityAt(2_100L))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, renewal, now = 2_100L))
        assertEquals(7_000L, gate.visibilityDeadlineAt(2_100L))
    }

    @Test fun oldGrantExpiresImmediatelyButStillValidRenewalMayRecoverTheUnknownSession() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        val renewal = gate.beginCheck(5_900L)!!
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(6_000L))
        assertNull(gate.visibilityDeadlineAt(6_000L))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, renewal, now = 6_001L))
        // Renewal still cannot outlive the original fix's freshness deadline.
        assertEquals(9_000L, gate.visibilityDeadlineAt(6_001L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(9_000L))
    }

    @Test fun unknownIsRecoverableOnlyWithANewRequest() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        assertEquals(
            ReadingEligibility.UNKNOWN,
            gate.accept(check, SESSION, ReadingEligibility.UNKNOWN, 0L, 1_000L),
        )
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, check))
        val retry = gate.beginCheck(1_000L)!!
        assertTrue(retry.generation > check.generation)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, retry))
    }

    @Test fun currentFailureHidesImmediatelyAndLateSuccessCannotRestoreIt() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        val renewal = gate.beginCheck(1_010L)!!
        assertEquals(ReadingEligibility.UNKNOWN, gate.verificationFailed(renewal, 1_010L))
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, renewal, now = 1_020L))
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, gate.beginCheck(1_020L)!!, now = 1_020L))
    }

    @Test fun failureOfASupersededRequestDoesNotInvalidateTheCurrentGrant() {
        val gate = readyGate()
        val old = gate.beginCheck(1_000L)!!
        val current = gate.beginCheck(1_010L)!!
        grant(gate, current, now = 1_010L)
        assertEquals(ReadingEligibility.IN_RANGE, gate.verificationFailed(old, 1_020L))
    }

    @Test fun networkLossAndPermissionLossHideImmediatelyAndRequireNewRequests() {
        listOf(true, false).forEach { revokePermission ->
            val gate = readyGate()
            grant(gate, gate.beginCheck(1_000L)!!)
            val old = gate.beginCheck(1_001L)!!
            assertEquals(
                ReadingEligibility.UNKNOWN,
                update(gate, now = 1_010L, permission = !revokePermission, network = revokePermission),
            )
            assertNull(gate.beginCheck(1_010L))
            update(gate, now = 1_020L)
            assertEquals(ReadingEligibility.UNKNOWN, grant(gate, old, now = 1_020L))
            val current = gate.beginCheck(1_020L)!!
            assertTrue(current.generation > old.generation)
            assertEquals(ReadingEligibility.IN_RANGE, grant(gate, current, now = 1_020L))
        }
    }

    @Test fun backgroundPermanentlyEndsEvenBeforeAnyServerResponse() {
        val gate = readyGate()
        val old = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.ENDED, update(gate, foreground = false))
        assertEquals(ReadingEligibility.ENDED, update(gate))
        assertEquals(ReadingEligibility.ENDED, grant(gate, old))
        assertNull(gate.beginCheck(1_000L))
    }

    @Test fun pauseImmediatelyHidesALeaseAndStopsChecksWithoutEndingSession() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, check))
        assertEquals(ReadingEligibility.UNKNOWN, gate.pause(1_100L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.eligibilityAt(1_100L))
        assertNull(gate.visibilityDeadlineAt(1_100L))
        assertNull(gate.beginCheck(1_100L))
        assertFalse(gate.isPending(check))
    }

    @Test fun pauseRejectsALateGrantAndRequiresNewFixAndSequenceOnResume() {
        val gate = readyGate()
        val old = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.UNKNOWN, gate.pause(1_100L))
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, old, now = 1_101L))
        assertEquals(ReadingEligibility.UNKNOWN, update(gate, now = 1_102L, quality = BASE_FIX))
        assertNull(gate.beginCheck(1_102L))
        assertEquals(ReadingEligibility.UNKNOWN, update(gate, now = 1_102L, quality = fix(at = 1_099L)))
        assertNull(gate.beginCheck(1_102L))

        val resumedFix = fix(at = 1_103L)
        assertEquals(ReadingEligibility.UNKNOWN, update(gate, now = 1_103L, quality = resumedFix))
        val resumed = gate.beginCheck(1_103L)!!
        assertEquals(SESSION, resumed.sessionId)
        assertTrue(resumed.sequence > old.sequence)
        assertTrue(resumed.generation > old.generation)
        assertSame(resumedFix, resumed.readerFix)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, resumed, now = 1_104L))
        assertEquals(ReadingEligibility.IN_RANGE, gate.eligibilityAt(1_104L))
    }

    @Test fun repeatedPauseInvalidatesEachPendingCheckAndNeedsAnotherFreshFix() {
        val gate = readyGate()
        val first = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.UNKNOWN, gate.pause(1_100L))
        assertEquals(ReadingEligibility.UNKNOWN, gate.pause(1_101L))
        assertNull(gate.beginCheck(1_101L))
        val resumedFix = fix(at = 1_102L)
        update(gate, now = 1_102L, quality = resumedFix)
        val second = gate.beginCheck(1_102L)!!
        assertEquals(ReadingEligibility.UNKNOWN, gate.pause(1_103L))
        assertFalse(gate.isPending(second))
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, first, now = 1_104L))
        assertEquals(ReadingEligibility.UNKNOWN, grant(gate, second, now = 1_104L))
        update(gate, now = 1_105L, quality = resumedFix)
        assertNull(gate.beginCheck(1_105L))
        update(gate, now = 1_106L, quality = fix(at = 1_106L))
        val third = gate.beginCheck(1_106L)!!
        assertTrue(third.sequence > second.sequence)
        assertEquals(ReadingEligibility.IN_RANGE, grant(gate, third, now = 1_107L))
    }

    @Test fun pauseCannotReviveTerminalDecisions() {
        listOf(ReadingEligibility.OUT_OF_RANGE, ReadingEligibility.ENDED).forEach { terminal ->
            val gate = readyGate()
            val check = gate.beginCheck(1_000L)!!
            assertEquals(terminal, gate.accept(check, SESSION, terminal, 0L, 1_000L))
            assertEquals(terminal, gate.pause(1_100L))
            assertEquals(terminal, update(gate, now = 1_101L, quality = fix(at = 1_101L)))
            assertEquals(terminal, grant(gate, check, now = 1_102L))
            assertNull(gate.beginCheck(1_102L))
        }
    }

    @Test fun backgroundAlsoHidesAnAlreadyVisibleDiary() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        assertEquals(ReadingEligibility.ENDED, update(gate, foreground = false))
        assertNull(gate.visibilityDeadlineAt(1_000L))
    }

    @Test fun outOfRangeAndEndedAreTerminalEvenAfterANewFix() {
        listOf(ReadingEligibility.OUT_OF_RANGE, ReadingEligibility.ENDED).forEach { terminal ->
            val gate = readyGate()
            val check = gate.beginCheck(1_000L)!!
            assertEquals(terminal, gate.accept(check, SESSION, terminal, 0L, 1_000L))
            assertEquals(terminal, update(gate, now = 2_000L, quality = fix(at = 2_000L)))
            assertEquals(terminal, grant(gate, check, now = 2_000L))
            assertNull(gate.beginCheck(2_000L))
            assertNull(gate.visibilityDeadlineAt(2_000L))
        }
    }

    @Test fun closingCannotBeUndoneByAPendingSuccess() {
        val gate = readyGate()
        val check = gate.beginCheck(1_000L)!!
        assertEquals(ReadingEligibility.ENDED, gate.end(1_001L))
        assertEquals(ReadingEligibility.ENDED, grant(gate, check, now = 1_002L))
        assertNull(gate.beginCheck(1_002L))
    }

    @Test fun decreasedElapsedTimeFailsClosedPermanently() {
        val gate = readyGate()
        grant(gate, gate.beginCheck(1_000L)!!)
        assertEquals(ReadingEligibility.ENDED, gate.eligibilityAt(999L))
        assertEquals(ReadingEligibility.ENDED, update(gate, now = 2_000L, quality = fix(at = 2_000L)))
        assertNull(gate.beginCheck(2_000L))
    }

    @Test fun negativeClockAndOverflowingDeadlineFailClosed() {
        assertEquals(ReadingEligibility.ENDED, NearbyReadingGate(SESSION).eligibilityAt(-1L))
        val nearOverflow = Long.MAX_VALUE - 100L
        val gate = readyGate(now = nearOverflow, quality = fix(at = nearOverflow))
        assertNull(gate.beginCheck(nearOverflow))
        assertEquals(ReadingEligibility.ENDED, gate.eligibilityAt(nearOverflow))
    }

    @Test fun requestDiagnosticStringDoesNotExposeSessionIdentifier() {
        val check = readyGate().beginCheck(1_000L)!!
        assertFalse(check.toString().contains(SESSION))
        assertEquals("NearbyReadingCheck(redacted)", check.toString())
    }

    @Test fun invalidSessionIdentifiersAreRejectedWithoutEchoingTheirValue() {
        listOf("", " ", "private\nidentifier", "a".repeat(257)).forEach { invalid ->
            val failure = runCatching { NearbyReadingGate(invalid) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals("Invalid anonymous reading session", failure?.message)
        }
    }

    private fun readyGate(now: Long = 1_000L, quality: NearbyReaderFixQuality? = BASE_FIX) =
        NearbyReadingGate(SESSION).also { update(it, now = now, quality = quality) }

    private fun update(
        gate: NearbyReadingGate,
        now: Long = 1_000L,
        quality: NearbyReaderFixQuality? = BASE_FIX,
        foreground: Boolean = true,
        permission: Boolean = true,
        network: Boolean = true,
        movement: NearbyMovementStatus = NearbyMovementStatus.Settled,
    ) = gate.updateEnvironment(foreground, permission, network, quality, now, movement)

    private fun grant(
        gate: NearbyReadingGate,
        check: NearbyReadingCheck,
        now: Long = 1_000L,
        lease: Long = 5_000L,
    ) = gate.accept(check, SESSION, ReadingEligibility.IN_RANGE, lease, now)

    private fun fix(at: Long = 1_000L, accuracy: Double = 20.0, mock: Boolean = false) =
        NearbyReaderFixQuality(at, accuracy, mock)

    private companion object {
        const val SESSION = "opaque-test-session-only"
        val BASE_FIX = NearbyReaderFixQuality(1_000L, 20.0, false)
    }
}
