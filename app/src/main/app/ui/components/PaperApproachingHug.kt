package com.starglean.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A character pose drives its silhouette, face, shoulders and both arms together. */
private data class SoftPersonPose(val x: Float, val y: Float, val lean: Float) {
    fun point(x: Float, y: Float): Offset {
        val radians = lean * PI.toFloat() / 180f
        return Offset(this.x + x * cos(radians) - (y - 80f) * sin(radians),
            this.y + 80f + x * sin(radians) + (y - 80f) * cos(radians))
    }
}

/** The accepted soft silhouettes are articulated, not swapped or crossfaded as pictures. */
@Composable
internal fun PaperApproachingHug(
    progress: Float,
    modifier: Modifier = Modifier,
    darkPaper: Boolean = false,
    thumbnail: Boolean = false,
) {
    val shapes = remember { ApproachingHugShapes() }
    val palette = remember(darkPaper) { ApproachingHugPalette(darkPaper) }
    val layerPaint = remember { Paint() }
    val canvasModifier = if (thumbnail) modifier.semantics { contentDescription = DiaryReaction.Hug.label } else modifier
    Canvas(canvasModifier) {
        val frame = paperApproachingHugFrame(if (thumbnail) .66f else progress)
        if (frame.alpha <= 0f) return@Canvas
        val viewWidth = if (thumbnail) 112f else 160f
        val viewHeight = if (thumbnail) 108f else 120f
        val unit = min(size.width / viewWidth, size.height / viewHeight)
            .coerceAtMost(if (thumbnail) Float.MAX_VALUE else 154.dp.toPx() / 160f)
        if (unit <= 0f) return@Canvas
        val left = SoftPersonPose(44f + frame.approach * 20f, 15f + frame.settleY,
            2.4f * frame.embrace + frame.swayDegrees)
        val right = SoftPersonPose(119f - frame.approach * 24f, 27f + frame.settleY + frame.squeeze,
            -2.4f * frame.embrace + frame.swayDegrees)
        layerPaint.alpha = frame.alpha
        drawIntoCanvas { it.saveLayer(Rect(0f, 0f, size.width, size.height), layerPaint) }
        try {
            withTransform({
                translate(size.width / 2f - 80f * unit, size.height / 2f - 60f * unit)
                scale(unit, unit, Offset.Zero)
            }) {
                val stroke = Stroke(if (thumbnail) 2.2f else 1.02f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                // Rear arms extend while approaching, then pass behind the partner's body.
                drawRearHugArm(shapes.rearArms[0], left, right, 1f, frame.embrace, palette.creamSolid, palette, stroke)
                drawRearHugArm(shapes.rearArms[1], right, left, -1f, frame.embrace, palette.apricotSolid, palette, stroke)
                drawSoftPerson(shapes.rightBody, shapes.faceRight, right, palette.apricot, palette, stroke)
                drawSoftPerson(shapes.leftBody, shapes.faceLeft, left, palette.cream, palette, stroke)
                // Lower apricot arm goes across first; the cream arm closes over it.
                drawFrontHugArm(shapes.frontArms[1], right, left, -1f, frame, palette.apricotArm, palette, stroke)
                drawFrontHugArm(shapes.frontArms[0], left, right, 1f, frame, palette.creamArm, palette, stroke)
            }
        } finally {
            drawIntoCanvas { it.restore() }
        }
    }
}

private fun DrawScope.drawSoftPerson(body: Path, face: Path, pose: SoftPersonPose, fill: Brush,
    palette: ApproachingHugPalette, stroke: Stroke) {
    withTransform({ translate(pose.x, pose.y); rotate(pose.lean, Offset(0f, 80f)) }) {
        drawPath(body, fill)
        drawPath(body, palette.outline, style = stroke)
        drawPath(face, palette.face, style = Stroke(stroke.width * 1.25f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawRearHugArm(path: Path, own: SoftPersonPose, partner: SoftPersonPose,
    direction: Float, embrace: Float, fill: Color, palette: ApproachingHugPalette, stroke: Stroke) {
    val start = own.point(direction * 14f, 29f)
    val open = own.point(direction * 35f, 32f)
    val closed = partner.point(direction * 8f, 27f)
    val hand = open + (closed - open) * embrace
    path.reset()
    path.moveTo(start.x, start.y)
    path.quadraticTo(start.x + direction * 15f, start.y + 5f, hand.x, hand.y)
    drawPath(path, palette.outline, style = Stroke(8f + stroke.width * 2, cap = StrokeCap.Round))
    drawPath(path, fill, style = Stroke(8f, cap = StrokeCap.Round))
}

private fun DrawScope.drawFrontHugArm(path: Path, own: SoftPersonPose, partner: SoftPersonPose,
    direction: Float, frame: PaperApproachingHugFrame, fill: Brush,
    palette: ApproachingHugPalette, stroke: Stroke) {
    val left = direction > 0f
    val shoulder = own.point(if (left) -12f else 13f, if (left) 35f else 38f)
    val openHand = own.point(if (left) 16f else -14f, (if (left) 43f else 46f) - 3f * frame.reach)
    val closedHand = partner.point(if (left) 12f else -4f, if (left) 35f else 49f)
    val hand = openHand + (closedHand - openHand) * frame.embrace
    val bend = 5f + 5f * frame.embrace
    path.reset()
    path.moveTo(shoulder.x, shoulder.y)
    path.cubicTo(shoulder.x + direction * 12f, shoulder.y + bend,
        hand.x - direction * 16f, hand.y + 2f * frame.embrace, hand.x, hand.y)
    path.quadraticTo(hand.x + direction * 7f, hand.y - 1f, hand.x + direction * 7f, hand.y + 4f)
    path.quadraticTo(hand.x + direction * 8f, hand.y + 7f, hand.x + direction * 3f, hand.y + 10f)
    path.cubicTo(hand.x - direction * 12f, hand.y + 18f,
        shoulder.x + direction * 6f, shoulder.y + 24f,
        shoulder.x - direction * 6f, shoulder.y + 12f)
    // Fill implicitly closes at the shoulder; the unclosed outline leaves a natural joint.
    drawPath(path, fill)
    drawPath(path, palette.outline, style = stroke)
}

private class ApproachingHugPalette(darkPaper: Boolean) {
    val outline = if (darkPaper) Color(0xFFD9AF8B) else Color(0xFFBF9677)
    val face = if (darkPaper) Color(0xFFA77854) else Color(0xFF9B7257)
    val creamSolid = Color(0xFFFFE9D1)
    val apricotSolid = Color(0xFFF6D7BC)
    val cream = Brush.linearGradient(listOf(Color(0xFFFFF2DF), Color(0xFFF6D7C0)), Offset(-20f, 5f), Offset(28f, 96f))
    val apricot = Brush.linearGradient(listOf(Color(0xFFFFE7CC), Color(0xFFEABE9F)), Offset(-25f, 5f), Offset(34f, 90f))
    val creamArm = Brush.linearGradient(listOf(Color(0xFFFFF0DD), Color(0xFFFBE1C8)), Offset(45f, 38f), Offset(107f, 87f))
    val apricotArm = Brush.linearGradient(listOf(Color(0xFFFFE7CC), Color(0xFFF0CBAB)), Offset(56f, 56f), Offset(111f, 91f))
}

private class ApproachingHugShapes {
    val rearArms = List(2) { Path() }
    val frontArms = List(2) { Path() }
    val leftBody = Path().apply {
        moveTo(2f, 0f)
        cubicTo(-16f, -2f, -25f, 10f, -27f, 29f)
        cubicTo(-30f, 48f, -29f, 60f, -37f, 73f)
        quadraticTo(-40f, 81f, -31f, 79f)
        cubicTo(-26f, 77f, -26f, 85f, -20f, 85f)
        cubicTo(-14f, 85f, -14f, 81f, -9f, 85f)
        cubicTo(-4f, 90f, 1f, 89f, 5f, 85f)
        cubicTo(10f, 83f, 10f, 88f, 16f, 85f)
        cubicTo(22f, 89f, 28f, 83f, 26f, 74f)
        cubicTo(27f, 62f, 23f, 52f, 25f, 41f)
        cubicTo(26f, 31f, 34f, 22f, 26f, 10f)
        cubicTo(22f, 2f, 12f, -1f, 2f, 0f)
        close()
    }
    val rightBody = Path().apply {
        moveTo(-1f, 0f)
        cubicTo(-16f, -1f, -23f, 10f, -24f, 26f)
        cubicTo(-24f, 40f, -23f, 53f, -25f, 65f)
        cubicTo(-27f, 75f, -22f, 80f, -15f, 77f)
        cubicTo(-12f, 75f, -9f, 83f, -3f, 79f)
        cubicTo(1f, 76f, 5f, 82f, 11f, 77f)
        cubicTo(16f, 74f, 17f, 79f, 23f, 75f)
        quadraticTo(27f, 69f, 32f, 72f)
        quadraticTo(39f, 74f, 36f, 66f)
        cubicTo(28f, 53f, 30f, 36f, 23f, 19f)
        cubicTo(18f, 6f, 11f, -1f, -1f, 0f)
        close()
    }
    val faceLeft = Path().apply {
        moveTo(-4f, 29f); quadraticTo(0f, 25f, 4f, 29f)
        moveTo(15f, 33f); quadraticTo(19f, 29f, 23f, 33f)
        moveTo(6f, 36f); quadraticTo(9f, 40f, 13f, 37f)
    }
    val faceRight = Path().apply {
        moveTo(-1f, 24f); quadraticTo(2f, 21f, 5f, 24f)
        moveTo(12f, 24f); quadraticTo(15f, 21f, 18f, 24f)
        moveTo(6f, 30f); quadraticTo(10f, 33f, 14f, 30f)
    }
}
