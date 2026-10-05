package com.starglean.app.core.memory

/** Local-only location data. Callers must not place this value in logs or network requests. */
data class MemoryLocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAtEpochMillis: Long,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude." }
        require(accuracyMeters.isFinite() && accuracyMeters > 0f) { "Invalid location accuracy." }
        require(capturedAtEpochMillis in 1L..253_402_300_799_999L) { "Invalid location timestamp." }
    }

    override fun toString(): String = "MemoryLocationFix(location=redacted, accuracyMeters=$accuracyMeters, capturedAtEpochMillis=$capturedAtEpochMillis)"
}

enum class MemoryLocationFailure {
    PermissionRequired, ServicesDisabled, Unavailable, Timeout, Unreliable,
}

/** Fixed, safe messages only; neither coordinates nor the provider's exception are retained. */
class MemoryLocationException(val code: MemoryLocationFailure) : Exception(
    when (code) {
        MemoryLocationFailure.PermissionRequired -> "Allow precise location to find the direction of your memory."
        MemoryLocationFailure.ServicesDisabled -> "Location services are off. The direction of your memory is unavailable."
        MemoryLocationFailure.Unavailable -> "Your current location is unavailable. Please try again later."
        MemoryLocationFailure.Timeout -> "Finding your location took too long. Please try again."
        MemoryLocationFailure.Unreliable -> "A recent, accurate location is unavailable. Try again in an open area."
    },
)

internal const val MEMORY_LOCATION_MAX_AGE_NANOS = 45_000_000_000L
internal const val MEMORY_LOCATION_FUTURE_TOLERANCE_NANOS = 5_000_000_000L
internal const val MEMORY_LOCATION_MAX_ACCURACY_METERS = 100f

/** Monotonic age prevents an old fix becoming recent after a wall-clock adjustment. */
internal fun isMemoryLocationReliable(
    latitude: Double,
    longitude: Double,
    hasAccuracy: Boolean,
    accuracyMeters: Float,
    capturedAtEpochMillis: Long,
    nowEpochMillis: Long,
    fixElapsedRealtimeNanos: Long,
    nowElapsedRealtimeNanos: Long,
): Boolean {
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return false
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return false
    if (!hasAccuracy || !accuracyMeters.isFinite() || accuracyMeters <= 0f ||
        accuracyMeters > MEMORY_LOCATION_MAX_ACCURACY_METERS
    ) return false
    if (capturedAtEpochMillis !in 1L..253_402_300_799_999L || nowEpochMillis <= 0L) return false
    // Non-negative operands make both subtractions overflow-safe.
    val wallAgeMillis = nowEpochMillis - capturedAtEpochMillis
    if (wallAgeMillis !in -5_000L..45_000L) return false
    if (fixElapsedRealtimeNanos <= 0L || nowElapsedRealtimeNanos <= 0L) return false
    val ageNanos = nowElapsedRealtimeNanos - fixElapsedRealtimeNanos
    return ageNanos in -MEMORY_LOCATION_FUTURE_TOLERANCE_NANOS..MEMORY_LOCATION_MAX_AGE_NANOS
}
