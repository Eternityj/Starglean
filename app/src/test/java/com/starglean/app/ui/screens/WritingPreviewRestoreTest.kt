package com.starglean.app.ui.screens

import com.starglean.app.core.model.*
import com.starglean.app.core.media.DiaryPhoto
import org.junit.Assert.*
import org.junit.Test

class WritingPreviewRestoreTest {
    private fun preview(kind: WeatherKind, origin: WritingPreviewOrigin = WritingPreviewOrigin.OwnDraft): WritingPreview =
        WritingPreview(DiaryDemo("own-00000000-0000-0000-0000-000000000001", "冻结的一刻",
            "这是用于验证冻结天气和正文恢复的十个字以上内容", "9月16日 · 10:01",
            DiaryWeatherSnapshot(kind, Daylight.Night, -3, "2026-09-16T10:00:00+10:00", origin != WritingPreviewOrigin.OwnDraft),
            "地点未记录", PaperStyle.Midnight, DiaryFont.Serif), DiaryVisibility.Private,
            "预览已固定此刻的天气", origin)

    @Test fun everyWeatherSnapshotRestoresWithoutQueryingCurrentWeather() {
        WeatherKind.entries.forEach { kind ->
            val captured = preview(kind)
            assertEquals(captured, decodeWritingPreview(encodeWritingPreview(captured)))
        }
    }

    @Test fun unavailableWeatherAndUntitledDraftKeepTheirExactMeaning() {
        val captured = preview(WeatherKind.Unknown).let {
            it.copy(diary = it.diary.copy(title = null, weatherSnapshot = DiaryWeatherSnapshot.Unavailable))
        }
        assertEquals(captured, decodeWritingPreview(encodeWritingPreview(captured)))
    }

    @Test fun inFlightWeatherCannotBeFrozenAsAnUnknownPlaceholder() {
        assertTrue(shouldWaitForWritingWeatherBeforePreview(weatherEnabled = true,
            weatherLoading = true, hasFreshWeather = false))
        // A refresh does not block a preview when an existing fresh snapshot is available.
        assertFalse(shouldWaitForWritingWeatherBeforePreview(weatherEnabled = true,
            weatherLoading = true, hasFreshWeather = true))
    }

    @Test fun disabledCancelledOrDefinitivelyFailedWeatherDoesNotBlockWriting() {
        assertFalse(shouldWaitForWritingWeatherBeforePreview(weatherEnabled = false,
            weatherLoading = false, hasFreshWeather = false))
        assertFalse(shouldWaitForWritingWeatherBeforePreview(weatherEnabled = true,
            weatherLoading = false, hasFreshWeather = false))
    }

    @Test fun demoProvenanceCannotTurnRealWhenSavedStateRestores() {
        listOf(WritingPreviewOrigin.DemoWeather, WritingPreviewOrigin.FixedTestLocation).forEach { origin ->
            val restored = checkNotNull(decodeWritingPreview(encodeWritingPreview(preview(WeatherKind.Rain, origin))))
            assertFalse(writingPreviewCanSave(restored.origin, restored.diary.weatherSnapshot.isDemo))
        }
    }

    @Test fun unknownOrMalformedSavedStateFailsClosed() {
        assertNull(decodeWritingPreview(emptyList()))
        assertNull(decodeWritingPreview(encodeWritingPreview(preview(WeatherKind.Clear)).toMutableList().also { it[0] = "99" }))
        assertNull(decodeWritingPreview(encodeWritingPreview(preview(WeatherKind.Clear)).toMutableList().also { it[5] = "fake-weather" }))
        assertNull(decodeWritingPreview(encodeWritingPreview(preview(WeatherKind.Clear)).toMutableList().also { it[1] = "demo-stranger" }))
    }

    @Test fun legacyPreviewStillRestoresWithoutPhotos() {
        val original = preview(WeatherKind.Clear)
        val legacy = encodeWritingPreview(original).take(16).toMutableList().also { it[0] = "1" }
        assertEquals(original, decodeWritingPreview(legacy))
    }

    @Test fun photosAreFrozenAndMalformedOrDuplicateReferencesFailClosed() {
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000001", 1200, 800, sizeBytes = 2000)
        val original = preview(WeatherKind.Rain).let { it.copy(diary = it.diary.copy(photos = listOf(photo))) }
        val fields = encodeWritingPreview(original)
        assertEquals(original, decodeWritingPreview(fields))
        assertNull(decodeWritingPreview(fields.toMutableList().also { it[17] = "../private" }))
        assertNull(decodeWritingPreview(fields.toMutableList().also { it[18] = "90000" }))
        assertNull(decodeWritingPreview(fields + fields.takeLast(5)))
        assertNull(decodeWritingPreview((fields + fields.takeLast(5)).toMutableList().also { it[16] = "2" }))
    }
}
