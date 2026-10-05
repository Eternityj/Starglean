package com.starglean.app.core.account

import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidKeystoreSessionStoreDeviceTest {
    @Test fun encryptedSessionRoundTripsThroughAndroidKeystore(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val isolated = File(context.cacheDir, "auth-roundtrip-${System.nanoTime()}")
        val alias = "starglean.auth.roundtrip.${System.nanoTime()}"
        val store = AndroidKeystoreSessionStore(context, alias, isolated)
        val session = AuthSession("user-device", "reader@example.com", false, "access-secret", "refresh-secret", 42_000)
        try {
            store.write(session)
            assertEquals(session, store.read())
        } finally {
            runCatching { store.clear() }
            File(isolated, "auth").delete(); isolated.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        }
    }

    @Test fun corruptCiphertextFailsWithoutDeletingEvidence(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val isolated = File(context.cacheDir, "auth-test-${System.nanoTime()}")
        val path = File(isolated, "auth/session-v1.enc")
        path.parentFile?.mkdirs()
        val evidence = ByteArray(40) { (it + 1).toByte() }
        path.writeBytes(evidence)
        val alias = "starglean.auth.test.${System.nanoTime()}"
        val store = AndroidKeystoreSessionStore(context, alias, isolated)

        val result = runCatching { store.read() }

        assertTrue(result.exceptionOrNull() is SessionStorageException)
        assertArrayEquals(evidence, path.readBytes())
        path.delete(); path.parentFile?.delete(); isolated.delete()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
    }

    @Test fun bothEmailOtpIntentsPersistAndReloadThroughRealKeystore(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val isolated = File(context.cacheDir, "auth-otp-${System.nanoTime()}")
        val alias = "starglean.auth.otp.${System.nanoTime()}"
        val store = AndroidKeystoreSessionStore(context, alias, isolated)
        val expected = AuthSession(
            userId = "verified-device-user",
            email = "verified@example.com",
            isAnonymous = false,
            accessToken = "device-access-secret",
            refreshToken = "device-refresh-secret",
            expiresAtEpochSeconds = 42_000,
        )
        var verificationCount = 0
        val transport = object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse {
                assertEquals("POST", method)
                assertEquals("/auth/v1/verify", path)
                val expectedType = if (verificationCount++ == 0) "signup" else "email"
                assertTrue(body?.contains("\"type\":\"$expectedType\"") == true)
                return AuthHttpResponse(200, """{
                    "access_token":"${expected.accessToken}",
                    "refresh_token":"${expected.refreshToken}",
                    "expires_at":${expected.expiresAtEpochSeconds},
                    "user":{"id":"${expected.userId}","email":"${expected.email}","is_anonymous":false}
                }""".trimIndent())
            }
        }

        try {
            val client = SupabaseAuthClient(transport, store, EpochClock { 1_000 })
            for (intent in listOf(EmailOtpIntent.NEW_ACCOUNT, EmailOtpIntent.EXISTING_ACCOUNT)) {
                val result = client.verifyEmailOtp("verified@example.com", "12345678", intent)

                assertTrue("$intent should accept and persist the verified session", result is AuthOutcome.Success)
                assertEquals(expected, (result as AuthOutcome.Success).value)
                // Construct a new client/store view to exercise the process-reload read path too.
                val reloaded = SupabaseAuthClient(transport, AndroidKeystoreSessionStore(context, alias, isolated), EpochClock { 1_000 })
                    .currentSession(refreshIfNeeded = false)
                assertEquals(expected, (reloaded as AuthOutcome.Success).value)
            }
        } finally {
            runCatching { store.clear() }
            File(isolated, "auth").delete(); isolated.delete()
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); if (containsAlias(alias)) deleteEntry(alias) }
        }
    }
}
