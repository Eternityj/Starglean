package com.starglean.app.core.account

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccessControllerTest {
    private val session = AuthSession(
        userId = "verified-user", email = "reader@example.com", isAnonymous = false,
        accessToken = "access", refreshToken = "refresh", expiresAtEpochSeconds = 900,
    )

    @Test fun `cold start stays loading until offline session evaluation completes`() = runBlocking {
        val auth = SupabaseAuthClient(NetworkFailureTransport, MemoryStore(session), EpochClock { 1_000 })
        val controller = AppAccessController(auth, CoroutineScope(coroutineContext))
        try {
            assertEquals(AppAccessState.Loading, controller.state.value)
            controller.load()
            waitForState(controller) { it !is AppAccessState.Loading }
            val granted = controller.state.value as AppAccessState.Granted
            assertEquals(AccessConnectivity.OFFLINE_VERIFIED_SESSION, granted.connectivity)
            assertEquals(session.userId, granted.account.userId)
        } finally { controller.close() }
    }

    @Test fun `anonymous stored session is cleared and never unlocks gate`() = runBlocking {
        val store = MemoryStore(session.copy(email = null, isAnonymous = true))
        val auth = SupabaseAuthClient(NetworkFailureTransport, store, EpochClock { 1_000 })
        val controller = AppAccessController(auth, CoroutineScope(coroutineContext))
        try {
            controller.load()
            waitForState(controller) { it !is AppAccessState.Loading }
            val locked = controller.state.value as AppAccessState.Locked
            assertEquals(AppAccessLockReason.EMAIL_VERIFICATION_REQUIRED, locked.reason)
            assertEquals(null, store.value)
        } finally { controller.close() }
    }

    @Test fun `sign out locks synchronously before remote revocation returns`() = runBlocking {
        val auth = SupabaseAuthClient(NetworkFailureTransport, MemoryStore(session.copy(expiresAtEpochSeconds = 10_000)), EpochClock { 1_000 })
        val controller = AppAccessController(auth, CoroutineScope(coroutineContext))
        try {
            controller.load()
            waitForState(controller) { it is AppAccessState.Granted }
            controller.signOut()
            assertTrue(controller.state.value is AppAccessState.Locked)
        } finally { controller.close() }
    }

    @Test fun `refresh response arriving after logout request cannot unlock gate`() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val transport = object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
                return if (path.startsWith("/auth/v1/token")) {
                    refreshStarted.complete(Unit)
                    releaseRefresh.await()
                    AuthHttpResponse(503, "")
                } else AuthHttpResponse(503, "")
            }
        }
        val auth = SupabaseAuthClient(transport, MemoryStore(session), EpochClock { 1_000 })
        val controller = AppAccessController(auth, CoroutineScope(coroutineContext))
        try {
            controller.load()
            withTimeout(5_000) { refreshStarted.await() }
            val logout = async { auth.signOut() }
            releaseRefresh.complete(Unit)
            withTimeout(5_000) { logout.await() }
            waitForState(controller) { it is AppAccessState.Locked }
            assertTrue(controller.state.value is AppAccessState.Locked)
        } finally { controller.close() }
    }

    private suspend fun waitForState(controller: AppAccessController, predicate: (AppAccessState) -> Boolean) {
        repeat(1_000) { if (predicate(controller.state.value)) return; delay(5) }
        error("state did not settle: ${controller.state.value}")
    }

    private object NetworkFailureTransport : AuthTransport {
        override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
            throw AuthNetworkException(IOException("offline"))
        }
    }

    private class MemoryStore(initial: AuthSession?) : SessionStore {
        var value = initial
        override suspend fun read() = value
        override suspend fun write(session: AuthSession) { value = session }
        override suspend fun clear() { value = null }
    }
}
