package com.starglean.app.core.model

/** The author's saved conditions, not the reader's current weather or a live forecast. */
enum class WeatherKind(val label: String) {
    Clear("Clear"),
    Cloudy("Cloudy"),
    Rain("Rain"),
    Snow("Snow"),
    Fog("Fog"),
    // Missing data is an internal fallback, not a sixth selectable weather condition.
    Unknown("Weather not recorded"),
}

/** Time of day is independent of the weather; a rainy diary can also have been written at night. */
enum class Daylight { Day, Dusk, Night }

data class DiaryWeatherSnapshot(
    val kind: WeatherKind,
    val daylight: Daylight,
    val temperatureC: Int? = null,
    val capturedAt: String? = null,
    val isDemo: Boolean = false,
) {
    val label: String
        get() {
            // Unknown has a default Day solely to keep the model total, not as observed weather.
            if (kind == WeatherKind.Unknown) return "Weather not recorded"
            val light = when (daylight) {
                Daylight.Day -> "Day"
                Daylight.Dusk -> "Dusk"
                Daylight.Night -> "Night"
            }
            return listOfNotNull(kind.label, light, temperatureC?.let { "$it°C" }).joinToString(" · ")
        }

    val sourceLabel: String
        get() = when {
            kind == WeatherKind.Unknown -> "Weather unavailable"
            isDemo -> "Demo · Sample weather"
            else -> "Weather saved by the author"
        }

    companion object {
        val Unavailable = DiaryWeatherSnapshot(WeatherKind.Unknown, Daylight.Day)
    }
}
