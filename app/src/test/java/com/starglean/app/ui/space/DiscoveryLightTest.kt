package com.starglean.app.ui.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryLightTest {
    @Test fun theDistantStarDissolvesBeforeTheClosestFrames() {
        assertEquals(.82f, DiscoveryLight.sample(0f, 0f).coreAlpha, 0f)
        var previous = 1f
        for (step in 0..1000) {
            val alpha = DiscoveryLight.sample(step / 1000f, step / 1000f).coreAlpha
            assertTrue(alpha <= previous)
            previous = alpha
        }
        assertEquals(0f, DiscoveryLight.sample(.93f, .9f).coreAlpha, 0f)
        assertEquals(0f, DiscoveryLight.sample(1f, 1f).coreAlpha, 0f)
    }
    @Test fun nearMistAndExposureStayBelowTheOldBrightnessBudget() {
        for (step in 0..100) {
            val frame = DiscoveryLight.sample(step / 100f, step / 100f)
            assertEquals(2f / 3f, frame.mistMultiplier, 0f)
            assertTrue(frame.exposureAlpha in 0f.. .16f)
        }
    }
    @Test fun invalidValuesCannotCreateFlashes() {
        assertEquals(DiscoveryLight.sample(0f, 0f), DiscoveryLight.sample(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(DiscoveryLight.sample(0f, 1f), DiscoveryLight.sample(-5f, 8f))
    }
}
