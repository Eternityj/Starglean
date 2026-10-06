package com.starglean.app.core.weather

import java.net.URL

/** Source-declared address detail. Unknown is retained for the Mac bridge. */
enum class WeatherPlaceGranularity { Street, Area, City, Region, Unknown }

/**
 * Exact validated coordinates for the user's explicitly authorized weather request.
 * This is request-scoped memory only: never log, persist or add this query to a diary.
 * Permission, purpose disclosure and fix quality are checked before construction.
 */
class WeatherQueryLocation private constructor(
    internal val latitude: Double,
    internal val longitude: Double,
    /** Optional reverse-geocoded display metadata, never an HTTP query parameter. */
    val placeName: String?,
    val placeGranularity: WeatherPlaceGranularity,
    /** Horizontal accuracy estimate of this weather fix, not a live proximity grant. */
    val accuracyMeters: Float?,
) {
    override fun toString(): String = "WeatherQueryLocation(redacted)"

    // Include display metadata so an updated name is not lost by state equality.
    // HTTP request identity is still determined solely by the exact coordinates.
    override fun equals(other: Any?): Boolean = other is WeatherQueryLocation &&
        latitude == other.latitude && longitude == other.longitude && placeName == other.placeName &&
        placeGranularity == other.placeGranularity &&
        accuracyMeters == other.accuracyMeters

    override fun hashCode(): Int {
        var result = latitude.hashCode()
        result = 31 * result + longitude.hashCode()
        result = 31 * result + (placeName?.hashCode() ?: 0)
        result = 31 * result + placeGranularity.hashCode()
        result = 31 * result + (accuracyMeters?.hashCode() ?: 0)
        return result
    }

    companion object {
        fun fromCoordinates(latitude: Double, longitude: Double, placeName: String? = null,
            accuracyMeters: Float? = null,
            placeGranularity: WeatherPlaceGranularity = WeatherPlaceGranularity.Unknown): WeatherQueryLocation {
            if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
                throw WeatherFetchException(WeatherFetchError.InvalidLocation)
            }
            val normalizedPlace = normalizeWeatherPlaceName(placeName)
            return WeatherQueryLocation(normalizeZero(latitude), normalizeZero(longitude), normalizedPlace,
                if (normalizedPlace == null) WeatherPlaceGranularity.Unknown else placeGranularity,
                accuracyMeters?.takeIf { it.isFinite() && it > 0f && it <= WEATHER_LOCATION_MAX_ACCURACY_METERS })
        }

        private fun normalizeZero(value: Double): Double = if (value == 0.0) 0.0 else value
    }
}

internal const val WEATHER_PLACE_NAME_MAX_CODE_POINTS = 100

/**
 * A missing or malformed optional name never makes otherwise valid weather fail.
 * Validate before trimming, so tabs, line breaks and invisible direction controls
 * cannot disappear into a seemingly valid label. Never invent or truncate a name.
 */
internal fun normalizeWeatherPlaceName(value: String?): String? {
    if (value == null) return null
    var index = 0
    while (index < value.length) {
        val first = value[index]
        if (Character.isLowSurrogate(first)) return null
        if (Character.isHighSurrogate(first) &&
            (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1]))) return null
        val codePoint = Character.codePointAt(value, index)
        when (Character.getType(codePoint)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(),
            Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> return null
        }
        index += Character.charCount(codePoint)
    }
    val trimmed = value.trim()
    if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length) > WEATHER_PLACE_NAME_MAX_CODE_POINTS) return null
    if (isCoordinateOnlyPlaceName(trimmed)) return null
    return trimmed
}

private val coordinateLabelWords = Regex(
    "latitude|longitude|north|south|east|west|degrees?|geo|lat|lng|lon|deg",
    RegexOption.IGNORE_CASE,
)
private const val COORDINATE_LABEL_CHARACTERS = "NnSsEeWw经經纬緯度东西東南北+-−.,，、;；:：°º˚'\"′″()[]{}=/"

/** Reject decimal/DMS coordinate-only fallback text, not names that merely contain digits. */
private fun isCoordinateOnlyPlaceName(value: String): Boolean {
    if (value.none(Char::isDigit)) return false
    val withoutLabels = coordinateLabelWords.replace(value, "")
    return withoutLabels.all { it.isDigit() || it.isWhitespace() || it in COORDINATE_LABEL_CHARACTERS }
}

internal fun openMeteoCurrentUrl(location: WeatherQueryLocation): URL = URL(
    "https://api.open-meteo.com/v1/forecast" +
        // Double.toString is locale-independent and round-trips without rounding the fix.
        "?latitude=${location.latitude}" +
        "&longitude=${location.longitude}" +
        "&current=temperature_2m,weather_code,is_day" +
        "&temperature_unit=celsius&timeformat=unixtime&timezone=auto",
)
