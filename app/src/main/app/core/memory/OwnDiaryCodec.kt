package com.starglean.app.core.memory

import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.DiaryPhotoRules
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/** Never let debugging this wrapper disclose diary text or a precise remembered location. */
data class OwnDiaryRecord(
    val diary: DiaryDemo,
    val place: MemoryLocationFix?,
    val savedAtMillis: Long,
) {
    override fun toString(): String = "OwnDiaryRecord(diary=<private>, place=<private>, savedAtMillis=$savedAtMillis)"
}

/** Pure validation/append policy, shared by the editor, codec and private-file store. */
object OwnDiaryRules {
    const val MAX_TITLE_CODE_POINTS = 80
    const val MAX_BODY_CODE_POINTS = 10_000
    const val MAX_RECORDS = 2_000
    private val ownId = Regex("own-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun codePointCount(value: String): Int = value.codePointCount(0, value.length)

    /** Spaces, control codes and invisible formatting characters cannot satisfy the minimum. */
    fun meaningfulCodePointCount(value: String): Int {
        var count = 0
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            if (!Character.isWhitespace(point) && !Character.isSpaceChar(point) &&
                !Character.isISOControl(point) && Character.getType(point) != Character.FORMAT.toInt()) count++
            index += Character.charCount(point)
        }
        return count
    }

    fun textError(title: String?, body: String): String? = when {
        title != null && codePointCount(title) > MAX_TITLE_CODE_POINTS -> "Keep the title to $MAX_TITLE_CODE_POINTS characters or fewer."
        codePointCount(body) > MAX_BODY_CODE_POINTS -> "Keep the entry to $MAX_BODY_CODE_POINTS characters or fewer."
        meaningfulCodePointCount(body) < 10 -> "Write at least 10 non-blank characters."
        else -> null
    }

    fun recordError(record: OwnDiaryRecord): String? {
        val diary = record.diary
        textError(diary.title, diary.body)?.let { return it }
        if (!ownId.matches(diary.id)) return "Invalid entry ID. Go back and preview again."
        if (record.savedAtMillis <= 0L) return "Invalid save time. Go back and preview again."
        if (diary.date.length > 128 || diary.blurredPlace.length > 128 ||
            (diary.weatherSnapshot.capturedAt?.length ?: 0) > 128) return "Entry details are incomplete. Go back and preview again."
        try { DiaryPhotoRules.validateAttachments(diary.photos) }
        catch (_: IllegalArgumentException) { return "This entry has invalid photo attachments. Go back and preview again." }
        val place = record.place
        if (place != null && (!place.latitude.isFinite() || place.latitude !in -90.0..90.0 ||
                !place.longitude.isFinite() || place.longitude !in -180.0..180.0 ||
                !place.accuracyMeters.isFinite() || place.accuracyMeters <= 0f || place.capturedAtEpochMillis <= 0L)) {
            return "This location is invalid. Retry or continue without a location."
        }
        return null
    }

    /** An identical retry is idempotent; an existing identity is never overwritten. */
    fun append(existing: List<OwnDiaryRecord>, record: OwnDiaryRecord): List<OwnDiaryRecord> {
        recordError(record)?.let { throw OwnDiaryDataException(it) }
        if (existing.any { recordError(it) != null } || existing.map { it.diary.id }.toSet().size != existing.size) {
            throw OwnDiaryDataException("Local entries could not be read safely. The original file is preserved; new entries cannot be added yet.")
        }
        val sameId = existing.firstOrNull { it.diary.id == record.diary.id }
        if (sameId != null) {
            if (sameId == record) return existing
            throw OwnDiaryDataException("This entry is already saved and will not be overwritten. Find it in My entries.")
        }
        if (existing.size >= MAX_RECORDS) throw OwnDiaryDataException("The local entry limit has been reached. Existing entries will not be overwritten.")
        return existing + record
    }
}

class OwnDiaryDataException(message: String) : IllegalArgumentException(message)

/**
 * Versioned, length-bounded private storage, not an exchange/upload format. A checksum and strict
 * UTF-8 parsing make malformed data fail closed; decoding never returns a partial/empty recovery.
 */
object OwnDiaryCodec {
    const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAGIC = 0x5354474C
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1
    private const val HEADER_BYTES = 20
    private const val MAX_STRING_BYTES = OwnDiaryRules.MAX_BODY_CODE_POINTS * 4

    fun encode(records: List<OwnDiaryRecord>): ByteArray {
        if (records.size > OwnDiaryRules.MAX_RECORDS || records.map { it.diary.id }.toSet().size != records.size) invalid()
        records.forEach { OwnDiaryRules.recordError(it)?.let { error -> throw OwnDiaryDataException(error) } }
        val payloadBytes = ByteArrayOutputStream()
        DataOutputStream(payloadBytes).use { out ->
            out.writeInt(records.size)
            records.forEach { record ->
                val diary = record.diary
                out.text(diary.id)
                out.nullableText(diary.title)
                out.text(diary.body)
                out.text(diary.date)
                out.text(diary.blurredPlace)
                out.text(diary.paper.name)
                out.text(diary.font.name)
                out.text(diary.weatherSnapshot.kind.name)
                out.text(diary.weatherSnapshot.daylight.name)
                out.writeBoolean(diary.weatherSnapshot.temperatureC != null)
                diary.weatherSnapshot.temperatureC?.let(out::writeInt)
                out.nullableText(diary.weatherSnapshot.capturedAt)
                out.writeBoolean(diary.weatherSnapshot.isDemo)
                out.writeLong(record.savedAtMillis)
                out.writeBoolean(record.place != null)
                record.place?.let { place ->
                    out.writeDouble(place.latitude)
                    out.writeDouble(place.longitude)
                    out.writeFloat(place.accuracyMeters)
                    out.writeLong(place.capturedAtEpochMillis)
                }
                out.writeInt(diary.photos.size)
                diary.photos.forEach { photo ->
                    out.text(photo.ref, DiaryPhotoRules.REF_BYTES)
                    out.writeInt(photo.width)
                    out.writeInt(photo.height)
                    out.text(photo.mimeType, 32)
                    out.writeLong(photo.sizeBytes)
                }
                if (payloadBytes.size() > MAX_FILE_BYTES - HEADER_BYTES) {
                    throw OwnDiaryDataException("Diary storage has reached its current limit. Existing entries will not be overwritten.")
                }
            }
        }
        val payload = payloadBytes.toByteArray()
        return ByteArrayOutputStream(HEADER_BYTES + payload.size).also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeInt(payload.size)
                out.writeLong(CRC32().apply { update(payload) }.value)
                out.write(payload)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): List<OwnDiaryRecord> {
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
            val count = data.readInt()
            if (count !in 0..OwnDiaryRules.MAX_RECORDS) invalid()
            val records = List(count) {
                val id = data.text()
                val title = data.nullableText()
                val body = data.text()
                val date = data.text()
                val blurredPlace = data.text()
                val paper = enumValue<PaperStyle>(data.text())
                val font = enumValue<DiaryFont>(data.text())
                val kind = enumValue<WeatherKind>(data.text())
                val daylight = enumValue<Daylight>(data.text())
                val temperature = if (data.flag()) data.readInt() else null
                val capturedAt = data.nullableText()
                val demo = data.flag()
                val savedAt = data.readLong()
                val place = if (data.flag()) MemoryLocationFix(
                    latitude = data.readDouble(), longitude = data.readDouble(),
                    accuracyMeters = data.readFloat(), capturedAtEpochMillis = data.readLong(),
                ) else null
                val photos = if (version == VERSION) data.photos() else emptyList()
                OwnDiaryRecord(
                    DiaryDemo(id, title, body, date, DiaryWeatherSnapshot(kind, daylight, temperature, capturedAt, demo),
                        blurredPlace, paper, font, photos), place, savedAt,
                ).also { if (OwnDiaryRules.recordError(it) != null) invalid() }
            }
            if (data.available() != 0 || records.map { it.diary.id }.toSet().size != records.size) invalid()
            return records
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun DataOutputStream.text(value: String, maxBytes: Int = MAX_STRING_BYTES) {
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value))
        if (bytes.remaining() > maxBytes) invalid()
        writeInt(bytes.remaining())
        while (bytes.hasRemaining()) writeByte(bytes.get().toInt())
    }

    private fun DataOutputStream.nullableText(value: String?) {
        writeBoolean(value != null)
        if (value != null) text(value)
    }

    private fun DataInputStream.text(maxBytes: Int = MAX_STRING_BYTES): String {
        val length = readInt()
        if (length !in 0..maxBytes || length > available()) invalid()
        val value = ByteArray(length)
        readFully(value)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
    }

    private fun DataInputStream.flag(): Boolean = when (readUnsignedByte()) { 0 -> false; 1 -> true; else -> invalid() }
    private fun DataInputStream.nullableText(): String? = if (flag()) text() else null
    private fun DataInputStream.photos(): List<DiaryPhoto> {
        val count = readInt()
        if (count !in 0..DiaryPhotoRules.MAX_ATTACHMENTS) invalid()
        return List(count) {
            DiaryPhoto(text(DiaryPhotoRules.REF_BYTES), readInt(), readInt(), text(32), readLong())
        }.also(DiaryPhotoRules::validateAttachments)
    }
    private inline fun <reified T : Enum<T>> enumValue(value: String): T = enumValues<T>().firstOrNull { it.name == value } ?: invalid()
    private fun invalid(): Nothing = throw OwnDiaryDataException("The diary file could not be read safely. The original is preserved. Do not clear app data.")
}
