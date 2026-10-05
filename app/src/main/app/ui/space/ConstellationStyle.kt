package com.starglean.app.ui.space

/**
 * Restrained, renderer-independent visual weights for the five orbiting figures.
 * The strongest point anchors each figure; connecting light should remain secondary.
 * These are art-direction weights, not astronomical apparent magnitudes.
 */
object ConstellationStyle {
    data class Star(val intensity: Float, val scale: Float)

    data class Mote(val position: CelestialGeometry.Vec3, val intensity: Float, val scale: Float)

    data class Visibility(val stars: Float, val lines: Float)

    private val primary = Star(intensity = 1.28f, scale = 1.34f)
    private val secondary = Star(intensity = .94f, scale = 1.04f)
    private val softSecondary = Star(intensity = .86f, scale = .96f)
    private val quiet = Star(intensity = .66f, scale = .78f)
    private val faint = Star(intensity = .53f, scale = .68f)

    // Order and point indices follow CelestialGeometry.constellations exactly.
    private val figures = listOf(
        listOf(primary, secondary, faint, quiet, faint, softSecondary), // Cygnus: Deneb
        listOf(secondary, quiet, faint, quiet, softSecondary, quiet, faint, primary, faint), // Orion: Rigel
        listOf(primary, quiet, faint, secondary, quiet), // Lyra: Vega
        listOf(quiet, primary, secondary, faint, softSecondary), // Cassiopeia: Schedar
        listOf(softSecondary, quiet, primary, faint, faint, quiet, secondary), // Big Dipper: Alioth
    )

    /** An unknown point stays unobtrusive instead of becoming a new visual anchor. */
    fun star(constellationIndex: Int, starIndex: Int): Star =
        figures.getOrNull(constellationIndex)?.getOrNull(starIndex) ?: faint

    /**
     * [distance] is camera-to-figure distance in world units; [facing] is the absolute
     * dot product of its plane normal and viewing direction (0 = edge-on, 1 = frontal).
     * Smoothstep removes hard thresholds as a figure passes sideways or into the back.
     * Stars remain readable while edge-on lines become only a trace of light.
     */
    fun visibility(distance: Float, facing: Float): Visibility {
        // Clamp infinities; invalid geometry should quietly recede, not flash brightly.
        val safeDistance = if (distance.isNaN()) FAR_DISTANCE else distance.coerceIn(NEAR_DISTANCE, FAR_DISTANCE)
        val safeFacing = if (facing.isNaN()) 0f else facing.coerceIn(0f, 1f)
        val nearness = 1f - smoothstep(NEAR_DISTANCE, FAR_DISTANCE, safeDistance)
        val front = smoothstep(.10f, .85f, safeFacing)
        return Visibility(
            stars = (.45f + .55f * nearness * (.90f + .10f * front)).coerceIn(.45f, 1f),
            lines = (.06f + .89f * front * (.32f + .68f * nearness)).coerceIn(.06f, .95f),
        )
    }

    private fun smoothstep(low: Float, high: Float, value: Float): Float {
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val NEAR_DISTANCE = 4.6f
    private const val FAR_DISTANCE = 8.2f
}
