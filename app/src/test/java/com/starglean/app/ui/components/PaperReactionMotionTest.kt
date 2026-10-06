package com.starglean.app.ui.components

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperReactionMotionTest {
    @Test
    fun allPaperIllustrationsUseTheirFullPlaybackDuration() {
        assertEquals(3000, PAPER_REACTION_DURATION_MS)
        assertEquals(PAPER_APPROACHING_HUG_DURATION_MS, DiaryReaction.Hug.durationMillis)
        assertEquals(PAPER_REACTION_DURATION_MS, DiaryReaction.Joy.durationMillis)
        assertEquals(PAPER_REACTION_DURATION_MS, DiaryReaction.Understand.durationMillis)
        assertEquals(PAPER_REACTION_DURATION_MS, DiaryReaction.Thanks.durationMillis)
    }

    @Test
    fun flowerLeavesNoMarkBeforeStartingOrAfterFinishing() {
        for (progress in listOf(-100f, -1f, 0f, 1f, 2f, 100f)) {
            assertEquals(0f, paperFlowerFrame(progress).alpha, 0f)
        }
        assertTrue(paperFlowerFrame(.5f).alpha > .95f)
    }

    @Test
    fun invalidProgressIsAnInvisibleSafeStartAndFiniteProgressIsClamped() {
        for (progress in listOf(Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)) {
            assertEquals(0f, paperReactionProgress(progress), 0f)
            assertEquals(paperFlowerFrame(0f), paperFlowerFrame(progress))
            for (petal in 0..5) {
                assertEquals(0f, paperFlowerPetalOpening(progress, petal), 0f)
            }
        }
        assertEquals(paperFlowerFrame(0f), paperFlowerFrame(-100f))
        assertEquals(paperFlowerFrame(1f), paperFlowerFrame(100f))
    }

    @Test
    fun flowerLandsBeforeBloomingAndTheBudGivesWayToThePetals() {
        val arriving = paperFlowerFrame(.1f)
        val landing = paperFlowerFrame(.2f)
        val landed = paperFlowerFrame(.29f)
        val opening = paperFlowerFrame(.46f)
        val blooming = paperFlowerFrame(.68f)

        assertTrue(arriving.landing > 0f)
        assertTrue(landing.landing > arriving.landing)
        assertEquals(1f, landed.landing, 0f)
        assertEquals(0f, arriving.stemGrowth, 0f)
        assertTrue(landed.stemGrowth > 0f)
        assertTrue(opening.stemGrowth > .95f)
        assertEquals(1f, landed.budAlpha, 0f)
        assertTrue(opening.budAlpha in .1f.. .9f)
        assertEquals(0f, blooming.budAlpha, 0f)
        for (petal in 0..5) {
            assertEquals(0f, paperFlowerPetalOpening(.28f, petal), 0f)
            assertEquals(1f, paperFlowerPetalOpening(.68f, petal), 0f)
        }
    }

    @Test
    fun allSixPetalsOpenAtDifferentTimesAndNeverFoldBackDuringTheBloom() {
        val duringBloom = (0..5).map { paperFlowerPetalOpening(.47f, it) }
        assertTrue(duringBloom.first() > .9f)
        assertTrue(duringBloom.last() < .1f)
        duringBloom.zipWithNext().forEach { (earlier, later) ->
            assertTrue("Adjacent petals should not share one opening phase", earlier > later + .03f)
        }
        for (petal in 0..5) {
            var previous = 0f
            for (step in 0..1000) {
                val opening = paperFlowerPetalOpening(step / 1000f, petal)
                assertTrue(opening in 0f..1f)
                assertTrue(opening >= previous)
                previous = opening
            }
        }
        // Defensive indexing should select a real petal, not change the animation envelope.
        assertEquals(paperFlowerPetalOpening(.47f, 0), paperFlowerPetalOpening(.47f, -1), 0f)
        assertEquals(paperFlowerPetalOpening(.47f, 5), paperFlowerPetalOpening(.47f, 6), 0f)
    }

    @Test
    fun fullBloomSwaysInBothDirectionsThenLiftsAndFadesQuietly() {
        val visibleBloom = (65..88).map { paperFlowerFrame(it / 100f) }
        assertTrue(visibleBloom.any { it.swayDegrees > .5f && it.alpha > .5f })
        assertTrue(visibleBloom.any { it.swayDegrees < -.5f && it.alpha > .5f })
        assertEquals(0f, paperFlowerFrame(.5f).swayDegrees, 0f)

        var previous = paperFlowerFrame(.84f)
        for (step in 85..100) {
            val frame = paperFlowerFrame(step / 100f)
            assertTrue(frame.alpha <= previous.alpha)
            assertTrue(frame.lift >= previous.lift)
            previous = frame
        }
        assertEquals(0f, previous.alpha, 0f)
        assertTrue(previous.lift > 0f && previous.lift <= 3f)
    }

    @Test
    fun millisecondSamplingHasFiniteBoundedValuesAndNoVisibleParameterJumps() {
        var previousFlower = paperFlowerFrame(0f)
        var previousPetals = (0..5).map { paperFlowerPetalOpening(0f, it) }
        for (millis in 1..PAPER_REACTION_DURATION_MS) {
            val progress = millis.toFloat() / PAPER_REACTION_DURATION_MS
            val flower = paperFlowerFrame(progress)
            val petals = (0..5).map { paperFlowerPetalOpening(progress, it) }

            flower.unitValues().forEach(::assertUnitValue)
            petals.forEach(::assertUnitValue)
            assertTrue(flower.swayDegrees.isFinite() && abs(flower.swayDegrees) <= 4f)
            assertTrue(flower.lift.isFinite() && flower.lift in 0f..3f)

            // Generous perceptual envelopes, not exact easing coefficients or keyframe times.
            assertSmallSteps(previousFlower.unitValues(), flower.unitValues(), .02f)
            assertSmallSteps(previousPetals, petals, .02f)
            assertTrue(abs(flower.swayDegrees - previousFlower.swayDegrees) < .05f)
            assertTrue(abs(flower.lift - previousFlower.lift) < .02f)

            previousFlower = flower
            previousPetals = petals
        }
    }

    private fun PaperFlowerFrame.unitValues() = listOf(alpha, landing, stemGrowth, budAlpha)

    private fun assertUnitValue(value: Float) {
        assertTrue("Expected a finite value in 0..1, got $value", value.isFinite() && value in 0f..1f)
    }

    private fun assertSmallSteps(previous: List<Float>, current: List<Float>, maximumStep: Float) {
        previous.zip(current).forEach { (before, after) ->
            assertTrue("Unexpected parameter jump: $before -> $after", abs(after - before) < maximumStep)
        }
    }
}
