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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A quiet acknowledgement: two soft stars find one another and rest side by side. */
@Composable
internal fun PaperEmpathyReaction(
    progress: Float,
    modifier: Modifier = Modifier,
    darkPaper: Boolean = false,
    thumbnail: Boolean = false,
) {
    val shapes = remember { PaperEmpathyShapes() }
    val palette = remember(darkPaper) { PaperEmpathyPalette(darkPaper) }
    Canvas(modifier.semantics { contentDescription = "I understand how you feel" }) {
        val frame = if (thumbnail) paperEmpathyFrame(.62f) else paperEmpathyFrame(progress)
        if (frame.alpha <= 0f) return@Canvas
        val viewWidth = if (thumbnail) 116f else 160f
        val viewHeight = if (thumbnail) 78f else 120f
        val centerY = if (thumbnail) 63f else 60f
        val unit = min(size.width / viewWidth, size.height / viewHeight)
            .let { if (thumbnail) it else min(it, 154.dp.toPx() / 160f) }
        if (unit <= 0f) return@Canvas
        withTransform({
            translate(size.width / 2f - 80f * unit, size.height / 2f - centerY * unit)
            scale(unit, unit, Offset.Zero)
        }) {
            val strokeWidth = if (thumbnail) 3.8f else 1.08f
            // Separate local transforms preserve a readable approach and a soft asymmetric
            // meeting, instead of enlarging one already-complete reaction symbol.
            drawEmpathyStar(
                shapes, palette, frame.leftX, frame.leftY, frame.leftLeanDegrees,
                frame.alpha, palette.cream, strokeWidth, thumbnail,
            )
            drawEmpathyStar(
                shapes, palette, frame.rightX, frame.rightY, frame.rightLeanDegrees,
                frame.alpha, palette.apricot, strokeWidth, thumbnail,
            )
            if (!thumbnail && frame.resonanceAlpha > .001f) {
                drawPath(
                    shapes.resonance,
                    palette.copper.copy(alpha = frame.alpha * frame.resonanceAlpha * .53f),
                    style = empathyStroke(.95f),
                )
            }
        }
    }
}

private fun DrawScope.drawEmpathyStar(
    shapes: PaperEmpathyShapes,
    palette: PaperEmpathyPalette,
    x: Float,
    y: Float,
    lean: Float,
    alpha: Float,
    fill: Brush,
    strokeWidth: Float,
    thumbnail: Boolean,
) {
    withTransform({
        translate(x, y)
        rotate(lean, Offset.Zero)
    }) {
        drawPath(shapes.star, fill, alpha = alpha)
        drawPath(
            shapes.star,
            palette.copper.copy(alpha = alpha * if (thumbnail) .88f else .64f),
            style = empathyStroke(strokeWidth),
        )
        if (!thumbnail) {
            drawPath(
                shapes.highlight,
                palette.highlight.copy(alpha = alpha * .65f),
                style = empathyStroke(1.5f),
            )
        }
    }
}

private class PaperEmpathyPalette(darkPaper: Boolean) {
    val copper = if (darkPaper) Color(0xFFE1B892) else Color(0xFFA97756)
    val highlight = Color(0xFFFFF5E7)
    val cream = Brush.linearGradient(
        listOf(Color(0xFFFFF0D8), Color(0xFFF2D6B5)),
        Offset(-14f, -26f), Offset(14f, 24f),
    )
    val apricot = Brush.linearGradient(
        listOf(Color(0xFFFFE7CC), Color(0xFFEABE9F)),
        Offset(-12f, -27f), Offset(14f, 23f),
    )
}

private class PaperEmpathyShapes {
    // A broad inner radius and quadratic corners make little rounded bodies, not sharp
    // geometric sparkles. Paths and brushes are built once, never in the frame loop.
    val star = roundedEmpathyStar()
    val highlight = Path().apply {
        moveTo(-4.8f, -16.5f)
        quadraticTo(-2.4f, -21f, -.2f, -21.8f)
    }
    val resonance = Path().apply {
        moveTo(75.6f, 29.2f)
        cubicTo(78.2f, 26.7f, 80.7f, 30.5f, 83.3f, 28.4f)
    }
}

private fun roundedEmpathyStar(): Path {
    val vertices = List(10) { index ->
        val angle = (-PI / 2.0 + index * PI / 5.0).toFloat()
        val radius = if (index % 2 == 0) 30f else 18.8f
        Offset(cos(angle) * radius, sin(angle) * radius)
    }
    val cut = .28f
    return Path().apply {
        for (index in vertices.indices) {
            val previous = vertices[(index + 9) % 10]
            val vertex = vertices[index]
            val next = vertices[(index + 1) % 10]
            val start = vertex + (previous - vertex) * cut
            val end = vertex + (next - vertex) * cut
            if (index == 0) moveTo(start.x, start.y) else lineTo(start.x, start.y)
            quadraticTo(vertex.x, vertex.y, end.x, end.y)
        }
        close()
    }
}

private fun empathyStroke(width: Float) =
    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
