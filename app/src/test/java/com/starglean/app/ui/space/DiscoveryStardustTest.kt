package com.starglean.app.ui.space

import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryStardustTest {
    @Test
    fun fixedSeedProducesTheExactTwoLayerBudget() {
        assertEquals(DiscoveryStardust.motes, DiscoveryStardust.createMotes())
        assertEquals(DiscoveryStardust.createMotes(), DiscoveryStardust.createMotes())
        assertEquals(240, DiscoveryStardust.motes.size)
        assertEquals(180, DiscoveryStardust.motes.count { !it.glint })
        assertEquals(60, DiscoveryStardust.motes.count { it.glint })
        assertEquals(240, DiscoveryStardust.motes.distinct().size)
    }

    @Test
    fun allMotesAreFiniteAndKeepTheMainStarClearInTheImagePlane() {
        DiscoveryStardust.motes.forEach { mote ->
            assertTrue(mote.x.isFinite() && mote.y.isFinite() && mote.z.isFinite())
            assertTrue(mote.x in -1f..1f)
            assertTrue(mote.y in -1f..1f)
            assertTrue(mote.z in -.35f.. .35f)
            assertTrue(sqrt(mote.x * mote.x + mote.y * mote.y) >= .14f)
            assertTrue(mote.warmth in .25f.. .8f)
            if (mote.glint) {
                assertTrue(mote.radius in .2f.. .5f)
                assertTrue(mote.intensity in .25f.. .6f)
            } else {
                assertTrue(mote.radius in .4f..1.3f)
                assertTrue(mote.intensity in .08f.. .25f)
            }
        }
    }

    @Test
    fun bothLayersOccupySeveralQuadrantsAndFrontBackDepth() {
        for (glint in listOf(false, true)) {
            val layer = DiscoveryStardust.motes.filter { it.glint == glint }
            for ((xSign, ySign) in listOf(1f to 1f, -1f to 1f, -1f to -1f, 1f to -1f)) {
                assertTrue(layer.count { it.x * xSign > 0f && it.y * ySign > 0f } >= 4)
            }
            assertTrue(layer.count { it.z > .035f } >= 10)
            assertTrue(layer.count { it.z < -.035f } >= 10)
            assertTrue(layer.count { abs(it.z) < .025f } >= 4)
        }
    }

    @Test
    fun theCloudIsHorizontallyElongatedAndNeitherARingNorMirroredRays() {
        val motes = DiscoveryStardust.motes
        val averageX = motes.map { it.x.toDouble() }.average()
        val averageY = motes.map { it.y.toDouble() }.average()
        val varianceX = motes.map { (it.x - averageX) * (it.x - averageX) }.average()
        val varianceY = motes.map { (it.y - averageY) * (it.y - averageY) }.average()
        val covariance = motes.map { (it.x - averageX) * (it.y - averageY) }.average()
        assertTrue(varianceX > varianceY * 1.35)
        assertTrue(covariance < 0.0)
        val radii = motes.map { sqrt(it.x * it.x + it.y * it.y) }
        assertTrue(radii.count { it in .14f.. .30f } >= 25)
        assertTrue(radii.count { it in .30f.. .55f } >= 40)
        assertTrue(radii.count { it > .55f } >= 15)
        assertTrue(motes.none { a -> motes.any { b -> a !== b && a.x == -b.x && a.y == -b.y } })
        assertTrue(motes.map { it.x }.distinct().size > 235)
        assertTrue(motes.map { it.y }.distinct().size > 235)
    }
}
