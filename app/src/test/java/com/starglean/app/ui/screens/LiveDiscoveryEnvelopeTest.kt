package com.starglean.app.ui.screens

import com.starglean.app.core.discovery.NearbyReaderFixQuality
import com.starglean.app.core.discovery.NearbyReadingGate
import com.starglean.app.core.model.ReadingEligibility
import com.starglean.app.core.network.*
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LiveDiscoveryEnvelopeTest {
    private val session = UUID.randomUUID()
    private val content = DiaryContent("title", "0123456789 body", "warm_white", "clear",
        WeatherSnapshot(WeatherKind.Unknown, Daylight.Day), emptyList())

    @Test fun `plaintext requires exact generation sequence session and content`() {
        val receipt = OpenedDiary("OPEN_COMMITTED", session, 7, Instant.now(), null, null, null, Instant.now(), content, 3)
        assertTrue(receipt.matchesPlaintextEnvelope("OPEN_COMMITTED", session, 7, 3))
        assertFalse(receipt.copy(sequence = 2).matchesPlaintextEnvelope("OPEN_COMMITTED", session, 7, 3))
        assertFalse(receipt.copy(generation = 6).matchesPlaintextEnvelope("OPEN_COMMITTED", session, 7, 3))
        assertFalse(receipt.copy(content = null).matchesPlaintextEnvelope("OPEN_COMMITTED", session, 7, 3))
        assertFalse(receipt.copy(readerSessionId = UUID.randomUUID()).matchesPlaintextEnvelope("OPEN_COMMITTED", session, 7, 3))
    }

    @Test fun `receipt only idempotent open remains resumable but is not plaintext`() {
        val receipt = OpenedDiary("OPEN_COMMITTED", session, 7, Instant.now(), null, null, null, Instant.now(), null, null)
        assertTrue(receipt.matchesOpenReceipt(session, 7, 3))
        assertFalse(receipt.matchesPlaintextEnvelope("OPEN_COMMITTED", session, 7, 3))
        assertFalse(receipt.copy(sequence = 2).matchesOpenReceipt(session, 7, 3))
        assertFalse(receipt.copy(content = content).matchesOpenReceipt(session, 7, 3))
    }

    @Test fun `fresh in range validation uses dispatch bounded lease without phone wall clock`() {
        val valid = LeaseDecision("IN_RANGE", session, 4, 9, Instant.parse("2099-01-01T00:00:00Z"), null)
        assertTrue(valid.matchesValidationEnvelope(session, 4, 9))
        assertEquals(5_000L, valid.validationLeaseMillis(session, 4, 9))
        assertFalse(valid.copy(expiresAt = null).matchesValidationEnvelope(session, 4, 9))
        assertFalse(valid.copy(remainingMillis = 0).matchesValidationEnvelope(session, 4, 9))
        assertFalse(valid.copy(remainingMillis = 5_001).matchesValidationEnvelope(session, 4, 9))
        assertFalse(valid.copy(sequence = 8).matchesValidationEnvelope(session, 4, 9))
    }

    @Test fun `explicit retry remainder is authoritative and never falls back`() {
        val retry = LeaseDecision("IN_RANGE", session, 4, 9, Instant.parse("2000-01-01T00:00:00Z"), 731)
        assertEquals(731L, retry.validationLeaseMillis(session, 4, 9))
        assertNull(retry.copy(remainingMillis = 0).validationLeaseMillis(session, 4, 9))
        assertNull(retry.copy(remainingMillis = 5_001).validationLeaseMillis(session, 4, 9))
    }

    @Test fun `repeated fresh wire decisions renew one gate without falling into resume loop`() {
        val gate = NearbyReadingGate(session.toString())
        var now = 1_000L
        var fix = NearbyReaderFixQuality(now, 10.0, false)
        assertEquals(ReadingEligibility.UNKNOWN,
            gate.updateEnvironment(true, true, true, fix, now))

        repeat(12) { cycle ->
            val ticket = gate.beginCheck(now)!!
            val decision = LeaseDecision("IN_RANGE", session, ticket.generation, ticket.sequence,
                Instant.parse("2099-01-01T00:00:00Z"), null)
            now += 100L
            assertEquals(ReadingEligibility.IN_RANGE, gate.accept(ticket, session.toString(),
                ReadingEligibility.IN_RANGE,
                decision.validationLeaseMillis(session, ticket.generation, ticket.sequence)!!, now))
            assertEquals(ReadingEligibility.IN_RANGE, gate.eligibilityAt(now))
            now += 900L
            fix = NearbyReaderFixQuality(now, 10.0, false)
            assertEquals(ReadingEligibility.IN_RANGE,
                gate.updateEnvironment(true, true, true, fix, now))
            assertEquals(cycle + 1L, ticket.sequence)
        }
    }

    @Test fun `terminal decision does not invent a positive presentation lease`() {
        val ended = LeaseDecision("ENDED", session, 4, 9, null, null)
        assertTrue(ended.matchesValidationEnvelope(session, 4, 9))
        assertTrue(ended.matchesTerminalEnvelope(session, 4, 9))
        assertFalse(ended.copy(sequence = 8).matchesTerminalEnvelope(session, 4, 9))
        assertFalse(ended.copy(readerSessionId = UUID.randomUUID()).matchesTerminalEnvelope(session, 4, 9))
        assertFalse(ended.copy(status = "UNKNOWN").matchesTerminalEnvelope(session, 4, 9))
    }

    @Test fun `terminal resume requires the exact request envelope`() {
        val ended = OpenedDiary("OUT_OF_RANGE", session, 7, Instant.now(), null, null, null,
            Instant.now(), null, 3)
        assertTrue(ended.matchesTerminalEnvelope(session, 7, 3))
        assertFalse(ended.copy(generation = 6).matchesTerminalEnvelope(session, 7, 3))
        assertFalse(ended.copy(sequence = 2).matchesTerminalEnvelope(session, 7, 3))
        assertFalse(ended.copy(status = "CONTENT_RESUMED").matchesTerminalEnvelope(session, 7, 3))
    }

    @Test fun `immutable timezone still proves an existing account`() {
        assertEquals("TIMEZONE_IMMUTABLE", NetworkResult.ServiceFailure("TIMEZONE_IMMUTABLE").accountStatus())
        assertEquals("ACCOUNT_READY", NetworkResult.Success(AccountSnapshot("ACCOUNT_READY", "UTC", 10)).accountStatus())
        assertNull(NetworkResult.TransportFailure(java.io.IOException()).accountStatus())
    }

    @Test fun `transport failures use specific safe delivery wording`() {
        assertEquals(
            "The service address could not be reached. No result was confirmed.",
            NetworkResult.TransportFailure(UnknownHostException("secret-host")).failureMessage(),
        )
        assertEquals(
            "The service did not respond in time. The result is not yet known.",
            NetworkResult.TransportFailure(SocketTimeoutException("secret-url")).failureMessage(),
        )
        assertEquals(
            "A connection error occurred. The result could not be determined.",
            NetworkResult.TransportFailure(IllegalStateException("secret-detail")).failureMessage(),
        )
    }
}
