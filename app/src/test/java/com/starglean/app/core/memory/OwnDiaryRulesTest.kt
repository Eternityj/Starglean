package com.starglean.app.core.memory

import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnDiaryRulesTest {
    @Test fun minimumCountsUnicodeTextButNotInvisiblePadding() {
        assertEquals(9, OwnDiaryRules.meaningfulCodePointCount("一二三四五六七八九"))
        assertNotNull(OwnDiaryRules.textError(null, "一二三四五六七八九"))
        assertNull(OwnDiaryRules.textError(null, "一二三四五六七八九十"))
        assertNull(OwnDiaryRules.textError(null, "🪐".repeat(10)))
        assertNotNull(OwnDiaryRules.textError(null, "🪐".repeat(9)))
        assertEquals(3, OwnDiaryRules.meaningfulCodePointCount("星 🌟 光"))
        assertEquals(0, OwnDiaryRules.meaningfulCodePointCount(" \n\t\u00a0\u200b\u200d\ufeff"))
        assertNotNull(OwnDiaryRules.textError(null, "星".repeat(9) + "\u200b".repeat(100)))
    }

    @Test fun limitsUseCodePointsAndAllowOptionalTitles() {
        assertNull(OwnDiaryRules.textError(null, "星".repeat(10)))
        assertNull(OwnDiaryRules.textError("🪐".repeat(80), "星".repeat(10_000)))
        assertNotNull(OwnDiaryRules.textError("🪐".repeat(81), "星".repeat(10)))
        assertNotNull(OwnDiaryRules.textError(null, "星".repeat(10_001)))
    }

    @Test fun localIdentityTimeAndCoordinatesMustBeValid() {
        val record = fixture()
        assertNull(OwnDiaryRules.recordError(record))
        assertNotNull(OwnDiaryRules.recordError(record.copy(diary = record.diary.copy(id = "demo-stranger-1"))))
        assertNotNull(OwnDiaryRules.recordError(record.copy(savedAtMillis = 0)))
        assertNull(OwnDiaryRules.recordError(record.copy(place = MemoryLocationFix(0.0, 0.0, 5f, 100L))))
    }

    @Test fun appendPreservesEveryExistingRecordAndDoesNotRequireAPlace() {
        val first = fixture()
        val second = first.copy(diary = first.diary.copy(id = "own-12345678-1234-4234-8234-123456789abd"), savedAtMillis = 200L)
        val previous = listOf(first)
        val next = OwnDiaryRules.append(previous, second)
        assertEquals(listOf(first), previous)
        assertEquals(listOf(first, second), next)
        assertNull(next.last().place)
    }

    @Test fun identicalRetryIsIdempotentButChangingAnExistingIdCannotOverwrite() {
        val record = fixture()
        val previous = listOf(record)
        assertSame(previous, OwnDiaryRules.append(previous, record))
        expectRejected { OwnDiaryRules.append(previous, record.copy(diary = record.diary.copy(body = "这不是原来保存的内容，请不要覆盖原日记。"))) }
        expectRejected { OwnDiaryRules.append(previous, record.copy(place = MemoryLocationFix(0.0, 0.0, 5f, 100L))) }
        assertEquals(record, previous.single())
    }

    @Test fun malformedExistingCollectionsAreNotSilentlyReplaced() {
        val record = fixture()
        expectRejected { OwnDiaryRules.append(listOf(record, record), record) }
        expectRejected { OwnDiaryRules.append(listOf(record.copy(savedAtMillis = -1)), record) }
        expectRejected { OwnDiaryRules.append(emptyList(), record.copy(diary = record.diary.copy(body = "短"))) }
    }

    private fun expectRejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: OwnDiaryDataException) { rejected = true }
        assertTrue(rejected)
    }

    private fun fixture() = OwnDiaryRecord(
        DiaryDemo("own-12345678-1234-4234-8234-123456789abc", null, "今天的心情，我想好好地记录下来。", "今天",
            DiaryWeatherSnapshot.Unavailable, "地点未记录", PaperStyle.WarmWhite, DiaryFont.Clear),
        place = null, savedAtMillis = 100L,
    )
}
