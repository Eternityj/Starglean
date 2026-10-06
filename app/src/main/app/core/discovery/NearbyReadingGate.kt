package com.starglean.app.core.discovery

import com.starglean.app.core.model.ReadingEligibility

/** Initial engineering limits, not a guarantee of physical location or a defence against spoofing. */
internal object NearbyReadingPolicy {
    const val MAX_LEASE_MILLIS = 5_000L
    const val MAX_FIX_AGE_MILLIS = 8_000L
    const val MAX_HORIZONTAL_ACCURACY_METERS = 35.0
}

/**
 * Metadata for the exact reader fix submitted by the transport, not the author's location.
 * The Android adapter must validate finite, legal coordinates separately. All times here use
 * the same monotonic elapsed-realtime clock, never wall time or a server timestamp.
 *
 * Identity represents the original reader fix. The adapter must create a NEW object for
 * every new original fix, even when timestamps/accuracy match another provider's sample,
 * and reuse that object when reporting the same unchanged fix. Equal quality numbers are
 * not proof that two locations are the same. Do not reconstruct this object from a response.
 */
internal class NearbyReaderFixQuality(
    val sampledAtElapsedMillis: Long,
    val horizontalAccuracyMeters: Double,
    val isMock: Boolean,
) {
    internal fun isQualifiedAt(nowElapsedMillis: Long): Boolean =
        sampledAtElapsedMillis >= 0L &&
            nowElapsedMillis >= sampledAtElapsedMillis &&
            nowElapsedMillis - sampledAtElapsedMillis < NearbyReadingPolicy.MAX_FIX_AGE_MILLIS &&
            horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters > 0.0 &&
            horizontalAccuracyMeters <= NearbyReadingPolicy.MAX_HORIZONTAL_ACCURACY_METERS && !isMock
}

/**
 * An in-memory request ticket, minted only by [NearbyReadingGate.beginCheck]. Identity, not
 * equality of its fields, establishes ownership. Reconstructing a ticket from a response,
 * persistence, a copied session id or a previous gate must never authorise reading.
 *
 * The transport captures this ticket in its callback and submits its sequence/generation
 * alongside the *same* reader fix whose quality is recorded here. It must authenticate the
 * response, check its echoed session/request identifiers and then deliver that captured
 * ticket to [NearbyReadingGate.accept]. This class is not a server credential.
 */
internal class NearbyReadingCheck internal constructor(
    val sessionId: String,
    val generation: Long,
    val sequence: Long,
    val requestedAtElapsedMillis: Long,
    val readerFix: NearbyReaderFixQuality,
    internal val deadlineElapsedMillis: Long,
) {
    override fun toString(): String = "NearbyReadingCheck(redacted)"
}

/**
 * Fail-closed presentation gate for ONE anonymous diary reading session. Own saved entries
 * never pass through this class: they remain readable anywhere. This stores no diary body,
 * author identifier, author coordinates, street address or calculated author distance.
 *
 * This is only a local prerequisite, not real discovery by itself. The authenticated backend
 * must independently enforce the 200 m radius, location quality, session state, quotas and
 * visibility. A client claiming IN_RANGE, including a tampered client, is not authorisation.
 *
 * Call all methods on one serial dispatcher. Every body/image/feedback presentation must use
 * [eligibilityAt]; schedule a recheck no later than [visibilityDeadlineAt]. A stored IN_RANGE
 * value is not a durable grant. Clear or mask visible content on UNKNOWN; OUT_OF_RANGE and
 * ENDED permanently terminate this instance. A temporary app pause must immediately call
 * [pause], not wait for another GPS/network callback. A permanent background exit may still
 * call [updateEnvironment] with foreground=false to end this gate.
 *
 * A qualified replacement fix becomes the candidate for the next request; it neither changes
 * the fix identity captured by an in-flight ticket nor truncates an already granted lease.
 * Explicitly beginning another check supersedes the previous ticket. A response can renew only
 * within its own dispatch/fix deadlines while the current local environment remains qualified.
 * The sole late-response exception is an authenticated, exact session/generation/sequence
 * OUT_OF_RANGE or ENDED response: those are permanent server session events and terminate this
 * gate even when a newer local check superseded their ticket. The transport must reject a foreign
 * or mismatched envelope before invoking [acceptVerifiedTerminal].
 * Any real environment or quality failure still masks immediately. The lease is measured from
 * local request dispatch, not response arrival, so a delayed response never creates a fresh window.
 */
internal class NearbyReadingGate(
    private val sessionId: String,
    private val requireMovementEvidence: Boolean = false,
) {
    init {
        require(sessionId.isNotBlank() && sessionId.length <= 256 && sessionId.none(Char::isISOControl)) {
            "Invalid anonymous reading session"
        }
    }

    private var eligibility = ReadingEligibility.UNKNOWN
    private var foreground = false
    private var precisePermission = false
    private var networkAvailable = false
    // Strict callers must supply movement evidence; until they do, treat it as doubtful.
    private var movementStatus = if (requireMovementEvidence) NearbyMovementStatus.Uncertain else NearbyMovementStatus.Settled
    private var readerFix: NearbyReaderFixQuality? = null
    private var latestAcceptedFixTime: Long? = null
    private var pausedAtElapsedMillis: Long? = null
    private var prePauseReaderFix: NearbyReaderFixQuality? = null
    private var lastNowElapsedMillis: Long? = null
    private var visibilityDeadlineElapsedMillis: Long? = null
    private var pending: NearbyReadingCheck? = null
    private var generation = 0L
    private var sequence = 0L

    /**
     * Hide a temporarily paused session immediately without ending it. Any old lease and
     * in-flight check are invalidated. Resuming requires a distinct reader fix sampled no
     * earlier than this pause and a newly minted check; the session id and sequence remain.
     */
    fun pause(nowElapsedMillis: Long): ReadingEligibility {
        if (!observeClock(nowElapsedMillis) || isTerminal()) return eligibility
        prePauseReaderFix = readerFix ?: prePauseReaderFix
        pausedAtElapsedMillis = nowElapsedMillis
        foreground = false
        precisePermission = false
        networkAvailable = false
        movementStatus = NearbyMovementStatus.Uncertain
        readerFix = null
        invalidate()
        return eligibility
    }

    /** Supply the latest actual fix, never a cached city centre, weather query, or mock fix. */
    fun updateEnvironment(
        foreground: Boolean,
        precisePermission: Boolean,
        networkAvailable: Boolean,
        readerFix: NearbyReaderFixQuality?,
        nowElapsedMillis: Long,
    ): ReadingEligibility = updateEnvironment(
        foreground, precisePermission, networkAvailable, readerFix, nowElapsedMillis,
        if (requireMovementEvidence) NearbyMovementStatus.Uncertain else NearbyMovementStatus.Settled,
    )

    /** Strict discovery callers pass the current result from [NearbyMovementGate]. */
    fun updateEnvironment(
        foreground: Boolean,
        precisePermission: Boolean,
        networkAvailable: Boolean,
        readerFix: NearbyReaderFixQuality?,
        nowElapsedMillis: Long,
        movementStatus: NearbyMovementStatus,
    ): ReadingEligibility {
        if (!observeClock(nowElapsedMillis) || isTerminal()) return eligibility
        if (!foreground) return terminate(ReadingEligibility.ENDED)

        this.foreground = foreground
        this.precisePermission = precisePermission
        this.networkAvailable = networkAvailable
        this.movementStatus = movementStatus
        val qualified = readerFix?.takeIf {
            it.isQualifiedAt(nowElapsedMillis) &&
                (latestAcceptedFixTime == null || it.sampledAtElapsedMillis >= latestAcceptedFixTime!!) &&
                (pausedAtElapsedMillis == null ||
                    (it !== prePauseReaderFix && it.sampledAtElapsedMillis >= pausedAtElapsedMillis!!))
        }
        if (!precisePermission || !networkAvailable || !movementStatus.allowsNearbyUse || qualified == null) {
            this.readerFix = null
            invalidate()
        } else {
            latestAcceptedFixTime = qualified.sampledAtElapsedMillis
            if (this.readerFix !== qualified) {
                this.readerFix = qualified
            }
        }
        return refresh(nowElapsedMillis)
    }

    /**
     * Only this call creates a pending request. Starting another check supersedes the old
     * ticket, even when its response has not arrived. Never create tickets from server input.
     */
    fun beginCheck(nowElapsedMillis: Long): NearbyReadingCheck? {
        if (!observeClock(nowElapsedMillis)) return null
        refresh(nowElapsedMillis)
        if (isTerminal() || !hasQualifiedEnvironment(nowElapsedMillis)) return null
        if (sequence == Long.MAX_VALUE) {
            terminate(ReadingEligibility.ENDED)
            return null
        }
        val fix = readerFix ?: return null
        val requestDeadline = safeDeadline(nowElapsedMillis, NearbyReadingPolicy.MAX_LEASE_MILLIS)
        val fixDeadline = safeDeadline(fix.sampledAtElapsedMillis, NearbyReadingPolicy.MAX_FIX_AGE_MILLIS)
        if (requestDeadline == null || fixDeadline == null) {
            terminate(ReadingEligibility.ENDED)
            return null
        }
        return NearbyReadingCheck(
            sessionId = sessionId,
            generation = generation,
            sequence = ++sequence,
            requestedAtElapsedMillis = nowElapsedMillis,
            readerFix = fix,
            deadlineElapsedMillis = minOf(requestDeadline, fixDeadline),
        ).also { pending = it }
    }

    /** True only while this exact locally minted ticket can still affect this gate. */
    fun isPending(check: NearbyReadingCheck): Boolean =
        !isTerminal() && pending === check && check.generation == generation

    /**
     * A response is meaningful only for the currently pending locally captured ticket. The
     * backend lease is a duration of at most five seconds. No device/server wall-clock sync
     * is assumed. The caller must not extend that duration to compensate for network delay.
     */
    fun accept(
        check: NearbyReadingCheck,
        responseSessionId: String,
        decision: ReadingEligibility,
        leaseMillis: Long,
        nowElapsedMillis: Long,
    ): ReadingEligibility {
        if (!observeClock(nowElapsedMillis)) return eligibility
        refresh(nowElapsedMillis)
        if (isTerminal() || pending !== check || check.generation != generation) return eligibility
        if (responseSessionId != sessionId || !hasQualifiedEnvironment(nowElapsedMillis)) {
            invalidate()
            return eligibility
        }
        pending = null
        when (decision) {
            ReadingEligibility.ENDED, ReadingEligibility.OUT_OF_RANGE -> terminate(decision)
            ReadingEligibility.UNKNOWN -> invalidate()
            ReadingEligibility.IN_RANGE -> {
                val deadline = if (leaseMillis in 1L..NearbyReadingPolicy.MAX_LEASE_MILLIS) {
                    safeDeadline(check.requestedAtElapsedMillis, leaseMillis)?.let {
                        minOf(it, check.deadlineElapsedMillis)
                    }
                } else null
                if (deadline == null || nowElapsedMillis >= deadline) {
                    invalidate()
                } else {
                    visibilityDeadlineElapsedMillis = deadline
                    eligibility = ReadingEligibility.IN_RANGE
                }
            }
        }
        return eligibility
    }

    /** A current network/parse/auth failure masks immediately and invalidates its callback. */
    fun verificationFailed(check: NearbyReadingCheck, nowElapsedMillis: Long): ReadingEligibility {
        if (!observeClock(nowElapsedMillis)) return eligibility
        refresh(nowElapsedMillis)
        if (!isTerminal() && pending === check) invalidate()
        return eligibility
    }

    /**
     * Apply a permanent server session event even if its ticket was locally superseded.
     * The caller must first authenticate the response and match its session, generation and
     * sequence to the original locally dispatched request. Never call this for a foreign or
     * malformed envelope; ordinary stale successes remain governed by [accept].
     */
    fun acceptVerifiedTerminal(decision: ReadingEligibility, nowElapsedMillis: Long): ReadingEligibility {
        require(decision == ReadingEligibility.OUT_OF_RANGE || decision == ReadingEligibility.ENDED)
        if (!observeClock(nowElapsedMillis) || isTerminal()) return eligibility
        return terminate(decision)
    }

    fun eligibilityAt(nowElapsedMillis: Long): ReadingEligibility {
        if (!observeClock(nowElapsedMillis)) return eligibility
        return refresh(nowElapsedMillis)
    }

    /** Exact monotonic deadline at which text, images AND feedback must stop being exposed. */
    fun visibilityDeadlineAt(nowElapsedMillis: Long): Long? =
        if (eligibilityAt(nowElapsedMillis) == ReadingEligibility.IN_RANGE) visibilityDeadlineElapsedMillis else null

    fun end(nowElapsedMillis: Long): ReadingEligibility {
        if (!observeClock(nowElapsedMillis) || isTerminal()) return eligibility
        return terminate(ReadingEligibility.ENDED)
    }

    private fun refresh(now: Long): ReadingEligibility {
        if (isTerminal()) return eligibility
        if (!hasQualifiedEnvironment(now) || pending?.let { now >= it.deadlineElapsedMillis } == true) {
            invalidate()
        } else if (visibilityDeadlineElapsedMillis?.let { now >= it } == true) {
            // Hide at the old grant's deadline, independently of network callbacks. A later
            // qualified renewal has its OWN bounded request deadline and may still recover
            // this UNKNOWN session; never stretch the old grant while waiting for it.
            visibilityDeadlineElapsedMillis = null
            eligibility = ReadingEligibility.UNKNOWN
        }
        return eligibility
    }

    private fun hasQualifiedEnvironment(now: Long): Boolean =
        foreground && precisePermission && networkAvailable && movementStatus.allowsNearbyUse &&
            readerFix?.isQualifiedAt(now) == true

    private fun invalidate() {
        if (isTerminal()) return
        visibilityDeadlineElapsedMillis = null
        pending = null
        eligibility = ReadingEligibility.UNKNOWN
        if (generation == Long.MAX_VALUE) terminate(ReadingEligibility.ENDED) else generation++
    }

    private fun terminate(state: ReadingEligibility): ReadingEligibility {
        eligibility = state
        visibilityDeadlineElapsedMillis = null
        pending = null
        readerFix = null
        return eligibility
    }

    private fun isTerminal(): Boolean =
        eligibility == ReadingEligibility.ENDED || eligibility == ReadingEligibility.OUT_OF_RANGE

    private fun observeClock(now: Long): Boolean {
        if (now < 0L || lastNowElapsedMillis?.let { now < it } == true) {
            if (!isTerminal()) terminate(ReadingEligibility.ENDED)
            return false
        }
        lastNowElapsedMillis = now
        return true
    }

    private fun safeDeadline(start: Long, duration: Long): Long? =
        if (start <= Long.MAX_VALUE - duration) start + duration else null
}
