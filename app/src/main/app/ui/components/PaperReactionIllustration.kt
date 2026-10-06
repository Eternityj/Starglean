package com.starglean.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A paper illustration, not a scaled-up button: each reaction owns its motion.
 * All marks stay inside a 154 × 116dp area, within the diary's existing 132dp blank space.
 * The owner supplies progress and cancellation; this composable never starts a timer.
 */
@Composable
internal fun PaperReactionIllustration(
    reaction: DiaryReaction,
    progress: Float,
    modifier: Modifier = Modifier,
    darkPaper: Boolean = false,
) {
    if (reaction == DiaryReaction.Hug) {
        PaperApproachingHug(progress, modifier, darkPaper)
        return
    }
    if (reaction == DiaryReaction.Understand) {
        PaperEmpathyReaction(progress, modifier, darkPaper)
        return
    }
    if (reaction == DiaryReaction.Thanks) {
        PaperThanksReaction(progress, modifier, darkPaper)
        return
    }
    val shapes = remember { PaperReactionShapes() }
    val palette = remember(darkPaper) { PaperReactionPalette(darkPaper) }
    Canvas(modifier) {
        val p = paperReactionProgress(progress)
        if (p <= 0f || p >= 1f) return@Canvas
        val unit = min(min(size.width / 160f, size.height / 120f), 154.dp.toPx() / 160f)
        if (unit <= 0f) return@Canvas
        withTransform({
            translate((size.width - 160f * unit) / 2f, (size.height - 120f * unit) / 2f)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            drawPaperFlower(shapes, palette, p, paperFlowerFrame(p), 1.06f)
        }
    }
}

/** Cropped, simplified thumbnails, with a physical one-dp line at 24dp button size. */
@Composable
internal fun PaperReactionIllustrationIcon(
    reaction: DiaryReaction,
    modifier: Modifier = Modifier,
    darkPaper: Boolean = true,
) {
    if (reaction == DiaryReaction.Hug) {
        PaperApproachingHug(.66f, modifier, darkPaper, thumbnail = true)
        return
    }
    if (reaction == DiaryReaction.Understand) {
        PaperEmpathyReaction(.62f, modifier, darkPaper, thumbnail = true)
        return
    }
    if (reaction == DiaryReaction.Thanks) {
        PaperThanksReaction(.74f, modifier, darkPaper, thumbnail = true)
        return
    }
    val shapes = remember { PaperReactionShapes() }
    val palette = remember(darkPaper) { PaperReactionPalette(darkPaper) }
    Canvas(modifier.semantics { contentDescription = reaction.label }) {
        val viewWidth = 78f
        val viewHeight = 78f
        val viewCenterY = 0f
        val viewCenterX = 0f
        val unit = min(size.width / viewWidth, size.height / viewHeight)
        if (unit <= 0f) return@Canvas
        withTransform({
            translate(size.width / 2f - viewCenterX * unit, size.height / 2f - viewCenterY * unit)
            scale(unit, unit, pivot = Offset.Zero)
        }) {
            // The control uses the bloom only, not a whole tiny plant and stem.
            repeat(6) { index ->
                withTransform({ rotate(index * 60f, Offset.Zero) }) {
                    drawPath(shapes.petal, if (index % 2 == 0) palette.petalBrush else palette.petalLightBrush)
                    drawPath(shapes.petal, palette.copper.copy(alpha = .85f), style = paperIllustrationStroke(2.8f))
                }
            }
            drawCircle(palette.pollen, 8f, Offset.Zero)
            drawCircle(palette.copper.copy(alpha = .8f), 8f, Offset.Zero, style = paperIllustrationStroke(2.5f))
        }
    }
}

private fun DrawScope.drawPaperFlower(
    shapes: PaperReactionShapes,
    palette: PaperReactionPalette,
    progress: Float,
    frame: PaperFlowerFrame,
    width: Float,
    thumbnail: Boolean = false,
) {
    if (frame.alpha <= 0f) return
    val alpha = frame.alpha
    val stroke = paperIllustrationStroke(width)
    withTransform({
        translate(0f, -frame.lift)
        rotate(frame.swayDegrees, Offset(80f, 106f))
    }) {
        val drop = -20f * (1f - frame.landing) + sin(frame.landing * PI.toFloat()) * 3.5f
        if (frame.stemGrowth > 0f) {
            // Grow down from the falling bud, not up from a detached baseline. Leaves
            // follow the same growing stem coordinates, so every intermediate pose joins.
            withTransform({
                translate(0f, drop)
                scale(1f, frame.stemGrowth, Offset(80f, 45f))
            }) {
                drawPath(shapes.stem, palette.leafInk.copy(alpha = alpha * .78f), style = paperIllustrationStroke(width * 1.05f))
            }
            for (side in 0..1) {
                val leafGrowth = paperReactionStage(frame.stemGrowth, if (side == 0) .2f else .4f, 1f)
                val originalLeafY = if (side == 0) 88f else 76f
                withTransform({
                    translate(if (side == 0) 79f else 80f, 45f + (originalLeafY - 45f) * frame.stemGrowth + drop)
                    scale(if (side == 0) leafGrowth else -leafGrowth, leafGrowth, Offset.Zero)
                    if (side == 1) rotate(-12f, Offset.Zero)
                }) {
                    if (leafGrowth > .01f) {
                        drawPath(shapes.leaf, palette.leaf.copy(alpha = alpha * .86f))
                        drawPath(shapes.leaf, palette.leafInk.copy(alpha = alpha * .58f), style = stroke)
                        if (!thumbnail) drawPath(shapes.leafVein, palette.leafInk.copy(alpha = alpha * .38f), style = paperIllustrationStroke(.72f))
                    }
                }
            }
        }

        withTransform({
            translate(80f, 45f + drop)
            rotate(-13f * (1f - frame.landing), Offset.Zero)
        }) {
            // Petals unfold in opposite pairs, making a small organic flower rather than
            // a rotating sun. The shared cached path is transformed, never rebuilt per frame.
            repeat(6) { index ->
                val opening = paperFlowerPetalOpening(progress, index)
                if (opening > .002f) {
                    withTransform({
                        rotate(shapes.petalAngles[index] * opening, Offset.Zero)
                        scale(.24f + .76f * opening, .43f + .57f * opening, Offset.Zero)
                    }) {
                        val petalAlpha = alpha * paperReactionStage(opening, 0f, .22f)
                        drawPath(shapes.petal, if (index % 2 == 0) palette.petalBrush else palette.petalLightBrush, alpha = petalAlpha)
                        drawPath(shapes.petal, palette.copper.copy(alpha = petalAlpha * .58f), style = stroke)
                        if (!thumbnail) {
                            drawPath(shapes.petalVein, palette.roseInk.copy(alpha = petalAlpha * .22f), style = paperIllustrationStroke(.7f))
                        }
                    }
                }
            }
            if (frame.budAlpha > .005f) {
                val budAlpha = alpha * frame.budAlpha
                drawPath(shapes.bud, palette.petalLightBrush, alpha = budAlpha)
                drawPath(shapes.bud, palette.copper.copy(alpha = budAlpha * .68f), style = stroke)
                drawPath(shapes.budFold, palette.roseInk.copy(alpha = budAlpha * .35f), style = paperIllustrationStroke(width * .74f))
            }
            val centerAlpha = alpha * paperReactionStage(progress, .43f, .61f)
            if (centerAlpha > .001f) {
                drawCircle(palette.pollen.copy(alpha = centerAlpha), 7.3f, Offset.Zero)
                drawCircle(palette.copper.copy(alpha = centerAlpha * .44f), 7.3f, Offset.Zero, style = paperIllustrationStroke(width * .75f))
                if (!thumbnail) {
                    drawPath(shapes.pollenMarks, palette.copper.copy(alpha = centerAlpha * .36f), style = paperIllustrationStroke(1.1f))
                }
            }
        }
    }
}

private class PaperReactionPalette(darkPaper: Boolean) {
    val copper = if (darkPaper) Color(0xFFE1B892) else Color(0xFFA97756)
    val roseInk = if (darkPaper) Color(0xFFE3AA98) else Color(0xFFBC8278)
    val leaf = if (darkPaper) Color(0xFFB2BDA2) else Color(0xFFBEC9AF)
    val leafInk = if (darkPaper) Color(0xFFD0D9BC) else Color(0xFF849779)
    val pollen = if (darkPaper) Color(0xFFE1BB78) else Color(0xFFE6C382)
    val petalBrush = Brush.linearGradient(listOf(Color(0xFFF6D7C0), Color(0xFFE5B199)), Offset(0f, -34f), Offset.Zero)
    val petalLightBrush = Brush.linearGradient(listOf(Color(0xFFFFE7CC), Color(0xFFEABE9F)), Offset(0f, -34f), Offset.Zero)
}

/** Immutable normalized paths, cached for the life of each illustration. */
private class PaperReactionShapes {
    val petalAngles = floatArrayOf(-60f, 60f, -120f, 120f, 180f, 0f)
    val stem = Path().apply {
        moveTo(80f, 106f)
        cubicTo(74f, 88f, 85f, 68f, 80f, 45f)
    }
    val leaf = Path().apply {
        moveTo(0f, 0f)
        cubicTo(-5f, -12f, -19f, -16f, -29f, -13f)
        cubicTo(-25f, 1f, -10f, 7f, 0f, 0f)
        close()
    }
    val leafVein = Path().apply {
        moveTo(-2f, -1f)
        quadraticTo(-14f, -7f, -25f, -11f)
    }
    val petal = Path().apply {
        moveTo(0f, 1f)
        cubicTo(-8f, -3f, -16f, -18f, -11f, -28f)
        cubicTo(-8f, -36f, 3f, -36f, 9f, -30f)
        cubicTo(18f, -22f, 10f, -6f, 0f, 1f)
        close()
    }
    val petalVein = Path().apply {
        moveTo(0f, -7f)
        quadraticTo(2f, -16f, 0f, -26f)
    }
    val bud = Path().apply {
        moveTo(0f, 5f)
        cubicTo(-12f, 2f, -10f, -14f, -2f, -21f)
        quadraticTo(1f, -24f, 3f, -20f)
        cubicTo(12f, -11f, 11f, 1f, 0f, 5f)
        close()
    }
    val budFold = Path().apply {
        moveTo(1f, -17f)
        cubicTo(-3f, -10f, 4f, -6f, 0f, 2f)
    }
    val pollenMarks = Path().apply {
        moveTo(-3f, -2f); lineTo(-2.9f, -2.1f)
        moveTo(2f, -3f); lineTo(2.1f, -3.1f)
        moveTo(3f, 1f); lineTo(3.1f, 1.1f)
        moveTo(-1f, 3f); lineTo(-.9f, 3.1f)
        moveTo(-.5f, -.2f); lineTo(-.4f, -.3f)
    }
}

private fun paperIllustrationStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
