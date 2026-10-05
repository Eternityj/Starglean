package com.starglean.app.core.account

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRefreshConcurrencyDeviceTest {
    @Test fun concurrentRefreshIsSingleFlightAndPersistsRotatedToken() = runBlocking {
        val calls = AtomicInteger()
        val transport = object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
                calls.incrementAndGet()
                delay(40)
                return AuthHttpResponse(200, """{
                    "access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,
                    "user":{"id":"user-a","email":"reader@example.com","is_anonymous":false}
                }""".trimIndent())
            }
        }
        val store = MemoryStore(AuthSession("user-a", "reader@example.com", false, "old-access", "old-refresh", 100))
        val auth = SupabaseAuthClient(transport, store, EpochClock { 100 })

        val results = List(8) { async { auth.refreshSession() } }.awaitAll()

        assertEquals(1, calls.get())
        assertTrue(results.all { it is AuthOutcome.Success && it.value?.refreshToken == "new-refresh" })
        assertEquals("new-refresh", store.value?.refreshToken)
    }

    private class MemoryStore(initial: AuthSession?) : SessionStore {
        var value = initial
        override suspend fun read() = value
        override suspend fun write(session: AuthSession) { value = session }
        override suspend fun clear() { value = null }
    }
}
