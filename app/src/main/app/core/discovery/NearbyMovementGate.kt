package com.starglean.app.core.discovery

import com.starglean.app.core.sensor.MovementAssessment
import com.starglean.app.core.sensor.MovementSample
import com.starglean.app.core.sensor.MovementWindow

/** Local safety evidence only; these values are not activity or vehicle classifications. */
internal enum class NearbyMovementStatus { Collecting, Settled, FastMovement, Uncertain }

/**
 * Reading and publishing start with the first precise fix and stop for fast or doubtful
 * movement. The movement check keeps running on every new fix while the page is open.
 */
internal val NearbyMovementStatus.allowsNearbyUse: Boolean
    get() = this == NearbyMovementStatus.Collecting || this == NearbyMovementStatus.Settled

/**
 * Dispatcher-confined, in-memory movement gate fed with each original foreground location sample.
 * It stores only a short bounded window and never persists a route. Fast or ambiguous evidence
 * blocks immediately; two fresh settled assessments are required to recover, preventing a noisy
 * boundary from repeatedly exposing and masking content.
 */
internal class NearbyMovementGate {
    private var window = MovementWindow()
    private var status = NearbyMovementStatus.Collecting
    private var settledConfirmations = 0
    private var lastNowNanos: Long? = null
    private var lastEvidenceNanos: Long? = null
    private var clockInvalid = false

    /** Feed the exact fix emitted by [NearbyReaderLocationMonitor], never a reconstructed cache. */
    fun update(fix: NearbyReaderLocationFix, nowElapsedNanos: Long): NearbyMovementStatus = update(
        NearbyReaderLocationSample(
            fix.latitude, fix.longitude, fix.quality.horizontalAccuracyMeters,
            fix.sampledAtElapsedNanos, fix.quality.isMock,
        ),
        nowElapsedNanos,
    )

    /** Any non-ready monitor update immediately removes settled evidence. */
    fun unavailable(nowElapsedNanos: Long): NearbyMovementStatus {
        if (!observeClock(nowElapsedNanos)) return status
        return uncertain(resetWindow = true)
    }

    fun update(sample: NearbyReaderLocationSample, nowElapsedNanos: Long): NearbyMovementStatus {
        if (!observeClock(nowElapsedNanos)) return status
        val accuracy = sample.accuracyMeters
        if (sample.sampledAtElapsedNanos < 0L || sample.sampledAtElapsedNanos > nowElapsedNanos ||
            nowElapsedNanos - sample.sampledAtElapsedNanos >= MAX_EVIDENCE_AGE_NANOS ||
            !sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
            !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0 ||
            accuracy == null || !accuracy.isFinite() || accuracy <= 0.0 ||
            accuracy > NearbyReadingPolicy.MAX_HORIZONTAL_ACCURACY_METERS || sample.isMock
        ) return uncertain(resetWindow = true)

        val sampledAtMillis = sample.sampledAtElapsedNanos / NANOS_PER_MILLI
        val nowMillis = nowElapsedNanos / NANOS_PER_MILLI
        val assessment = window.add(
            MovementSample(sample.latitude, sample.longitude, accuracy.toFloat(), sampledAtMillis),
            nowMillis,
        )
        // An exact duplicate is often the same platform fix fanned out by several providers.
        // It must not refresh freshness, add a settled confirmation, or erase prior evidence.
        if (window.lastAddWasEquivalentDuplicate) return status
        lastEvidenceNanos = sample.sampledAtElapsedNanos
        status = when (assessment) {
            MovementAssessment.FastMoving -> {
                settledConfirmations = 0
                NearbyMovementStatus.FastMovement
            }
            MovementAssessment.SlowOrStill -> {
                settledConfirmations = (settledConfirmations + 1).coerceAtMost(SETTLED_CONFIRMATIONS)
                if (settledConfirmations >= SETTLED_CONFIRMATIONS) NearbyMovementStatus.Settled
                else NearbyMovementStatus.Collecting
            }
            MovementAssessment.Collecting -> {
                settledConfirmations = 0
                NearbyMovementStatus.Collecting
            }
            MovementAssessment.Uncertain -> return uncertain(resetWindow = true)
        }
        return status
    }

    fun statusAt(nowElapsedNanos: Long): NearbyMovementStatus {
        if (!observeClock(nowElapsedNanos)) return status
        val evidence = lastEvidenceNanos
        if (evidence == null || nowElapsedNanos < evidence ||
            nowElapsedNanos - evidence >= MAX_EVIDENCE_AGE_NANOS
        ) return uncertain(resetWindow = true)
        return status
    }

    fun reset(): NearbyMovementStatus {
        window = MovementWindow()
        settledConfirmations = 0
        lastEvidenceNanos = null
        status = if (clockInvalid) NearbyMovementStatus.Uncertain else NearbyMovementStatus.Collecting
        return status
    }

    private fun observeClock(now: Long): Boolean {
        if (clockInvalid || now < 0L || lastNowNanos?.let { now < it } == true) {
            clockInvalid = true
            uncertain(resetWindow = true)
            return false
        }
        lastNowNanos = now
        return true
    }

    private fun uncertain(resetWindow: Boolean): NearbyMovementStatus {
        if (resetWindow) window = MovementWindow()
        settledConfirmations = 0
        lastEvidenceNanos = null
        status = NearbyMovementStatus.Uncertain
        return status
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_EVIDENCE_AGE_NANOS = NearbyReadingPolicy.MAX_FIX_AGE_MILLIS * NANOS_PER_MILLI
        const val SETTLED_CONFIRMATIONS = 2
    }
}
