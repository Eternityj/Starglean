package com.starglean.app.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackPreferencesPolicyTest {
    @Test fun `defaults preserve both system-authorized effects`() {
        assertEquals(EncounterFeedbackDecision(sound = true, haptic = true), applyFeedbackPreferences(
            EncounterFeedbackDecision(sound = true, haptic = true), FeedbackPreferenceState(), hapticHardwareAvailable = true))
    }

    @Test fun `sound and haptic choices are independent`() {
        val allowed = EncounterFeedbackDecision(sound = true, haptic = true)
        assertEquals(EncounterFeedbackDecision(sound = false, haptic = true), applyFeedbackPreferences(
            allowed, FeedbackPreferenceState(soundEnabled = false, hapticEnabled = true), hapticHardwareAvailable = true))
        assertEquals(EncounterFeedbackDecision(sound = true, haptic = false), applyFeedbackPreferences(
            allowed, FeedbackPreferenceState(soundEnabled = true, hapticEnabled = false), hapticHardwareAvailable = true))
    }

    @Test fun `preferences never override system suppression or missing hardware`() {
        val enabled = FeedbackPreferenceState(soundEnabled = true, hapticEnabled = true)
        assertEquals(EncounterFeedbackDecision(sound = false, haptic = true), applyFeedbackPreferences(
            EncounterFeedbackDecision(sound = false, haptic = true), enabled, hapticHardwareAvailable = true))
        assertEquals(EncounterFeedbackDecision(sound = true, haptic = false), applyFeedbackPreferences(
            EncounterFeedbackDecision(sound = true, haptic = true), enabled, hapticHardwareAvailable = false))
    }
}
