package com.starglean.app.ui.components

import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GalleryPoint
import com.starglean.app.ui.space.CelestialGeometry.Vec3
import com.starglean.app.ui.space.ConstellationFilaments
import com.starglean.app.ui.space.ConstellationStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Gallery-only continuation of the approved irregular, disconnected stardust material.
 * Home-backed charts retain their exact existing particles. Other charts derive decorative
 * grains from their explicit geometry without adding any diary anchors or touching the home
 * catalogue. These pure generators have no global cache: callers remember their returned lists.
 */
internal object GalleryStarDust {
    fun motes(item: GalleryConstellation): List<ConstellationStyle.Mote> {
        item.geometryIndex?.let { return ConstellationFilaments.motes(it) }
        val random = Random(MOTE_SEED xor item.latinName.hashCode())
        val result = ArrayList<ConstellationStyle.Mote>()
        fun noise(): Float = random.nextFloat() + random.nextFloat() - 1f

        item.links.forEach { (a, b) ->
            val start = item.points[a]
            val end = item.points[b]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = sqrt(dx * dx + dy * dy)
            if (!length.isFinite() || length <= .032f) return@forEach
            val sideX = -dy / length
            val sideY = dx / length
            val phase = random.nextFloat() * 2f * PI.toFloat()
            val trim = maxOf(.055f, .016f / length).coerceAtMost(.45f)
            val count = ((length - .032f) * 190f).roundToInt().coerceIn(6, 100)
            repeat(count) {
                for (attempt in 0 until MAX_ATTEMPTS) {
                    val t = trim + random.nextFloat() * (1f - 2f * trim)
                    val density = .69f + .16f * sin(t * 5.7f + phase) +
                        .12f * cos(t * 13.3f - phase * .61f)
                    if (random.nextFloat() > density) continue
                    val middle = sin(PI.toFloat() * t).let { it * it }
                    val side = .0035f * sin(t * 7.1f + phase) * middle +
                        noise() * (.010f + .014f * middle)
                    val depth = .003f * cos(t * 6.3f - phase) * middle +
                        noise() * (.010f + .012f * middle)
                    val point = Vec3(
                        start.x + dx * t + sideX * side,
                        start.y + dy * t + sideY * side,
                        depth,
                    )
                    if (!clearOfAnchors(point, item.points, .015f)) continue
                    val endpointFade = smoothstep(trim, trim + .09f, t) *
                        smoothstep(trim, trim + .09f, 1f - t)
                    val light = (.26f - .065f * middle + random.nextFloat() * .095f) *
                        (.62f + .38f * endpointFade)
                    result += ConstellationStyle.Mote(
                        position = point,
                        intensity = light.coerceIn(.13f, .38f),
                        scale = (.25f + random.nextFloat() * .28f + middle * .06f)
                            .coerceIn(.25f, .60f),
                    )
                    break
                }
            }
        }
        return result.toList()
    }

    fun flecks(item: GalleryConstellation): List<ConstellationStyle.Mote> {
        item.geometryIndex?.let { return ConstellationFilaments.flecks(it) }
        val random = Random(FLECK_SEED xor item.latinName.hashCode())
        val result = ArrayList<ConstellationStyle.Mote>()

        item.links.forEach { (a, b) ->
            val start = item.points[a]
            val end = item.points[b]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = sqrt(dx * dx + dy * dy)
            if (!length.isFinite() || length <= .09f) return@forEach
            val sideX = -dy / length
            val sideY = dx / length
            val trim = maxOf(.08f, .035f / length).coerceAtMost(.40f)
            val count = ((length - .09f) * 70f).roundToInt().coerceIn(4, 32)
            val sides = List(count) { if (it < count / 2) -1f else 1f }.shuffled(random)
            sides.forEach { sign ->
                // Bounded rejection keeps crowded junctions clear of misleading extra stars.
                for (attempt in 0 until MAX_ATTEMPTS) {
                    val t = trim + random.nextFloat() * (1f - 2f * trim)
                    val width = if (random.nextFloat() < .13f) {
                        .08f + random.nextFloat() * .02f
                    } else {
                        .025f + random.nextFloat() * .055f
                    }
                    val depth = (random.nextFloat() + random.nextFloat() - 1f) *
                        (.042f + random.nextFloat() * .018f)
                    val point = Vec3(
                        start.x + dx * t + sideX * sign * width,
                        start.y + dy * t + sideY * sign * width,
                        depth,
                    )
                    if (!clearOfAnchors(point, item.points, .045f)) continue
                    val sparkle = random.nextFloat()
                    val size = random.nextFloat()
                    result += ConstellationStyle.Mote(
                        position = point,
                        intensity = .25f + .35f * sparkle * sparkle,
                        scale = .22f + .24f * size * size,
                    )
                    break
                }
            }
        }
        return result.toList()
    }

    private fun clearOfAnchors(point: Vec3, anchors: List<GalleryPoint>, clearance: Float): Boolean =
        anchors.all { anchor ->
            val dx = point.x - anchor.x
            val dy = point.y - anchor.y
            dx * dx + dy * dy >= clearance * clearance
        }

    private fun smoothstep(low: Float, high: Float, value: Float): Float {
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val MOTE_SEED = 0x474D4F54
    private const val FLECK_SEED = 0x47464C4B
    private const val MAX_ATTEMPTS = 32
}
