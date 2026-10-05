package com.starglean.app.core.draft

import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.media.DiaryPhoto
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingDraftCodecTest {
    @Test fun blankShortAndFullUnicodeDraftsRoundTripWithEveryStyle() {
        for (paper in PaperStyle.entries) for (font in DiaryFont.entries) {
            listOf("", "雨", "  这一页\n还没有写完 🌙🪐  ").forEach { body ->
                val state = WritingDraftState(18L, WritingDraft(ID, "  ", body, paper, font))
                assertEquals(state, WritingDraftCodec.decode(WritingDraftCodec.encode(state)))
            }
        }
    }

    @Test fun tombstoneRetainsRevisionAcrossEncodingSoAnOldWriterStillConflicts() {
        val state = WritingDraftState(85L, null)
        val restored = WritingDraftCodec.decode(WritingDraftCodec.encode(state))
        assertEquals(state, restored)
        assertTrue(runCatching { WritingDraftRules.save(restored, 84L, draft()) }.exceptionOrNull() is WritingDraftConflictException)
        assertEquals(WritingDraftState(0L, null), WritingDraftCodec.decode(WritingDraftCodec.encode(WritingDraftState(0L, null))))
    }

    @Test fun maximumLegalUnicodeTextStaysWithinTheStrictFileBound() {
        val state = WritingDraftState(1L, draft().copy(title = "🌙".repeat(80), body = "🪐".repeat(10_000)))
        val bytes = WritingDraftCodec.encode(state)
        assertTrue(bytes.size < WritingDraftCodec.MAX_FILE_BYTES)
        assertEquals(state, WritingDraftCodec.decode(bytes))
    }

    @Test fun photoMetadataRoundTripsWithoutAliasingTheInputList() {
        val mutable = mutableListOf(photo("1"), photo("2"))
        val state = WritingDraftState(4L, draft().copy(photos = mutable))
        val restored = WritingDraftCodec.decode(WritingDraftCodec.encode(state))
        mutable.clear()
        assertEquals(2, restored.draft!!.photos.size)
        assertEquals(listOf(photo("1"), photo("2")), restored.draft!!.photos)
    }

    @Test fun versionOneDraftMigratesWithNoInventedPhotos() {
        val current = WritingDraftCodec.encode(WritingDraftState(7L, draft()))
        val legacy = current.copyOf(current.size - 4).also {
            ByteBuffer.wrap(it).putInt(4, 1).putInt(8, it.size - 20)
            ByteBuffer.wrap(it).putLong(12, CRC32().apply { update(it, 20, it.size - 20) }.value)
        }
        assertEquals(WritingDraftState(7L, draft()), WritingDraftCodec.decode(legacy))
    }

    @Test fun maliciousPhotoCountAndMetadataFailClosed() {
        rejects(envelope(payload() + intBytes(3), version = 2))
        rejects(envelope(payload() + photoPayload(ref = "../private.jpg"), version = 2))
        rejects(envelope(payload() + photoPayload(width = 0), version = 2))
        rejects(envelope(payload() + photoPayload(size = 0), version = 2))
        val duplicated = rawPhoto("1")
        rejects(envelope(payload() + intBytes(2) + duplicated + duplicated, version = 2))
    }

    @Test fun checksumTruncationOversizeAndTrailingDataFailClosed() {
        val valid = WritingDraftCodec.encode(WritingDraftState(1L, draft()))
        rejects(valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() })
        rejects(valid.copyOf(valid.size - 1))
        rejects(valid + byteArrayOf(0))
        rejects(ByteArray(WritingDraftCodec.MAX_FILE_BYTES + 1))
        rejects(byteArrayOf())
    }

    @Test fun unknownHeaderVersionMagicAndInvalidLengthAreRejected() {
        val valid = WritingDraftCodec.encode(WritingDraftState(1L, draft()))
        listOf(0 to 0, 4 to 3, 8 to -1, 8 to Int.MAX_VALUE).forEach { (offset, value) ->
            rejects(valid.copyOf().also { ByteBuffer.wrap(it).putInt(offset, value) })
        }
    }

    @Test fun invalidFlagsRevisionsAndPayloadTailCannotHideBehindAValidChecksum() {
        rejects(envelope(payload(flag = 2)))
        rejects(envelope(payload(revision = -1L)))
        rejects(envelope(payload(revision = 0L)))
        rejects(envelope(payload() + byteArrayOf(0)))
        rejects(envelope(payload(flag = 0) + byteArrayOf(0)))
    }

    @Test fun malformedUtf8UnknownEnumsAndExampleIdsAreRejectedWithAValidChecksum() {
        rejects(envelope(payload(body = byteArrayOf(0xc3.toByte(), 0x28))))
        rejects(envelope(payload(id = "demo:weather-preview")))
        rejects(envelope(payload(paper = "UnknownPaper")))
        rejects(envelope(payload(font = "UnknownFont")))
    }

    @Test fun stringByteLimitsAndCodePointLimitsAreEnforcedIndependently() {
        // ASCII can fit the byte bound while exceeding the title's character bound.
        rejects(envelope(payload(title = "a".repeat(81).toByteArray())))
        rejects(envelope(payload(body = "a".repeat(10_001).toByteArray())))
        rejects(envelope(payload(title = ByteArray(321))))
        rejects(envelope(payload(body = ByteArray(40_001))))
        val maliciousLength = payload().copyOf().also { ByteBuffer.wrap(it).putInt(9, Int.MAX_VALUE) }
        rejects(envelope(maliciousLength))
    }

    private fun rejects(bytes: ByteArray) {
        val failure = runCatching { WritingDraftCodec.decode(bytes) }.exceptionOrNull()
        assertTrue(failure is WritingDraftDataException)
        assertEquals(null, failure?.cause)
    }

    private fun payload(
        revision: Long = 1L,
        flag: Int = 1,
        id: String = ID,
        title: ByteArray = byteArrayOf(),
        body: ByteArray = "还没写完".toByteArray(Charsets.UTF_8),
        paper: String = PaperStyle.WarmWhite.name,
        font: String = DiaryFont.Clear.name,
    ): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeLong(revision)
            out.writeByte(flag)
            if (flag == 1) {
                listOf(id.toByteArray(Charsets.UTF_8), title, body, paper.toByteArray(), font.toByteArray()).forEach {
                    out.writeInt(it.size)
                    out.write(it)
                }
            }
        }
    }.toByteArray()

    private fun envelope(payload: ByteArray, version: Int = 1): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x53544446)
            out.writeInt(version)
            out.writeInt(payload.size)
            out.writeLong(CRC32().apply { update(payload) }.value)
            out.write(payload)
        }
    }.toByteArray()

    private fun photoPayload(
        ref: String = "photo-00000000-0000-0000-0000-000000000001",
        width: Int = 100,
        size: Long = 500,
    ) = intBytes(1) + ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            val refBytes = ref.toByteArray()
            out.writeInt(refBytes.size); out.write(refBytes)
            out.writeInt(width); out.writeInt(80)
            val mime = "image/jpeg".toByteArray()
            out.writeInt(mime.size); out.write(mime)
            out.writeLong(size)
        }
    }.toByteArray()

    private fun rawPhoto(suffix: String): ByteArray = photoPayload(
        ref = "photo-00000000-0000-0000-0000-${suffix.padStart(12, '0')}",
    ).copyOfRange(4, photoPayload().size)

    private fun intBytes(value: Int) = ByteBuffer.allocate(4).putInt(value).array()

    private fun photo(suffix: String) = DiaryPhoto(
        "photo-00000000-0000-0000-0000-${suffix.padStart(12, '0')}", 100, 80, sizeBytes = 500,
    )

    private fun draft() = WritingDraft(ID, "", "还没写完", PaperStyle.WarmWhite, DiaryFont.Clear)
    private companion object { const val ID = "own-00000000-0000-0000-0000-000000000001" }
}
