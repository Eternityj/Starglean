package com.starglean.app.ui.components

import com.starglean.app.core.model.GalleryPoint
import com.starglean.app.core.model.GallerySamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySceneMathTest {
    @Test fun cygnusChartMatchesTheTransparentArtwork() {
        val expected = listOf(.5408f to .1732f, .5f to .452f, .2416f to .5064f,
            .7584f to .384f, .4456f to .6764f, .3776f to .8668f)
        GallerySamples.items.first().points.forEachIndexed { index, point ->
            val result = GallerySceneMath.unitPoint(point)
            assertEquals(expected[index].first, result.x, .0001f)
            assertEquals(expected[index].second, result.y, .0001f)
        }
    }

    @Test fun everyKnownAnchorStaysInsideTheSquare() {
        GallerySamples.items.flatMap { it.points }.map(GallerySceneMath::unitPoint).forEach {
            assertTrue(it.x in 0f..1f && it.y in 0f..1f)
        }
    }

    @Test fun nearestRealStarWinsWhenTargetsOverlap() {
        val points = listOf(GalleryPoint(0f, 0f), GalleryPoint(.10f, 0f))
        assertEquals(0, GallerySceneMath.nearestStar(points, 50f, 52f, 100f, 24f))
        assertEquals(1, GallerySceneMath.nearestStar(points, 57f, 52f, 100f, 24f))
    }

    @Test fun emptySpaceAndInvalidMeasurementsNeverInventDiarySlots() {
        val points = listOf(GalleryPoint(0f, 0f))
        assertNull(GallerySceneMath.nearestStar(points, 0f, 0f, 100f, 24f))
        assertNull(GallerySceneMath.nearestStar(points, Float.NaN, 52f, 100f, 24f))
        assertNull(GallerySceneMath.nearestStar(points, 50f, 52f, Float.POSITIVE_INFINITY, 24f))
        assertNull(GallerySceneMath.nearestStar(points, 50f, 52f, 100f, -1f))
        assertNull(GallerySceneMath.nearestStar(emptyList(), 50f, 52f, 100f, 24f))
    }

    @Test fun hitTestingScalesWithTheDisplayedSquare() {
        val points = GallerySamples.items.first().points
        val point = GallerySceneMath.unitPoint(points[5])
        listOf(100f, 400f, 1080f).forEach { side ->
            assertEquals(5, GallerySceneMath.nearestStar(points, point.x * side, point.y * side, side, 24f))
        }
    }

    @Test fun everyGalleryFigureKeepsItsOwnStarIndicesAtMultipleDisplaySizes() {
        GallerySamples.items.forEach { item ->
            listOf(240f, 411f, 1080f).forEach { side ->
                item.points.forEachIndexed { index, point ->
                    val unit = GallerySceneMath.unitPoint(point)
                    assertEquals("${item.id} star $index at $side", index,
                        GallerySceneMath.nearestStar(item.points, unit.x * side, unit.y * side, side, 24f))
                }
            }
        }
    }
}
