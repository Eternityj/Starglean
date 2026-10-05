package com.starglean.app.ui.space

import com.starglean.app.ui.space.CelestialGeometry.Constellation
import com.starglean.app.ui.space.CelestialGeometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Shared starlight material, fitted to each original star chart.
 * All positions remain local 3D data; neither dust nor flecks add diary nodes.
 * Every constellation uses the same generator, including Cygnus.
 */
object ConstellationFilaments {
    private data class Cloud(val motes: List<ConstellationStyle.Mote>, val flecks: List<ConstellationStyle.Mote>)

    private val clouds = CelestialGeometry.constellations.indices.map { index ->
        Cloud(createMotes(index), createFlecks(index))
    }

    fun motes(index: Int): List<ConstellationStyle.Mote> = clouds.getOrNull(index)?.motes.orEmpty()

    fun flecks(index: Int): List<ConstellationStyle.Mote> = clouds.getOrNull(index)?.flecks.orEmpty()

    fun star(index: Int, starIndex: Int): ConstellationStyle.Star {
        val previous = ConstellationStyle.star(index, starIndex)
        if (CelestialGeometry.constellations.getOrNull(index)?.points?.getOrNull(starIndex) == null) {
            return previous
        }
        val prominence = ((previous.intensity - .53f) / .75f).coerceIn(0f, 1f)
        return ConstellationStyle.Star(
            intensity = (1.08f + .27f * prominence).coerceIn(1.08f, 1.35f),
            scale = (1.10f + .30f * prominence).coerceIn(1.10f, 1.40f),
        )
    }

    internal fun createMotes(index: Int): List<ConstellationStyle.Mote> {
        val figure = CelestialGeometry.constellations.getOrNull(index) ?: return emptyList()
        val random = Random(0x464D4F54 + index * 104729)
        val result = ArrayList<ConstellationStyle.Mote>()
        fun noise(): Float = random.nextFloat() + random.nextFloat() - 1f

        figure.links.forEach { (a, b) ->
            val start = figure.points[a]
            val end = figure.points[b]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = sqrt(dx * dx + dy * dy)
            if (!length.isFinite() || length <= .032f) return@forEach
            val sideX = -dy / length
            val sideY = dx / length
            val phase = random.nextFloat() * 2f * PI.toFloat()
            val trim = maxOf(.055f, .016f / length).coerceAtMost(.45f)
            // Density follows usable length. Orion's tiny belt edges receive
            // around 20 grains instead of a fixed long-segment budget.
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
                        start.z + (end.z - start.z) * t + depth,
                    )
                    if (!clearOfAnchors(point, figure, .015f)) continue
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

    internal fun createFlecks(index: Int): List<ConstellationStyle.Mote> {
        val figure = CelestialGeometry.constellations.getOrNull(index) ?: return emptyList()
        val random = Random(0x46464C4B + index * 104729)
        val result = ArrayList<ConstellationStyle.Mote>()

        figure.links.forEach { (a, b) ->
            val start = figure.points[a]
            val end = figure.points[b]
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
                // A dense junction may reject a glint. Dropping that glint after
                // bounded attempts is preferable to crowding an anchor or hanging.
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
                        start.z + (end.z - start.z) * t + depth,
                    )
                    if (!clearOfAnchors(point, figure, .045f)) continue
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

    private fun clearOfAnchors(point: Vec3, figure: Constellation, clearance: Float): Boolean =
        figure.points.all { anchor ->
            val dx = point.x - anchor.x
            val dy = point.y - anchor.y
            dx * dx + dy * dy >= clearance * clearance
        }

    private fun smoothstep(low: Float, high: Float, value: Float): Float {
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val MAX_ATTEMPTS = 32
}
