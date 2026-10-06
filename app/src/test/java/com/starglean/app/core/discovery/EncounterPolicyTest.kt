package com.starglean.app.core.discovery

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EncounterPolicyTest {
    @Test fun fixedIanaZoneDefinesAccountDayAcrossMelbourneDst() {
        assertEquals(LocalDate.of(2026, 10, 4), day("2026-10-03T16:30:00Z"))
        assertEquals(LocalDate.of(2026, 10, 5), day("2026-10-04T13:00:00Z"))
        expectIllegal { EncounterQuotaCoordinator("") }
        expectIllegal { EncounterQuotaCoordinator("GMT+10:00") }
    }

    @Test fun onlyCommittedOpenUpdatesTheDisplayedAllowance() {
        val coordinator = EncounterQuotaCoordinator(ZONE)
        val failed = coordinator.beginOpen(UUID.randomUUID(), 1_000L)!!
        assertEquals(EncounterResponseResult.NO_CHARGE_CONFIRMED, coordinator.accept(
            failed, response(failed, EncounterOpenResponseStatus.PREPARE_FAILED), 1_100L,
        ))
        assertNull(coordinator.quotaAt(Instant.parse("2026-09-27T00:00:00Z")))

        val committed = coordinator.beginOpen(UUID.randomUUID(), 2_000L)!!
        assertEquals(EncounterResponseResult.COMMITTED_SNAPSHOT_APPLIED, coordinator.accept(
            committed, committedResponse(committed, used = 1), 2_100L,
        ))
        val display = coordinator.quotaAt(Instant.parse("2026-09-27T01:00:00Z"))!!
        assertEquals(1, display.used)
        assertEquals(9, display.remaining)
    }

    @Test fun retryReusesTheExactTicketAndDuplicateReceiptCannotChargeAgain() {
        val coordinator = EncounterQuotaCoordinator(ZONE)
        val id = UUID.randomUUID()
        val request = coordinator.beginOpen(id, 1_000L)!!
        assertSame(request, coordinator.beginOpen(id, 1_500L))
        val receipt = committedResponse(request, used = 4)
        assertEquals(EncounterResponseResult.COMMITTED_SNAPSHOT_APPLIED, coordinator.accept(request, receipt, 2_000L))
        assertEquals(EncounterResponseResult.DUPLICATE_IGNORED, coordinator.accept(request, receipt, 2_100L))
        assertEquals(EncounterResponseResult.INVALID_IGNORED,
            coordinator.accept(request, receipt.copy(used = 5), 2_200L))
        assertEquals(4, coordinator.quotaAt(Instant.parse("2026-09-27T01:00:00Z"))!!.used)
    }

    @Test fun newRequestSupersedesOldGenerationAndLateResponseIsIgnored() {
        val coordinator = EncounterQuotaCoordinator(ZONE)
        val old = coordinator.beginOpen(UUID.randomUUID(), 1_000L)!!
        val current = coordinator.beginOpen(UUID.randomUUID(), 1_100L)!!
        assertNotSame(old, current)
        assertTrue(current.generation > old.generation)
        assertEquals(EncounterResponseResult.STALE_IGNORED,
            coordinator.accept(old, committedResponse(old, used = 1), 1_200L))
        assertNull(coordinator.quotaAt(Instant.parse("2026-09-27T01:00:00Z")))
        assertEquals(EncounterResponseResult.COMMITTED_SNAPSHOT_APPLIED,
            coordinator.accept(current, committedResponse(current, used = 1), 1_300L))
    }

    @Test fun copiedTicketWrongGenerationAndExpiredResponseCannotUpdateDisplay() {
        val coordinator = EncounterQuotaCoordinator(ZONE)
        val request = coordinator.beginOpen(UUID.randomUUID(), 1_000L)!!
        val copy = EncounterOpenRequest(request.requestId, request.generation,
            request.requestedAtElapsedMillis, request.deadlineElapsedMillis)
        assertEquals(EncounterResponseResult.STALE_IGNORED,
            coordinator.accept(copy, committedResponse(request, used = 1), 1_100L))
        assertEquals(EncounterResponseResult.STALE_IGNORED,
            coordinator.accept(request, committedResponse(request, used = 1).copy(generation = request.generation + 1), 1_200L))
        assertEquals(EncounterResponseResult.STALE_IGNORED,
            coordinator.accept(request, committedResponse(request, used = 1), 9_000L))
        assertNull(coordinator.quotaAt(Instant.parse("2026-09-27T01:00:00Z")))
    }

    @Test fun midnightDoesNotLocallyRollOverOrCarryAllowance() {
        val coordinator = EncounterQuotaCoordinator(ZONE)
        val first = coordinator.beginOpen(UUID.randomUUID(), 1_000L)!!
        val beforeMidnight = Instant.parse("2026-09-27T13:59:59Z") // 23:59:59 Melbourne
        assertEquals(EncounterResponseResult.COMMITTED_SNAPSHOT_APPLIED,
            coordinator.accept(first, committedResponse(first, used = 10, at = beforeMidnight), 1_100L))
        assertEquals(0, coordinator.quotaAt(beforeMidnight)!!.remaining)
        assertNull(coordinator.quotaAt(Instant.parse("2026-09-27T14:00:00Z")))

        val nextDay = coordinator.beginOpen(UUID.randomUUID(), 2_000L)!!
        val afterMidnight = Instant.parse("2026-09-27T14:00:01Z")
        coordinator.accept(nextDay, committedResponse(nextDay, used = 1, at = afterMidnight), 2_100L)
        assertEquals(9, coordinator.quotaAt(afterMidnight)!!.remaining)
    }

    @Test fun malformedCommittedSnapshotFailsClosed() {
        val coordinator = EncounterQuotaCoordinator(ZONE)
        val request = coordinator.beginOpen(UUID.randomUUID(), 1_000L)!!
        assertEquals(EncounterResponseResult.INVALID_IGNORED, coordinator.accept(
            request, committedResponse(request, used = 1).copy(dailyLimit = 11), 1_100L,
        ))
        assertNull(coordinator.quotaAt(Instant.parse("2026-09-27T01:00:00Z")))
    }

    private fun day(value: String): LocalDate = EncounterPolicy.accountDate(Instant.parse(value), ZONE)

    private fun response(request: EncounterOpenRequest, status: EncounterOpenResponseStatus) =
        EncounterOpenResponse(request.requestId, request.generation, status)

    private fun committedResponse(
        request: EncounterOpenRequest,
        used: Int,
        at: Instant = Instant.parse("2026-09-27T01:00:00Z"),
    ) = EncounterOpenResponse(
        requestId = request.requestId,
        generation = request.generation,
        status = EncounterOpenResponseStatus.OPEN_COMMITTED,
        accountDate = EncounterPolicy.accountDate(at, ZONE),
        used = used,
        dailyLimit = EncounterPolicy.DAILY_OPEN_LIMIT,
        serverCommittedAt = at,
    )

    private fun expectIllegal(block: () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    private companion object { const val ZONE = "Australia/Melbourne" }
}
