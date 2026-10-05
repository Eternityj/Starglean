package com.starglean.app.core.model

import com.starglean.app.core.memory.OwnDiaryRules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModelEnglishCopyTest {
    @Test fun navigationUsesTheSharedEnglishLabelsWithoutChangingDestinationKeys() {
        assertEquals(listOf("Planet", "Discover", "Write", "My constellations"), AppDestination.entries.map { it.label })
        assertEquals(listOf("Planet", "Discover", "Write", "Constellations"), AppDestination.entries.map { it.name })
    }

    @Test fun shippedExampleEntriesAreEnglishAndStillExplicitlyDemoOnly() {
        val samples = DemoContent.discoveryDiaries + DemoContent.ownDiary
        assertEquals(6, samples.map { it.id }.toSet().size)
        samples.forEach { entry ->
            assertTrue(entry.id.startsWith("demo-"))
            assertTrue(entry.weatherSnapshot.isDemo)
            assertNull(OwnDiaryRules.textError(entry.title, entry.body))
        }
        assertEquals("Sunlight on the Page", DemoContent.strangerDiary.title)
        assertEquals("Watching a Cloud Drift By", DemoContent.discoveryDiaries[1].title)
        assertEquals("The World Grew Quiet", DemoContent.discoveryDiaries[3].title)
    }

    @Test fun paperFontAndVisibilityStorageKeysStayStable() {
        assertEquals(listOf("WarmWhite", "Kraft", "Midnight"), PaperStyle.entries.map { it.name })
        assertEquals(listOf("Clear", "Handwritten", "Serif"), DiaryFont.entries.map { it.name })
        assertEquals(listOf("Public", "Private"), DiaryVisibility.entries.map { it.name })
    }

    @Test fun englishDisplayLabelsDoNotTranslateAuthorWrittenContent() {
        val originalTitle = "\u6211\u7684\u65e5\u8bb0"
        val originalBody = "\u4eca\u5929\u60f3\u7559\u4e0b\u7684\u6587\u5b57\u3002"
        val entry = DemoContent.ownDiary.copy(id = "own-preserved-content", title = originalTitle, body = originalBody,
            weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Cloudy, Daylight.Night))
        assertEquals("Cloudy · Night", entry.weather)
        assertEquals(originalTitle, entry.title)
        assertEquals(originalBody, entry.body)
    }
}
