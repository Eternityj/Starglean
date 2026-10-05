package com.starglean.app.core.draft

import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.DiaryPhotoRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/** Versioned, bounded and strict UTF-8. Corruption is never interpreted as an empty draft. */
object WritingDraftCodec {
    const val MAX_FILE_BYTES = 64 * 1024
    private const val MAGIC = 0x53544446
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1
    private const val HEADER_BYTES = 20

    fun encode(state: WritingDraftState): ByteArray {
        try {
            state.draft?.let(WritingDraftRules::validate)
            val payload = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeLong(state.revision)
                    out.writeBoolean(state.draft != null)
                    state.draft?.let {
                        out.text(it.id, 40)
                        out.text(it.title, WritingDraftRules.MAX_TITLE_CODE_POINTS * 4)
                        out.text(it.body, WritingDraftRules.MAX_BODY_CODE_POINTS * 4)
                        out.text(it.paper.name, 32)
                        out.text(it.font.name, 32)
                        out.writeInt(it.photos.size)
                        it.photos.forEach { photo ->
                            out.text(photo.ref, DiaryPhotoRules.REF_BYTES)
                            out.writeInt(photo.width)
                            out.writeInt(photo.height)
                            out.text(photo.mimeType, 32)
                            out.writeLong(photo.sizeBytes)
                        }
                    }
                }
            }.toByteArray()
            if (payload.size > MAX_FILE_BYTES - HEADER_BYTES) invalid()
            return ByteArrayOutputStream(HEADER_BYTES + payload.size).also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(VERSION)
                    out.writeInt(payload.size)
                    out.writeLong(CRC32().apply { update(payload) }.value)
                    out.write(payload)
                }
            }.toByteArray()
        } catch (error: WritingDraftDataException) {
            throw error
        } catch (_: Exception) { invalid() }
    }

    fun decode(bytes: ByteArray): WritingDraftState {
        try {
            if (bytes.size !in HEADER_BYTES..MAX_FILE_BYTES) invalid()
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) invalid()
            val version = input.readInt()
            if (version != LEGACY_VERSION && version != VERSION) invalid()
            val length = input.readInt()
            val checksum = input.readLong()
            if (length != bytes.size - HEADER_BYTES) invalid()
            val payload = ByteArray(length)
            input.readFully(payload)
            if (CRC32().apply { update(payload) }.value != checksum) invalid()
            val data = DataInputStream(ByteArrayInputStream(payload))
            val revision = data.readLong()
            val present = when (data.readUnsignedByte()) { 0 -> false; 1 -> true; else -> invalid() }
            val draft = if (present) {
                val id = data.text(40)
                val title = data.text(WritingDraftRules.MAX_TITLE_CODE_POINTS * 4)
                val body = data.text(WritingDraftRules.MAX_BODY_CODE_POINTS * 4)
                val paper = enumValue<PaperStyle>(data.text(32))
                val font = enumValue<DiaryFont>(data.text(32))
                val photos = if (version == VERSION) data.photos() else emptyList()
                WritingDraft(id, title, body, paper, font, photos)
            } else null
            if (data.available() != 0) invalid()
            return WritingDraftState(revision, draft)
        } catch (_: Exception) { invalid() }
    }

    private fun DataOutputStream.text(value: String, maxBytes: Int) {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        if (encoded.remaining() > maxBytes) invalid()
        writeInt(encoded.remaining())
        while (encoded.hasRemaining()) writeByte(encoded.get().toInt())
    }

    private fun DataInputStream.text(maxBytes: Int): String {
        val length = readInt()
        if (length !in 0..maxBytes || length > available()) invalid()
        val encoded = ByteArray(length)
        readFully(encoded)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString()
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name == value } ?: invalid()

    private fun DataInputStream.photos(): List<DiaryPhoto> {
        val count = readInt()
        if (count !in 0..2) invalid()
        return List(count) {
            DiaryPhoto(
                ref = text(DiaryPhotoRules.REF_BYTES),
                width = readInt(),
                height = readInt(),
                mimeType = text(32),
                sizeBytes = readLong(),
            )
        }
    }

    private fun invalid(): Nothing = throw WritingDraftDataException("The local draft could not be read safely. The original is preserved. Do not clear app data.")
}
