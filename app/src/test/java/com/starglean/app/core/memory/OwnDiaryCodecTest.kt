package com.starglean.app.core.memory

import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.media.DiaryPhoto
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnDiaryCodecTest {
    @Test fun roundTripRetainsUnicodePaperFontAndFrozenWeatherWithoutInventingLocation() {
        val record = fixture()
        assertEquals(listOf(record), OwnDiaryCodec.decode(OwnDiaryCodec.encode(listOf(record))))
        assertEquals(emptyList<OwnDiaryRecord>(), OwnDiaryCodec.decode(OwnDiaryCodec.encode(emptyList())))
        assertEquals(null, OwnDiaryCodec.decode(OwnDiaryCodec.encode(listOf(record))).single().place)
    }

    @Test fun optionalRememberedFixRetainsItsOwnAccuracyAndCaptureTime() {
        val place = MemoryLocationFix(12.1234567, -45.7654321, 18.5f, 1_800_000_000_000L)
        val record = fixture().copy(place = place)
        val decoded = OwnDiaryCodec.decode(OwnDiaryCodec.encode(listOf(record))).single()
        assertEquals(record, decoded)
        assertEquals(18.5f, decoded.place!!.accuracyMeters, 0f)
        assertFalse(decoded.toString().contains("12.1234567"))
        assertFalse(decoded.toString().contains("-45.7654321"))
        assertFalse(decoded.toString().contains(record.diary.body))
        assertFalse(decoded.toString().contains(record.diary.title!!))
    }

    @Test fun photoMetadataRoundTripsAndTheDecodedListIsIsolated() {
        val mutable = mutableListOf(photo("1"), photo("2"))
        val record = fixture().let { it.copy(diary = it.diary.copy(photos = mutable)) }
        val restored = OwnDiaryCodec.decode(OwnDiaryCodec.encode(listOf(record))).single()
        mutable.clear()
        assertEquals(listOf(photo("1"), photo("2")), restored.diary.photos)
    }

    @Test fun versionOneLibraryMigratesWithEmptyPhotos() {
        val current = OwnDiaryCodec.encode(listOf(fixture()))
        val legacy = current.copyOf(current.size - 4).also {
            ByteBuffer.wrap(it).putInt(4, 1).putInt(8, it.size - 20)
            ByteBuffer.wrap(it).putLong(12, CRC32().apply { update(it, 20, it.size - 20) }.value)
        }
        assertEquals(listOf(fixture()), OwnDiaryCodec.decode(legacy))
        assertTrue(OwnDiaryCodec.decode(legacy).single().diary.photos.isEmpty())
    }

    @Test fun maliciousPhotoMetadataFailsClosed() {
        val valid = OwnDiaryCodec.encode(listOf(fixture().let {
            it.copy(diary = it.diary.copy(photos = listOf(photo("1"))))
        }))
        val refStart = indexOf(valid, photo("1").ref.toByteArray())
        expectRejected { OwnDiaryCodec.decode(rechecksum(valid.copyOf().also {
            ByteBuffer.wrap(it).putInt(refStart - 8, 3)
        })) }
        expectRejected { OwnDiaryCodec.decode(rechecksum(valid.copyOf().also {
            ByteBuffer.wrap(it).putLong(it.size - 8, 0L)
        })) }
        expectRejected { OwnDiaryCodec.decode(rechecksum(valid.copyOf().also { it[refStart] = '/'.code.toByte() })) }
    }

    @Test fun nullTitleUnavailableWeatherAndEveryFontPaperSurviveRoundTrip() {
        for (paper in PaperStyle.entries) for (font in DiaryFont.entries) {
            val record = fixture().let { it.copy(diary = it.diary.copy(title = null, paper = paper, font = font,
                weatherSnapshot = DiaryWeatherSnapshot.Unavailable)) }
            assertEquals(record, OwnDiaryCodec.decode(OwnDiaryCodec.encode(listOf(record))).single())
        }
    }

    @Test fun everyTruncatedPrefixAndTrailingBytesFailWithoutPartialRecovery() {
        val encoded = OwnDiaryCodec.encode(listOf(fixture()))
        for (size in 0 until encoded.size) expectRejected { OwnDiaryCodec.decode(encoded.copyOf(size)) }
        expectRejected { OwnDiaryCodec.decode(encoded + byteArrayOf(0)) }
    }

    @Test fun checksumUnknownVersionAndInvalidEnvelopeCannotBecomeAnEmptyLibrary() {
        val encoded = OwnDiaryCodec.encode(listOf(fixture()))
        val corrupt = encoded.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        expectRejected { OwnDiaryCodec.decode(corrupt) }
        val future = encoded.copyOf().apply { ByteBuffer.wrap(this).putInt(4, 3) }
        expectRejected { OwnDiaryCodec.decode(future) }
        val badLength = encoded.copyOf().apply { ByteBuffer.wrap(this).putInt(8, -1) }
        expectRejected { OwnDiaryCodec.decode(badLength) }
        expectRejected { OwnDiaryCodec.decode(ByteArray(OwnDiaryCodec.MAX_FILE_BYTES + 1)) }
    }

    @Test fun validChecksumCannotHideInvalidCountsStringLengthsOrUtf8() {
        val encoded = OwnDiaryCodec.encode(listOf(fixture()))
        expectRejected { OwnDiaryCodec.decode(rechecksum(encoded.copyOf().apply { ByteBuffer.wrap(this).putInt(20, -1) })) }
        expectRejected { OwnDiaryCodec.decode(rechecksum(encoded.copyOf().apply { ByteBuffer.wrap(this).putInt(24, -1) })) }
        expectRejected { OwnDiaryCodec.decode(rechecksum(encoded.copyOf().apply { this[28] = 0xff.toByte() })) }
    }

    @Test fun encoderRejectsDuplicateIdsAndOversizedOrUnusableRecords() {
        val record = fixture()
        expectRejected { OwnDiaryCodec.encode(listOf(record, record)) }
        expectRejected { OwnDiaryCodec.encode(listOf(record.copy(diary = record.diary.copy(body = "短文")))) }
        expectRejected { OwnDiaryCodec.encode(listOf(record.copy(diary = record.diary.copy(title = "星".repeat(81))))) }
    }

    private fun rechecksum(bytes: ByteArray): ByteArray {
        val checksum = CRC32().apply { update(bytes, 20, bytes.size - 20) }.value
        ByteBuffer.wrap(bytes).putLong(12, checksum)
        return bytes
    }

    private fun expectRejected(block: () -> Unit) {
        var rejected = false
        try { block() } catch (error: OwnDiaryDataException) {
            rejected = true
            assertFalse(error.message.orEmpty().contains("12.1234567"))
            assertFalse(error.message.orEmpty().contains(fixture().diary.body))
        }
        assertTrue("Invalid storage must fail closed", rejected)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (index in 0..haystack.size - needle.size) {
            if (needle.indices.all { haystack[index + it] == needle[it] }) return index
        }
        throw AssertionError("fixture metadata missing")
    }

    private fun photo(suffix: String) = DiaryPhoto(
        "photo-00000000-0000-0000-0000-${suffix.padStart(12, '0')}", 1200, 800, sizeBytes = 123_456,
    )

    private fun fixture() = OwnDiaryRecord(
        DiaryDemo("own-12345678-1234-4234-8234-123456789abc", "留给明天的自己 🌙",
            "这一刻很安静，把今天的心情轻轻留在这里。\n明天也愿意继续向前走。🪐", "9月18日 · 20:30",
            DiaryWeatherSnapshot(WeatherKind.Rain, Daylight.Night, 12, "2026-09-18T20:30:00+10:00", false),
            "地点未记录", PaperStyle.Midnight, DiaryFont.Serif),
        place = null, savedAtMillis = 1_800_000_000_100L,
    )
}
