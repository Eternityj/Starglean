package com.starglean.app.core.feedback

import kotlin.math.PI
import kotlin.math.sin

/** Deterministic, dependency-free signal shared by production playback and unit tests. */
object EncounterFeedbackSignal {
    const val DURATION_MILLIS = 2_330
    const val SAMPLE_RATE_HZ = 24_000
    const val SAMPLE_COUNT = SAMPLE_RATE_HZ * DURATION_MILLIS / 1_000
    const val MAX_ABSOLUTE_SAMPLE = 6_900

    // Follows the arrival: a tap as the star appears, a build while it approaches, then a firm
    // pulse and echo as it lands (~1.7 s). The old 18-110 swell was too faint to feel in a hand.
    val amplitudeVibrationTimings = longArrayOf(0, 60, 540, 300, 300, 200, 130, 110, 120, 570)
    val amplitudeVibrationValues = intArrayOf(0, 160, 0, 60, 110, 0, 255, 0, 170, 0)

    /** On/off fallback for devices that cannot render amplitude envelopes: the same four beats. */
    val pulseVibrationTimings = longArrayOf(0, 70, 830, 90, 700, 160, 110, 120, 250)

    fun synthesizePcm16(): ShortArray {
        val samples = ShortArray(SAMPLE_COUNT)
        var noiseState = 0x51f15e5
        var filteredNoise = 0.0
        for (index in samples.indices) {
            val time = index.toDouble() / SAMPLE_RATE_HZ
            val progress = index.toDouble() / (samples.size - 1)
            val envelope = smoothstep((progress / .20).coerceIn(0.0, 1.0)) *
                smoothstep(((1.0 - progress) / .28).coerceIn(0.0, 1.0))

            noiseState = noiseState * 1_664_525 + 1_013_904_223
            val noise = ((noiseState ushr 8) and 0xffff) / 32_767.5 - 1.0
            filteredNoise += .025 * (noise - filteredNoise)
            val wind = filteredNoise * (0.22 + 0.18 * sin(2.0 * PI * .43 * time))

            val bellOne = bell(time, start = .32, frequency = 784.0, decay = 2.8)
            val bellTwo = bell(time, start = .77, frequency = 1_174.66, decay = 3.4)
            val bellThree = bell(time, start = 1.18, frequency = 1_568.0, decay = 4.1)
            val shimmer = .13 * sin(2.0 * PI * (510.0 + 75.0 * progress) * time)
            val mixed = envelope * (.30 * wind + .23 * bellOne + .18 * bellTwo + .12 * bellThree + shimmer)
            samples[index] = (mixed * MAX_ABSOLUTE_SAMPLE).toInt()
                .coerceIn(-MAX_ABSOLUTE_SAMPLE, MAX_ABSOLUTE_SAMPLE).toShort()
        }
        return samples
    }

    private fun bell(time: Double, start: Double, frequency: Double, decay: Double): Double {
        val local = time - start
        if (local <= 0.0) return 0.0
        val attack = smoothstep((local / .045).coerceIn(0.0, 1.0))
        val fade = kotlin.math.exp(-decay * local)
        return attack * fade * (
            sin(2.0 * PI * frequency * local) +
                .28 * sin(2.0 * PI * frequency * 2.01 * local) +
                .10 * sin(2.0 * PI * frequency * 3.98 * local)
            )
    }

    private fun smoothstep(value: Double): Double = value * value * (3.0 - 2.0 * value)
}

data class EncounterFeedbackEnvironment(
    val sharedFeedbackAllowed: Boolean,
    val mediaVolumeAudible: Boolean,
    val otherMusicActive: Boolean,
    val soundModeAllowed: Boolean = true,
)

data class EncounterFeedbackDecision(val sound: Boolean, val haptic: Boolean)

internal fun competingMusicActive(reportedMusicActive: Boolean, ownsPlayingTrack: Boolean): Boolean =
    reportedMusicActive && !ownsPlayingTrack

fun decideEncounterFeedback(environment: EncounterFeedbackEnvironment): EncounterFeedbackDecision =
    EncounterFeedbackDecision(
        sound = environment.sharedFeedbackAllowed && environment.soundModeAllowed &&
            environment.mediaVolumeAudible && !environment.otherMusicActive,
        // The app's own vibration switch decides haptics, not the system touch-feedback setting.
        haptic = environment.sharedFeedbackAllowed,
    )
