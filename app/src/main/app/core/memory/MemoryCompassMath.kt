package com.starglean.app.core.memory

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle approximation for recollection, never turn-by-turn navigation. */
object MemoryCompassMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8
    private val directionNames = arrayOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")

    fun distanceMeters(from: MemoryLocationFix, to: MemoryLocationFix): Double {
        val latitude1 = Math.toRadians(from.latitude)
        val latitude2 = Math.toRadians(to.latitude)
        val halfLatitude = sin((latitude2 - latitude1) / 2.0)
        val halfLongitude = sin(Math.toRadians(to.longitude - from.longitude) / 2.0)
        val haversine = (halfLatitude * halfLatitude + cos(latitude1) * cos(latitude2) *
            halfLongitude * halfLongitude).coerceIn(0.0, 1.0)
        return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(haversine), sqrt(1.0 - haversine))
    }

    /** Null means the uncertainty overlaps the destination, or no unique direction exists. */
    fun bearingDegrees(from: MemoryLocationFix, to: MemoryLocationFix): Double? {
        val distance = distanceMeters(from, to)
        val combinedAccuracy = from.accuracyMeters.toDouble() + to.accuracyMeters.toDouble()
        val uncertainty = maxOf(30.0, combinedAccuracy)
        // Non-overlap alone is not enough: nearly touching uncertainty circles can imply a
        // wildly uncertain direction. Four radii caps this estimate at about 14.5 degrees.
        val distanceFromAntipode = PI * EARTH_RADIUS_METERS - distance
        if (distance <= uncertainty || distance < combinedAccuracy * 4.0 ||
            distanceFromAntipode <= uncertainty || distanceFromAntipode < combinedAccuracy * 4.0
        ) return null
        val latitude1 = Math.toRadians(from.latitude)
        val latitude2 = Math.toRadians(to.latitude)
        // A local north-oriented azimuth is undefined at the starting geographic pole.
        if (abs(cos(latitude1)) < 1e-12) return null
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val east = sin(longitudeDelta) * cos(latitude2)
        val north = cos(latitude1) * sin(latitude2) - sin(latitude1) * cos(latitude2) * cos(longitudeDelta)
        if (!east.isFinite() || !north.isFinite() || hypot(east, north) < 1e-12) return null
        return normalizeDegrees(Math.toDegrees(atan2(east, north)))
    }

    /** Signed shortest arc in [-180, 180); normalizing first avoids subtraction overflow. */
    fun signedDeltaDegrees(target: Double, heading: Double): Double =
        (normalizeDegrees(target) - normalizeDegrees(heading) + 540.0) % 360.0 - 180.0

    fun directionName(bearing: Double): String {
        val sector = floor((normalizeDegrees(bearing) + 22.5) / 45.0).toInt() % 8
        return directionNames[sector]
    }

    /** Full gathering within 10 degrees, smoothly fading to zero at 30 degrees. */
    fun alignmentStrength(delta: Double): Float {
        if (!delta.isFinite()) return 0f
        val angle = abs(signedDeltaDegrees(delta, 0.0))
        val progress = ((30.0 - angle) / 20.0).coerceIn(0.0, 1.0)
        return (progress * progress * (3.0 - 2.0 * progress)).toFloat()
    }

    fun normalizeDegrees(degrees: Double): Double {
        require(degrees.isFinite()) { "Invalid heading angle." }
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        return if (normalized == 0.0) 0.0 else normalized
    }
}
