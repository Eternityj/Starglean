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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A small piece of paper, not a feedback card. The caller owns playback and cancellation. */
@Composable
internal fun PaperThanksReaction(
    progress: Float,
    modifier: Modifier = Modifier,
    darkPaper: Boolean = false,
    thumbnail: Boolean = false,
) {
    val shapes = remember { PaperThanksShapes() }
    val palette = remember(darkPaper) { PaperThanksPalette(darkPaper) }
    Canvas(modifier.semantics { contentDescription = "Thank you for sharing" }) {
        val p = if (thumbnail) .74f else paperReactionProgress(progress)
        val frame = paperThanksFrame(p)
        if (frame.alpha <= 0f) return@Canvas
        val viewWidth = if (thumbnail) 80f else 160f
        val viewHeight = if (thumbnail) 88f else 120f
        val unit = if (thumbnail) {
            min(size.width / viewWidth, size.height / viewHeight)
        } else {
            min(min(size.width / viewWidth, size.height / viewHeight), min(154.dp.toPx() / 160f, 116.dp.toPx() / 120f))
        }
        if (unit <= 0f) return@Canvas
        withTransform({
            translate(size.width / 2f - 84f * unit, size.height / 2f - 65f * unit)
            scale(unit, unit, Offset.Zero)
        }) {
            drawThanksSheet(shapes, palette, frame, p, thumbnail)
        }
    }
}

private fun DrawScope.drawThanksSheet(
    shapes: PaperThanksShapes,
    palette: PaperThanksPalette,
    frame: PaperThanksFrame,
    progress: Float,
    thumbnail: Boolean,
) {
    val alpha = frame.alpha
    val stroke = thanksStroke(if (thumbnail) 2.9f else 1.08f)
    withTransform({
        translate(0f, frame.noteOffsetY)
        rotate(frame.noteRotation, Offset(80f, 80f))
    }) {
        // The bottom half stays anchored while the top half turns around the real crease.
        clipRect(44f, 65f, 116f, 109f) {
            drawPath(shapes.sheet, palette.paper, alpha = alpha)
            drawPath(shapes.sheet, palette.outline.copy(alpha = alpha * .76f), style = stroke)
        }
        if (abs(frame.flapScaleY) > .005f) {
            withTransform({ scale(1f, frame.flapScaleY, Offset(80f, 65f)) }) {
                clipRect(44f, 22f, 116f, 65.2f) {
                    drawPath(shapes.sheet, if (frame.flapScaleY < 0f) palette.paperBack else palette.paper, alpha = alpha)
                    drawPath(shapes.sheet, palette.outline.copy(alpha = alpha * .76f), style = stroke)
                    if (frame.flapScaleY > 0f) {
                        drawPath(shapes.corner, palette.corner, alpha = alpha)
                        drawPath(shapes.cornerFold, palette.outline.copy(alpha = alpha * .45f), style = stroke)
                    }
                }
            }
        }
        if (!thumbnail) {
            drawPath(
                shapes.crease,
                palette.outline.copy(alpha = alpha * (.2f - .14f * frame.unfolding)),
                style = thanksStroke(.7f),
            )
        }
        val inkAlpha = paperReactionStage(frame.unfolding, .86f, 1f) * alpha
        if (inkAlpha > 0f) {
            repeat(if (thumbnail) 2 else 3) { index ->
                val reveal = if (thumbnail) 1f else paperThanksInkReveal(progress, index)
                if (reveal > 0f) {
                    clipRect(61f, 48f, 61f + shapes.inkWidths[index] * reveal, 90f) {
                        drawPath(shapes.ink[index], palette.ink.copy(alpha = inkAlpha * .56f), style = thanksStroke(if (thumbnail) 2.8f else 1.15f))
                    }
                }
            }
        }
        val starAlpha = alpha * frame.starAlpha
        if (starAlpha > 0f) {
            withTransform({
                translate(frame.starX, frame.starY)
                rotate(frame.starRotation, Offset.Zero)
            }) {
                drawPath(shapes.star, palette.star, alpha = starAlpha)
                drawPath(shapes.star, palette.outline.copy(alpha = starAlpha * .8f), style = stroke)
                if (!thumbnail) {
                    drawPath(shapes.starHighlight, palette.highlight.copy(alpha = starAlpha * .68f), style = thanksStroke(1.45f))
                }
            }
        }
    }
}

private fun thanksStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

private class PaperThanksPalette(darkPaper: Boolean) {
    val outline = if (darkPaper) Color(0xFFDEB28C) else Color(0xFFA97756)
    val ink = if (darkPaper) Color(0xFFB99075) else Color(0xFFA68168)
    val highlight = Color(0xFFFFF5E7)
    val paper = Brush.linearGradient(listOf(Color(0xFFFFF5E3), Color(0xFFF4DDC1)), Offset(60f, 35f), Offset(104f, 103f))
    val paperBack = Brush.linearGradient(listOf(Color(0xFFF2D9BB), Color(0xFFEBCBAC)), Offset(60f, 35f), Offset(105f, 65f))
    val corner = Brush.linearGradient(listOf(Color(0xFFE6BE9B), Color(0xFFFFEAD0)), Offset(95f, 32f), Offset(103f, 47f))
    val star = Brush.linearGradient(listOf(Color(0xFFFFE7CC), Color(0xFFEABE9F)), Offset(-6f, -12f), Offset(8f, 11f))
}

/** All paths and gradient brushes are retained, not rebuilt while the diary animates. */
private class PaperThanksShapes {
    val sheet = Path().apply {
        moveTo(57f, 30f)
        quadraticTo(53f, 30f, 53f, 35f)
        cubicTo(52f, 54f, 53f, 78f, 52f, 97f)
        quadraticTo(52f, 102f, 57f, 102f)
        cubicTo(72f, 101f, 88f, 104f, 102f, 102f)
        quadraticTo(108f, 102f, 108f, 96f)
        lineTo(108f, 46f)
        quadraticTo(102f, 37f, 93f, 30f)
        quadraticTo(75f, 29f, 57f, 30f)
        close()
    }
    val corner = Path().apply {
        moveTo(93f, 30f)
        quadraticTo(94f, 38f, 92f, 43f)
        quadraticTo(99f, 44f, 108f, 46f)
        quadraticTo(102f, 37f, 93f, 30f)
        close()
    }
    val cornerFold = Path().apply {
        moveTo(93f, 30f)
        quadraticTo(94f, 38f, 92f, 43f)
        quadraticTo(99f, 44f, 108f, 46f)
    }
    val crease = Path().apply {
        moveTo(54f, 65f)
        quadraticTo(80f, 64.2f, 106f, 65f)
    }
    val inkWidths = floatArrayOf(30f, 37f, 24f)
    val ink = arrayOf(
        Path().apply {
            moveTo(63f, 55f)
            cubicTo(68f, 52.5f, 70f, 57f, 75f, 54.5f)
            cubicTo(80f, 52.8f, 83f, 56.5f, 89f, 54.8f)
        },
        Path().apply {
            moveTo(63f, 69f)
            cubicTo(70f, 67f, 75f, 70.6f, 80f, 68.5f)
            cubicTo(85f, 66.5f, 90f, 70f, 96f, 68.2f)
        },
        Path().apply {
            moveTo(63f, 82f)
            cubicTo(67f, 80f, 71f, 83.5f, 76f, 81.4f)
            quadraticTo(80f, 80f, 83f, 81.8f)
        },
    )
    val star = roundedThanksStar()
    val starHighlight = Path().apply {
        moveTo(-6f, -3f)
        quadraticTo(-3.3f, -3.4f, -2.2f, -7f)
    }
}

/** Five broad, rounded tips: a little apricot star, never a sharp sparkle or cross. */
private fun roundedThanksStar(): Path {
    val points = (0..9).map { index ->
        val angle = (-90f + index * 36f) * PI.toFloat() / 180f
        val radius = if (index % 2 == 0) 12.7f else 7.2f
        Offset(cos(angle) * radius, sin(angle) * radius)
    }
    fun near(a: Offset, b: Offset): Offset = a + (b - a) * .28f
    return Path().apply {
        val first = near(points[0], points[9])
        moveTo(first.x, first.y)
        repeat(10) { index ->
            val current = points[index]
            val outgoing = near(current, points[(index + 1) % 10])
            quadraticTo(current.x, current.y, outgoing.x, outgoing.y)
            val incoming = near(points[(index + 1) % 10], current)
            lineTo(incoming.x, incoming.y)
        }
        close()
    }
}
