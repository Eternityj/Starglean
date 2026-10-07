package com.starglean.app.core.online

import com.starglean.app.core.account.AccountIdentity
import com.starglean.app.core.account.AuthHttpResponse
import com.starglean.app.core.account.AuthSession
import com.starglean.app.core.account.AuthTransport
import com.starglean.app.core.account.SessionStore
import com.starglean.app.core.account.SupabaseAuthClient
import com.starglean.app.core.network.AccountSnapshot
import com.starglean.app.core.network.AccessTokenProvider
import com.starglean.app.core.network.Daylight
import com.starglean.app.core.network.HttpsConnectionFactory
import com.starglean.app.core.network.NearbyRepository
import com.starglean.app.core.network.NetworkResult
import com.starglean.app.core.network.OwnEntriesCursor
import com.starglean.app.core.network.OwnEntriesSnapshot
import com.starglean.app.core.network.OwnEntry
import com.starglean.app.core.network.OwnerAnchor
import com.starglean.app.core.network.ReactionInbox
import com.starglean.app.core.network.ReactionKind
import com.starglean.app.core.network.ReactionSummary
import com.starglean.app.core.network.SupabaseNearbyClient
import com.starglean.app.core.network.WeatherKind
import com.starglean.app.core.network.WeatherSnapshot
import java.net.URL
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLibraryControllerTest {
    @Test fun `inbox failure does not block the first entries page`() = runBlocking {
        val loader = FakeLoader().apply {
            entries = { _, _ -> successPage((1..3).map(::entry)) }
            inbox = { _ -> NetworkResult.TransportFailure(IllegalStateException("offline")) }
        }
        fixture(loader).use { fixture ->
            val controller = fixture.controller
            controller.setActive(true, ACCOUNT_A)
            awaitState(controller) { it.entriesLoaded && !it.inboxLoading }

            assertEquals((1..3).map(::entry), controller.state.value.entries)
            assertTrue(controller.state.value.entriesMessage == null)
            assertNotNull(controller.state.value.inboxMessage)
            assertFalse(controller.state.value.inboxLoaded)
        }
    }

    @Test fun `load more failure keeps the old page and remains retryable`() = runBlocking {
        var calls = 0
        val first = (1..10).map(::entry)
        val loader = FakeLoader().apply {
            entries = { _, _ ->
                calls++
                if (calls == 1) successPage(first) else NetworkResult.TransportFailure(IllegalStateException("offline"))
            }
        }
        fixture(loader).use { fixture ->
            val controller = fixture.controller
            controller.setActive(true, ACCOUNT_A)
            awaitState(controller) { it.entriesLoaded }
            controller.loadMoreEntries()
            awaitState(controller) { !it.entriesLoading && it.entriesMessage != null }

            assertEquals(first, controller.state.value.entries)
            assertTrue(controller.state.value.hasMoreEntries)
            assertEquals(2, calls)
        }
    }

    @Test fun `late non cooperative response from the old account cannot replace new account state`() = runBlocking {
        val oldResponse = CompletableDeferred<NetworkResult<OwnEntriesSnapshot>>()
        val loader = FakeLoader().apply {
            entries = { account, _ ->
                if (account == ACCOUNT_A.userId) try { oldResponse.await() }
                catch (_: kotlinx.coroutines.CancellationException) { withContext(NonCancellable) { oldResponse.await() } }
                else successPage(listOf(entry(20)))
            }
        }
        fixture(loader).use { fixture ->
            val controller = fixture.controller
            controller.setActive(true, ACCOUNT_A)
            awaitCondition { loader.entryCalls[ACCOUNT_A.userId] == 1 }
            controller.setActive(true, ACCOUNT_B)
            awaitState(controller) { it.account?.userId == ACCOUNT_B.userId && it.entriesLoaded }
            oldResponse.complete(successPage(listOf(entry(1))))
            delay(50)

            assertEquals(ACCOUNT_B.userId, controller.state.value.account?.userId)
            assertEquals(listOf(entry(20)), controller.state.value.entries)
        }
    }

    @Test fun `inbox resolves only owner entry ids actually returned by the inbox`() = runBlocking {
        val allowed = entry(7)
        val unrelated = entry(8)
        val loader = FakeLoader().apply {
            inbox = { _ -> NetworkResult.Success(ReactionInbox("FOUND", Instant.parse("2026-09-01T00:00:00Z"),
                listOf(ReactionSummary(allowed.entryId, ReactionKind.Hug, 2, 1)))) }
            ownEntry = { _, id -> NetworkResult.Success(if (id == allowed.entryId) allowed else unrelated) }
        }
        fixture(loader).use { fixture ->
            val controller = fixture.controller
            controller.setActive(true, ACCOUNT_A)
            awaitState(controller) { it.inboxEntries[allowed.entryId] == allowed }
            controller.resolveInboxEntry(unrelated.entryId)
            delay(50)

            assertEquals(listOf(allowed.entryId), loader.ownEntryCalls)
            assertFalse(controller.state.value.inboxEntries.containsKey(unrelated.entryId))
        }
    }

    @Test fun `mismatched owner entry response is rejected instead of opening another diary`() = runBlocking {
        val requested = entry(9)
        val mismatched = entry(10)
        val loader = FakeLoader().apply {
            inbox = { _ -> NetworkResult.Success(ReactionInbox("FOUND", Instant.parse("2026-09-01T00:00:00Z"),
                listOf(ReactionSummary(requested.entryId, ReactionKind.Thanks, 1, 1)))) }
            ownEntry = { _, _ -> NetworkResult.Success(mismatched) }
        }
        fixture(loader).use { fixture ->
            val controller = fixture.controller
            controller.setActive(true, ACCOUNT_A)
            awaitState(controller) { requested.entryId in it.inboxEntryErrors }

            assertFalse(controller.state.value.inboxEntries.containsKey(requested.entryId))
            assertFalse(controller.state.value.inboxEntries.containsValue(mismatched))
        }
    }

    private class Fixture(
        val controller: CloudLibraryController,
        private val scope: CoroutineScope,
        private val dispatcher: ExecutorCoroutineDispatcher,
    ) : java.io.Closeable {
        override fun close() {
            controller.setActive(false, null)
            scope.cancel()
            dispatcher.close()
        }
    }

    private fun fixture(loader: FakeLoader): Fixture {
        // Production owns this controller from Dispatchers.Main.immediate. Keep tests on a
        // serial dispatcher too, so independent entry/inbox jobs cannot manufacture a
        // concurrent StateFlow read-copy-write race that the production contract excludes.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return Fixture(
            CloudLibraryController(dummyRepository(), null, null, scope, auth = dummyAuth(), loader = loader),
            scope,
            dispatcher,
        )
    }

    private class FakeLoader : CloudLibraryLoader {
        var entries: suspend (String, OwnEntriesCursor?) -> NetworkResult<OwnEntriesSnapshot> = { _, _ ->
            NetworkResult.Success(OwnEntriesSnapshot("FOUND", emptyList(), null))
        }
        var inbox: suspend (String) -> NetworkResult<ReactionInbox> = { _ -> NetworkResult.Success(ReactionInbox("FOUND", null, emptyList())) }
        var ownEntry: suspend (String, UUID) -> NetworkResult<OwnEntry?> = { _, _ -> NetworkResult.Success(null) }
        val entryCalls: MutableMap<String, Int> = java.util.Collections.synchronizedMap(mutableMapOf())
        val ownEntryCalls: MutableList<UUID> = java.util.Collections.synchronizedList(mutableListOf())

        override suspend fun open(accountId: String) = object : CloudLibraryLoadSession {
            override suspend fun isCurrent() = true
            override suspend fun ensureAccount(timeZone: String) = NetworkResult.Success(AccountSnapshot("ACCOUNT_READY", timeZone, 10))
            override suspend fun ownEntries(limit: Int, before: OwnEntriesCursor?): NetworkResult<OwnEntriesSnapshot> {
                synchronized(entryCalls) { entryCalls[accountId] = (entryCalls[accountId] ?: 0) + 1 }
                return entries(accountId, before)
            }
            override suspend fun reactionInbox() = inbox(accountId)
            override suspend fun ownEntry(entryId: UUID): NetworkResult<OwnEntry?> {
                synchronized(ownEntryCalls) { ownEntryCalls += entryId }
                return ownEntry(accountId, entryId)
            }
        }
    }

    private suspend fun awaitState(controller: CloudLibraryController, predicate: (CloudLibraryState) -> Boolean) =
        withTimeout(2_000) { while (!predicate(controller.state.value)) delay(5) }
    private suspend fun awaitCondition(predicate: () -> Boolean) = withTimeout(2_000) { while (!predicate()) delay(5) }

    private fun successPage(items: List<OwnEntry>): NetworkResult<OwnEntriesSnapshot> = NetworkResult.Success(
        OwnEntriesSnapshot("FOUND", items, items.lastOrNull()?.let { OwnEntriesCursor(it.createdAt, it.entryId) }))

    private fun entry(number: Int): OwnEntry {
        val id = UUID(0L, number.toLong())
        val at = Instant.parse("2026-09-01T00:00:00Z").minusSeconds(number.toLong())
        return OwnEntry(UUID(1L, number.toLong()), id, "Diary $number", "A sufficiently complete diary body.", at,
            "private", "private", "warm_white", "clear", WeatherSnapshot(WeatherKind.Clear, Daylight.Day, 20, at),
            OwnerAnchor(-37.8, 144.9, 8.0, at), emptyList())
    }

    private fun dummyRepository() = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
        "sb_publishable_test_key_123", AccessTokenProvider { "unused" }, HttpsConnectionFactory { error("unused") }))

    private fun dummyAuth() = SupabaseAuthClient(object : AuthTransport {
        override suspend fun request(method: String, path: String, body: String?, accessToken: String?) = AuthHttpResponse(500, "")
    }, object : SessionStore {
        override suspend fun read(): AuthSession? = null
        override suspend fun write(session: AuthSession) = Unit
        override suspend fun clear() = Unit
    })

    companion object {
        val ACCOUNT_A = AccountIdentity("00000000-0000-0000-0000-000000000001", "a@example.test", false)
        val ACCOUNT_B = AccountIdentity("00000000-0000-0000-0000-000000000002", "b@example.test", false)
    }
}
