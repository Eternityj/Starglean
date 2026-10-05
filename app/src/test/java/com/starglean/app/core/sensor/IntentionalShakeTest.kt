package com.starglean.app.core.sensor

import org.junit.Assert.*
import org.junit.Test

class IntentionalShakeTest {
    @Test fun oppositePeaksWithAValleyTriggerOnce() {
        val detector = IntentionalShake()
        assertFalse(detector.add(1_000, 14f, 0f, 0f))
        assertFalse(detector.add(1_060, 1f, 0f, 0f))
        assertTrue(detector.add(1_140, -14f, 0f, 0f))
        assertFalse(detector.add(1_180, 0f, 0f, 0f))
        assertFalse(detector.add(1_260, 14f, 0f, 0f))
    }
    @Test fun sustainedAccelerationIsNotMultiplePeaks() {
        val detector = IntentionalShake()
        repeat(30) { assertFalse(detector.add(1_000L + it * 20L, 15f, 0f, 0f)) }
    }
    @Test fun sameDirectionPeaksDoNotTrigger() {
        val detector = IntentionalShake()
        detector.add(1_000, 14f, 0f, 0f)
        detector.add(1_060, 0f, 0f, 0f)
        assertFalse(detector.add(1_140, 14f, 0f, 0f))
    }
    @Test fun walkingScaleMotionDoesNotTrigger() {
        val detector = IntentionalShake()
        repeat(120) { assertFalse(detector.add(1_000L + it * 20L, if (it % 2 == 0) 3f else -3f, 1f, 0f)) }
    }
    @Test fun gapsResetOldPeaks() {
        val detector = IntentionalShake()
        detector.add(1_000, 14f, 0f, 0f)
        detector.add(1_060, 0f, 0f, 0f)
        assertFalse(detector.add(1_500, -14f, 0f, 0f))
    }
    @Test fun noiseAndBackwardsTimestampsDoNotCount() {
        val detector = IntentionalShake()
        assertFalse(detector.add(1_000, Float.NaN, 0f, 0f))
        detector.add(1_000, 14f, 0f, 0f)
        assertFalse(detector.add(900, -14f, 0f, 0f))
        assertFalse(detector.add(1_000, -14f, 0f, 0f))
    }
    @Test fun rotatingShakeWithoutAQuietValleyTriggers() {
        // A wrist shake often turns through the reversal instead of pausing near zero.
        val detector = IntentionalShake()
        assertFalse(detector.add(1_000, 12f, 0f, 0f))
        assertFalse(detector.add(1_020, 8f, 8f, 0f))
        assertFalse(detector.add(1_040, 0f, 10f, 0f))
        assertFalse(detector.add(1_060, -8f, 6f, 0f))
        assertTrue(detector.add(1_080, -12f, 0f, 0f))
    }
    @Test fun moderateBackAndForthShakeTriggers() {
        val detector = IntentionalShake()
        assertFalse(detector.add(1_000, 9.5f, 0f, 0f))
        assertFalse(detector.add(1_060, .5f, 0f, 0f))
        assertTrue(detector.add(1_140, -9.5f, 0f, 0f))
    }
    @Test fun walkingBounceBelowTheThresholdDoesNotTrigger() {
        val detector = IntentionalShake()
        repeat(120) { assertFalse(detector.add(1_000L + it * 20L, if (it % 4 < 2) 8f else -8f, 1f, 0f)) }
    }
    @Test fun slowReturnLongAfterAPushDoesNotTrigger() {
        val detector = IntentionalShake()
        repeat(40) { detector.add(1_000L + it * 20L, if (it == 0) 14f else 1f, 0f, 0f) }
        assertFalse(detector.add(1_800, -14f, 0f, 0f))
    }
    @Test fun secondGestureWorksAfterCooldown() {
        val detector = IntentionalShake()
        detector.add(1_000, 14f, 0f, 0f); detector.add(1_060, 0f, 0f, 0f)
        assertTrue(detector.add(1_140, -14f, 0f, 0f))
        detector.add(3_000, 14f, 0f, 0f); detector.add(3_060, 0f, 0f, 0f)
        assertTrue(detector.add(3_140, -14f, 0f, 0f))
    }
}
