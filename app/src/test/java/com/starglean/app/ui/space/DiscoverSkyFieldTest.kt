package com.starglean.app.ui.space

import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverSkyFieldTest {
    @Test
    fun fixedSeedCreatesMostlyTinyDistantStarsAndOnlyAFewFlares() {
        assertEquals(DiscoverSkyField.stars, DiscoverSkyField.createStars())
        assertEquals(DiscoverSkyField.createStars(), DiscoverSkyField.createStars())
        assertEquals(392, DiscoverSkyField.stars.size)
        assertEquals(248, DiscoverSkyField.stars.count { it.layer == DiscoverSkyField.Layer.FAR })
        assertEquals(112, DiscoverSkyField.stars.count { it.layer == DiscoverSkyField.Layer.MID })
        assertEquals(32, DiscoverSkyField.stars.count { it.layer == DiscoverSkyField.Layer.NEAR })
        assertTrue(DiscoverSkyField.stars.count { it.radiusDp < 1f } >= 360)
        assertEquals(5, DiscoverSkyField.stars.count { it.flare })
        assertTrue(DiscoverSkyField.stars.filter { it.flare }.all { it.layer == DiscoverSkyField.Layer.NEAR })
        assertEquals(392, DiscoverSkyField.stars.distinct().size)
        DiscoverSkyField.stars.forEach { star ->
            assertTrue(star.x in 0f..1f && star.y in 0f..1f)
            assertTrue(star.depth in .10f.. .99f)
            assertTrue(star.radiusDp in .34f..1.48f)
            assertTrue(star.alpha in .22f.. .83f)
            assertTrue(star.warmth in 0f..1f)
            assertTrue(star.phase.isFinite())
            assertTrue(star.periodSeconds in 3.6f..8f)
        }
        assertTrue(DiscoverSkyField.stars.map { it.periodSeconds }.distinct().size > 380)
    }

    @Test
    fun allStarsShareASlowDirectionAndNearStarsMoveFaster() {
        val width = 400f
        val height = 860f
        val speeds = DiscoverSkyField.Layer.entries.associateWith { layer ->
            DiscoverSkyField.stars.filter { it.layer == layer }.map { star ->
                val centered = star.copy(x = .5f, y = .5f)
                val a = DiscoverSkyField.sample(centered, 0.0, width, height)
                val b = DiscoverSkyField.sample(centered, 10.0, width, height)
                val dx = b.x - a.x
                val dy = b.y - a.y
                assertTrue(dx > 0f && dy < 0f)
                assertEquals(-.382 / .924, (dy / dx).toDouble(), .0001)
                hypot(dx.toDouble(), dy.toDouble()) / 10.0 / width
            }
        }
        val far = speeds.getValue(DiscoverSkyField.Layer.FAR)
        val mid = speeds.getValue(DiscoverSkyField.Layer.MID)
        val near = speeds.getValue(DiscoverSkyField.Layer.NEAR)
        assertTrue(far.all { it in .0021.. .0036 })
        assertTrue(near.all { it in .0099.. .0144 })
        assertTrue(mid.minOrNull()!! > far.maxOrNull()!!)
        assertTrue(near.minOrNull()!! > mid.maxOrNull()!!)
        assertTrue(near.minOrNull()!! > far.maxOrNull()!! * 2.8)
    }

    @Test
    fun motionIsContinuousWithoutAGroupReturnAtEighteenSeconds() {
        val star = DiscoverSkyField.stars.last().copy(x = .5f, y = .5f)
        val before = DiscoverSkyField.sample(star, 17.999, 400f, 860f)
        val after = DiscoverSkyField.sample(star, 18.001, 400f, 860f)
        assertTrue(after.x > before.x)
        assertTrue(after.y < before.y)
        assertTrue(abs(after.x - before.x) < .02f)
        assertTrue(abs(after.alpha - before.alpha) < .001f)
        var previous = DiscoverSkyField.sample(star, 0.0, 400f, 860f)
        for (step in 1..1200) {
            val next = DiscoverSkyField.sample(star, step / 60.0, 400f, 860f)
            assertTrue(next.x >= previous.x)
            assertTrue(next.y <= previous.y)
            assertTrue(abs(next.alpha - previous.alpha) < .01f)
            previous = next
        }
    }

    @Test
    fun wrapOccursOutsideTheViewportWhileBothSidesAreInvisible() {
        val star = DiscoverSkyField.stars.last().copy(x = .5f, y = .5f)
        val width = 400f
        val height = 860f
        val padding = width * .065
        val initial = DiscoverSkyField.sample(star, 0.0, width, height)
        val one = DiscoverSkyField.sample(star, 1.0, width, height)
        val velocityX = one.x - initial.x
        val firstWrap = (width + padding - initial.x) / velocityX
        val before = DiscoverSkyField.sample(star, firstWrap - .05, width, height)
        val after = DiscoverSkyField.sample(star, firstWrap + .05, width, height)
        assertTrue(before.x > width)
        assertTrue(after.x < 0f)
        assertTrue(before.alpha < .0001f)
        assertTrue(after.alpha < .0001f)

        // Inspect actual frame-sized steps after the 3× speed increase, over the same 20 minutes.
        // A half-second sample can already be several pixels away from the invisible wrap boundary.
        var previous = initial
        for (step in 1..72_000) {
            val next = DiscoverSkyField.sample(star, step / 60.0, width, height)
            if (abs(next.x - previous.x) > width / 2f || abs(next.y - previous.y) > height / 2f) {
                assertTrue(previous.alpha < .002f)
                assertTrue(next.alpha < .002f)
            }
            previous = next
        }
    }

    @Test
    fun timeZeroIsStaticAndVeryLongSessionsStayFiniteAndBounded() {
        val times = listOf(-1.0, 0.0, .5, 60.0, 3600.0, 1e7, 1e12, 1e100, Double.MAX_VALUE)
        DiscoverSkyField.stars.forEach { star ->
            assertEquals(
                DiscoverSkyField.sample(star, 0.0, 400f, 860f),
                DiscoverSkyField.sample(star, 0.0, 400f, 860f),
            )
            times.forEach { time ->
                val sample = DiscoverSkyField.sample(star, time, 400f, 860f)
                assertTrue(sample.x.isFinite() && sample.y.isFinite() && sample.alpha.isFinite())
                assertTrue(sample.x in -26f..426f)
                assertTrue(sample.y in -26f..886f)
                assertTrue(sample.alpha in 0f..star.alpha)
            }
        }
    }

    @Test
    fun strongerTwinkleRemainsContinuousAndNeverBlinksOffInTheViewport() {
        DiscoverSkyField.stars.forEach { star ->
            val centred = star.copy(x = .5f, y = .5f)
            val samples = (0..180).map { frame ->
                DiscoverSkyField.sample(centred, frame * star.periodSeconds / 180.0, 400f, 860f)
            }
            assertTrue(samples.all { it.alpha >= star.alpha * .55f && it.alpha <= star.alpha })
            assertTrue(samples.maxOf { it.alpha } - samples.minOf { it.alpha } > star.alpha * .23f)
            samples.zipWithNext().forEach { (a, b) -> assertTrue(abs(a.alpha - b.alpha) < .01f) }
        }
    }

    @Test
    fun invalidInputsStayQuietInsteadOfFlashingOrThrowing() {
        val star = DiscoverSkyField.stars.first()
        for ((width, height) in listOf(0f to 860f, -1f to 860f, Float.NaN to 860f, 400f to Float.POSITIVE_INFINITY)) {
            assertEquals(DiscoverSkyField.Sample(0f, 0f, 0f), DiscoverSkyField.sample(star, 1.0, width, height))
        }
        val zero = DiscoverSkyField.sample(star, 0.0, 400f, 860f)
        assertEquals(zero, DiscoverSkyField.sample(star, Double.NaN, 400f, 860f))
        assertEquals(zero, DiscoverSkyField.sample(star, Double.POSITIVE_INFINITY, 400f, 860f))
        assertEquals(DiscoverSkyField.Sample(0f, 0f, 0f), DiscoverSkyField.sample(star.copy(periodSeconds = 0f), 1.0, 400f, 860f))
        val enormous = DiscoverSkyField.sample(star, Double.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        assertTrue(enormous.x.isFinite() && enormous.y.isFinite() && enormous.alpha.isFinite())
    }
}
