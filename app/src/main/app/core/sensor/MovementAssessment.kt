package com.starglean.app.core.sensor

import kotlin.math.*

/** Transient input; never serialized, logged, or attached to an entry. */
internal data class MovementSample(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val atMillis: Long) {
    override fun toString() = "MovementSample(location=redacted)"
}

internal enum class MovementAssessment { Collecting, SlowOrStill, FastMoving, Uncertain }

/** Conservative foreground displacement check, not a walking/driving classifier. */
internal class MovementWindow {
    private val samples = ArrayDeque<MovementSample>()
    var lastAddWasEquivalentDuplicate: Boolean = false
        private set

    fun add(sample: MovementSample, now: Long): MovementAssessment {
        lastAddWasEquivalentDuplicate = false
        if (!sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 || !sample.longitude.isFinite() ||
            sample.longitude !in -180.0..180.0 || !sample.accuracyMeters.isFinite() || sample.accuracyMeters !in .01f..50f ||
            sample.atMillis <= 0 || now - sample.atMillis !in 0..10_000) return MovementAssessment.Uncertain
        val previous = samples.lastOrNull()
        if (previous != null && sample.atMillis == previous.atMillis) {
            if (sample.latitude == previous.latitude && sample.longitude == previous.longitude &&
                sample.accuracyMeters == previous.accuracyMeters) {
                // Multiple Android providers may emit the same original fix. It is neither new
                // evidence nor a reason to discard the independent evidence already collected.
                lastAddWasEquivalentDuplicate = true
                return MovementAssessment.Collecting
            }
            return MovementAssessment.Uncertain
        }
        if (previous != null && sample.atMillis < previous.atMillis) return MovementAssessment.Uncertain
        if (previous != null && sample.atMillis - previous.atMillis > MAX_SAMPLE_GAP_MILLIS) {
            samples.clear()
            samples.addLast(sample)
            return MovementAssessment.Uncertain
        }
        samples.addLast(sample)
        while (samples.first().atMillis < sample.atMillis - WINDOW_MILLIS || samples.size > MAX_SAMPLES) {
            samples.removeFirst()
        }

        // A single fresh displacement whose conservative lower bound exceeds 5 m/s is enough to
        // close the gate. This is deliberately only a displacement check, not a vehicle claim.
        if (previous != null) {
            val seconds = (sample.atMillis - previous.atMillis) / 1_000.0
            val distance = distanceMeters(previous, sample)
            val uncertainty = previous.accuracyMeters + sample.accuracyMeters
            if ((distance - uncertainty).coerceAtLeast(0.0) / seconds > FAST_METERS_PER_SECOND) {
                return MovementAssessment.FastMoving
            }
            // A large jump hidden by broad accuracy is ambiguous, rather than evidence of rest.
            if (distance > uncertainty * 1.5 + 15.0) return MovementAssessment.Uncertain
        }

        // Reading and publishing now start with the first fix, so catch car speed early: any anchor
        // at least 2 s back whose conservative lower bound exceeds 5 m/s closes the gate.
        if (samples.dropLast(1).any { anchor ->
                val spanMillis = sample.atMillis - anchor.atMillis
                spanMillis >= FAST_MIN_BASELINE_MILLIS &&
                    (distanceMeters(anchor, sample) - anchor.accuracyMeters - sample.accuracyMeters)
                        .coerceAtLeast(0.0) / (spanMillis / 1_000.0) > FAST_METERS_PER_SECOND
            }) return MovementAssessment.FastMoving

        val baselines = samples.dropLast(1).mapNotNull { anchor ->
            val spanMillis = sample.atMillis - anchor.atMillis
            if (spanMillis < MIN_BASELINE_MILLIS) null else {
                val seconds = spanMillis / 1_000.0
                val distance = distanceMeters(anchor, sample)
                val uncertainty = anchor.accuracyMeters + sample.accuracyMeters
                Baseline(spanMillis, (distance - uncertainty).coerceAtLeast(0.0) / seconds,
                    (distance + uncertainty) / seconds)
            }
        }
        if (baselines.isEmpty()) return MovementAssessment.Collecting
        if (baselines.any { it.lowerMetersPerSecond > FAST_METERS_PER_SECOND }) return MovementAssessment.FastMoving
        // Long baselines let ordinary 5--15 m fixes establish bounded slow/still evidence without
        // pretending that their accuracy radius was actual travelled distance on every short fix.
        // A shorter eligible baseline can still be dominated by ordinary accuracy uncertainty;
        // keep collecting until the longest bounded baseline can resolve it.
        if (baselines.maxBy { it.spanMillis }.upperMetersPerSecond <= SLOW_UPPER_BOUND_METERS_PER_SECOND) {
            return MovementAssessment.SlowOrStill
        }
        return MovementAssessment.Collecting
    }

    private fun distanceMeters(a: MovementSample, b: MovementSample): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val hav = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(hav.coerceIn(0.0, 1.0)))
    }

    private data class Baseline(
        val spanMillis: Long,
        val lowerMetersPerSecond: Double,
        val upperMetersPerSecond: Double,
    )

    private companion object {
        const val MIN_BASELINE_MILLIS = 10_000L
        const val MAX_SAMPLE_GAP_MILLIS = 8_000L
        const val WINDOW_MILLIS = 24_000L
        const val MAX_SAMPLES = 256
        const val FAST_METERS_PER_SECOND = 5.0
        const val SLOW_UPPER_BOUND_METERS_PER_SECOND = 3.0
        const val FAST_MIN_BASELINE_MILLIS = 2_000L
    }
}
