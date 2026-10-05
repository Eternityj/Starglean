package com.starglean.app.core.memory

import kotlin.math.cos
import kotlin.math.exp

enum class HeadingReliability { Reliable, NeedsCalibration, Unavailable, TooSteep }

data class MemoryHeadingReading(
    val magneticDegrees: Double?,
    val reliability: HeadingReliability,
    val sampledAtElapsedMillis: Long,
) {
    init {
        require(sampledAtElapsedMillis >= 0L) { "Invalid heading timestamp." }
        require(magneticDegrees == null || magneticDegrees.isFinite() && magneticDegrees >= 0.0 && magneticDegrees < 360.0) {
            "Invalid heading angle."
        }
        require((reliability == HeadingReliability.Reliable) == (magneticDegrees != null)) {
            "Only reliable readings may contain a heading."
        }
    }
}

/** Time-aware shortest-arc filter: crossing north never takes the long route through south. */
internal class MemoryHeadingFilter(
    private val timeConstantMillis: Double = 180.0,
    private val resetAfterMillis: Long = 1_500L,
) {
    private var degrees: Double? = null
    private var sampledAt: Long? = null

    init {
        require(timeConstantMillis.isFinite() && timeConstantMillis > 0.0)
        require(resetAfterMillis > 0L)
    }

    fun update(rawDegrees: Double, sampledAtElapsedMillis: Long): Double? {
        if (!rawDegrees.isFinite() || sampledAtElapsedMillis < 0L) {
            reset()
            return null
        }
        val value = MemoryCompassMath.normalizeDegrees(rawDegrees)
        val previous = degrees
        val previousTime = sampledAt
        if (previousTime != null && sampledAtElapsedMillis < previousTime) {
            reset()
            return null
        }
        val elapsed = if (previousTime == null) 0L else sampledAtElapsedMillis - previousTime
        val filtered = when {
            previous == null || previousTime == null || elapsed > resetAfterMillis -> value
            elapsed == 0L -> previous
            else -> MemoryCompassMath.normalizeDegrees(previous +
                (1.0 - exp(-elapsed / timeConstantMillis)) * MemoryCompassMath.signedDeltaDegrees(value, previous))
        }
        degrees = filtered
        sampledAt = sampledAtElapsedMillis
        return filtered
    }

    fun reset() {
        degrees = null
        sampledAt = null
    }
}

/** Pure quality gate. Android accuracy levels 0/1 mean unreliable/low; 2/3 are usable. */
internal fun memoryHeadingReliability(
    hasMagnetometer: Boolean,
    hasRecentSample: Boolean,
    sensorAccuracy: Int,
    magneticAccuracy: Int,
    magneticFieldMicrotesla: Double,
    screenNormalZ: Double,
    estimatedHeadingAccuracyRadians: Double? = null,
): HeadingReliability = when {
    !hasMagnetometer || !hasRecentSample -> HeadingReliability.Unavailable
    !screenNormalZ.isFinite() -> HeadingReliability.Unavailable
    screenNormalZ < cos(Math.toRadians(60.0)) -> HeadingReliability.TooSteep
    sensorAccuracy !in 2..3 || magneticAccuracy !in 2..3 -> HeadingReliability.NeedsCalibration
    !magneticFieldMicrotesla.isFinite() || magneticFieldMicrotesla !in 15.0..100.0 -> HeadingReliability.NeedsCalibration
    estimatedHeadingAccuracyRadians != null && (!estimatedHeadingAccuracyRadians.isFinite() ||
        estimatedHeadingAccuracyRadians < 0.0 || estimatedHeadingAccuracyRadians > Math.toRadians(25.0)) ->
        HeadingReliability.NeedsCalibration
    else -> HeadingReliability.Reliable
}
