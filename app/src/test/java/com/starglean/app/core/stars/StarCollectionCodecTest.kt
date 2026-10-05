package com.starglean.app.core.stars

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarCollectionCodecTest {
    @Test fun emptyRealCollectionRoundTripsWithoutSampleProgress() {
        val initial = StarCollectionState.initial()
        assertEquals(initial, StarCollectionCodec.decode(StarCollectionCodec.encode(initial)))
    }

    @Test fun placedEntriesTargetDuplicateTypeAndHomeRoundTripExactly() {
        val state = completedState()
        assertEquals(state, StarCollectionCodec.decode(StarCollectionCodec.encode(state)))
        assertEquals(state.galleryItems(), StarCollectionCodec.decode(StarCollectionCodec.encode(state)).galleryItems())
    }

    @Test fun homeSetHasDeterministicEncodingRegardlessOfInsertionOrder() {
        var state = completedState()
        state = fill(state, "cygnus-1")
        val first = state.copy(homeIds = linkedSetOf("lyra-1", "cygnus-1"))
        val second = state.copy(homeIds = linkedSetOf("cygnus-1", "lyra-1"))
        assertArrayEquals(StarCollectionCodec.encode(first), StarCollectionCodec.encode(second))
    }

    @Test fun everySingleByteCorruptionFailsRatherThanReturningPartialProgress() {
        val bytes = StarCollectionCodec.encode(completedState())
        bytes.indices.forEach { index ->
            val corrupt = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            expectFailure { StarCollectionCodec.decode(corrupt) }
        }
    }

    @Test fun everyTruncationAndAppendedGarbageAreRejected() {
        val bytes = StarCollectionCodec.encode(completedState())
        bytes.indices.forEach { end -> expectFailure { StarCollectionCodec.decode(bytes.copyOf(end)) } }
        expectFailure { StarCollectionCodec.decode(bytes + byteArrayOf(1)) }
        expectFailure { StarCollectionCodec.decode(reframe(payload(bytes) + byteArrayOf(1))) }
    }

    @Test fun oversizedAndUnknownFormatFilesFailClosed() {
        expectFailure { StarCollectionCodec.decode(ByteArray(StarCollectionCodec.MAX_FILE_BYTES + 1)) }
        val bytes = StarCollectionCodec.encode(StarCollectionState.initial())
        expectFailure { StarCollectionCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(0, 0) }) }
        expectFailure { StarCollectionCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) }) }
        expectFailure { StarCollectionCodec.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) }) }
    }

    @Test fun negativeOrHugeInstanceCountsAreRejectedBeforeAllocationEvenWithAValidChecksum() {
        val data = payload(StarCollectionCodec.encode(StarCollectionState.initial()))
        listOf(-1, 0, 7, 41, Int.MAX_VALUE).forEach { count ->
            expectFailure { StarCollectionCodec.decode(reframe(data.copyOf().also { ByteBuffer.wrap(it).putInt(0, count) })) }
        }
    }

    @Test fun malformedUtf8AndOversizedIdentityLengthAreRejectedWithAValidChecksum() {
        val data = payload(StarCollectionCodec.encode(StarCollectionState.initial()))
        expectFailure {
            StarCollectionCodec.decode(reframe(data.copyOf().also { it[8] = 0xc0.toByte(); it[9] = 0xaf.toByte() }))
        }
        listOf(-1, 0, 41, Int.MAX_VALUE).forEach { length ->
            expectFailure { StarCollectionCodec.decode(reframe(data.copyOf().also { ByteBuffer.wrap(it).putInt(4, length) })) }
        }
    }

    @Test fun unknownTemplateOrInstanceIdentityIsRejectedWithAValidChecksum() {
        val data = payload(StarCollectionCodec.encode(StarCollectionState.initial()))
        // The first identity is cygnus-1; replacing c with x preserves byte count and CRC framing.
        expectFailure { StarCollectionCodec.decode(reframe(data.copyOf().also { it[8] = 'x'.code.toByte() })) }
    }

    @Test fun booleanPresenceUsesOnlyZeroOrOneAndInvalidTargetIsRejected() {
        val data = payload(StarCollectionCodec.encode(StarCollectionState.initial()))
        val position = afterInstances(data)
        listOf(2, 127, 255).forEach { boolean ->
            expectFailure { StarCollectionCodec.decode(reframe(data.copyOf().also { it[position] = boolean.toByte() })) }
        }
        val state = StarCollectionRules.setTarget(StarCollectionState.initial(), "lyra-1")
        val selected = payload(StarCollectionCodec.encode(state))
        val targetStart = afterInstances(selected) + 5
        expectFailure { StarCollectionCodec.decode(reframe(selected.copyOf().also { it[targetStart] = 'x'.code.toByte() })) }
    }

    @Test fun homeAndPlacementCountsAreBoundedBeforeAllocation() {
        val data = payload(StarCollectionCodec.encode(StarCollectionState.initial()))
        val homeCount = afterInstances(data) + 1
        val placementsCount = homeCount + 4
        listOf(-1, 9, Int.MAX_VALUE).forEach { count ->
            expectFailure { StarCollectionCodec.decode(reframe(data.copyOf().also { ByteBuffer.wrap(it).putInt(homeCount, count) })) }
        }
        listOf(-1, StarCollectionRules.maxPlacements + 1, Int.MAX_VALUE).forEach { count ->
            expectFailure { StarCollectionCodec.decode(reframe(data.copyOf().also { ByteBuffer.wrap(it).putInt(placementsCount, count) })) }
        }
    }

    @Test fun duplicateHomeIdentitiesAreNotSilentlyDeduplicated() {
        val state = completedState()
        expectFailure { StarCollectionCodec.decode(rawState(state, homes = listOf("lyra-1", "lyra-1"))) }
    }

    @Test fun duplicateEntryOrPointAndInvalidOrdinalRemainInvalidAfterChecksumming() {
        val state = completedState()
        val first = state.placements.first()
        expectFailure { StarCollectionCodec.decode(rawState(state, placements = state.placements + first.copy(instanceId = "cygnus-1"))) }
        expectFailure { StarCollectionCodec.decode(rawState(state, placements = state.placements + first.copy(diaryId = id(100)))) }
        expectFailure {
            StarCollectionCodec.decode(rawState(state, instances = state.instances.mapIndexed { index, instance ->
                if (index == 0) instance.copy(ordinal = 0) else instance
            }))
        }
    }

    @Test fun encodedContentContainsOnlyIdentitiesAndProgressNotDiaryBodiesOrPositions() {
        val bytes = StarCollectionCodec.encode(completedState())
        val printable = String(bytes, Charsets.ISO_8859_1)
        assertTrue(printable.contains("lyra-1"))
        assertTrue(printable.contains(id(1)))
        assertFalse(printable.contains("latitude"))
        assertFalse(printable.contains("longitude"))
        assertFalse(printable.contains("body"))
        assertFalse(printable.contains("demo:"))
    }

    @Test fun maximumValidCollectionFitsTheBoundAndRoundTrips() {
        var state = StarCollectionState.initial()
        StarConstellationTemplates.items.forEach { template -> repeat(4) { state = StarCollectionRules.createInstance(state, template.id) } }
        state.instances.forEach { state = fill(state, it.id) }
        state = StarCollectionRules.setHome(state, state.instances.take(8).map { it.id }.toSet())
        val bytes = StarCollectionCodec.encode(state)
        assertTrue(bytes.size < StarCollectionCodec.MAX_FILE_BYTES)
        assertEquals(285, state.placements.size)
        assertEquals(state, StarCollectionCodec.decode(bytes))
    }

    private fun completedState(): StarCollectionState {
        var state = StarCollectionRules.createInstance(StarCollectionState.initial(), "lyra-1")
        state = fill(state, "lyra-1")
        return StarCollectionRules.setTarget(StarCollectionRules.setHome(state, setOf("lyra-1")), "lyra-2")
    }

    private fun fill(initial: StarCollectionState, instanceId: String): StarCollectionState {
        var state = initial
        val remaining = state.galleryItems().single { it.id == instanceId }.let { it.totalStars - it.litStars }
        repeat(remaining) {
            val nextId = id(state.placements.size + 1)
            state = StarCollectionRules.placeDiary(state, nextId, state.placements.map { it.diaryId }.toSet() + nextId, instanceId).state
        }
        return state
    }

    /** Creates deliberately invalid wire data without constructing a valid domain state first. */
    private fun rawState(
        state: StarCollectionState,
        homes: List<String> = state.homeIds.toList(),
        placements: List<DiaryStarPlacement> = state.placements,
        instances: List<StarConstellationInstance> = state.instances,
    ): ByteArray = reframe(ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            fun text(value: String) { val encoded = value.toByteArray(Charsets.UTF_8); out.writeInt(encoded.size); out.write(encoded) }
            out.writeInt(instances.size)
            instances.forEach { text(it.id); text(it.templateId); out.writeInt(it.ordinal) }
            out.writeBoolean(state.targetId != null)
            state.targetId?.let(::text)
            out.writeInt(homes.size)
            homes.forEach(::text)
            out.writeInt(placements.size)
            placements.forEach { text(it.diaryId); text(it.instanceId); out.writeInt(it.starIndex) }
        }
    }.toByteArray())

    private fun afterInstances(payload: ByteArray): Int {
        val input = DataInputStream(ByteArrayInputStream(payload))
        repeat(input.readInt()) { repeat(2) { input.skipBytes(input.readInt()) }; input.readInt() }
        return payload.size - input.available()
    }

    private fun payload(bytes: ByteArray) = bytes.copyOfRange(20, bytes.size)
    private fun reframe(payload: ByteArray): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x53545343); out.writeInt(1); out.writeInt(payload.size)
            out.writeLong(CRC32().apply { update(payload) }.value); out.write(payload)
        }
    }.toByteArray()

    private fun id(number: Int) = "own-00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
    private fun expectFailure(action: () -> Any) {
        try { action() } catch (_: StarCollectionDataException) { return }
        throw AssertionError("Expected a safe constellation codec failure")
    }
}
