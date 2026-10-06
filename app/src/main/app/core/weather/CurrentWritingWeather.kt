package com.starglean.app.core.weather

import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.WeatherKind
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

val OPEN_METEO_ATTRIBUTION: String get() = "Weather data · Open-Meteo"
const val OPEN_METEO_ATTRIBUTION_URL = "https://open-meteo.com/"
const val OPEN_METEO_LICENCE_URL = "https://open-meteo.com/en/licence"

/** Model-based current conditions, not a sensor measurement or a whole-day forecast. */
data class CurrentWritingWeather(
    val snapshot: DiaryWeatherSnapshot,
    val fetchedAt: Instant,
    val observedAt: Instant,
    val zoneId: ZoneId,
    val attribution: String = OPEN_METEO_ATTRIBUTION,
    /** Optional validated display metadata; never part of the saved diary weather snapshot. */
    val placeName: String? = null,
    /** Address detail declared by the source; never inferred from the display string. */
    val placeGranularity: WeatherPlaceGranularity = WeatherPlaceGranularity.Unknown,
    /** Estimate for the weather lookup's fix, not the reader's current position. Never saved in a diary. */
    val locationAccuracyMeters: Float? = null,
) {
    fun isFresh(at: Instant = Instant.now()): Boolean = isCurrentWeatherTimeFresh(observedAt, at)

    // Avoid implicit data-class logs exposing either a reverse-geocoded name or its timezone.
    override fun toString(): String = "CurrentWritingWeather(redacted)"
}

enum class WeatherFetchError {
    InvalidLocation,
    Network,
    Timeout,
    ServiceUnavailable,
    InvalidResponse,
    StaleObservation,
    UnsupportedWeather,
}

/** Safe, stable UI errors: never includes coordinates, request URLs, or server response bodies. */
class WeatherFetchException(val code: WeatherFetchError) : Exception(
    when (code) {
        WeatherFetchError.InvalidLocation -> "Your location is unavailable right now."
        WeatherFetchError.Network -> "Cannot reach the weather service. Check your connection and try again."
        WeatherFetchError.Timeout -> "The weather request timed out. Please try again."
        WeatherFetchError.ServiceUnavailable -> "The weather service is temporarily unavailable. Please try again later."
        WeatherFetchError.InvalidResponse -> "Weather data is incomplete. A quiet background is shown instead."
        WeatherFetchError.StaleObservation -> "The weather is out of date. Refresh to try again."
        WeatherFetchError.UnsupportedWeather -> "This weather condition is unavailable. A quiet background is shown instead."
    },
)

// A current result may be at most 90 minutes old or 15 minutes ahead of the device clock.
// No date-only match, previous day's fixture, hourly forecast, or cached fallback is substituted.
internal val CURRENT_WEATHER_MAX_AGE: Duration = Duration.ofMinutes(90)
internal val CURRENT_WEATHER_FUTURE_TOLERANCE: Duration = Duration.ofMinutes(15)

internal fun isCurrentWeatherTimeFresh(observedAt: Instant, now: Instant): Boolean {
    val age = Duration.between(observedAt, now)
    return age <= CURRENT_WEATHER_MAX_AGE && age >= CURRENT_WEATHER_FUTURE_TOLERANCE.negated()
}

/** Nullable on purpose: absent/null/wrongly typed JSON must fail validation, not default to code 0. */
internal data class OpenMeteoCurrentPayload(
    val weatherCode: Double?,
    val isDay: Double?,
    val temperatureC: Double?,
    val observedEpochSeconds: Double?,
    val timezone: String?,
    val temperatureUnit: String?,
    val timeUnit: String?,
)

internal fun mapOpenMeteoCurrent(payload: OpenMeteoCurrentPayload, now: Instant): CurrentWritingWeather {
    if (payload.temperatureUnit != "°C" || payload.timeUnit != "unixtime") invalidWeatherResponse()
    val code = payload.weatherCode.requireWholeNumber().let {
        if (it !in 0L..Int.MAX_VALUE.toLong()) invalidWeatherResponse()
        it.toInt()
    }
    val daylight = when (payload.isDay.requireWholeNumber()) {
        0L -> Daylight.Night
        1L -> Daylight.Day
        else -> invalidWeatherResponse()
    }
    val temperature = payload.temperatureC?.takeIf { it.isFinite() && it in -100.0..65.0 }
        ?: invalidWeatherResponse()
    val observedAt = try {
        Instant.ofEpochSecond(payload.observedEpochSeconds.requireWholeNumber())
    } catch (_: DateTimeException) {
        invalidWeatherResponse()
    }
    val zoneId = try {
        ZoneId.of(payload.timezone?.takeIf { it.isNotBlank() } ?: invalidWeatherResponse())
    } catch (_: DateTimeException) {
        invalidWeatherResponse()
    }
    if (!isCurrentWeatherTimeFresh(observedAt, now)) {
        throw WeatherFetchException(WeatherFetchError.StaleObservation)
    }
    val kind = weatherKindFromWmo(code)
    if (kind == WeatherKind.Unknown) throw WeatherFetchException(WeatherFetchError.UnsupportedWeather)
    return CurrentWritingWeather(
        snapshot = DiaryWeatherSnapshot(
            kind = kind,
            daylight = daylight,
            temperatureC = temperature.roundToInt(),
            capturedAt = observedAt.toString(),
            isDemo = false,
        ),
        fetchedAt = now,
        observedAt = observedAt,
        zoneId = zoneId,
    )
}

/** App categories deliberately merge WMO rain strengths and partly-cloudy conditions. */
internal fun weatherKindFromWmo(code: Int): WeatherKind = when (code) {
    0, 1 -> WeatherKind.Clear
    2, 3 -> WeatherKind.Cloudy
    45, 48 -> WeatherKind.Fog
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99 -> WeatherKind.Rain
    71, 73, 75, 77, 85, 86 -> WeatherKind.Snow
    else -> WeatherKind.Unknown
}

private fun Double?.requireWholeNumber(): Long {
    val value = this ?: invalidWeatherResponse()
    // Keep conversion exact and reject overflow, decimal codes, booleans, and NaN defaults.
    if (!value.isFinite() || value < Long.MIN_VALUE.toDouble() || value >= Long.MAX_VALUE.toDouble()) {
        invalidWeatherResponse()
    }
    val whole = value.toLong()
    if (whole.toDouble() != value) invalidWeatherResponse()
    return whole
}

internal fun invalidWeatherResponse(): Nothing = throw WeatherFetchException(WeatherFetchError.InvalidResponse)
