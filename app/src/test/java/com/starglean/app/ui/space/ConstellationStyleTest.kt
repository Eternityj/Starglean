package com.starglean.app.ui.space

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstellationStyleTest {
    @Test
    fun eachExistingFigureHasExactlyOneAnchorAndOneOrTwoSecondaryStars() {
        assertEquals(listOf(6, 9, 5, 5, 7), CelestialGeometry.constellations.map { it.points.size })
        CelestialGeometry.constellations.forEachIndexed { figureIndex, figure ->
            val styles = figure.points.indices.map { ConstellationStyle.star(figureIndex, it) }
            styles.forEach { star ->
                assertTrue(star.intensity.isFinite() && star.intensity in .48f..1.35f)
                assertTrue(star.scale.isFinite() && star.scale in .65f..1.4f)
            }
            assertEquals("One visual anchor in ${figure.name}", 1, styles.count { it.intensity > 1.1f })
            assertTrue(styles.count { it.intensity in .8f..1.1f } in 1..2)
            // Missing/trailing geometry points must never accidentally become anchors.
            assertTrue(ConstellationStyle.star(figureIndex, figure.points.size).intensity < .8f)
        }
        assertTrue(ConstellationStyle.star(-1, 0).intensity < .8f)
        assertTrue(ConstellationStyle.star(5, 0).intensity < .8f)
        assertTrue(ConstellationStyle.star(0, -1).intensity < .8f)
    }

    @Test
    fun recedingFiguresFadeMonotonicallyWithoutLosingTheirStars() {
        for (facingStep in 0..20) {
            val facing = facingStep / 20f
            var previous = ConstellationStyle.visibility(4f, facing)
            for (step in 1..200) {
                val current = ConstellationStyle.visibility(4f + step * .025f, facing)
                assertTrue(current.stars <= previous.stars + EPSILON)
                assertTrue(current.lines <= previous.lines + EPSILON)
                assertInRange(current)
                previous = current
            }
        }
    }

    @Test
    fun sidewaysLinesRecedeMoreStronglyThanTheirStarPoints() {
        for (distanceStep in 0..20) {
            val distance = 4.6f + distanceStep * .18f
            var previous = ConstellationStyle.visibility(distance, 0f)
            assertEquals(.06f, previous.lines, EPSILON)
            assertTrue(previous.stars >= .45f)
            for (step in 1..100) {
                val current = ConstellationStyle.visibility(distance, step / 100f)
                assertTrue(current.stars >= previous.stars - EPSILON)
                assertTrue(current.lines >= previous.lines - EPSILON)
                assertInRange(current)
                previous = current
            }
        }
        val front = ConstellationStyle.visibility(4.6f, 1f)
        assertEquals(1f, front.stars, EPSILON)
        assertEquals(.95f, front.lines, EPSILON)
    }

    @Test
    fun smallCameraChangesCannotCauseVisibilityToPopAtFadeThresholds() {
        for (distanceStep in 0..100) {
            for (facingStep in 0..100) {
                val distance = 4f + distanceStep * .05f
                val facing = facingStep / 100f
                val before = ConstellationStyle.visibility(distance, facing)
                val after = ConstellationStyle.visibility(distance + .001f, facing + .001f)
                assertTrue(abs(before.stars - after.stars) < .003f)
                assertTrue(abs(before.lines - after.lines) < .003f)
            }
        }
    }

    @Test
    fun invalidAndExtremeInputsAlwaysYieldFiniteRestrainedValues() {
        val values = listOf(Float.NaN, Float.NEGATIVE_INFINITY, -100f, 0f, .5f, 1f, 100f, Float.POSITIVE_INFINITY)
        for (distance in values) {
            for (facing in values) assertInRange(ConstellationStyle.visibility(distance, facing))
        }
        assertEquals(ConstellationStyle.visibility(8.2f, 0f), ConstellationStyle.visibility(Float.NaN, Float.NaN))
    }

    private fun assertInRange(visibility: ConstellationStyle.Visibility) {
        assertTrue(visibility.stars.isFinite() && visibility.stars in .45f..1f)
        assertTrue(visibility.lines.isFinite() && visibility.lines in 0.06f..0.95f)
    }

    private companion object {
        const val EPSILON = .000001f
    }
}
