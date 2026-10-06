package com.starglean.app.core.network

import com.starglean.app.core.discovery.NearbyReaderFixQuality
import com.starglean.app.core.discovery.NearbyReadingGate
import com.starglean.app.core.model.ReadingEligibility
import com.starglean.app.ui.screens.validationLeaseMillis
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlinx.coroutines.CancellationException

class SupabaseNearbyClientTest {
    @Test fun `publication payload keeps the complete real weather snapshot`() {
        val captured = Instant.parse("2026-09-27T01:02:03Z")
        val request = PublishEntryRequest(UUID.randomUUID(), "title", "0123456789 body", "warm_white", "clear",
            WeatherSnapshot(WeatherKind.Rain, Daylight.Night, 12, captured), visibility = EntryVisibility.Public,
            location = ReaderLocation(-37.8, 144.9, 8.0, captured, false), shareConfirmed = true)

        @Suppress("UNCHECKED_CAST")
        val weather = request.payload()["weather_snapshot"] as Map<String, Any?>

        assertEquals(mapOf("kind" to "rain", "daylight" to "night", "temperature_c" to 12,
            "captured_at" to captured.toString(), "is_demo" to false), weather)
    }

    @Test fun `recorded publication weather cannot lose its observation time`() {
        assertThrows(IllegalArgumentException::class.java) {
            WeatherSnapshot(WeatherKind.Clear, Daylight.Day, 21, capturedAt = null)
        }
        assertEquals(null, WeatherSnapshot(WeatherKind.Unknown, Daylight.Day).capturedAt)
    }

    @Test fun `opened public diary restores weather returned by the service`() {
        val session = UUID.randomUUID()
        val requestId = UUID.randomUUID()
        val captured = "2026-09-27T01:02:03Z"
        val connection = FakeConnection("""{"status":"OPEN_COMMITTED","reader_session_id":"$session","generation":2,"sequence":1,"title":"title","body":"0123456789 body","paper_style":"warm_white","font_style":"clear","weather_snapshot":{"kind":"fog","daylight":"dusk","temperature_c":7,"captured_at":"$captured","is_demo":false},"media":[]}""")
        val repository = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "jwt" }, HttpsConnectionFactory { connection }))

        val result = runSuspend { repository.commitOpen(SessionLocationRequest(requestId, session, 2, 1,
            ReaderLocation(-37.8, 144.9, 8.0, Instant.parse(captured), false))) }

        assertTrue(result is NetworkResult.Success)
        @Suppress("UNCHECKED_CAST")
        val weather = (result as NetworkResult.Success<OpenedDiary>).value.content?.weather
        assertEquals(WeatherSnapshot(WeatherKind.Fog, Daylight.Dusk, 7, Instant.parse(captured)), weather)
    }

    @Test fun `legacy public diary without a weather snapshot remains honestly unavailable`() {
        val session = UUID.randomUUID()
        val connection = FakeConnection("""{"status":"OPEN_COMMITTED","reader_session_id":"$session","generation":2,"sequence":1,"title":"old","body":"0123456789 body","paper_style":"warm_white","font_style":"clear","media":[]}""")
        val repository = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "jwt" }, HttpsConnectionFactory { connection }))

        val result = runSuspend { repository.commitOpen(SessionLocationRequest(UUID.randomUUID(), session, 2, 1,
            ReaderLocation(-37.8, 144.9, 8.0, Instant.parse("2026-09-27T01:02:03Z"), false))) }

        assertTrue(result is NetworkResult.Success)
        @Suppress("UNCHECKED_CAST")
        val opened = (result as NetworkResult.Success<OpenedDiary>).value
        assertEquals(null, opened.content?.weather)
    }

    @Test fun `all blocking transports leave the caller thread`() {
        val caller = Thread.currentThread()
        val transportThreads = mutableListOf<Thread>()
        val responses = ArrayDeque(listOf(
            FakeConnection("""{"status":"ACCOUNT_READY"}"""),
            FakeConnection("{}"),
            FakeConnection("{}").apply { statusCode = 401 },
            FakeConnection("{}").apply { statusCode = 401 },
        ))
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123",
            AccessTokenProvider { "jwt" }, HttpsConnectionFactory {
                synchronized(transportThreads) { transportThreads += Thread.currentThread() }
                responses.removeFirst()
            })
        val entry = "00000000-0000-0000-0000-000000000001"
        val media = "00000000-0000-0000-0000-000000000002"

        assertTrue(runSuspend { client.command("account_get", emptyMap()) } is NetworkResult.Success)
        assertTrue(runSuspend { client.uploadObject("nearby-private-photos", "$entry/$entry/$media", "image/jpeg", byteArrayOf(1)) } is NetworkResult.Success)
        assertEquals(NetworkResult.AuthenticationRequired, runSuspend { client.edgeCommand("finalize", mapOf("entry_id" to entry, "media_id" to media)) })
        assertEquals(NetworkResult.AuthenticationRequired, runSuspend { client.readPrivateMedia(UUID.fromString(media)) })

        assertEquals(4, transportThreads.size)
        assertTrue(transportThreads.all { it !== caller })
    }

    @Test fun `prepare request matches rpc envelope and sends auth once`() {
        val connection = FakeConnection("""{"status":"CONTENT_STAGED","reader_session_id":"00000000-0000-0000-0000-000000000002","generation":3}""")
        var opened = 0
        val repository = NearbyRepository(SupabaseNearbyClient(
            URL("https://example.supabase.co"), "sb_publishable_test_key_123", AccessTokenProvider { "jwt" },
            HttpsConnectionFactory { opened++; connection },
        ))
        val result = runSuspend { repository.prepareOpen(PrepareOpenRequest(
            UUID.fromString("00000000-0000-0000-0000-000000000001"), 3,
            ReaderLocation(-37.81, 144.96, 6.5, Instant.parse("2026-09-27T00:00:00Z"), false),
        )) }

        assertTrue(result is NetworkResult.Success)
        assertEquals(1, opened)
        assertEquals("Bearer jwt", connection.headers["Authorization"])
        val envelope = JsonValue.parseObject(connection.output.toString(Charsets.UTF_8.name()))
        assertEquals("prepare_open", envelope["action"])
        @Suppress("UNCHECKED_CAST") val payload = envelope["payload"] as Map<String, Any?>
        assertEquals(setOf("request_id", "generation", "latitude", "longitude", "accuracy_m", "captured_at", "is_mock"), payload.keys)
        assertFalse(connection.output.toString().contains("service_role"))
    }

    @Test fun `fresh validation wire shape preserves missing retry remainder`() {
        val session = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val connection = FakeConnection("""{"status":"IN_RANGE","reader_session_id":"$session","generation":3,"sequence":7,"server_expires_at":"2026-09-27T01:02:08Z"}""")
        val repository = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "jwt" }, HttpsConnectionFactory { connection }))

        val result = runSuspend { repository.validate(SessionLocationRequest(UUID.randomUUID(), session, 3, 7,
            ReaderLocation(-37.8, 144.9, 8.0, Instant.parse("2026-09-27T01:02:03Z"), false))) }

        assertTrue(result is NetworkResult.Success)
        @Suppress("UNCHECKED_CAST") val decision = (result as NetworkResult.Success<LeaseDecision>).value
        assertEquals(Instant.parse("2026-09-27T01:02:08Z"), decision.expiresAt)
        assertEquals(null, decision.remainingMillis)
    }

    @Test fun `fresh validation wire renews the gate across arrival and refresh cycles`() {
        val session = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val generation = 3L
        val responses = ArrayDeque((1L..4L).map { sequence ->
            FakeConnection("""{"status":"IN_RANGE","reader_session_id":"$session","generation":$generation,"sequence":$sequence,"server_expires_at":"2026-09-27T01:02:08Z"}""")
        })
        val repository = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "jwt" },
            HttpsConnectionFactory { responses.removeFirst() }))
        val gate = NearbyReadingGate(session.toString())
        var dispatchedAt = 1_000L

        repeat(4) { index ->
            val fix = NearbyReaderFixQuality(dispatchedAt, 10.0, false)
            assertEquals(if (index == 0) ReadingEligibility.UNKNOWN else ReadingEligibility.IN_RANGE,
                gate.updateEnvironment(true, true, true, fix, dispatchedAt))
            val ticket = gate.beginCheck(dispatchedAt)!!
            val result = runSuspend { repository.validate(SessionLocationRequest(UUID.randomUUID(), session,
                generation, ticket.sequence, ReaderLocation(-37.8, 144.9, 8.0,
                    Instant.parse("2026-09-27T01:02:03Z"), false))) }
            assertTrue(result is NetworkResult.Success)
            @Suppress("UNCHECKED_CAST")
            val decision = (result as NetworkResult.Success<LeaseDecision>).value
            val responseAt = dispatchedAt + 100L
            assertEquals(5_000L,
                decision.validationLeaseMillis(session, generation, ticket.sequence))
            assertEquals(ReadingEligibility.IN_RANGE, gate.accept(ticket, session.toString(),
                ReadingEligibility.IN_RANGE,
                decision.validationLeaseMillis(session, generation, ticket.sequence)!!, responseAt))
            assertEquals(dispatchedAt + 5_000L, gate.visibilityDeadlineAt(responseAt))
            dispatchedAt += 1_200L
        }
        assertTrue(dispatchedAt > 1_000L + 2_330L)
        assertTrue(responses.isEmpty())

        // The compatibility lease is still capped by the exact captured fix's eight-second life.
        val fixBoundGate = NearbyReadingGate(session.toString())
        val oldButQualifiedFix = NearbyReaderFixQuality(2_000L, 10.0, false)
        assertEquals(ReadingEligibility.UNKNOWN,
            fixBoundGate.updateEnvironment(true, true, true, oldButQualifiedFix, 9_000L))
        val fixBoundTicket = fixBoundGate.beginCheck(9_000L)!!
        assertEquals(ReadingEligibility.IN_RANGE, fixBoundGate.accept(fixBoundTicket, session.toString(),
            ReadingEligibility.IN_RANGE, 5_000L, 9_100L))
        assertEquals(10_000L, fixBoundGate.visibilityDeadlineAt(9_100L))
        assertEquals(ReadingEligibility.UNKNOWN, fixBoundGate.eligibilityAt(10_000L))
    }

    @Test fun `present malformed or unbounded validation remainder fails the wire response`() {
        val session = UUID.fromString("00000000-0000-0000-0000-000000000002")
        listOf("null", "\"5000\"", "1.5", "0", "5001").forEach { remainder ->
            val connection = FakeConnection("""{"status":"IN_RANGE","reader_session_id":"$session","generation":3,"sequence":7,"server_expires_at":"2026-09-27T01:02:08Z","remaining_ms":$remainder}""")
            val repository = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
                "sb_publishable_test_key_123", AccessTokenProvider { "jwt" }, HttpsConnectionFactory { connection }))

            val result = runSuspend { repository.validate(SessionLocationRequest(UUID.randomUUID(), session, 3, 7,
                ReaderLocation(-37.8, 144.9, 8.0, Instant.parse("2026-09-27T01:02:03Z"), false))) }

            assertTrue("remaining_ms=$remainder must fail closed", result is NetworkResult.InvalidResponse)
        }
    }

    @Test fun `transport failure is not replayed automatically`() {
        var opened = 0
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123", AccessTokenProvider { "jwt" },
            HttpsConnectionFactory { opened++; throw java.io.IOException("offline") })
        val result = runSuspend { client.command("react", mapOf("request_id" to UUID.randomUUID().toString())) }
        assertTrue(result is NetworkResult.TransportFailure)
        assertEquals(1, opened)
    }

    @Test fun `cancellation is never converted into an unknown transport outcome`() {
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123", AccessTokenProvider { "jwt" },
            HttpsConnectionFactory { throw CancellationException("left foreground") })

        val thrown = runCatching { runSuspend { client.command("publication_status", emptyMap()) } }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }

    @Test fun `oversized response fails closed`() {
        val connection = FakeConnection("{" + "x".repeat(2000) + "}")
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123", AccessTokenProvider { "jwt" },
            HttpsConnectionFactory { connection }, maxResponseBytes = 1024)
        assertTrue(runSuspend { client.command("validate", emptyMap()) } is NetworkResult.InvalidResponse)
    }

    @Test fun `protocol error status is not a success`() {
        val connection = FakeConnection("""{"status":"REQUEST_CONFLICT"}""")
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123", AccessTokenProvider { "jwt" },
            HttpsConnectionFactory { connection })
        assertEquals(NetworkResult.ServiceFailure("REQUEST_CONFLICT"), runSuspend { client.command("create_entry", emptyMap()) })
    }

    @Test fun `cloud entry capacity remains a definite service rejection`() {
        val connection = FakeConnection("""{"status":"CLOUD_ENTRY_LIMIT"}""")
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123",
            AccessTokenProvider { "jwt" }, HttpsConnectionFactory { connection })

        assertEquals(NetworkResult.ServiceFailure("CLOUD_ENTRY_LIMIT"),
            runSuspend { client.command("create_public", emptyMap()) })
    }

    @Test fun `malformed own entry fails the whole response instead of inventing empty state`() {
        val connection = FakeConnection("""{"status":"FOUND","items":[{"entry_id":"bad"}]}""")
        val repository = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "jwt" }, HttpsConnectionFactory { connection }))
        assertTrue(runSuspend { repository.ownEntries() } is NetworkResult.InvalidResponse)
    }

    @Test fun `upload is single attempt and explicitly disables upsert`() {
        val connection = FakeConnection("{}")
        var opened = 0
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123",
            AccessTokenProvider { "jwt" }, HttpsConnectionFactory { opened++; connection })
        val bytes = byteArrayOf(1, 2, 3)
        val result = runSuspend { client.uploadObject("nearby-private-photos",
            "00000000-0000-0000-0000-000000000001/00000000-0000-0000-0000-000000000002/00000000-0000-0000-0000-000000000003",
            "image/jpeg", bytes) }
        assertTrue(result is NetworkResult.Success)
        assertEquals("false", connection.headers["x-upsert"])
        assertEquals(1, opened)
    }

    @Test fun `reader media request sends only opaque media id to edge function`() {
        val connection = FakeConnection("jpeg-bytes")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("jpeg-bytes".toByteArray())
        connection.responseHeaders["Content-Type"] = "image/jpeg"
        connection.responseHeaders["Digest"] = "sha-256=" + java.util.Base64.getEncoder().encodeToString(digest)
        var requestedUrl: URL? = null
        val client = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123",
            AccessTokenProvider { "jwt" }, HttpsConnectionFactory { requestedUrl = it; connection })
        val mediaId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val result = runSuspend { client.readPrivateMedia(mediaId) }
        assertTrue(result is NetworkResult.Success)
        assertEquals("/functions/v1/nearby-private-media", requestedUrl?.path)
        assertEquals(mapOf("action" to "read", "media_id" to mediaId.toString()),
            JsonValue.parseObject(connection.output.toString(Charsets.UTF_8.name())))
        assertFalse(connection.output.toString().contains("session"))
    }

    @Test fun `scoped request reports the exact rejected old token`() {
        val connection = FakeConnection("{}").apply { statusCode = 401 }
        val rejected = mutableListOf<String>()
        val base = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123",
            AccessTokenProvider { "new-session-token" }, HttpsConnectionFactory { connection },
            authRejectionHandler = AuthRejectionHandler { rejected += it })

        val result = runSuspend { base.scoped("old-request-token").command("account_get", emptyMap()) }

        assertEquals(NetworkResult.AuthenticationRequired, result)
        assertEquals(listOf("old-request-token"), rejected)
        assertEquals("Bearer old-request-token", connection.headers["Authorization"])
    }

    @Test fun `rpc auth required body invokes callback but other failures do not`() {
        val rejected = mutableListOf<String>()
        fun client(connection: FakeConnection) = SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "bound-token" }, HttpsConnectionFactory { connection },
            authRejectionHandler = AuthRejectionHandler { rejected += it })

        assertEquals(NetworkResult.AuthenticationRequired, runSuspend {
            client(FakeConnection("""{"status":"AUTH_REQUIRED"}""")).command("account_get", emptyMap())
        })
        listOf(403, 404, 500).forEach { code ->
            val connection = FakeConnection("{}").apply { statusCode = code }
            assertEquals(NetworkResult.HttpFailure(code), runSuspend { client(connection).command("account_get", emptyMap()) })
        }
        val offline = SupabaseNearbyClient(URL("https://example.supabase.co"), "sb_publishable_test_key_123",
            AccessTokenProvider { "bound-token" }, HttpsConnectionFactory { throw java.io.IOException("offline") },
            authRejectionHandler = AuthRejectionHandler { rejected += it })
        assertTrue(runSuspend { offline.command("account_get", emptyMap()) } is NetworkResult.TransportFailure)
        assertEquals(listOf("bound-token"), rejected)
    }

    @Test fun `storage and edge 401s use the same token bound rejection bridge`() {
        val rejected = mutableListOf<String>()
        fun client(connection: FakeConnection) = SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "transport-token" }, HttpsConnectionFactory { connection },
            authRejectionHandler = AuthRejectionHandler { rejected += it })
        fun rejectedConnection() = FakeConnection("{}").apply { statusCode = 401 }
        val entry = "00000000-0000-0000-0000-000000000001"
        val media = "00000000-0000-0000-0000-000000000002"

        assertEquals(NetworkResult.AuthenticationRequired, runSuspend {
            client(rejectedConnection()).uploadObject("nearby-private-photos", "$entry/$entry/$media", "image/jpeg", byteArrayOf(1))
        })
        assertEquals(NetworkResult.AuthenticationRequired, runSuspend {
            client(rejectedConnection()).edgeCommand("finalize", mapOf("entry_id" to entry, "media_id" to media))
        })
        assertEquals(NetworkResult.AuthenticationRequired, runSuspend {
            client(rejectedConnection()).readPrivateMedia(UUID.fromString(media))
        })
        assertEquals(listOf("transport-token", "transport-token", "transport-token"), rejected)
    }

    @Test fun `ensure account reads existing account without registration`() {
        val calls = mutableListOf<FakeConnection>()
        val ready = FakeConnection("""{"status":"ACCOUNT_READY","account_timezone":"UTC","daily_limit":10}""")
        val repository = queuedRepository(listOf(ready), calls)

        val result = runSuspend { repository.ensureAccount("Australia/Melbourne") }

        assertTrue(result is NetworkResult.Success)
        assertEquals(listOf("account_get"), calls.map(::actionOf))
    }

    @Test fun `ensure account registers only after definitive setup required`() {
        val calls = mutableListOf<FakeConnection>()
        val repository = queuedRepository(listOf(
            FakeConnection("""{"status":"ACCOUNT_SETUP_REQUIRED"}"""),
            FakeConnection("""{"status":"ACCOUNT_READY","account_timezone":"Australia/Melbourne","daily_limit":10}"""),
        ), calls)

        val result = runSuspend { repository.ensureAccount("Australia/Melbourne") }

        assertTrue(result is NetworkResult.Success)
        assertEquals(listOf("account_get", "register_account"), calls.map(::actionOf))
    }

    @Test fun `ensure account does not initialize or retry after auth or transport failure`() {
        val authCalls = mutableListOf<FakeConnection>()
        val unauthorized = FakeConnection("{}").apply { statusCode = 401 }
        assertEquals(NetworkResult.AuthenticationRequired,
            runSuspend { queuedRepository(listOf(unauthorized), authCalls).ensureAccount("UTC") })
        assertEquals(listOf("account_get"), authCalls.map(::actionOf))

        var opened = 0
        val offline = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "jwt" }, HttpsConnectionFactory {
                opened++; throw java.io.IOException("offline")
            }))
        assertTrue(runSuspend { offline.ensureAccount("UTC") } is NetworkResult.TransportFailure)
        assertEquals(1, opened)
    }

    @Test fun `timezone immutable registration race rereads fixed account without reset`() {
        val calls = mutableListOf<FakeConnection>()
        val repository = queuedRepository(listOf(
            FakeConnection("""{"status":"ACCOUNT_SETUP_REQUIRED"}"""),
            FakeConnection("""{"status":"TIMEZONE_IMMUTABLE"}"""),
            FakeConnection("""{"status":"ACCOUNT_READY","account_timezone":"Australia/Sydney","daily_limit":10}"""),
        ), calls)

        val result = runSuspend { repository.ensureAccount("Australia/Melbourne") }

        assertEquals("Australia/Sydney", (result as NetworkResult.Success).value.accountTimeZone)
        assertEquals(listOf("account_get", "register_account", "account_get"), calls.map(::actionOf))
    }

    private fun queuedRepository(responses: List<FakeConnection>, calls: MutableList<FakeConnection>): NearbyRepository {
        val queue = ArrayDeque(responses)
        return NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "jwt" }, HttpsConnectionFactory {
                queue.removeFirst().also(calls::add)
            }))
    }

    private fun actionOf(connection: FakeConnection): String =
        JsonValue.parseObject(connection.output.toString(Charsets.UTF_8.name()))["action"] as String
}

private class FakeConnection(private val response: String) : HttpsURLConnection(URL("https://example.supabase.co")) {
    val output = ByteArrayOutputStream()
    val headers = linkedMapOf<String, String>()
    val responseHeaders = linkedMapOf<String, String>()
    override fun setRequestProperty(key: String, value: String) { headers[key] = value }
    override fun getOutputStream() = output
    var statusCode = 200
    override fun getResponseCode() = statusCode
    override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    override fun getErrorStream() = ByteArrayInputStream(response.toByteArray())
    override fun getHeaderField(name: String?): String? = responseHeaders[name]
    override fun disconnect() = Unit
    override fun usingProxy() = false
    override fun connect() = Unit
    override fun getCipherSuite(): String = "test"
    override fun getLocalCertificates(): Array<Certificate>? = null
    @Throws(SSLPeerUnverifiedException::class) override fun getServerCertificates(): Array<Certificate>? = null
    @Throws(SSLPeerUnverifiedException::class) override fun getPeerPrincipal(): Principal? = null
    override fun getLocalPrincipal(): Principal? = null
}

private fun <T> runSuspend(block: suspend () -> T): T {
    val outcome = AtomicReference<Result<T>>()
    val completed = CountDownLatch(1)
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome.set(result)
            completed.countDown()
        }
    })
    check(completed.await(10, TimeUnit.SECONDS)) { "Suspending test did not complete" }
    return checkNotNull(outcome.get()).getOrThrow()
}
