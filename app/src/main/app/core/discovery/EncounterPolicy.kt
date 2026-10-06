package com.starglean.app.core.discovery

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.zone.ZoneRulesException
import java.util.UUID

/**
 * Local bookkeeping for the server-authoritative daily encounter allowance.
 *
 * This class never spends, resets, or grants quota. It only displays a snapshot from an
 * authenticated OPEN_COMMITTED response that is bound to the current in-memory request.
 * The backend remains responsible for account locking, idempotency and the atomic daily count.
 */
internal object EncounterPolicy {
    const val DAILY_OPEN_LIMIT = 10
    const val OPEN_RESPONSE_TIMEOUT_MILLIS = 8_000L

    fun accountDate(instant: Instant, accountTimeZoneId: String): LocalDate =
        instant.atZone(validatedAccountZone(accountTimeZoneId)).toLocalDate()

    fun validatedAccountZone(accountTimeZoneId: String): ZoneId {
        require(accountTimeZoneId.isNotBlank() && accountTimeZoneId in ZoneId.getAvailableZoneIds()) {
            "A fixed IANA account timezone is required."
        }
        return try {
            ZoneId.of(accountTimeZoneId)
        } catch (_: ZoneRulesException) {
            throw IllegalArgumentException("A fixed IANA account timezone is required.")
        }
    }
}

internal data class EncounterQuotaDisplay(
    val accountDate: LocalDate,
    val used: Int,
    val serverCommittedAt: Instant,
) {
    init { require(used in 0..EncounterPolicy.DAILY_OPEN_LIMIT) }
    val remaining: Int get() = EncounterPolicy.DAILY_OPEN_LIMIT - used
}

/** Identity belongs to this coordinator instance; a reconstructed copy is never current. */
internal class EncounterOpenRequest internal constructor(
    val requestId: UUID,
    val generation: Long,
    val requestedAtElapsedMillis: Long,
    internal val deadlineElapsedMillis: Long,
) {
    override fun toString(): String = "EncounterOpenRequest(redacted)"
}

internal enum class EncounterOpenResponseStatus {
    /** Preparation/selection failed before the backend committed an open; no quota was spent. */
    PREPARE_FAILED,
    /** The backend rejected the open without committing it; no quota was spent. */
    OPEN_REJECTED,
    /** The backend atomically opened content and spent exactly one of today's allowances. */
    OPEN_COMMITTED,
}

/** Parsed only after transport authentication and a strict response-field allowlist. */
internal data class EncounterOpenResponse(
    val requestId: UUID,
    val generation: Long,
    val status: EncounterOpenResponseStatus,
    val accountDate: LocalDate? = null,
    val used: Int? = null,
    val dailyLimit: Int? = null,
    val serverCommittedAt: Instant? = null,
)

internal enum class EncounterResponseResult {
    COMMITTED_SNAPSHOT_APPLIED,
    NO_CHARGE_CONFIRMED,
    DUPLICATE_IGNORED,
    STALE_IGNORED,
    INVALID_IGNORED,
}

/**
 * Single-dispatcher coordinator. Starting a different request supersedes the old generation;
 * retrying the same request ID reuses the exact ticket and cannot create another local charge.
 */
internal class EncounterQuotaCoordinator(accountTimeZoneId: String) {
    val accountTimeZoneId: String = EncounterPolicy.validatedAccountZone(accountTimeZoneId).id

    private var generation = 0L
    private var pending: EncounterOpenRequest? = null
    private var quota: EncounterQuotaDisplay? = null
    private var lastElapsedMillis: Long? = null
    private var completedResponse: EncounterOpenResponse? = null

    fun beginOpen(requestId: UUID, nowElapsedMillis: Long): EncounterOpenRequest? {
        if (!observeClock(nowElapsedMillis)) return null
        pending?.takeIf {
            it.requestId == requestId && nowElapsedMillis < it.deadlineElapsedMillis
        }?.let { return it }
        if (generation == Long.MAX_VALUE || nowElapsedMillis > Long.MAX_VALUE - EncounterPolicy.OPEN_RESPONSE_TIMEOUT_MILLIS) {
            pending = null
            return null
        }
        generation++
        return EncounterOpenRequest(
            requestId = requestId,
            generation = generation,
            requestedAtElapsedMillis = nowElapsedMillis,
            deadlineElapsedMillis = nowElapsedMillis + EncounterPolicy.OPEN_RESPONSE_TIMEOUT_MILLIS,
        ).also { pending = it }
    }

    fun accept(
        request: EncounterOpenRequest,
        response: EncounterOpenResponse,
        nowElapsedMillis: Long,
    ): EncounterResponseResult {
        if (!observeClock(nowElapsedMillis)) return EncounterResponseResult.STALE_IGNORED
        val binding = response.requestId to response.generation
        completedResponse?.takeIf { it.requestId to it.generation == binding }?.let {
            return if (it == response) EncounterResponseResult.DUPLICATE_IGNORED
            else EncounterResponseResult.INVALID_IGNORED
        }
        val current = pending
        if (current !== request || response.requestId != request.requestId ||
            response.generation != request.generation || nowElapsedMillis >= request.deadlineElapsedMillis
        ) {
            if (current === request && nowElapsedMillis >= request.deadlineElapsedMillis) pending = null
            return EncounterResponseResult.STALE_IGNORED
        }
        pending = null
        if (response.status != EncounterOpenResponseStatus.OPEN_COMMITTED) {
            return if (response.accountDate == null && response.used == null && response.dailyLimit == null &&
                response.serverCommittedAt == null
            ) {
                completedResponse = response
                EncounterResponseResult.NO_CHARGE_CONFIRMED
            } else EncounterResponseResult.INVALID_IGNORED
        }
        val date = response.accountDate
        val used = response.used
        val committedAt = response.serverCommittedAt
        if (date == null || used == null || committedAt == null ||
            response.dailyLimit != EncounterPolicy.DAILY_OPEN_LIMIT ||
            used !in 1..EncounterPolicy.DAILY_OPEN_LIMIT ||
            EncounterPolicy.accountDate(committedAt, accountTimeZoneId) != date
        ) return EncounterResponseResult.INVALID_IGNORED
        quota = EncounterQuotaDisplay(date, used, committedAt)
        completedResponse = response
        return EncounterResponseResult.COMMITTED_SNAPSHOT_APPLIED
    }

    /** Null across an account-midnight boundary until the backend supplies that day's snapshot. */
    fun quotaAt(now: Instant): EncounterQuotaDisplay? = quota?.takeIf {
        it.accountDate == EncounterPolicy.accountDate(now, accountTimeZoneId)
    }

    fun invalidate() {
        pending = null
        if (generation < Long.MAX_VALUE) generation++
    }

    private fun observeClock(now: Long): Boolean {
        if (now < 0L || lastElapsedMillis?.let { now < it } == true) {
            invalidate()
            return false
        }
        lastElapsedMillis = now
        return true
    }
}
