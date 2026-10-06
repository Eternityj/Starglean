package com.starglean.app.ui.components

import com.starglean.app.core.model.GalleryPoint

/** One transform for the chart, transparent apparition, dust, and accessible star targets. */
internal object GallerySceneMath {
    const val CHART_SCALE = .68f
    const val CENTRE_X = .50f
    const val CENTRE_Y = .52f

    fun unitPoint(point: GalleryPoint): GalleryPoint = GalleryPoint(
        x = CENTRE_X + CHART_SCALE * point.x,
        y = CENTRE_Y - CHART_SCALE * point.y,
    )

    /** Dense charts can have overlapping 48 dp targets; a tap selects the nearest real star. */
    fun nearestStar(
        points: List<GalleryPoint>,
        tapX: Float,
        tapY: Float,
        sidePx: Float,
        radiusPx: Float,
    ): Int? {
        if (!tapX.isFinite() || !tapY.isFinite() || !sidePx.isFinite() ||
            !radiusPx.isFinite() || sidePx <= 0f || radiusPx <= 0f
        ) return null
        var nearest: Int? = null
        var distance = radiusPx.toDouble() * radiusPx
        points.forEachIndexed { index, point ->
            val unit = unitPoint(point)
            val dx = unit.x.toDouble() * sidePx - tapX
            val dy = unit.y.toDouble() * sidePx - tapY
            val candidate = dx * dx + dy * dy
            // Stable tie-breaking: a single tap can never select two diary slots.
            if (candidate < distance || (candidate == distance && nearest == null)) {
                distance = candidate
                nearest = index
            }
        }
        return nearest
    }
}
