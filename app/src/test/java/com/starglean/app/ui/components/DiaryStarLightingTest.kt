package com.starglean.app.ui.components

import com.starglean.app.core.model.GalleryPoint
import com.starglean.app.core.model.GalleryConstellation
import org.junit.Assert.*
import org.junit.Test

class DiaryStarLightingTest {
    private val target = GalleryPoint(-.23f, .39f)

    @Test fun arrivesAtTheActualCatalogStar() {
        val end = diaryStarLightFrame(target, 1f, true)
        assertEquals(GallerySceneMath.unitPoint(target), end.point)
        assertTrue(end.revealed)
        assertEquals(0f, end.travellingAlpha, 0f)
        assertEquals(0f, end.arrivalAlpha, 0f)
    }

    @Test fun reducedMotionImmediatelyShowsTheCommittedResult() {
        val frame = diaryStarLightFrame(target, 0f, false)
        assertEquals(GallerySceneMath.unitPoint(target), frame.point)
        assertTrue(frame.revealed)
        assertEquals(0f, frame.travellingAlpha, 0f)
        assertEquals(0f, frame.arrivalAlpha, 0f)
    }

    @Test fun lightRisesBeforeTheNewStarIsRevealed() {
        val start = diaryStarLightFrame(target, 0f, true)
        val midway = diaryStarLightFrame(target, .35f, true)
        assertFalse(start.revealed)
        assertFalse(midway.revealed)
        assertTrue(midway.point.y < start.point.y)
        assertTrue(diaryStarLightFrame(target, .72f, true).revealed)
    }

    @Test fun invalidOrOutOfRangeProgressIsSafeAndLightIsAlwaysSubtle() {
        assertEquals(diaryStarLightFrame(target, 1f, true), diaryStarLightFrame(target, Float.NaN, true))
        assertEquals(diaryStarLightFrame(target, 0f, true), diaryStarLightFrame(target, -1f, true))
        repeat(101) {
            val frame = diaryStarLightFrame(target, it / 100f, true)
            assertTrue(frame.point.x in 0f..1f)
            assertTrue(frame.point.y in 0f..1f)
            assertTrue(frame.arrivalAlpha in 0f..0.28001f)
        }
    }

    @Test fun ArrivalOnlyConnectsGenuineAlreadyLitNeighbours() {
        val chart = GalleryConstellation("own-chart", "Chart", "Chart", 1, null,
            listOf(GalleryPoint(0f, 0f), GalleryPoint(.3f, 0f), GalleryPoint(0f, .3f)),
            listOf(0 to 1, 0 to 2), setOf(0, 1), false)
        assertEquals(listOf(1), diaryStarArrivalNeighbours(chart, 0))
        assertTrue(diaryStarArrivalNeighbours(chart, 2).isEmpty())
        assertTrue(diaryStarArrivalNeighbours(chart, 99).isEmpty())
    }
}
