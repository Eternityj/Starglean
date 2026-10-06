package com.starglean.app.ui.components

import com.starglean.app.core.model.GallerySamples
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class GalleryApparitionKind { Orion, Lyra }

/**
 * Small, illustration-specific movement for the remaining gallery apparitions.
 * Orion moves only the outside of his cloak; Lyra keeps its frame rigid and moves only the
 * inner strings. Native chart stars and their interpolated surroundings remain pinned.
 * This is a restrained 2D bitmap mesh, not an anatomical or physical 3D simulation.
 *
 * Coordinate masks and phases are cached once. [update] reuses the native mesh arrays and
 * performs no per-frame collection or object allocation.
 */
internal class GalleryApparitionMesh(val kind: GalleryApparitionKind) {
    val columns: Int = 48
    val rows: Int = 48
    val vertices: FloatArray = FloatArray((columns + 1) * (rows + 1) * 2)
    val colors: IntArray = IntArray((columns + 1) * (rows + 1))

    private val unitX = FloatArray(colors.size)
    private val unitY = FloatArray(colors.size)
    private val movement = FloatArray(colors.size)
    private val phaseSin = FloatArray(colors.size)
    private val phaseCos = FloatArray(colors.size)
    private val lightSin = FloatArray(colors.size)
    private val lightCos = FloatArray(colors.size)

    init {
        val geometryIndex = when (kind) {
            GalleryApparitionKind.Orion -> 1
            GalleryApparitionKind.Lyra -> 2
        }
        val anchors = GallerySamples.items.first { it.geometryIndex == geometryIndex }
            .points.map(GallerySceneMath::unitPoint)
        for (row in 0..rows) for (column in 0..columns) {
            val index = row * (columns + 1) + column
            val x = column.toFloat() / columns
            val y = row.toFloat() / rows
            unitX[index] = x
            unitY[index] = y

            // Same broad flat pin as the approved swan mesh. It covers the mesh corners
            // around each diary star, not only the exact point in the illustration.
            var anchorFreedom = 1f
            anchors.forEach { anchor ->
                val dx = x - anchor.x
                val dy = y - anchor.y
                anchorFreedom = min(anchorFreedom, smooth(.055f, .135f, sqrt(dx * dx + dy * dy)))
            }
            val boundary = smooth(0f, .055f, x) * smooth(0f, .055f, 1f - x) *
                smooth(0f, .055f, y) * smooth(0f, .055f, 1f - y)
            val region = when (kind) {
                GalleryApparitionKind.Orion -> {
                    // The generated image has an exposed left hand and a bow crossing the
                    // lower-right cloak. Protect the hand, then move only the right tail
                    // OUTSIDE the bow. Moving the original right shoulder-width rectangle
                    // would deform the forearm and bowstring together with the cloth.
                    val handDistance = sqrt((x - .274f) * (x - .274f) + (y - .533f) * (y - .533f))
                    val handFreedom = smooth(.055f, .100f, handDistance)
                    val left = smooth(.15f, .185f, x) * (1f - smooth(.225f, .260f, x)) *
                        smooth(.30f, .40f, y) * (1f - smooth(.65f, .75f, y)) * handFreedom
                    val right = smooth(.85f, .880f, x) * (1f - smooth(.915f, .945f, x)) *
                        smooth(.59f, .625f, y) * (1f - smooth(.70f, .765f, y))
                    left + right
                }
                GalleryApparitionKind.Lyra -> {
                    // Inset from the four sides so all mesh cells touching the physical
                    // lyre frame are rigid. Only the open string bed has any freedom.
                    smooth(.028f, .060f, stringBedDistance(x, y))
                }
            }
            movement[index] = anchorFreedom * boundary * region
            val displacementPhase = when (kind) {
                GalleryApparitionKind.Orion -> (y * .24f + if (x < .5f) 0f else .12f)
                GalleryApparitionKind.Lyra -> y * .65f
            } * (2.0 * PI)
            phaseSin[index] = sin(displacementPhase).toFloat()
            phaseCos[index] = cos(displacementPhase).toFloat()
            val shimmerPhase = (x * .35f - y * .8f) * (2.0 * PI)
            lightSin[index] = sin(shimmerPhase).toFloat()
            lightCos[index] = cos(shimmerPhase).toFloat()
        }
        update(seconds = 0.0, side = 1f, motionEnabled = false)
    }

    fun update(seconds: Double, side: Float, motionEnabled: Boolean = true) {
        val safeSide = if (side.isFinite() && side > 0f) side else 0f
        val moving = motionEnabled && seconds.isFinite() && safeSide > 0f
        val period = when (kind) {
            GalleryApparitionKind.Orion -> ORION_PERIOD_SECONDS
            GalleryApparitionKind.Lyra -> LYRA_PERIOD_SECONDS
        }
        // Reducing time first also handles very large finite timestamps safely.
        val phase = if (moving) (seconds % period) / period * (2.0 * PI) else 0.0
        val waveSin = sin(phase).toFloat()
        val waveCos = cos(phase).toFloat()
        for (index in colors.indices) {
            var x = unitX[index]
            var y = unitY[index]
            var color = -1
            if (moving) {
                val freedom = movement[index]
                val wave = waveSin * phaseCos[index] + waveCos * phaseSin[index]
                when (kind) {
                    GalleryApparitionKind.Orion -> {
                        // The open left cloth gets 30% more movement. The narrow right tail
                        // keeps its original excursion: increasing it would collapse cells
                        // beside the protected bow. Both sides share the livelier V21 period.
                        val gain = if (x < .5f) 1.3f else 1f
                        x += .004f * gain * freedom * wave * if (x < .5f) -1f else 1f
                        y += .022f * gain * freedom * wave
                    }
                    GalleryApparitionKind.Lyra -> {
                        x += .00238f * freedom * wave
                        y += .00084f * freedom * wave
                    }
                }
                val shimmer = .5f + .5f * (waveSin * lightCos[index] + waveCos * lightSin[index])
                val opacity = (1f - .18f * freedom * shimmer).coerceIn(.82f, 1f)
                val alpha = (opacity * 255f + .5f).toInt().coerceIn(210, 255)
                color = (alpha shl 24) or 0x00ffffff
            }
            vertices[index * 2] = x.coerceIn(0f, 1f) * safeSide
            vertices[index * 2 + 1] = y.coerceIn(0f, 1f) * safeSide
            colors[index] = color
        }
    }

    companion object {
        const val ORION_PERIOD_SECONDS = 8.4
        const val LYRA_PERIOD_SECONDS = 6.3

        private fun smooth(start: Float, end: Float, value: Float): Float {
            val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun stringBedDistance(x: Float, y: Float): Float = minOf(
            edgeDistance(x, y, .510f, .460f, .650f, .515f),
            edgeDistance(x, y, .650f, .515f, .570f, .775f),
            edgeDistance(x, y, .570f, .775f, .430f, .715f),
            edgeDistance(x, y, .430f, .715f, .510f, .460f),
        )

        private fun edgeDistance(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            return (dx * (y - ay) - dy * (x - ax)) / sqrt(dx * dx + dy * dy)
        }
    }
}
