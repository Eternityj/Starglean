package com.starglean.app.core.weather

/** Require a precise fix; sending exact coordinates for weather has a separate purpose disclosure. */
internal const val WEATHER_LOCATION_MAX_AGE_NANOS = 120_000_000_000L
internal const val WEATHER_LOCATION_FUTURE_TOLERANCE_NANOS = 5_000_000_000L
internal const val WEATHER_LOCATION_MAX_ACCURACY_METERS = 100f

enum class WeatherLocationFailure {
    PermissionRequired,
    ServicesDisabled,
    Unavailable,
    Timeout,
    Unreliable,
}

/** No provider messages or coordinates are retained in user-facing errors. */
class WeatherLocationException(val code: WeatherLocationFailure) : Exception(
    when (code) {
        WeatherLocationFailure.PermissionRequired -> "Allow precise location to get local weather."
        WeatherLocationFailure.ServicesDisabled -> "Location services are off. You can still write your diary."
        WeatherLocationFailure.Unavailable -> "Your location is unavailable right now. Please try again later."
        WeatherLocationFailure.Timeout -> "Finding your location took too long. Please try again."
        WeatherLocationFailure.Unreliable -> "A recent, accurate location is not available yet. Please try again."
    },
)

/**
 * Pure validation so JVM tests do not need a mocked Android Location. Monotonic
 * timestamps avoid interpreting a wall-clock adjustment as a recent location.
 */
internal fun isWeatherLocationReliable(
    latitude: Double,
    longitude: Double,
    hasAccuracy: Boolean,
    accuracyMeters: Float,
    fixElapsedRealtimeNanos: Long,
    nowElapsedRealtimeNanos: Long,
): Boolean {
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return false
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return false
    if (!hasAccuracy || !accuracyMeters.isFinite()) return false
    if (accuracyMeters <= 0f || accuracyMeters > WEATHER_LOCATION_MAX_ACCURACY_METERS) return false
    if (fixElapsedRealtimeNanos < 0L || nowElapsedRealtimeNanos < 0L) return false

    // Both values are non-negative, so subtraction cannot overflow a signed Long.
    val ageNanos = nowElapsedRealtimeNanos - fixElapsedRealtimeNanos
    return ageNanos in -WEATHER_LOCATION_FUTURE_TOLERANCE_NANOS..WEATHER_LOCATION_MAX_AGE_NANOS
}
