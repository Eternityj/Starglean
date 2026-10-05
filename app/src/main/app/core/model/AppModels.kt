package com.starglean.app.core.model

import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.DiaryPhotoRules

enum class AppDestination(val label: String) {
    Planet("Planet"),
    Discover("Discover"),
    Write("Write"),
    Constellations("My constellations"),
}

enum class DiscoveryUiState {
    Idle,
    Requesting,
    Revealing,
    Reading,
    Empty,
    Exhausted,
    Unavailable,
}

enum class ReadingEligibility {
    IN_RANGE,
    UNKNOWN,
    OUT_OF_RANGE,
    ENDED,
}

enum class PaperStyle(val label: String) {
    WarmWhite("Warm White"),
    Kraft("Vintage Kraft"),
    Midnight("Midnight Blue"),
}

enum class DiaryFont(val label: String) {
    Clear("Sans Serif"),
    Handwritten("Handwritten"),
    Serif("Book Serif"),
}

enum class DiaryVisibility(val label: String) {
    Public("Public"),
    Private("Private"),
}

data class DiaryDemo(
    val id: String,
    val title: String?,
    val body: String,
    val date: String,
    val weatherSnapshot: DiaryWeatherSnapshot,
    val blurredPlace: String,
    val paper: PaperStyle,
    val font: DiaryFont,
    val photos: List<DiaryPhoto> = emptyList(),
) {
    init { DiaryPhotoRules.validateAttachments(photos) }
    val weather: String get() = weatherSnapshot.label
}

object DemoContent {
    val strangerDiary = DiaryDemo(
        id = "demo-stranger-1",
        title = "Sunlight on the Page",
        body = "I sat by the window reading as the sunlight slowly crossed the page. Nothing remarkable happened today, but this small pocket of quiet is something I want to keep.",
        date = "12 Sep · 2:10 PM",
        weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Clear, Daylight.Day, 22, "2026-09-12T14:10:00+10:00", isDemo = true),
        blurredPlace = "Northern Melbourne",
        paper = PaperStyle.WarmWhite,
        font = DiaryFont.Serif,
    )

    val ownDiary = DiaryDemo(
        id = "demo-own-1",
        title = "Before the Library Closed",
        body = "When the last row of lights went dark, a trace of blue still lingered beyond the window. I can leave the unfinished pages for tomorrow. I did enough today.",
        date = "8 Sep · 9:06 PM",
        weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Cloudy, Daylight.Night, null, "2026-09-08T21:06:00+10:00", isDemo = true),
        blurredPlace = "Near campus",
        paper = PaperStyle.Midnight,
        font = DiaryFont.Clear,
    )

    // Explicit sample snapshots, never observations of current weather or real nearby diaries.
    val discoveryDiaries = listOf(
        strangerDiary,
        strangerDiary.copy(id = "demo-cloudy", title = "Watching a Cloud Drift By", date = "12 Sep · 3:20 PM",
            body = "The sky was a soft grey-blue. With nowhere to rush to, I stopped on the way home and watched a cloud drift over the rooftops. For a little while, I let my worries drift with it.",
            weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Cloudy, Daylight.Day, 15, "2026-09-12T15:20:00+10:00", true)),
        strangerDiary.copy(id = "demo-rain", title = "The Rain Said Goodnight", date = "12 Sep · 9:05 PM",
            body = "Rain tapped softly against the window, and just one lamp lit the room. I left the words I couldn't say today here on the page. If you happen to find them, I hope you sleep well tonight too.",
            weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Rain, Daylight.Night, 12, "2026-09-12T21:05:00+10:00", true)),
        strangerDiary.copy(id = "demo-snow", title = "The World Grew Quiet", date = "12 Sep · 7:16 PM",
            body = "Snow was still falling when I stepped outside. Under the streetlight, every flake found its own way down. I pulled my scarf a little closer and realised how lovely this quiet walk home could be.",
            weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Snow, Daylight.Night, -2, "2026-09-12T19:16:00+10:00", true)),
        strangerDiary.copy(id = "demo-fog", title = "There Is Time to Go Slowly", date = "12 Sep · 7:35 AM",
            body = "Morning fog hid the distant trees, leaving only a short stretch of path ahead. I stopped trying to see so far into the future and paid attention to the next few steps. Today can begin slowly.",
            weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Fog, Daylight.Day, 8, "2026-09-12T07:35:00+10:00", true)),
    )

}
