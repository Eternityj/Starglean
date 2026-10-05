package com.starglean.app.core.sensor

import kotlin.math.sqrt

/**
 * A shake is two strong pushes in opposite directions, 80–600 ms apart. The reversal
 * itself re-arms the detector: a wrist shake often turns through it without ever
 * slowing to near zero. One long push in a single direction never counts twice.
 */
internal class IntentionalShake {
    private var lastSample = -1L
    private var lastTrigger = -10_000L
    private var peakTime = -1L
    private var peak = FloatArray(3)

    fun add(timeMillis: Long, x: Float, y: Float, z: Float): Boolean {
        if (timeMillis < 0 || !x.isFinite() || !y.isFinite() || !z.isFinite()) return false
        if (lastSample >= 0 && timeMillis <= lastSample) return false
        if (lastSample >= 0 && timeMillis - lastSample > 350) peakTime = -1
        lastSample = timeMillis
        val magnitude = sqrt(x * x + y * y + z * z)
        if (magnitude < THRESHOLD || timeMillis - lastTrigger < 1_600) return false
        val direction = floatArrayOf(x / magnitude, y / magnitude, z / magnitude)
        val interval = timeMillis - peakTime
        val opposite = peakTime >= 0 &&
            direction[0] * peak[0] + direction[1] * peak[1] + direction[2] * peak[2] < -.2f
        when {
            opposite && interval in 80..600 -> {
                lastTrigger = timeMillis
                peakTime = -1
                return true
            }
            // Too soon after the first push: wait for the reversal to continue.
            opposite && interval < 80 -> Unit
            // No first push yet, or it is too old to pair with: start from this one.
            peakTime < 0 || interval > 600 -> {
                peakTime = timeMillis
                peak = direction
            }
        }
        return false
    }

    private companion object {
        const val THRESHOLD = 9f
    }
}
