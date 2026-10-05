package com.starglean.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiarySnapshotIntegrationTest {
    @Test fun weatherPreviewFixturesCoverEveryConditionWithoutClaimingLiveObservations() {
        assertEquals(WeatherKind.entries.toSet() - WeatherKind.Unknown,
            DemoContent.discoveryDiaries.map { it.weatherSnapshot.kind }.toSet())
        assertEquals(5, DemoContent.discoveryDiaries.size)
        assertEquals(5, DemoContent.discoveryDiaries.map { it.id }.distinct().size)
        assertEquals(5, DemoContent.discoveryDiaries.map { it.title }.distinct().size)
        assertEquals(5, DemoContent.discoveryDiaries.map { it.weatherSnapshot.kind }.distinct().size)
        DemoContent.discoveryDiaries.forEach { diary ->
            assertTrue(diary.weatherSnapshot.isDemo)
            assertEquals(diary.weatherSnapshot.label, diary.weather)
            if (diary.weatherSnapshot.kind != WeatherKind.Unknown) assertTrue(!diary.weatherSnapshot.capturedAt.isNullOrBlank())
        }
    }
    @Test fun readingSnapshotIsNotChangedBySelectingAnotherPreview() {
        var selected = DemoContent.discoveryDiaries.first()
        val reading = selected
        val originalWeather = reading.weatherSnapshot
        selected = DemoContent.discoveryDiaries.first { it.weatherSnapshot.kind == WeatherKind.Rain }
        assertEquals(WeatherKind.Rain, selected.weatherSnapshot.kind)
        assertEquals(WeatherKind.Clear, reading.weatherSnapshot.kind)
        assertEquals(originalWeather, reading.weatherSnapshot)
        assertEquals("2026-09-12T14:10:00+10:00", reading.weatherSnapshot.capturedAt)
    }

    @Test fun previewOptionsAreExactlyTheFiveRequestedCategoriesInOrder() {
        val labels = listOf("Clear", "Cloudy", "Rain", "Snow", "Fog")
        assertEquals(labels,
            DemoContent.discoveryDiaries.map { it.weatherSnapshot.kind.label })
        assertEquals(1, DemoContent.discoveryDiaries.count { it.weatherSnapshot.kind == WeatherKind.Rain })
    }
}
