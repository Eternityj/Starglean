package com.starglean.app.ui.components

import com.starglean.app.core.model.GalleryFigure
import com.starglean.app.core.model.GalleryPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Local, illustration-specific cloth, feather, mane and tail movement for the five new figures.
 * The native star chart does not move: its anchors and a flat surrounding neighbourhood are
 * pinned before any image-space motion is applied. This is a restrained 2D mesh, not a rigged
 * anatomical animation. The three previously approved figures retain their own renderers.
 *
 * All shape masks, star protection and spatial phases are cached during construction. [update]
 * only rewrites the two native drawing arrays; their identity never changes between frames.
 */
internal class GalleryExpandedMesh(val figure: GalleryFigure, points: List<GalleryPoint>) {
    val columns: Int = 48
    val rows: Int = 48
    val vertices: FloatArray = FloatArray((columns + 1) * (rows + 1) * 2)
    val colors: IntArray = IntArray((columns + 1) * (rows + 1))
    val periodSeconds: Double = when (figure) {
        GalleryFigure.Cassiopeia -> 8.6
        GalleryFigure.Leo -> 7.8
        GalleryFigure.Scorpius -> 9.2
        GalleryFigure.Pegasus -> 8.0
        GalleryFigure.Delphinus -> 6.8
        else -> 9.0 // Defensive still fallback; old figures never borrow this motion.
    }

    private val unitX = FloatArray(colors.size)
    private val unitY = FloatArray(colors.size)
    private val xSin = FloatArray(colors.size)
    private val xCos = FloatArray(colors.size)
    private val ySin = FloatArray(colors.size)
    private val yCos = FloatArray(colors.size)
    private val lightMask = FloatArray(colors.size)
    private val lightSin = FloatArray(colors.size)
    private val lightCos = FloatArray(colors.size)

    init {
        val anchors = points.map(GallerySceneMath::unitPoint)
        val patches = patchesFor(figure)
        // V21: make the approved local gestures easier to notice without widening their masks
        // or brightening the artwork. The densely pinned scorpion tail needs a little more gain.
        val motionGain = if (figure == GalleryFigure.Scorpius) 1.40f else 1.30f
        for (row in 0..rows) for (column in 0..columns) {
            val index = row * (columns + 1) + column
            val x = column.toFloat() / columns
            val y = row.toFloat() / rows
            unitX[index] = x
            unitY[index] = y
            var anchorFreedom = 1f
            anchors.forEach { anchor ->
                // The .055 flat pin also protects corners of mesh cells around a star,
                // keeping bilinear interpolation still rather than pinning only one vertex.
                anchorFreedom = min(anchorFreedom, smooth(.055f, .135f, hypot(x - anchor.x, y - anchor.y)))
            }
            val boundary = smooth(0f, .055f, x) * smooth(0f, .055f, 1f - x) *
                smooth(0f, .055f, y) * smooth(0f, .055f, 1f - y)
            val protection = anchorFreedom * boundary
            patches.forEach { patch ->
                val freedom = patch.mask(x, y).coerceIn(0f, 1f) * protection
                val phase = (patch.phase + x * patch.phaseX + y * patch.phaseY) * (2.0 * PI)
                val phaseSin = sin(phase).toFloat()
                val phaseCos = cos(phase).toFloat()
                xSin[index] += patch.dx * motionGain * freedom * phaseCos
                xCos[index] += patch.dx * motionGain * freedom * phaseSin
                ySin[index] += patch.dy * motionGain * freedom * phaseCos
                yCos[index] += patch.dy * motionGain * freedom * phaseSin
                lightMask[index] = maxOf(lightMask[index], freedom * patch.light)
            }
            // A conservative bound covers every phase, including overlapping patches.
            val maximum = hypot(xSin[index], ySin[index]) + hypot(xCos[index], yCos[index])
            if (maximum > MAX_EXCURSION) {
                val scale = MAX_EXCURSION / maximum
                xSin[index] *= scale
                xCos[index] *= scale
                ySin[index] *= scale
                yCos[index] *= scale
            }
            val illuminationPhase = (x * .24f - y * .32f) * (2.0 * PI)
            lightSin[index] = sin(illuminationPhase).toFloat()
            lightCos[index] = cos(illuminationPhase).toFloat()
        }
        update(0.0, 1f, motionEnabled = false)
    }

    fun update(seconds: Double, side: Float, motionEnabled: Boolean = true) {
        val safeSide = if (side.isFinite() && side > 0f) side else 0f
        val moving = motionEnabled && seconds.isFinite() && safeSide > 0f
        // Reduce first: even enormous finite timestamps must not overflow into native geometry.
        val phase = if (moving) (seconds % periodSeconds) / periodSeconds * (2.0 * PI) else 0.0
        val waveSin = sin(phase).toFloat()
        val waveCos = cos(phase).toFloat()
        for (index in colors.indices) {
            var x = unitX[index]
            var y = unitY[index]
            var color = -1
            if (moving) {
                x += xSin[index] * waveSin + xCos[index] * waveCos
                y += ySin[index] * waveSin + yCos[index] * waveCos
                val light = .5f + .5f * (waveSin * lightCos[index] + waveCos * lightSin[index])
                val opacity = (1f - lightMask[index] * light).coerceIn(.83f, 1f)
                val alpha = (opacity * 255f + .5f).toInt().coerceIn(212, 255)
                color = (alpha shl 24) or 0x00ffffff
            }
            vertices[index * 2] = x.coerceIn(0f, 1f) * safeSide
            vertices[index * 2 + 1] = y.coerceIn(0f, 1f) * safeSide
            colors[index] = color
        }
    }

    private data class MotionPatch(
        val dx: Float,
        val dy: Float,
        val phase: Float = 0f,
        val phaseX: Float = 0f,
        val phaseY: Float = .12f,
        val light: Float = .17f,
        val mask: (Float, Float) -> Float,
    )

    companion object {
        private const val MAX_EXCURSION = .0315f

        private fun smooth(start: Float, end: Float, value: Float): Float {
            val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun ellipse(x: Float, y: Float, cx: Float, cy: Float, rx: Float, ry: Float): Float =
            1f - smooth(.18f, 1f, hypot((x - cx) / rx, (y - cy) / ry))

        // These masks were fitted to the actual 1254-square *_apparition_v20 PNGs. In particular,
        // Cassiopeia's throne and hands, Leo's face/legs, and Pegasus's wing roots stay rigid.
        private fun patchesFor(figure: GalleryFigure): List<MotionPatch> = when (figure) {
            GalleryFigure.Cassiopeia -> listOf(
                // Loose lower-left hem; the centre of the dress and the occupied throne do not sway.
                MotionPatch(dx = -.005f, dy = .018f) { x, y ->
                    ellipse(x, y, .22f, .82f, .23f, .15f) * smooth(.66f, .75f, y) *
                        (1f - smooth(.40f, .46f, x))
                },
                MotionPatch(dx = .004f, dy = .017f, phase = .13f) { x, y ->
                    ellipse(x, y, .86f, .84f, .12f, .14f) * smooth(.70f, .78f, x)
                },
            )
            GalleryFigure.Leo -> listOf(
                // Topmost mane is above the ear; the second patch is below the jaw, not the face.
                MotionPatch(dx = .006f, dy = -.012f) { x, y ->
                    ellipse(x, y, .45f, .12f, .14f, .085f)
                },
                MotionPatch(dx = -.011f, dy = .007f, phase = .09f) { x, y ->
                    ellipse(x, y, .19f, .49f, .085f, .16f) * smooth(.38f, .45f, y)
                },
                // Only the upper-right tuft; the curved tail stem and the rump stay fixed.
                MotionPatch(dx = .010f, dy = .013f, phase = .17f) { x, y ->
                    ellipse(x, y, .87f, .26f, .115f, .15f) * (1f - smooth(.35f, .41f, y))
                },
            )
            GalleryFigure.Scorpius -> listOf(
                // Final outer tail segments and their light only, well clear of the rigid legs.
                // Broad star pins intentionally keep the tip's native diary anchor completely still.
                MotionPatch(dx = -.006f, dy = .008f, phaseY = .08f) { x, y ->
                    ellipse(x, y, .90f, .57f, .115f, .12f)
                },
            )
            GalleryFigure.Pegasus -> listOf(
                // Outermost feather ends, not the large wing root attached to the shoulder.
                MotionPatch(dx = .004f, dy = -.018f, phaseX = .12f) { x, y ->
                    ellipse(x, y, .82f, .25f, .15f, .20f) * smooth(.66f, .78f, x)
                },
                MotionPatch(dx = -.003f, dy = -.012f, phase = .07f) { x, y ->
                    ellipse(x, y, .535f, .165f, .080f, .10f)
                },
                // The tail starts behind the hindquarter; stay right of both extended hind legs.
                MotionPatch(dx = .010f, dy = -.013f, phase = .18f) { x, y ->
                    ellipse(x, y, .90f, .70f, .10f, .20f) * smooth(.77f, .855f, x)
                },
            )
            GalleryFigure.Delphinus -> listOf(
                // The two flukes flex with a small phase difference. The tail stock, torso,
                // dorsal fin, pectoral fins and face are outside both masks.
                MotionPatch(dx = -.011f, dy = .015f) { x, y ->
                    ellipse(x, y, .145f, .725f, .15f, .065f)
                },
                MotionPatch(dx = .008f, dy = -.015f, phase = .10f) { x, y ->
                    ellipse(x, y, .33f, .855f, .09f, .11f) * smooth(.755f, .815f, y)
                },
            )
            else -> emptyList()
        }
    }
}
