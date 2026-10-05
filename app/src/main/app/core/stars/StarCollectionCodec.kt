package com.starglean.app.core.stars

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/** Only bounded identities/progress are stored here, never entry text, weather or location. */
object StarCollectionCodec {
    const val MAX_FILE_BYTES = 64 * 1024
    private const val MAGIC = 0x53545343
    private const val VERSION = 1
    private const val HEADER_BYTES = 20
    private const val MAX_ID_BYTES = 40

    fun encode(state: StarCollectionState): ByteArray {
        try {
            StarCollectionRules.validate(state)
            val payload = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeInt(state.instances.size)
                    state.instances.forEach { out.text(it.id); out.text(it.templateId); out.writeInt(it.ordinal) }
                    out.writeBoolean(state.targetId != null)
                    state.targetId?.let { out.text(it) }
                    out.writeInt(state.homeIds.size)
                    // Selection is a set. Persist deterministically in collection creation order.
                    state.instances.filter { it.id in state.homeIds }.forEach { out.text(it.id) }
                    out.writeInt(state.placements.size)
                    state.placements.forEach { out.text(it.diaryId); out.text(it.instanceId); out.writeInt(it.starIndex) }
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
        } catch (error: StarCollectionDataException) { throw error }
        catch (_: Exception) { invalid() }
    }

    fun decode(bytes: ByteArray): StarCollectionState {
        try {
            if (bytes.size !in HEADER_BYTES..MAX_FILE_BYTES) invalid()
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC || input.readInt() != VERSION) invalid()
            val length = input.readInt()
            val checksum = input.readLong()
            if (length != bytes.size - HEADER_BYTES) invalid()
            val payload = ByteArray(length)
            input.readFully(payload)
            if (CRC32().apply { update(payload) }.value != checksum) invalid()
            val data = DataInputStream(ByteArrayInputStream(payload))
            val instances = List(data.count(StarCollectionRules.maxInstances)) {
                StarConstellationInstance(data.text(), data.text(), data.readInt())
            }
            val targetId = when (data.readUnsignedByte()) { 0 -> null; 1 -> data.text(); else -> invalid() }
            val home = List(data.count(StarCollectionRules.MAX_HOME_INSTANCES)) { data.text() }
            if (home.toSet().size != home.size) invalid()
            val placements = List(data.count(StarCollectionRules.maxPlacements)) {
                DiaryStarPlacement(data.text(), data.text(), data.readInt())
            }
            if (data.available() != 0) invalid()
            return StarCollectionState(instances, targetId, home.toSet(), placements)
        } catch (_: Exception) { invalid() }
    }

    private fun DataInputStream.count(maximum: Int): Int = readInt().also { if (it !in 0..maximum) invalid() }

    private fun DataOutputStream.text(value: String) {
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        if (encoded.remaining() !in 1..MAX_ID_BYTES) invalid()
        writeInt(encoded.remaining())
        while (encoded.hasRemaining()) writeByte(encoded.get().toInt())
    }

    private fun DataInputStream.text(): String {
        val length = readInt()
        if (length !in 1..MAX_ID_BYTES || length > available()) invalid()
        val encoded = ByteArray(length)
        readFully(encoded)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(encoded)).toString()
    }

    private fun invalid(): Nothing = throw StarCollectionDataException(
        "The local constellation collection could not be read safely. The original is preserved. Do not clear app data.",
    )
}
