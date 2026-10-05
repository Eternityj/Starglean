package com.starglean.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryWeatherTest {
    private fun expected(english: String, chinese: String) = english

    @Test
    fun savedWeatherLabelIncludesConditionsDaylightAndRecordedTemperature() {
        val snapshot = DiaryWeatherSnapshot(WeatherKind.Rain, Daylight.Night, 16, "2026-09-12T21:18:00+08:00")
        assertEquals(expected("Rain · Night · 16°C", "雨天 · 夜晚 · 16°C"), snapshot.label)
        assertEquals(expected("Weather saved by the author", "作者保存时的天气"), snapshot.sourceLabel)
    }

    @Test
    fun missingTemperatureDoesNotBecomeAnInventedValue() {
        val snapshot = DiaryWeatherSnapshot(WeatherKind.Cloudy, Daylight.Day)
        assertEquals(expected("Cloudy · Day", "阴天 · 白天"), snapshot.label)
        assertFalse(snapshot.label.contains("°C"))
        assertNull(snapshot.temperatureC)
        assertNull(snapshot.capturedAt)
    }

    @Test
    fun unavailableWeatherDoesNotPresentDefaultDayAsAnObservation() {
        val snapshot = DiaryWeatherSnapshot.Unavailable
        assertEquals(WeatherKind.Unknown, snapshot.kind)
        assertEquals(expected("Weather not recorded", "未记录天气"), snapshot.label)
        assertEquals(expected("Weather unavailable", "天气暂不可用"), snapshot.sourceLabel)
        assertNull(snapshot.temperatureC)
        assertNull(snapshot.capturedAt)
        assertFalse(snapshot.isDemo)
        assertFalse(snapshot.label.contains(expected("Day", "白天")))
    }

    @Test
    fun demoSnapshotsExplicitlyDiscloseTheirSource() {
        val snapshot = DiaryWeatherSnapshot(WeatherKind.Clear, Daylight.Dusk, 23, isDemo = true)
        assertEquals(expected("Clear · Dusk · 23°C", "晴天 · 黄昏 · 23°C"), snapshot.label)
        assertEquals(expected("Demo · Sample weather", "演示 · 示例天气"), snapshot.sourceLabel)
        assertFalse(snapshot.sourceLabel.contains(expected("saved by the author", "作者保存时")))
    }

    @Test
    fun unknownDemoStillDoesNotInventWeather() {
        val snapshot = DiaryWeatherSnapshot(WeatherKind.Unknown, Daylight.Night, isDemo = true)
        assertEquals(expected("Weather not recorded", "未记录天气"), snapshot.label)
        assertEquals(expected("Weather unavailable", "天气暂不可用"), snapshot.sourceLabel)
    }

    @Test
    fun daylightIsIndependentOfWeatherKind() {
        WeatherKind.entries.filter { it != WeatherKind.Unknown }.forEach { kind ->
            val day = DiaryWeatherSnapshot(kind, Daylight.Day)
            val night = day.copy(daylight = Daylight.Night)
            assertEquals(kind, night.kind)
            assertTrue(day.label.contains(expected("Day", "白天")))
            assertTrue(night.label.contains(expected("Night", "夜晚")))
        }
    }

    @Test
    fun copyRetainsTheOriginalSnapshotTimestampAndDoesNotChangeTheOriginal() {
        val original = DiaryWeatherSnapshot(WeatherKind.Clear, Daylight.Dusk, 8, "2026-09-12T18:03:00+10:00", true)
        val modified = original.copy(kind = WeatherKind.Rain)
        assertEquals(original.capturedAt, modified.capturedAt)
        assertEquals(original.temperatureC, modified.temperatureC)
        assertEquals(original.daylight, modified.daylight)
        assertEquals(original.isDemo, modified.isDemo)
        assertEquals(WeatherKind.Clear, original.kind)
        assertEquals(expected("Rain · Dusk · 8°C", "雨天 · 黄昏 · 8°C"), modified.label)
    }

    @Test
    fun temperaturesKeepTheirSignAndTimeOfDayDoesNotComeFromTheDeviceClock() {
        val winterNight = DiaryWeatherSnapshot(WeatherKind.Clear, Daylight.Night, -4)
        assertEquals(expected("Clear · Night · -4°C", "晴天 · 夜晚 · -4°C"), winterNight.label)
        assertEquals(Daylight.Night, winterNight.copy(capturedAt = "date intentionally not parsed").daylight)
    }

    @Test
    fun commonWeatherKindsHaveDistinctHumanReadableLabels() {
        val expected = mapOf(
            WeatherKind.Clear to "Clear", WeatherKind.Cloudy to "Cloudy", WeatherKind.Rain to "Rain",
            WeatherKind.Snow to "Snow", WeatherKind.Fog to "Fog",
        )
        assertEquals(5, expected.size)
        assertEquals(expected.size, expected.values.toSet().size)
        assertEquals(WeatherKind.entries.toSet() - WeatherKind.Unknown, expected.keys)
        expected.forEach { (kind, label) ->
            assertEquals("$label · ${expected("Day", "白天")}", DiaryWeatherSnapshot(kind, Daylight.Day).label)
        }
    }

    @Test
    fun rainHasOneNameAcrossAllSavedTimesOfDay() {
        Daylight.entries.forEach { daylight ->
            val snapshot = DiaryWeatherSnapshot(WeatherKind.Rain, daylight, 14, "saved-time", true)
            assertTrue(snapshot.label.startsWith(expected("Rain · ", "雨天 · ")))
            assertFalse(snapshot.label.contains("Light rain"))
            assertFalse(snapshot.label.contains("Moderate rain"))
            assertFalse(snapshot.label.contains("Heavy rain"))
            assertEquals("saved-time", snapshot.capturedAt)
        }
    }

    @Test
    fun translatingLabelsDoesNotRenamePersistedWeatherOrDaylightKeys() {
        assertEquals(listOf("Clear", "Cloudy", "Rain", "Snow", "Fog", "Unknown"), WeatherKind.entries.map { it.name })
        assertEquals(listOf("Day", "Dusk", "Night"), Daylight.entries.map { it.name })
        WeatherKind.entries.forEach { assertEquals(it, WeatherKind.valueOf(it.name)) }
        Daylight.entries.forEach { assertEquals(it, Daylight.valueOf(it.name)) }
    }
}
