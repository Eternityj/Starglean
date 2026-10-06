package com.starglean.app.ui.components

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperApproachingHugMotionTest {
    @Test
    fun durationIncludesDistinctApproachEmbraceAndHold() {
        assertEquals(3600, PAPER_APPROACHING_HUG_DURATION_MS)
        val firstFourHundredMillis = paperApproachingHugFrame(400f / PAPER_APPROACHING_HUG_DURATION_MS)
        assertTrue(firstFourHundredMillis.alpha > .99f)
        assertTrue(firstFourHundredMillis.approach < .1f)
        assertEquals(0f, firstFourHundredMillis.embrace, 0f)
        val approachingContact = paperApproachingHugFrame(1300f / PAPER_APPROACHING_HUG_DURATION_MS)
        assertTrue(approachingContact.approach > .99f)
        assertEquals(1f, approachingContact.reach, 0f)
        assertTrue(approachingContact.embrace < .3f)
        val armsClosed = paperApproachingHugFrame(1900f / PAPER_APPROACHING_HUG_DURATION_MS)
        assertEquals(1f, armsClosed.approach, 0f)
        assertEquals(1f, armsClosed.embrace, 0f)
        assertTrue(armsClosed.squeeze > 0f)
    }

    @Test
    fun endpointsAreInvisibleAndInvalidInputIsASafeInvisibleStart() {
        for (p in listOf(-100f, -1f, 0f, 1f, 2f, 100f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, paperApproachingHugFrame(p).alpha, 0f)
        }
        for (p in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1f)) {
            assertEquals(paperApproachingHugFrame(0f), paperApproachingHugFrame(p))
        }
        assertEquals(paperApproachingHugFrame(1f), paperApproachingHugFrame(100f))
    }

    @Test
    fun reachingPrecedesWrappingAndSqueezing() {
        val reaching = paperApproachingHugFrame(.24f)
        assertTrue(reaching.approach > .4f)
        assertTrue(reaching.reach > .6f)
        assertEquals(0f, reaching.embrace, 0f)
        assertEquals(0f, reaching.squeeze, 0f)
        val wrapping = paperApproachingHugFrame(.43f)
        assertEquals(1f, wrapping.approach, 0f)
        assertEquals(1f, wrapping.reach, 0f)
        assertTrue(wrapping.embrace in .5f.. .8f)
        assertEquals(0f, wrapping.squeeze, 0f)
        val squeeze = paperApproachingHugFrame(.57f)
        assertEquals(1f, squeeze.embrace, 0f)
        assertTrue(squeeze.squeeze in .7f.. .9f)
    }

    @Test
    fun completeEmbraceHasAVisibleHoldBeforeTheSqueezeRelaxes() {
        for (p in listOf(.60f, .65f, .70f, .75f)) {
            val frame = paperApproachingHugFrame(p)
            assertEquals(1f, frame.alpha, 0f)
            assertEquals(1f, frame.approach, 0f)
            assertEquals(1f, frame.reach, 0f)
            assertEquals(1f, frame.embrace, 0f)
            assertEquals(1f, frame.squeeze, .0001f)
        }
        val relaxing = paperApproachingHugFrame(.84f)
        assertEquals(1f, relaxing.alpha, 0f)
        assertEquals(1f, relaxing.embrace, 0f)
        assertTrue(relaxing.squeeze in .2f.. .5f)
        assertEquals(0f, paperApproachingHugFrame(.91f).squeeze, 0f)
    }

    @Test
    fun figuresStayTogetherDuringFadeRatherThanSplittingApart() {
        var previousAlpha = 1f
        for (step in 86..100) {
            val frame = paperApproachingHugFrame(step / 100f)
            assertEquals(1f, frame.approach, 0f)
            assertEquals(1f, frame.reach, 0f)
            assertEquals(1f, frame.embrace, 0f)
            assertTrue(frame.alpha <= previousAlpha)
            previousAlpha = frame.alpha
        }
        assertEquals(0f, previousAlpha, 0f)
    }

    @Test
    fun millisecondSamplingIsFiniteBoundedAndContinuous() {
        var previous = paperApproachingHugFrame(0f)
        var sawLeftSway = false
        var sawRightSway = false
        for (millis in 1..PAPER_APPROACHING_HUG_DURATION_MS) {
            val frame = paperApproachingHugFrame(millis.toFloat() / PAPER_APPROACHING_HUG_DURATION_MS)
            frame.unitValues().forEach { assertTrue(it.isFinite() && it in 0f..1f) }
            assertTrue(frame.swayDegrees.isFinite() && abs(frame.swayDegrees) <= .7f)
            assertTrue(frame.settleY.isFinite() && frame.settleY in 0f..1.58f)
            assertTrue(frame.approach >= previous.approach)
            assertTrue(frame.reach >= previous.reach)
            assertTrue(frame.embrace >= previous.embrace)
            assertTrue(abs(frame.swayDegrees - previous.swayDegrees) < .01f)
            assertTrue(abs(frame.settleY - previous.settleY) < .02f)
            previous.unitValues().zip(frame.unitValues()).forEach { (before, after) ->
                assertTrue("Parameter jumped: $before -> $after", abs(after - before) < .01f)
            }
            if (frame.swayDegrees > .1f) sawRightSway = true
            if (frame.swayDegrees < -.1f) sawLeftSway = true
            previous = frame
        }
        assertTrue(sawLeftSway && sawRightSway)
        assertEquals(0f, previous.alpha, 0f)
        assertEquals(0f, previous.settleY, 0f)
    }

    private fun PaperApproachingHugFrame.unitValues() = listOf(alpha, approach, reach, embrace, squeeze)
}
