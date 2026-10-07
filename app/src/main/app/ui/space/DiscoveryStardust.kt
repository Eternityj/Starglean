package com.starglean.app.ui.space

import kotlin.random.Random

/**
 * An irregular shallow cloud around the approaching discovery star, not a flare
 * cross, orbit ring, spiral, or radial ray fan. The drawing layer chooses its size.
 * The XY opening protects the main star even when a mote has a nonzero depth.
 */
object DiscoveryStardust {
    data class Mote(
        val x: Float,
        val y: Float,
        val z: Float,
        val radius: Float,
        val intensity: Float,
        /** 0 is cool silver, 1 is warm champagne. */
        val warmth: Float,
        val glint: Boolean,
    )

    val motes: List<Mote> = createMotes()

    internal fun createMotes(): List<Mote> {
        val random = Random(0x44534455)
        val result = ArrayList<Mote>(240)

        fun between(low: Float, high: Float): Float = low + random.nextFloat() * (high - low)
        fun noise(): Float = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) / 1.5f

        repeat(240) { index ->
            val glint = index >= 180
            // Rejection creates only an opening around the core, not a constant
            // radius shell. Independent positions never form mirrored ray pairs.
            var x = 0f
            var y = 0f
            var accepted = false
            for (attempt in 0 until 64) {
                x = (noise() * 1.11f + .035f).coerceIn(-1f, 1f)
                y = (noise() * .78f - x * .18f + .024f).coerceIn(-1f, 1f)
                if (x * x + y * y >= .14f * .14f) {
                    accepted = true
                    break
                }
            }
            if (!accepted) {
                // Bounded fallback remains irregular and clear of the star.
                x = between(.22f, .87f)
                y = between(-.25f, .31f)
            }
            val z = (noise() * .33f + x * .019f).coerceIn(-.35f, .35f)
            result += Mote(
                x = x,
                y = y,
                z = z,
                radius = if (glint) between(.2f, .5f) else between(.4f, 1.3f),
                intensity = if (glint) between(.25f, .6f) else between(.08f, .25f),
                warmth = between(.25f, .8f),
                glint = glint,
            )
        }
        return result.toList()
    }
}
