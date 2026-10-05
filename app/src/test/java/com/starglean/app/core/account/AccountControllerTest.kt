package com.starglean.app.core.account

import com.starglean.app.ui.screens.AccountAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountControllerTest {
    @Test fun `verified existing registration returns to sign in without losing email`() = runBlocking {
        var call = 0
        val auth = SupabaseAuthClient(
            transport = object : AuthTransport {
                override suspend fun request(method: String, path: String, body: String?, accessToken: String?) = when (++call) {
                    1 -> AuthHttpResponse(200, "{}")
                    2 -> AuthHttpResponse(403, "{\"error_code\":\"otp_expired\"}")
                    3 -> AuthHttpResponse(200, """{
                        "access_token":"temporary-access","refresh_token":"temporary-refresh","expires_at":10000,
                        "user":{"id":"existing-user","email":"reader@example.com","is_anonymous":false}
                    }""".trimIndent())
                    else -> AuthHttpResponse(204, "")
                }
            },
            sessionStore = object : SessionStore {
                override suspend fun read(): AuthSession? = null
                override suspend fun write(session: AuthSession) = error("fallback session must not persist")
                override suspend fun clear() = Unit
            },
        )
        var draft: AccountFormDraft? = null
        val controller = AccountController(auth, this, {}, onFormDraftChanged = { draft = it })
        controller.dispatch(AccountAction.ChooseMode(EmailOtpIntent.NEW_ACCOUNT))
        controller.dispatch(AccountAction.EditEmail("reader@example.com"))
        controller.dispatch(AccountAction.SetSignupConsent(true))
        controller.dispatch(AccountAction.RequestOtp)
        while (controller.state.value.busy) delay(1)
        controller.dispatch(AccountAction.EditOtp("12345678"))
        controller.dispatch(AccountAction.VerifyOtp)
        while (controller.state.value.busy) delay(1)

        val state = controller.state.value
        assertEquals(EmailOtpIntent.EXISTING_ACCOUNT, state.mode)
        assertEquals("reader@example.com", state.email)
        assertEquals("", state.otp)
        assertFalse(state.otpSent)
        assertFalse(state.signupConsent)
        assertEquals("An account already uses this email. Sign in and request a new code.", state.message)
        assertEquals(AccountFormDraft(EmailOtpIntent.EXISTING_ACCOUNT, "reader@example.com", false), draft)
    }

    @Test fun `safe form draft restores after controller recreation without otp state`() {
        var saved = AccountFormDraft()
        val first = controller(onDraft = { saved = it })
        first.dispatch(AccountAction.ChooseMode(EmailOtpIntent.NEW_ACCOUNT))
        first.dispatch(AccountAction.EditEmail("reader@example.com"))
        first.dispatch(AccountAction.SetSignupConsent(true))
        first.dispatch(AccountAction.EditOtp("12345678"))

        val restored = controller(initial = saved)

        assertEquals(EmailOtpIntent.NEW_ACCOUNT, restored.state.value.mode)
        assertEquals("reader@example.com", restored.state.value.email)
        assertEquals(true, restored.state.value.signupConsent)
        assertEquals("", restored.state.value.otp)
        assertFalse(restored.state.value.otpSent)
        assertFalse(restored.state.value.busy)
    }

    @Test fun `restored draft normalizes malformed email and impossible consent`() {
        val controller = controller(initial = AccountFormDraft(
            mode = EmailOtpIntent.EXISTING_ACCOUNT,
            email = "  user@example.com\r\nignored  ",
            signupConsent = true,
        ))

        assertEquals("user@example.comignored", controller.state.value.email)
        assertFalse(controller.state.value.signupConsent)
    }

    @Test fun `draft callback exposes only bounded normalized non-secret fields`() {
        var saved: AccountFormDraft? = null
        val controller = controller(onDraft = { saved = it })
        controller.dispatch(AccountAction.ChooseMode(EmailOtpIntent.NEW_ACCOUNT))
        controller.dispatch(AccountAction.EditEmail("  ${"a".repeat(400)}\n  "))
        controller.dispatch(AccountAction.EditOtp("87654321"))
        controller.dispatch(AccountAction.SetSignupConsent(true))

        assertEquals(320, requireNotNull(saved).email.length)
        assertFalse(requireNotNull(saved).email.contains('\n'))
        assertEquals(true, requireNotNull(saved).signupConsent)
        assertFalse(saved.toString().contains("87654321"))
    }

    private fun controller(
        initial: AccountFormDraft = AccountFormDraft(),
        onDraft: (AccountFormDraft) -> Unit = {},
    ): AccountController {
        val auth = SupabaseAuthClient(
            transport = object : AuthTransport {
                override suspend fun request(method: String, path: String, body: String?, accessToken: String?): AuthHttpResponse =
                    error("network must not be used")
            },
            sessionStore = object : SessionStore {
                override suspend fun read(): AuthSession? = null
                override suspend fun write(session: AuthSession) = Unit
                override suspend fun clear() = Unit
            },
        )
        return AccountController(auth, CoroutineScope(Job()), {}, initialFormDraft = initial, onFormDraftChanged = onDraft)
    }
}
