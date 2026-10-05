package com.starglean.app.ui.space

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * World-space constellation geometry. The planet is centred at the origin with radius 1.
 * Angles are radians; +Y is up, and the default camera looks from +Z toward the origin.
 *
 * Each constellation is a rigid figure on a plane tangent to its orbit. Rotating that
 * plane with its centre produces perspective foreshortening instead of a camera-facing
 * billboard. Occlusion is intentionally left to the renderer's depth buffer.
 */
object CelestialGeometry {
    const val ORBIT_RADIUS = 2f

    data class Vec3(val x: Float, val y: Float, val z: Float)

    data class Constellation(
        val name: String,
        /** Local tangent-plane points, centred around (0, 0, 0), with +Y pointing up. */
        val points: List<Vec3>,
        val links: List<Pair<Int, Int>>,
        val inclination: Float,
        val phaseOffset: Float,
    )

    /** Stable order: Cygnus, Orion, Lyra, Cassiopeia, and the Big Dipper asterism. */
    val constellations = listOf(
        Constellation(
            name = "Cygnus",
            points = listOf(
                Vec3(.06f, .51f, 0f),   // Deneb
                Vec3(0f, .10f, 0f),    // Sadr, the centre of the Northern Cross
                Vec3(-.38f, .02f, 0f),
                Vec3(.38f, .20f, 0f),
                Vec3(-.08f, -.23f, 0f),
                Vec3(-.18f, -.51f, 0f), // Albireo
            ),
            links = listOf(0 to 1, 2 to 1, 1 to 3, 1 to 4, 4 to 5),
            inclination = .64f,
            phaseOffset = .25f,
        ),
        Constellation(
            name = "Orion",
            points = listOf(
                Vec3(-.31f, .34f, 0f), // Betelgeuse
                Vec3(.28f, .42f, 0f),  // Bellatrix
                Vec3(-.02f, .58f, 0f), // Meissa
                Vec3(-.13f, .03f, 0f), // The three belt stars
                Vec3(0f, 0f, 0f),
                Vec3(.13f, -.03f, 0f),
                Vec3(-.26f, -.46f, 0f), // Saiph
                Vec3(.32f, -.40f, 0f),  // Rigel
                Vec3(.015f, -.22f, 0f), // Sword
            ),
            links = listOf(
                0 to 2, 2 to 1, 0 to 3, 1 to 5,
                3 to 4, 4 to 5, 3 to 6, 5 to 7, 4 to 8,
            ),
            inclination = -.80f,
            phaseOffset = 1.51f,
        ),
        Constellation(
            name = "Lyra",
            points = listOf(
                Vec3(-.23f, .39f, 0f), // Vega above the small parallelogram
                Vec3(-.08f, .16f, 0f),
                Vec3(.29f, .09f, 0f),
                Vec3(.18f, -.30f, 0f),
                Vec3(-.19f, -.22f, 0f),
            ),
            links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 1),
            inclination = .98f,
            phaseOffset = 2.77f,
        ),
        Constellation(
            name = "Cassiopeia",
            points = listOf(
                Vec3(-.47f, .25f, 0f),
                Vec3(-.23f, -.21f, 0f),
                Vec3(0f, .10f, 0f),
                Vec3(.24f, -.17f, 0f),
                Vec3(.47f, .31f, 0f),
            ),
            links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4),
            inclination = -.40f,
            phaseOffset = 4.03f,
        ),
        Constellation(
            name = "Big Dipper",
            points = listOf(
                Vec3(-.56f, -.18f, 0f), // Handle, from Alkaid toward the bowl
                Vec3(-.35f, .03f, 0f),
                Vec3(-.09f, .09f, 0f),
                Vec3(.11f, .04f, 0f),
                Vec3(.17f, -.27f, 0f),
                Vec3(.46f, -.20f, 0f),
                Vec3(.43f, .19f, 0f),
            ),
            links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 3),
            inclination = .22f,
            phaseOffset = 5.29f,
        ),
    )

    fun constellationPoints(index: Int, orbitPhase: Float): List<Vec3> {
        val constellation = constellations[index]
        val angle = orbitPhase + constellation.phaseOffset
        return constellation.points.map { point ->
            rotateX(
                rotateY(Vec3(point.x, point.y, ORBIT_RADIUS + point.z), angle),
                constellation.inclination,
            )
        }
    }

    fun constellationCenter(index: Int, orbitPhase: Float): Vec3 {
        val constellation = constellations[index]
        return rotateX(
            rotateY(Vec3(0f, 0f, ORBIT_RADIUS), orbitPhase + constellation.phaseOffset),
            constellation.inclination,
        )
    }

    fun rotateY(point: Vec3, angle: Float): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec3(point.x * c + point.z * s, point.y, -point.x * s + point.z * c)
    }

    fun rotateX(point: Vec3, angle: Float): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec3(point.x, point.y * c - point.z * s, point.y * s + point.z * c)
    }

    /**
     * Whether the origin-centred planet blocks the segment from camera to point.
     * Supply the point after any renderer-wide scene rotation; hidden stars must not
     * steal taps from a visible star. A point on the near surface remains visible.
     */
    fun isOccluded(
        point: Vec3,
        camera: Vec3 = Vec3(0f, 0f, 6.4f),
        radius: Float = 1f,
    ): Boolean {
        val dx = point.x - camera.x
        val dy = point.y - camera.y
        val dz = point.z - camera.z
        val a = dx * dx + dy * dy + dz * dz
        if (a < .000001f) return false
        val b = 2f * (camera.x * dx + camera.y * dy + camera.z * dz)
        val c = camera.x * camera.x + camera.y * camera.y + camera.z * camera.z - radius * radius
        val discriminant = b * b - 4f * a * c
        if (discriminant < 0f) return false
        val root = sqrt(discriminant)
        val near = (-b - root) / (2f * a)
        val far = (-b + root) / (2f * a)
        return far > .00001f && near < 1f - .00001f
    }
}
