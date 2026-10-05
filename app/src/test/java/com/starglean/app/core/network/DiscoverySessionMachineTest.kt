package com.starglean.app.core.network

import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverySessionMachineTest {
    private val ids = generateSequence(1L) { it + 1 }.iterator()
    private val machine = DiscoverySessionMachine { UUID(0, ids.next()) }
    private val location = ReaderLocation(-37.8, 144.9, 5.0, Instant.parse("2026-09-27T00:00:00Z"), false)

    @Test fun `coordinates cannot be requested before explicit foreground consent`() {
        machine.enterForeground("user-a:session-1")
        assertTrue(machine.locationReady(location).isEmpty())
        assertEquals(listOf(DiscoverySessionMachine.Effect.RequestLocation), machine.grantDiscoveryConsent())
        assertTrue(machine.locationReady(location).single() is DiscoverySessionMachine.Effect.Prepare)
    }

    @Test fun `prepare produces exactly one commit and duplicate callback is ignored`() {
        machine.enterForeground("identity")
        machine.grantDiscoveryConsent()
        val prepare = machine.locationReady(location).single() as DiscoverySessionMachine.Effect.Prepare
        val session = UUID(0, 99)
        val staged = PreparedOpen("CONTENT_STAGED", session, prepare.request.generation, Instant.parse("2026-09-27T00:00:08Z"))
        val first = machine.prepared(prepare.operation, staged)
        assertTrue(first.single() is DiscoverySessionMachine.Effect.Commit)
        assertTrue(machine.prepared(prepare.operation, staged).isEmpty())
    }

    @Test fun `foreground termination masks and late commit cannot resurrect`() {
        machine.enterForeground("identity")
        machine.grantDiscoveryConsent()
        val prepare = machine.locationReady(location).single() as DiscoverySessionMachine.Effect.Prepare
        val session = UUID(0, 99)
        val commit = machine.prepared(prepare.operation, PreparedOpen("CONTENT_STAGED", session, prepare.request.generation, null))
            .single() as DiscoverySessionMachine.Effect.Commit
        val termination = machine.leaveForeground()
        assertTrue(termination.any { it is DiscoverySessionMachine.Effect.MaskContent })
        assertTrue(termination.any { it == DiscoverySessionMachine.Effect.End(session) })
        val late = OpenedDiary("OPEN_COMMITTED", session, commit.request.generation, null, null, 1, 10, null,
            DiaryContent("title", "private body", "warm_white", "clear", null, emptyList()))
        assertTrue(machine.committed(commit.operation, late).isEmpty())
        assertEquals(DiscoverySessionMachine.Phase.Ended, machine.phase)
    }

    @Test fun `authentication identity change terminates active session`() {
        machine.enterForeground("user-a:session-1")
        machine.grantDiscoveryConsent()
        val prepare = machine.locationReady(location).single() as DiscoverySessionMachine.Effect.Prepare
        val session = UUID(0, 88)
        machine.prepared(prepare.operation, PreparedOpen("CONTENT_STAGED", session, prepare.request.generation, null))
        val effects = machine.authenticationChanged("user-a:session-2")
        assertTrue(effects.any { it == DiscoverySessionMachine.Effect.End(session) })
        assertEquals(DiscoverySessionMachine.Phase.Ended, machine.phase)
    }

    @Test fun `publication identity survives coordinator recreation and remains immutable`() {
        val values = mutableMapOf<String, String>()
        val store = object : PublicationJournalStore {
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String?): Boolean { if (value == null) values.remove(key) else values[key] = value; return true }
        }
        val requestId = UUID.randomUUID()
        DurablePublicationJournal(store).begin(requestId)
        val afterCrash = DurablePublicationJournal(store)
        assertEquals(requestId, afterCrash.pendingRequestId())
        afterCrash.begin(requestId)
        runCatching { afterCrash.begin(UUID.randomUUID()) }.also { assertTrue(it.isFailure) }
        afterCrash.complete(requestId)
        assertEquals(null, DurablePublicationJournal(store).pendingRequestId())
    }

    @Test fun `publication fence rejects changed draft payload after recreation`() {
        val values = mutableMapOf<String, String>()
        val store = object : PublicationJournalStore {
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String?): Boolean { if (value == null) values.remove(key) else values[key] = value; return true }
        }
        val frozen = PublicationFence(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64))
        PublicationFenceJournal(store).begin(frozen)
        val restarted = PublicationFenceJournal(store)
        assertEquals(frozen, restarted.read())
        assertTrue(runCatching { restarted.begin(frozen.copy(payloadSha256 = "b".repeat(64))) }.isFailure)
    }
}
