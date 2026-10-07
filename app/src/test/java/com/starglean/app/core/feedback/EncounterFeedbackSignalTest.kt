package com.starglean.app.core.feedback

import com.starglean.app.ui.space.DiscoveryArrival
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncounterFeedbackSignalTest {
    @Test fun `signal duration remains synchronized with discovery arrival`() {
        assertEquals(DiscoveryArrival.TOTAL_MILLIS, EncounterFeedbackSignal.DURATION_MILLIS)
        assertEquals(55_920, EncounterFeedbackSignal.SAMPLE_COUNT)
        assertEquals(EncounterFeedbackSignal.DURATION_MILLIS,
            EncounterFeedbackSignal.amplitudeVibrationTimings.sum().toInt())
        assertEquals(EncounterFeedbackSignal.DURATION_MILLIS,
            EncounterFeedbackSignal.pulseVibrationTimings.sum().toInt())
    }

    @Test fun `synthesis is deterministic bounded and softly enveloped`() {
        val first = EncounterFeedbackSignal.synthesizePcm16()
        val second = EncounterFeedbackSignal.synthesizePcm16()

        assertArrayEquals(first, second)
        assertEquals(EncounterFeedbackSignal.SAMPLE_COUNT, first.size)
        assertTrue(first.maxOf { abs(it.toInt()) } <= EncounterFeedbackSignal.MAX_ABSOLUTE_SAMPLE)
        assertEquals(0, first.first().toInt())
        assertEquals(0, first.last().toInt())
        val edgeSamples = EncounterFeedbackSignal.SAMPLE_RATE_HZ / 20
        val opening = rms(first, 0, edgeSamples)
        val middle = rms(first, first.size / 2, edgeSamples)
        val ending = rms(first, first.size - edgeSamples, edgeSamples)
        assertTrue("opening should fade in", opening < middle * .45)
        assertTrue("ending should fade out", ending < middle * .45)
        assertTrue("signal should remain audible", middle > 120.0)
    }

    @Test fun `policy never bypasses shared silence and separates media from haptics`() {
        val denied = decideEncounterFeedback(EncounterFeedbackEnvironment(false, true, false))
        assertFalse(denied.sound)
        assertFalse(denied.haptic)

        val mutedMedia = decideEncounterFeedback(EncounterFeedbackEnvironment(true, false, false))
        assertFalse(mutedMedia.sound)
        assertTrue(mutedMedia.haptic)

        val musicPlaying = decideEncounterFeedback(EncounterFeedbackEnvironment(true, true, true))
        assertFalse(musicPlaying.sound)
        assertTrue(musicPlaying.haptic)

        val vibrateMode = decideEncounterFeedback(EncounterFeedbackEnvironment(
            sharedFeedbackAllowed = true,
            mediaVolumeAudible = true,
            otherMusicActive = false,
            soundModeAllowed = false,
        ))
        assertFalse(vibrateMode.sound)
        assertTrue(vibrateMode.haptic)
    }

    @Test fun `vibration starts at once and has a beat strong enough to feel`() {
        assertTrue(EncounterFeedbackSignal.amplitudeVibrationValues[1] >= 150)
        assertEquals(0L, EncounterFeedbackSignal.amplitudeVibrationTimings[0])
        assertEquals(255, EncounterFeedbackSignal.amplitudeVibrationValues.max())
        assertEquals(EncounterFeedbackSignal.amplitudeVibrationTimings.size, EncounterFeedbackSignal.amplitudeVibrationValues.size)
        assertEquals(0L, EncounterFeedbackSignal.pulseVibrationTimings[0])
    }

    @Test fun `owned playing track is not mistaken for competing music`() {
        assertTrue(competingMusicActive(reportedMusicActive = true, ownsPlayingTrack = false))
        assertFalse(competingMusicActive(reportedMusicActive = true, ownsPlayingTrack = true))
        assertFalse(competingMusicActive(reportedMusicActive = false, ownsPlayingTrack = false))
    }

    private fun rms(samples: ShortArray, start: Int, count: Int): Double {
        var sum = 0.0
        for (index in start until start + count) {
            val value = samples[index].toDouble()
            sum += value * value
        }
        return sqrt(sum / count)
    }
}
