package com.starglean.app.ui.components

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperEmpathyMotionTest {
    @Test
    fun endpointsAreInvisibleAndInvalidInputIsSafe() {
        for (p in listOf(-10f, 0f, 1f, 10f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, paperEmpathyFrame(p).alpha, 0f)
        }
        assertEquals(paperEmpathyFrame(0f), paperEmpathyFrame(-10f))
        assertEquals(paperEmpathyFrame(1f), paperEmpathyFrame(10f))
        assertEquals(paperEmpathyFrame(0f), paperEmpathyFrame(Float.NaN))
    }

    @Test
    fun twoBodiesApproachIndependentlyBeforeTheirQuietHold() {
        val start = paperEmpathyFrame(.12f)
        val approaching = paperEmpathyFrame(.3f)
        val contact = paperEmpathyFrame(.6f)
        assertTrue(start.rightX - start.leftX > 80f)
        assertTrue(approaching.leftX > start.leftX + 8f)
        assertTrue(approaching.rightX < start.rightX - 4f)
        assertTrue(contact.rightX - contact.leftX < 51f)
        assertTrue(contact.leftLeanDegrees > start.leftLeanDegrees + 10f)
        assertTrue(contact.rightLeanDegrees < start.rightLeanDegrees - 10f)
        // The left star starts first, rather than a rigid pair simply changing scale.
        val early = paperEmpathyFrame(.14f)
        assertTrue(early.leftX > 37f)
        assertEquals(123f, early.rightX, 0f)
    }

    @Test
    fun heldBodiesStayStillWhileTheAcknowledgementStrokeQuietlyDisappears() {
        val held = paperEmpathyFrame(.61f)
        val later = paperEmpathyFrame(.79f)
        assertEquals(held.leftX, later.leftX, 0f)
        assertEquals(held.leftY, later.leftY, .0001f)
        assertEquals(held.leftLeanDegrees, later.leftLeanDegrees, .0001f)
        assertEquals(held.rightX, later.rightX, 0f)
        assertEquals(held.rightY, later.rightY, .0001f)
        assertEquals(held.rightLeanDegrees, later.rightLeanDegrees, .0001f)
        assertEquals(1f, held.alpha, 0f)
        assertEquals(1f, later.alpha, 0f)
        assertTrue(held.resonanceAlpha > .9f)
        assertTrue(later.resonanceAlpha < .3f)
    }

    @Test
    fun allBodyBoundsAndMotionParametersAreFiniteWithoutSingleFrameJumps() {
        var previous = paperEmpathyFrame(0f)
        for (millis in 1..PAPER_REACTION_DURATION_MS) {
            val frame = paperEmpathyFrame(millis.toFloat() / PAPER_REACTION_DURATION_MS)
            frame.values().forEach { assertTrue(it.isFinite()) }
            assertTrue(frame.alpha in 0f..1f)
            assertTrue(frame.resonanceAlpha in 0f..1f)
            // A radius-31 disk conservatively contains every rotated 30-unit star,
            // the round outline and its highlight in the 160 x 120 canvas.
            for ((x, y) in listOf(frame.leftX to frame.leftY, frame.rightX to frame.rightY)) {
                assertTrue(x - 31f >= 0f && x + 31f <= 160f)
                assertTrue(y - 31f >= 0f && y + 31f <= 120f)
            }
            assertTrue(frame.leftX >= previous.leftX)
            assertTrue(frame.rightX <= previous.rightX)
            previous.values().zip(frame.values()).forEach { (before, after) ->
                assertTrue("Parameter jumped: $before -> $after", abs(after - before) < .08f)
            }
            previous = frame
        }
    }

    @Test
    fun fadeDoesNotPullTheStarsBackApart() {
        val beforeFade = paperEmpathyFrame(.83f)
        var previousAlpha = beforeFade.alpha
        for (step in 84..100) {
            val frame = paperEmpathyFrame(step / 100f)
            assertEquals(beforeFade.leftX, frame.leftX, 0f)
            assertEquals(beforeFade.rightX, frame.rightX, 0f)
            assertTrue(frame.alpha <= previousAlpha)
            previousAlpha = frame.alpha
        }
        assertEquals(0f, previousAlpha, 0f)
    }

    private fun PaperEmpathyFrame.values() = listOf(
        alpha, leftX, leftY, leftLeanDegrees, rightX, rightY, rightLeanDegrees, resonanceAlpha,
    )
}
