package com.starglean.app.ui.space

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryArrivalTest {
    @Test
    fun startsAsOneSmallBackgroundStarAndArrivesAtTheCameraTarget() {
        assertEquals(DiscoveryArrival.Frame(0f, .74f, .25f, 1f, 1f, 0f, 0f), DiscoveryArrival.sample(0f))
        val arrival = DiscoveryArrival.sample(atMillis(DiscoveryArrival.APPROACH_MILLIS))
        assertEquals(1f, arrival.travel, .000001f)
        assertEquals(.5f, arrival.x, .000001f)
        assertEquals(.46f, arrival.y, .000001f)
        assertEquals(48f, arrival.scale, .00001f)
        assertEquals(DiscoveryArrival.MAX_LIGHT_ALPHA, arrival.lightAlpha, .000001f)
        assertTrue(arrival.starAlpha in .1f.. .9f)
        assertTrue(arrival.revealAlpha in 0f.. .02f)
        val complete = DiscoveryArrival.sample(1f)
        assertEquals(1f, complete.travel, 0f)
        assertEquals(48f, complete.scale, 0f)
        assertEquals(0f, complete.starAlpha, 0f)
        assertEquals(0f, complete.lightAlpha, 0f)
        assertEquals(1f, complete.revealAlpha, 0f)
    }

    @Test
    fun approachIsMonotonicWithPerspectiveExpansionNotAPageStretch() {
        var previous = DiscoveryArrival.sample(0f)
        for (millis in 1..DiscoveryArrival.APPROACH_MILLIS) {
            val frame = DiscoveryArrival.sample(atMillis(millis))
            assertTrue(frame.travel >= previous.travel)
            assertTrue(frame.scale >= previous.scale)
            assertTrue(frame.x <= previous.x)
            assertTrue(frame.y >= previous.y)
            if (millis <= DiscoveryArrival.LIGHT_START_MILLIS) assertEquals(0f, frame.lightAlpha, .000001f)
            previous = frame
        }
        val earlyGrowth = DiscoveryArrival.sample(atMillis(600)).scale - DiscoveryArrival.sample(atMillis(300)).scale
        val lateGrowth = DiscoveryArrival.sample(atMillis(1650)).scale - DiscoveryArrival.sample(atMillis(1350)).scale
        assertTrue(lateGrowth > earlyGrowth * 10f)
    }

    @Test
    fun diaryAndWeatherShareAHalfSecondFadeNotAnOpaqueSwap() {
        assertEquals(560, DiscoveryArrival.REVEAL_MILLIS)
        assertEquals(2330, DiscoveryArrival.TOTAL_MILLIS)
        val start = DiscoveryArrival.REVEAL_START_MILLIS
        for (millis in 0..start) {
            assertEquals(0f, DiscoveryArrival.sample(atMillis(millis)).revealAlpha, .000001f)
        }
        val middle = DiscoveryArrival.sample(atMillis(start + DiscoveryArrival.REVEAL_MILLIS / 2))
        assertEquals(.5f, middle.revealAlpha, .000001f)
        assertTrue(middle.lightAlpha < .2f)
        assertEquals(0f, middle.starAlpha, 0f)
        assertEquals(1f, DiscoveryArrival.sample(1f).revealAlpha, 0f)
        var previousAlpha = 0f
        for (millis in start..DiscoveryArrival.TOTAL_MILLIS) {
            val alpha = DiscoveryArrival.sample(atMillis(millis)).revealAlpha
            assertTrue(alpha >= previousAlpha)
            assertTrue(alpha - previousAlpha < .003f)
            previousAlpha = alpha
        }
    }

    @Test
    fun localLightStaysBelowOneThirdAndDissolvesWithoutBoundaryJumps() {
        var previous = DiscoveryArrival.sample(0f)
        for (millis in 1..DiscoveryArrival.TOTAL_MILLIS) {
            val frame = DiscoveryArrival.sample(atMillis(millis))
            assertTrue(frame.lightAlpha in 0f.. .32f)
            if (millis <= DiscoveryArrival.LIGHT_PEAK_MILLIS) assertTrue(frame.lightAlpha >= previous.lightAlpha)
            else assertTrue(frame.lightAlpha <= previous.lightAlpha)
            assertTrue(abs(frame.lightAlpha - previous.lightAlpha) < .002f)
            assertTrue(abs(frame.starAlpha - previous.starAlpha) < .006f)
            assertTrue(frame.starAlpha <= previous.starAlpha)
            previous = frame
        }
        for (millis in listOf(1500, 1680, 1770, 1800, 1960, 2330)) {
            val progress = atMillis(millis)
            val before = DiscoveryArrival.sample(progress - .000001f)
            val after = DiscoveryArrival.sample(progress + .000001f)
            assertTrue(abs(after.lightAlpha - before.lightAlpha) < .0001f)
            assertTrue(abs(after.revealAlpha - before.revealAlpha) < .0001f)
            assertTrue(abs(after.starAlpha - before.starAlpha) < .0001f)
            assertTrue(abs(after.scale - before.scale) < .001f)
        }
    }

    @Test
    fun customOriginsStartAtTheSelectedStarAndShareTheSameArrival() {
        for ((x, y) in listOf(.19f to .37f, .87f to .09f, .5f to .46f, 0f to 1f)) {
            val start = DiscoveryArrival.sample(0f, x, y)
            assertEquals(x, start.x, 0f)
            assertEquals(y, start.y, 0f)
            assertEquals(1f, start.scale, 0f)
            val complete = DiscoveryArrival.sample(1f, x, y)
            assertEquals(.5f, complete.x, 0f)
            assertEquals(.46f, complete.y, 0f)
            for (step in 0..100) {
                val frame = DiscoveryArrival.sample(step / 100f, x, y)
                assertTrue(frame.x.isFinite() && frame.y.isFinite())
                assertTrue(frame.x in minOf(x, .5f)..maxOf(x, .5f))
                assertTrue(frame.y in minOf(y, .46f)..maxOf(y, .46f))
            }
        }
    }

    @Test
    fun invalidProgressAndOriginsCannotProduceNaNOrUnboundedLight() {
        assertEquals(DiscoveryArrival.sample(0f), DiscoveryArrival.sample(Float.NaN))
        assertEquals(DiscoveryArrival.sample(0f), DiscoveryArrival.sample(Float.NEGATIVE_INFINITY))
        assertEquals(DiscoveryArrival.sample(1f), DiscoveryArrival.sample(Float.POSITIVE_INFINITY))
        assertEquals(DiscoveryArrival.sample(0f), DiscoveryArrival.sample(-100f))
        assertEquals(DiscoveryArrival.sample(1f), DiscoveryArrival.sample(100f))
        assertEquals(DiscoveryArrival.sample(0f), DiscoveryArrival.sample(0f, Float.NaN, Float.POSITIVE_INFINITY))
        val clamped = DiscoveryArrival.sample(0f, -2f, 5f)
        assertEquals(0f, clamped.x, 0f)
        assertEquals(1f, clamped.y, 0f)
        for (step in 0..1000) {
            val frame = DiscoveryArrival.sample(step / 1000f)
            assertTrue(frame.travel in 0f..1f)
            assertTrue(frame.scale in 1f..48f)
            assertTrue(frame.starAlpha in 0f..1f)
            assertTrue(frame.lightAlpha in 0f..DiscoveryArrival.MAX_LIGHT_ALPHA)
            assertTrue(frame.revealAlpha in 0f..1f)
        }
    }

    private fun atMillis(millis: Int): Float = millis.toFloat() / DiscoveryArrival.TOTAL_MILLIS
}
