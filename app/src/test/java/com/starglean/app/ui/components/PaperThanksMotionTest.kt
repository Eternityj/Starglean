package com.starglean.app.ui.components

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperThanksMotionTest {
    @Test
    fun emptyEndpointsAndInvalidProgressCannotLeavePaperOnTheDiary() {
        for (p in listOf(-2f, 0f, 1f, 2f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, paperThanksFrame(p).alpha, 0f)
        }
        for (p in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(paperThanksFrame(0f), paperThanksFrame(p))
            assertEquals(0f, paperThanksInkReveal(p, 0), 0f)
        }
    }

    @Test
    fun actualFlapTurnsFromClosedThroughEdgeOnToOpenWithoutScalingTheWholeSheet() {
        val closed = paperThanksFrame(.1f)
        val opening = paperThanksFrame(.23f)
        val unfolded = paperThanksFrame(.4f)
        assertTrue(closed.flapScaleY < -.8f)
        assertTrue(abs(opening.flapScaleY) < .15f)
        assertEquals(1f, unfolded.flapScaleY, .00001f)
        assertEquals(0f, unfolded.noteOffsetY, 0f)
        assertTrue(unfolded.alpha > .99f)
        assertEquals(0f, paperThanksInkReveal(.3f, 0), 0f)
    }

    @Test
    fun threeInkStrokesRevealInOrderAfterThePageStartsOpening() {
        val ink = (0..2).map { paperThanksInkReveal(.46f, it) }
        assertTrue(ink[0] > ink[1] && ink[1] > ink[2])
        for (line in 0..2) {
            var previous = 0f
            for (step in 0..1000) {
                val current = paperThanksInkReveal(step / 1000f, line)
                assertTrue(current in 0f..1f && current >= previous)
                previous = current
            }
            assertEquals(1f, paperThanksInkReveal(.62f, line), 0f)
        }
        assertEquals(paperThanksInkReveal(.46f, 0), paperThanksInkReveal(.46f, -1), 0f)
        assertEquals(paperThanksInkReveal(.46f, 2), paperThanksInkReveal(.46f, 5), 0f)
    }

    @Test
    fun starTravelsIndependentlyThenSettlesAndHoldsInsteadOfPulsingASticker() {
        val start = paperThanksFrame(.45f)
        val falling = paperThanksFrame(.55f)
        val landed = paperThanksFrame(.66f)
        val held = paperThanksFrame(.82f)
        assertTrue(start.starY < falling.starY && falling.starY < landed.starY)
        assertTrue(start.starY + 25f < landed.starY)
        assertTrue(start.starX > falling.starX && falling.starX > landed.starX)
        assertTrue(abs(start.starRotation) > 15f)
        assertEquals(landed.starX, held.starX, 0f)
        assertEquals(landed.starY, held.starY, .00001f)
        assertEquals(0f, held.starRotation, .00001f)
        assertEquals(1f, held.alpha, 0f)
        assertEquals(1f, held.starAlpha, 0f)
    }

    @Test
    fun allMotionParametersRemainFiniteBoundedAndContinuous() {
        var previous = paperThanksFrame(0f)
        for (millis in 1..3000) {
            val frame = paperThanksFrame(millis / 3000f)
            listOf(frame.alpha, frame.unfolding, frame.starAlpha).forEach {
                assertTrue(it.isFinite() && it in 0f..1f)
            }
            assertTrue(frame.flapScaleY.isFinite() && frame.flapScaleY in -.84f..1.00001f)
            assertTrue(frame.noteOffsetY in -8f..0f)
            assertTrue(frame.noteRotation in -7f..-3f)
            assertTrue(frame.starX in 106f..115f)
            assertTrue(frame.starY in 16f..45f)
            assertTrue(frame.starRotation in -21f..3f)
            assertTrue(abs(frame.flapScaleY - previous.flapScaleY) < .01f)
            assertTrue(abs(frame.starY - previous.starY) < .08f)
            assertTrue(abs(frame.starX - previous.starX) < .03f)
            assertTrue(abs(frame.alpha - previous.alpha) < .01f)
            previous = frame
        }
    }
}
