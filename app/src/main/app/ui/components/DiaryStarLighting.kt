package com.starglean.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GalleryPoint

internal data class DiaryStarLightFrame(
    val point: GalleryPoint,
    val revealed: Boolean,
    val travellingAlpha: Float,
    val arrivalAlpha: Float,
)

internal fun diaryStarArrivalNeighbours(item: GalleryConstellation, starIndex: Int): List<Int> =
    if (starIndex !in item.litStarIndices) emptyList() else item.links.mapNotNull { (from, to) ->
        when (starIndex) { from -> to; to -> from; else -> null }
    }.filter { it in item.litStarIndices }.distinct()

/** The destination is always a real catalog anchor, never decorative dust or an invented slot. */
internal fun diaryStarLightFrame(target: GalleryPoint, progress: Float, motionEnabled: Boolean): DiaryStarLightFrame {
    val end = GallerySceneMath.unitPoint(target)
    val p = if (motionEnabled && progress.isFinite()) progress.coerceIn(0f, 1f) else 1f
    val t = (p / .72f).coerceIn(0f, 1f)
    val a = 1f - t
    val point = GalleryPoint(
        a * a * .5f + 2f * a * t * (end.x * .55f + .2f) + t * t * end.x,
        a * a * .95f + 2f * a * t * (end.y + .2f).coerceAtMost(.9f) + t * t * end.y,
    )
    return DiaryStarLightFrame(point, p >= .72f,
        if (p < .72f) .85f else 0f,
        if (p >= .72f) ((1f - p) / .28f).coerceIn(0f, 1f) * .28f else 0f)
}

/** A small, bounded celebration over the same chart renderer used by My constellations. */
@Composable
internal fun DiaryStarLighting(
    item: GalleryConstellation,
    starIndex: Int,
    animateArrival: Boolean,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val validStar = starIndex.takeIf { it in item.points.indices && it in item.litStarIndices }
    val progress = remember(item.id, starIndex, animateArrival) { Animatable(if (animateArrival && motionEnabled) 0f else 1f) }
    LaunchedEffect(item.id, starIndex, animateArrival, motionEnabled) {
        if (animateArrival && motionEnabled && validStar != null) progress.animateTo(1f, tween(1900))
        else progress.snapTo(1f)
    }
    val frame = validStar?.let { diaryStarLightFrame(item.points[it], progress.value, motionEnabled) }
    val arrivalNeighbours = remember(item, starIndex) { diaryStarArrivalNeighbours(item, starIndex) }
    val shown = if (frame != null && !frame.revealed) item.copy(
        litStarIndices = item.litStarIndices - starIndex, shownOnHome = false,
    ) else item
    Box(modifier.semantics {
        contentDescription = "${item.name}, ${item.litStars} of ${item.totalStars} stars lit. This entry is star ${starIndex + 1}."
    }) {
        GalleryConstellationScene(shown, motionEnabled = false, modifier = Modifier.fillMaxSize(),
            apparitionVisible = true, selectedStar = validStar, lightweight = true)
        if (frame != null) Canvas(Modifier.fillMaxSize()) {
            val side = size.minDimension
            val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            val centre = origin + Offset(frame.point.x * side, frame.point.y * side)
            if (frame.travellingAlpha > 0f) {
                val radius = 11.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(StargleanColors.Gold.copy(alpha = .24f), Color.Transparent), centre, radius), radius, centre)
                drawCircle(StargleanColors.GoldBright, 1.65.dp.toPx(), centre, alpha = frame.travellingAlpha)
                // Tiny grains accompany the light; no cross-shaped flare or full-screen flash.
                repeat(5) { index ->
                    drawCircle(StargleanColors.Gold, (.6f - index * .07f).dp.toPx(),
                        centre + Offset((if (index % 2 == 0) -1 else 1) * (index + 1) * 1.2.dp.toPx(),
                            (index + 1) * 3.dp.toPx()), alpha = frame.travellingAlpha * (5 - index) / 11f)
                }
            }
            if (frame.arrivalAlpha > 0f) {
                val radius = 19.dp.toPx()
                drawCircle(Brush.radialGradient(listOf(StargleanColors.Gold.copy(alpha = frame.arrivalAlpha), Color.Transparent), centre, radius), radius, centre)
                // The new light softly finds already-lit neighbours along genuine catalog links.
                val reveal = ((progress.value - .72f) / .22f).coerceIn(0f, 1f)
                arrivalNeighbours.forEach { neighbour ->
                    val unit = GallerySceneMath.unitPoint(item.points[neighbour])
                    val end = origin + Offset(unit.x * side, unit.y * side)
                    repeat(18) { index ->
                        val along = index / 17f
                        if (along <= reveal) drawCircle(StargleanColors.GoldBright,
                            (.38f + index % 3 * .1f).dp.toPx(), centre + (end - centre) * along,
                            alpha = frame.arrivalAlpha * (if (index % 3 == 0) .9f else .55f))
                    }
                }
            }
        }
    }
}
