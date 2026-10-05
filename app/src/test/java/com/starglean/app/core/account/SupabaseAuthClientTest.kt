package com.starglean.app.core.account

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseAuthClientTest {
    private val session = AuthSession(
        userId = "user-a",
        email = "reader@example.com",
        isAnonymous = false,
        accessToken = "access-secret",
        refreshToken = "refresh-secret",
        expiresAtEpochSeconds = 10_000,
    )

    @Test fun `new account verifies only signup token and persists verified session`() = runBlocking {
        val store = MemorySessionStore(null)
        val transport = RecordingTransport { request ->
            assertEquals("signup", JSONObject(requireNotNull(request.body)).getString("type"))
            sessionResponse(session)
        }
        val result = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
            .verifyEmailOtp("Reader@Example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)

        assertTrue(result is AuthOutcome.Success)
        assertEquals(session, store.value)
        assertEquals(1, transport.calls)
    }

    @Test fun `existing address in registration is proven then locally revoked without persistence`() = runBlocking {
        val previous = session.copy(userId = "local-user", email = "local@example.com")
        val store = MemorySessionStore(previous)
        val requests = mutableListOf<Request>()
        val transport = RecordingTransport { request ->
            requests += request
            when (requests.size) {
                1 -> AuthHttpResponse(403, "{\"error_code\":\"otp_expired\"}")
                2 -> sessionResponse(session)
                3 -> AuthHttpResponse(204, "")
                else -> error("unexpected request")
            }
        }

        val result = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
            .verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)

        assertTrue(result is AuthOutcome.Failure)
        assertEquals(AuthError.Kind.ACCOUNT_EXISTS, (result as AuthOutcome.Failure).error.kind)
        assertEquals(listOf("signup", "magiclink"), requests.take(2).map { JSONObject(requireNotNull(it.body)).getString("type") })
        assertEquals("/auth/v1/logout?scope=local", requests[2].path)
        assertEquals(session.accessToken, requests[2].accessToken)
        assertEquals(previous, store.value)
        assertEquals(0, store.clearCount)
    }

    @Test fun `wrong or expired registration code never claims account exists`() = runBlocking {
        val store = MemorySessionStore(null)
        val transport = RecordingTransport { AuthHttpResponse(403, "{\"error_code\":\"otp_expired\"}") }

        val result = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
            .verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)

        assertTrue(result is AuthOutcome.Failure)
        assertEquals(AuthError.Kind.UNAUTHORIZED, (result as AuthOutcome.Failure).error.kind)
        assertEquals(2, transport.calls)
        assertNull(store.value)
    }

    @Test fun `registration fallback requires machine otp expired code`() = runBlocking {
        for (response in listOf(
            AuthHttpResponse(403, "{\"message\":\"Email link is invalid or has expired\"}"),
            AuthHttpResponse(422, "{\"error_code\":\"otp_expired\"}"),
        )) {
            val transport = RecordingTransport { response }
            val result = SupabaseAuthClient(transport, MemorySessionStore(null), EpochClock { 1_000 })
                .verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)
            assertTrue(result is AuthOutcome.Failure)
            assertEquals(1, transport.calls)
        }
    }

    @Test fun `registration fallback preserves network and rate failures`() = runBlocking {
        val responses = java.util.ArrayDeque<Any>().apply {
            add(AuthHttpResponse(403, "{\"error_code\":\"otp_expired\"}"))
            add(AuthNetworkException(java.io.IOException("offline")))
        }
        val network = SupabaseAuthClient(RecordingTransport {
            when (val next = responses.removeFirst()) {
                is AuthHttpResponse -> next
                is Throwable -> throw next
                else -> error("unexpected")
            }
        }, MemorySessionStore(null), EpochClock { 1_000 })
        val networkResult = network.verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)
        assertEquals(AuthError.Kind.NETWORK, (networkResult as AuthOutcome.Failure).error.kind)

        val limited = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(429, "") }, MemorySessionStore(null), EpochClock { 1_000 })
        val limitedResult = limited.verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)
        assertEquals(AuthError.Kind.RATE_LIMITED, (limitedResult as AuthOutcome.Failure).error.kind)
    }

    @Test fun `failed temporary local logout still discards fallback session`() = runBlocking {
        val store = MemorySessionStore(null)
        var call = 0
        val client = SupabaseAuthClient(RecordingTransport {
            when (++call) {
                1 -> AuthHttpResponse(400, "{\"error_code\":\"otp_expired\"}")
                2 -> sessionResponse(session)
                else -> throw AuthNetworkException(java.io.IOException("offline during cleanup"))
            }
        }, store, EpochClock { 1_000 })

        val result = client.verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)
        assertEquals(AuthError.Kind.ACCOUNT_EXISTS, (result as AuthOutcome.Failure).error.kind)
        assertNull(store.value)
        assertEquals(3, call)
    }

    @Test fun `cancellation cannot skip temporary session cleanup`() = runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val requests = mutableListOf<Request>()
        val transport = object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
                requests += Request(method, path, body, accessToken)
                return when (requests.size) {
                    1 -> AuthHttpResponse(403, "{\"error_code\":\"otp_expired\"}")
                    2 -> sessionResponse(session)
                    3 -> {
                        cleanupStarted.complete(Unit)
                        releaseCleanup.await()
                        AuthHttpResponse(204, "")
                    }
                    else -> error("unexpected request")
                }
            }
        }
        val store = MemorySessionStore(null)
        val client = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
        val pending = async {
            client.verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)
        }

        cleanupStarted.await()
        pending.cancel()
        releaseCleanup.complete(Unit)
        runCatching { pending.await() }

        assertEquals("/auth/v1/logout?scope=local", requests.single { it.path.contains("logout") }.path)
        assertEquals(session.accessToken, requests.single { it.path.contains("logout") }.accessToken)
        assertNull(store.value)
    }

    @Test fun `cancellation while fallback verification is in flight still revokes returned session`() = runBlocking {
        val fallbackStarted = CompletableDeferred<Unit>()
        val releaseFallback = CompletableDeferred<Unit>()
        val requests = mutableListOf<Request>()
        val transport = object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
                requests += Request(method, path, body, accessToken)
                return when (requests.size) {
                    1 -> AuthHttpResponse(403, "{\"error_code\":\"otp_expired\"}")
                    2 -> {
                        fallbackStarted.complete(Unit)
                        releaseFallback.await()
                        sessionResponse(session)
                    }
                    3 -> AuthHttpResponse(204, "")
                    else -> error("unexpected request")
                }
            }
        }
        val store = MemorySessionStore(null)
        val client = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
        val pending = async {
            client.verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)
        }

        fallbackStarted.await()
        pending.cancel()
        releaseFallback.complete(Unit)
        runCatching { pending.await() }

        assertTrue(pending.isCancelled)
        assertEquals("/auth/v1/logout?scope=local", requests.single { it.path.contains("logout") }.path)
        assertEquals(session.accessToken, requests.single { it.path.contains("logout") }.accessToken)
        assertNull(store.value)
    }

    @Test fun `mismatched or anonymous fallback sessions are revoked and never persisted`() = runBlocking {
        for (temporary in listOf(
            session.copy(email = "different@example.com"),
            session.copy(isAnonymous = true),
        )) {
            val previous = session.copy(userId = "local-user", email = "local@example.com")
            val store = MemorySessionStore(previous)
            val requests = mutableListOf<Request>()
            val transport = RecordingTransport { request ->
                requests += request
                when (requests.size) {
                    1 -> AuthHttpResponse(403, "{\"error_code\":\"otp_expired\"}")
                    2 -> sessionResponse(temporary)
                    3 -> AuthHttpResponse(204, "")
                    else -> error("unexpected request")
                }
            }

            val result = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
                .verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.NEW_ACCOUNT)

            assertEquals(AuthError.Kind.UNAUTHORIZED, (result as AuthOutcome.Failure).error.kind)
            assertEquals("/auth/v1/logout?scope=local", requests[2].path)
            assertEquals(temporary.accessToken, requests[2].accessToken)
            assertEquals(previous, store.value)
            assertEquals(0, store.clearCount)
        }
    }

    @Test fun `existing account sign in keeps generic email verification`() = runBlocking {
        val transport = RecordingTransport { request ->
            assertEquals("email", JSONObject(requireNotNull(request.body)).getString("type"))
            sessionResponse(session)
        }
        val result = SupabaseAuthClient(transport, MemorySessionStore(null), EpochClock { 1_000 })
            .verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.EXISTING_ACCOUNT)
        assertTrue(result is AuthOutcome.Success)
        assertEquals(1, transport.calls)
    }

    @Test fun `cancelled verification cannot persist through uncontended mutex fast path`() = runBlocking {
        val verificationStarted = CompletableDeferred<Unit>()
        val releaseVerification = CompletableDeferred<Unit>()
        val requests = mutableListOf<Request>()
        val transport = object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
                requests += Request(method, path, body, accessToken)
                return when (requests.size) {
                    1 -> kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        verificationStarted.complete(Unit)
                        releaseVerification.await()
                        sessionResponse(session)
                    }
                    2 -> AuthHttpResponse(204, "")
                    else -> error("unexpected request")
                }
            }
        }
        val previous = session.copy(userId = "previous-user", email = "previous@example.com")
        val store = MemorySessionStore(previous)
        val client = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
        val pending = async {
            client.verifyEmailOtp("reader@example.com", "12345678", EmailOtpIntent.EXISTING_ACCOUNT)
        }

        verificationStarted.await()
        pending.cancel()
        releaseVerification.complete(Unit)
        runCatching { pending.await() }

        assertTrue(pending.isCancelled)
        assertEquals(0, store.writeCount)
        assertEquals(previous, store.value)
        assertEquals("/auth/v1/logout?scope=local", requests[1].path)
        assertEquals(session.accessToken, requests[1].accessToken)
        assertNull(client.accountIdentity.value)
    }

    @Test fun `current session stays offline when token is fresh`() = runBlocking {
        val transport = RecordingTransport { error("network must not be used") }
        val store = MemorySessionStore(session)
        val client = SupabaseAuthClient(transport, store, EpochClock { 1_000 })

        val result = client.currentSession()

        assertTrue(result is AuthOutcome.Success)
        assertEquals(session, (result as AuthOutcome.Success).value)
        assertEquals(0, transport.calls)
    }

    @Test fun `failed remote logout locks locally and truthfully reports unconfirmed global revocation`() = runBlocking {
        val store = MemorySessionStore(session)
        val client = SupabaseAuthClient(
            RecordingTransport { AuthHttpResponse(503, "") }, store, EpochClock { 1_000 },
        )

        val result = client.signOut()

        assertTrue(result is AuthOutcome.Success)
        assertEquals(false, (result as AuthOutcome.Success<SignOutResult>).value.remoteRevocationConfirmed)
        assertNull(store.value)
        assertEquals(1, store.clearCount)
    }

    @Test fun `successful remote logout clears only auth session`() = runBlocking {
        val store = MemorySessionStore(session)
        val transport = RecordingTransport { request ->
            assertEquals("/auth/v1/logout?scope=local", request.path)
            assertEquals("access-secret", request.accessToken)
            AuthHttpResponse(204, "")
        }
        val client = SupabaseAuthClient(transport, store, EpochClock { 1_000 })

        val result = client.signOut()

        assertTrue(result is AuthOutcome.Success)
        assertEquals(true, (result as AuthOutcome.Success<SignOutResult>).value.remoteRevocationConfirmed)
        assertNull(store.value)
        assertEquals(1, store.clearCount)
    }

    @Test fun `secret-bearing values are redacted from diagnostic strings`() {
        val sessionText = session.toString()
        val responseText = AuthHttpResponse(200, "{\"access_token\":\"top-secret\"}").toString()
        assertTrue(!sessionText.contains("access-secret") && !sessionText.contains("refresh-secret"))
        assertTrue(!responseText.contains("top-secret"))
    }

    @Test fun `transport rejects non-https endpoint and service role key`() {
        val anonPayload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"role\":\"service_role\"}".toByteArray())
        val serviceKey = "x.$anonPayload.x"
        assertTrue(runCatching { UrlConnectionAuthTransport("http://example.com", "sb_publishable_abcdefghijklmnopqrstuvwxyz") }.isFailure)
        assertTrue(runCatching { UrlConnectionAuthTransport("https://example.com", serviceKey) }.isFailure)
    }

    @Test fun `operation lease binds user and becomes stale after logout`() = runBlocking {
        val store = MemorySessionStore(session)
        val client = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(204, "") }, store, EpochClock { 1_000 })
        client.currentSession(refreshIfNeeded = false)

        val lease = (client.operationLease("user-a") as AuthOutcome.Success).value
        assertTrue(client.isOperationLeaseCurrent(lease))
        client.signOut()

        assertTrue(!client.isOperationLeaseCurrent(lease))
        assertTrue(!lease.toString().contains("access-secret"))
    }

    @Test fun `same user re-login cannot revive old operation lease`() = runBlocking {
        val store = MemorySessionStore(session)
        val client = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(204, "") }, store, EpochClock { 1_000 })
        client.currentSession(refreshIfNeeded = false)
        val oldLease = (client.operationLease("user-a") as AuthOutcome.Success).value
        client.signOut()
        store.write(session.copy(accessToken = "replacement-access", refreshToken = "replacement-refresh"))
        client.currentSession(refreshIfNeeded = false)

        assertTrue(!client.isOperationLeaseCurrent(oldLease))
        val newLease = (client.operationLease("user-a") as AuthOutcome.Success).value
        assertTrue(client.isOperationLeaseCurrent(newLease))
    }

    @Test fun `expired verified session opens offline on transient refresh failure`() = runBlocking {
        val expired = session.copy(expiresAtEpochSeconds = 900)
        val store = MemorySessionStore(expired)
        val client = SupabaseAuthClient(RecordingTransport { throw AuthNetworkException(java.io.IOException("offline")) }, store, EpochClock { 1_000 })

        val result = client.evaluateStoredAppAccess()

        assertEquals(StoredAccessEvaluation.Granted(expired, offline = true), result)
        assertEquals(expired, store.value)
    }

    @Test fun `rate limit and server failures do not discard verified session`() = runBlocking {
        for (status in listOf(429, 503)) {
            val expired = session.copy(expiresAtEpochSeconds = 900)
            val store = MemorySessionStore(expired)
            val client = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(status, "") }, store, EpochClock { 1_000 })

            assertEquals(StoredAccessEvaluation.Granted(expired, offline = true), client.evaluateStoredAppAccess())
            assertEquals(expired, store.value)
        }
    }

    @Test fun `definitively rejected refresh clears auth only and locks access`() = runBlocking {
        val store = MemorySessionStore(session.copy(expiresAtEpochSeconds = 900))
        val client = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(400, "{\"error_code\":\"refresh_token_not_found\"}") }, store, EpochClock { 1_000 })

        val result = client.evaluateStoredAppAccess()

        assertTrue(result is StoredAccessEvaluation.Locked)
        assertEquals(AppAccessLockReason.SESSION_REVOKED, (result as StoredAccessEvaluation.Locked).reason)
        assertNull(store.value)
        assertEquals(1, store.clearCount)
    }

    @Test fun `unexpected refresh bad request preserves credentials for offline access`() = runBlocking {
        val expired = session.copy(expiresAtEpochSeconds = 900)
        val store = MemorySessionStore(expired)
        val client = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(400, "{\"message\":\"bad gateway configuration\"}") }, store, EpochClock { 1_000 })

        assertEquals(StoredAccessEvaluation.Granted(expired, offline = true), client.evaluateStoredAppAccess())
        assertEquals(expired, store.value)
        assertEquals(0, store.clearCount)
    }

    @Test fun `every refresh entry point immediately clears a definitively invalid current session`() = runBlocking {
        suspend fun assertCleared(call: suspend (SupabaseAuthClient) -> Any?) {
            val store = MemorySessionStore(session.copy(expiresAtEpochSeconds = 900))
            val client = SupabaseAuthClient(
                RecordingTransport { AuthHttpResponse(400, "{\"error_code\":\"session_not_found\"}") },
                store, EpochClock { 1_000 },
            )
            assertTrue(client.currentSession(refreshIfNeeded = false) is AuthOutcome.Success)
            assertNotNull(client.accountIdentity.value)
            call(client)
            assertNull(store.value)
            assertNull(client.accountIdentity.value)
            assertEquals(AppAccessLockReason.SESSION_REVOKED, client.accessLockReason.value)
            assertEquals(1, store.clearCount)
        }

        assertCleared { it.currentSession() }
        assertCleared { it.refreshSession() }
        assertCleared { it.operationLease("user-a") }
    }

    @Test fun `direct refresh network error preserves the current session`() = runBlocking {
        val expired = session.copy(expiresAtEpochSeconds = 900)
        val store = MemorySessionStore(expired)
        val client = SupabaseAuthClient(
            RecordingTransport { throw AuthNetworkException(java.io.IOException("offline")) },
            store, EpochClock { 1_000 },
        )

        val result = client.refreshSession()

        assertTrue(result is AuthOutcome.Failure)
        assertEquals(expired, store.value)
        assertEquals(0, store.clearCount)
    }

    @Test fun `late rejection for replaced session cannot clear replacement`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = MemorySessionStore(session.copy(expiresAtEpochSeconds = 900))
        val transport = object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
                started.complete(Unit)
                release.await()
                return AuthHttpResponse(401, "")
            }
        }
        val client = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
        val pending = async { client.refreshSession() }
        started.await()
        val replacement = session.copy(accessToken = "new-access", refreshToken = "new-refresh", expiresAtEpochSeconds = 20_000)
        store.value = replacement
        release.complete(Unit)

        val result = pending.await()

        assertTrue(result is AuthOutcome.Success && result.value == replacement)
        assertEquals(replacement, store.value)
        assertEquals(0, store.clearCount)
    }

    @Test fun `network rejection is token fenced and cannot clear replacement session`() = runBlocking {
        val replacement = session.copy(accessToken = "replacement-access", refreshToken = "replacement-refresh")
        val store = MemorySessionStore(replacement)
        val client = SupabaseAuthClient(RecordingTransport { error("stale rejection must not use network") }, store, EpochClock { 1_000 })

        val result = client.onAuthRejected("access-secret")

        assertEquals(AuthRejectionResult.STALE_TOKEN, result)
        assertEquals(replacement, store.value)
    }

    @Test fun `matching rejected token clears only after definitive refresh rejection`() = runBlocking {
        val store = MemorySessionStore(session)
        val client = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(401, "") }, store, EpochClock { 1_000 })

        val result = client.onAuthRejected(session.accessToken)

        assertEquals(AuthRejectionResult.SESSION_REVOKED, result)
        assertNull(store.value)
    }

    @Test fun `storage read failure does not delete evidence`() = runBlocking {
        val store = MemorySessionStore(session).apply { failRead = true }
        val client = SupabaseAuthClient(RecordingTransport { error("network must not be used") }, store, EpochClock { 1_000 })

        val result = client.evaluateStoredAppAccess()

        assertTrue(result is StoredAccessEvaluation.Locked)
        assertEquals(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE, (result as StoredAccessEvaluation.Locked).reason)
        assertEquals(0, store.clearCount)
        assertEquals(session, store.value)
    }

    @Test fun `anonymous enrollment is disabled without a network request`() = runBlocking {
        val transport = RecordingTransport { error("anonymous signup must not be dispatched") }
        val client = SupabaseAuthClient(transport, MemorySessionStore(null), EpochClock { 1_000 })

        val result = client.enrollAnonymous()

        assertTrue(result is AuthOutcome.Failure)
        assertEquals(AuthError.Kind.CONFIGURATION, (result as AuthOutcome.Failure).error.kind)
        assertEquals(0, transport.calls)
    }

    @Test fun `user verification requires matching confirmed nonanonymous identity`() = runBlocking {
        val validBody = """{"id":"user-a","email":"reader@example.com","is_anonymous":false,"email_confirmed_at":"2026-09-27T00:00:00Z"}"""
        val online = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(200, validBody) }, MemorySessionStore(session), EpochClock { 1_000 })
        assertEquals(StoredAccessEvaluation.Granted(session, offline = false), online.evaluateStoredAppAccess())

        val malformedStore = MemorySessionStore(session)
        val malformed = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(200, "{}") }, malformedStore, EpochClock { 1_000 })
        assertEquals(StoredAccessEvaluation.Granted(session, offline = true), malformed.evaluateStoredAppAccess())
        assertEquals(session, malformedStore.value)

        val anonymousStore = MemorySessionStore(session)
        val anonymousBody = """{"id":"user-a","email":"reader@example.com","is_anonymous":true,"email_confirmed_at":"2026-09-27T00:00:00Z"}"""
        val anonymous = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(200, anonymousBody) }, anonymousStore, EpochClock { 1_000 })
        val rejected = anonymous.evaluateStoredAppAccess()
        assertTrue(rejected is StoredAccessEvaluation.Locked)
        assertNull(anonymousStore.value)

        val nullConfirmationStore = MemorySessionStore(session)
        val nullConfirmationBody = """{"id":"user-a","email":"reader@example.com","is_anonymous":false,"email_confirmed_at":null}"""
        val nullConfirmation = SupabaseAuthClient(RecordingTransport { AuthHttpResponse(200, nullConfirmationBody) }, nullConfirmationStore, EpochClock { 1_000 })
        assertTrue(nullConfirmation.evaluateStoredAppAccess() is StoredAccessEvaluation.Locked)
        assertNull(nullConfirmationStore.value)
    }

    private data class Request(val method: String, val path: String, val body: String?, val accessToken: String?)

    private fun sessionResponse(value: AuthSession) = AuthHttpResponse(200, """{
        "access_token":"${value.accessToken}",
        "refresh_token":"${value.refreshToken}",
        "expires_at":${value.expiresAtEpochSeconds},
        "user":{"id":"${value.userId}","email":"${value.email}","is_anonymous":${value.isAnonymous}}
    }""".trimIndent())

    private class RecordingTransport(private val response: (Request) -> AuthHttpResponse) : AuthTransport {
        var calls = 0
        override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
            calls++
            return response(Request(method, path, body, accessToken))
        }
    }

    private class MemorySessionStore(initial: AuthSession?) : SessionStore {
        var value = initial
        var clearCount = 0
        var writeCount = 0
        var failRead = false
        override suspend fun read() = if (failRead) throw SessionStorageException("corrupt evidence preserved") else value
        override suspend fun write(session: AuthSession) { writeCount++; value = session }
        override suspend fun clear() { clearCount++; value = null }
    }
}
