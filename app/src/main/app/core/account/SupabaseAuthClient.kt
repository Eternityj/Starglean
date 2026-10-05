package com.starglean.app.core.account

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SupabaseAuthClient(
    private val transport: AuthTransport,
    private val sessionStore: SessionStore,
    private val clock: EpochClock = EpochClock { System.currentTimeMillis() / 1_000L },
) {
    private val sessionMutex = Mutex()
    private val mutableSessionRevision = MutableStateFlow(0L)
    private val mutableAccountIdentity = MutableStateFlow<AccountIdentity?>(null)
    private val mutableAccessLockReason = MutableStateFlow(AppAccessLockReason.SIGN_IN_REQUIRED)
    private val mutableAccessMessage = MutableStateFlow("Sign in with email to continue.")
    private val authActionEpoch = AtomicLong(0L)
    /** Increments after every accepted login, token rotation, logout, deletion, or observed account switch. */
    val sessionRevision: StateFlow<Long> = mutableSessionRevision.asStateFlow()
    val accountIdentity: StateFlow<AccountIdentity?> = mutableAccountIdentity.asStateFlow()
    internal val accessLockReason: StateFlow<AppAccessLockReason> = mutableAccessLockReason.asStateFlow()
    internal val accessMessage: StateFlow<String> = mutableAccessMessage.asStateFlow()

    suspend fun operationLease(expectedUserId: String? = null): AuthOutcome<AuthOperationLease> {
        return sessionMutex.withLock {
            var session = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE, "Saved sign-in is unavailable."))
            } ?: return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED, "Sign in to continue."))
            if (session.expiresAtEpochSeconds - clock.nowSeconds() <= REFRESH_SKEW_SECONDS) {
                when (val refreshed = refreshLocked(session)) {
                    is AuthOutcome.Failure -> return@withLock refreshed
                    is AuthOutcome.Success -> session = refreshed.value
                        ?: return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED, "Sign in to continue."))
                }
            }
            publishObserved(session)
            if (expectedUserId != null && session.userId != expectedUserId) {
                AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED, "The signed-in account changed. Please try again."))
            } else AuthOutcome.Success(AuthOperationLease(session.userId, mutableSessionRevision.value, session.accessToken))
        }
    }

    suspend fun isOperationLeaseCurrent(lease: AuthOperationLease): Boolean = sessionMutex.withLock {
        if (mutableSessionRevision.value != lease.sessionRevision || mutableAccountIdentity.value?.userId != lease.userId) return@withLock false
        val session = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withLock false } ?: return@withLock false
        session.userId == lease.userId && mutableSessionRevision.value == lease.sessionRevision &&
            java.security.MessageDigest.isEqual(session.accessToken.toByteArray(Charsets.UTF_8), lease.accessToken.toByteArray(Charsets.UTF_8))
    }

    suspend fun requestEmailOtp(email: String, intent: EmailOtpIntent): AuthOutcome<Unit> {
        val normalized = normalizeEmail(email) ?: return invalidEmail()
        return call {
            val response = transport.request(
                method = "POST",
                path = "/auth/v1/otp",
                body = JSONObject().put("email", normalized)
                    .put("create_user", intent == EmailOtpIntent.NEW_ACCOUNT).toString(),
            )
            response.unitOutcome()
        }
    }

    suspend fun verifyEmailOtp(email: String, token: String, intent: EmailOtpIntent): AuthOutcome<AuthSession> {
        val normalized = normalizeEmail(email) ?: return invalidEmail()
        if (!token.matches(Regex("^[0-9]{8}$"))) {
            return AuthOutcome.Failure(AuthError(AuthError.Kind.INVALID_INPUT, "Enter the 8-digit code from the email."))
        }
        val actionEpoch = authActionEpoch.get()
        return call {
            val verificationType = if (intent == EmailOtpIntent.NEW_ACCOUNT) "signup" else "email"
            val response = transport.request(
                method = "POST",
                path = "/auth/v1/verify",
                body = JSONObject().put("email", normalized).put("token", token).put("type", verificationType).toString(),
            )
            if (intent == EmailOtpIntent.NEW_ACCOUNT && response.isOtpExpired()) {
                return@call verifyExistingAccountFallback(normalized, token)
            }
            val parsed = response.sessionOutcome() ?: return@call response.failureOutcome()
            if (!isVerifiedEmailSession(parsed.value) || normalizeEmail(requireNotNull(parsed.value.email)) != normalized) {
                return@call AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED,
                    "The verified session did not match the requested email account."))
            }
            var persistenceStarted = false
            try {
                sessionMutex.withLock {
                    // Mutex.lock's uncontended fast path does not check cancellation in coroutines 1.9.0.
                    currentCoroutineContext().ensureActive()
                    if (authActionEpoch.get() != actionEpoch) {
                        return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED, "Sign-in was cancelled by a newer account action."))
                    }
                    persistenceStarted = true
                    persist(parsed.value)
                    parsed
                }
            } catch (cancelled: CancellationException) {
                if (!persistenceStarted) revokeUnpersistedSession(parsed.value)
                throw cancelled
            }
        }
    }

    private suspend fun revokeUnpersistedSession(session: AuthSession) = withContext(NonCancellable) {
        runCatching { transport.revokeTemporarySession(session.accessToken) }
    }

    /**
     * A create-user OTP request deliberately has the same public response for new and existing addresses.
     * Prove that an unsuccessful signup token is actually a magic-link token before reporting that the
     * mailbox already belongs to an account. The temporary login is never persisted or published.
     */
    private suspend fun verifyExistingAccountFallback(email: String, token: String): AuthOutcome<AuthSession> =
        withContext(NonCancellable) {
            val response = transport.request(
                method = "POST",
                path = "/auth/v1/verify",
                body = JSONObject().put("email", email).put("token", token).put("type", "magiclink").toString(),
            )
            val session = response.sessionOutcome()?.value ?: return@withContext response.failureOutcome()
            try {
                if (!isVerifiedEmailSession(session) || normalizeEmail(requireNotNull(session.email)) != email) {
                    return@withContext AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED,
                        "The verified session did not match the requested email account."))
                }
                AuthOutcome.Failure(AuthError(
                    AuthError.Kind.ACCOUNT_EXISTS,
                    "An account already uses this email. Sign in and request a new code.",
                ))
            } finally {
                // Verification necessarily creates a server session. Every parsed session is revoked locally,
                // including mismatched identities and cancellation races; it is never persisted or published.
                runCatching { transport.revokeTemporarySession(session.accessToken) }
            }
        }

    suspend fun enrollAnonymous(): AuthOutcome<AuthSession> = AuthOutcome.Failure(
        AuthError(AuthError.Kind.CONFIGURATION, "Temporary accounts are disabled. Sign in with a verified email account."),
    )

    /** Reads local state only unless an existing session is close to expiry. It never creates an account. */
    suspend fun currentSession(refreshIfNeeded: Boolean = true): AuthOutcome<AuthSession?> = sessionMutex.withLock {
        val session = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE,
                if (error is SessionStorageException) error.message ?: "Saved sign-in is unavailable." else "Saved sign-in is unavailable."))
        } ?: run {
            if (mutableAccountIdentity.value != null) publishCleared()
            return@withLock AuthOutcome.Success(null)
        }
        publishObserved(session)
        if (!refreshIfNeeded || session.expiresAtEpochSeconds - clock.nowSeconds() > REFRESH_SKEW_SECONDS) {
            AuthOutcome.Success(session)
        } else refreshLocked(session)
    }

    suspend fun refreshSession(): AuthOutcome<AuthSession?> = sessionMutex.withLock {
        val before = try {
            sessionStore.read()
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE, "Saved sign-in is unavailable."))
        } ?: return@withLock AuthOutcome.Success(null)
        refreshLocked(before)
    }

    private suspend fun refreshLocked(before: AuthSession, force: Boolean = false): AuthOutcome<AuthSession?> {
        if (!force && before.expiresAtEpochSeconds - clock.nowSeconds() > REFRESH_SKEW_SECONDS) return AuthOutcome.Success(before)
        val actionEpoch = authActionEpoch.get()
        val outcome = call {
            val response = transport.request(
                "POST", "/auth/v1/token?grant_type=refresh_token",
                JSONObject().put("refresh_token", before.refreshToken).toString(),
            )
            val refreshed = response.parsedSession()
            if (response.status !in 200..299) {
                val failure = response.failureOutcome()
                return@call if (response.isDefinitiveRefreshRejection()) failure
                else AuthOutcome.Failure(failure.error.copy(kind = when (response.status) {
                    429 -> AuthError.Kind.RATE_LIMITED
                    else -> AuthError.Kind.SERVER
                }))
            }
            if (refreshed == null) return@call AuthOutcome.Failure(
                AuthError(AuthError.Kind.SERVER, "The refresh response was incomplete; the saved sign-in was preserved."),
            )
            if (refreshed.userId != before.userId || !isVerifiedEmailSession(refreshed)) {
                return@call AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED,
                    "The refreshed session no longer belongs to the verified email account."))
            }
            // Refresh tokens rotate. Persist atomically before exposing the new token.
            val stillCurrent = sessionStore.read()
            if (stillCurrent?.userId != before.userId || stillCurrent.refreshToken != before.refreshToken) {
                if (stillCurrent == null) publishCleared() else publishObserved(stillCurrent)
                AuthOutcome.Success(stillCurrent)
            } else {
                persist(refreshed)
                AuthOutcome.Success(refreshed)
            }
        }
        if (outcome !is AuthOutcome.Failure || outcome.error.kind != AuthError.Kind.UNAUTHORIZED) return outcome
        val stillCurrent = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                return AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE,
                    "The rejected session could not be checked safely; saved credentials were preserved."))
            }
        if (authActionEpoch.get() != actionEpoch) return outcome
        if (stillCurrent == null) return AuthOutcome.Success(null)
        val sameSession = stillCurrent.userId == before.userId &&
            java.security.MessageDigest.isEqual(stillCurrent.refreshToken.toByteArray(Charsets.UTF_8), before.refreshToken.toByteArray(Charsets.UTF_8)) &&
            java.security.MessageDigest.isEqual(stillCurrent.accessToken.toByteArray(Charsets.UTF_8), before.accessToken.toByteArray(Charsets.UTF_8))
        if (!sameSession) {
            publishObserved(stillCurrent)
            return AuthOutcome.Success(stillCurrent)
        }
        return if (clearSessionLocked(AppAccessLockReason.SESSION_REVOKED)) outcome
        else {
            publishCleared(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE,
                "The revoked session could not be removed safely. Access is locked; local diaries were preserved.")
            AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE,
                "The revoked session could not be removed safely; access remains locked."))
        }
    }

    suspend fun requestEmailChange(newEmail: String): AuthOutcome<Unit> {
        val normalized = normalizeEmail(newEmail) ?: return invalidEmail()
        return withStoredSession { session ->
            val response = transport.request(
                "PUT", "/auth/v1/user", JSONObject().put("email", normalized).toString(), session.accessToken,
            )
            response.unitOutcome()
        }
    }

    /**
     * Secure Email Change sends one code to each address. A successful call confirms only that side; callers
     * must not report the address changed until [currentSession] returns the new address after both sides.
     */
    suspend fun verifyEmailChange(email: String, token: String, side: EmailChangeSide): AuthOutcome<AuthSession?> {
        val normalized = normalizeEmail(email) ?: return invalidEmail()
        if (!token.matches(Regex("^[0-9]{8}$"))) {
            return AuthOutcome.Failure(AuthError(AuthError.Kind.INVALID_INPUT, "Enter the 8-digit code from that email."))
        }
        return withStoredSession { session ->
            val response = transport.request(
                "POST", "/auth/v1/verify",
                JSONObject().put("email", normalized).put("token", token).put("type", "email_change").toString(),
                session.accessToken,
            )
            if (response.status !in 200..299) return@withStoredSession response.failureOutcome()
            val returned = response.parsedSession()
            if (returned != null) persist(returned)
            AuthOutcome.Success(returned)
        }
    }

    /**
     * Locks this installation before attempting global revocation. A network/server failure can
     * therefore never leave the app open, and the typed result never claims remote revocation.
     */
    suspend fun signOut(): AuthOutcome<SignOutResult> {
        authActionEpoch.incrementAndGet()
        val local = sessionMutex.withLock<AuthOutcome<AuthSession?>> {
            val stored = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                publishCleared(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE,
                    "Secure sign-in storage is unavailable. Retry after unlocking the device; local diaries were preserved.")
                return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE, "Saved sign-in could not be read safely."))
            }
            try { sessionStore.clear() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                publishCleared(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE,
                    "Saved sign-in could not be cleared safely. Access is locked; local diaries were preserved.")
                return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE, "Saved sign-in could not be cleared safely."))
            }
            publishCleared(AppAccessLockReason.SIGN_IN_REQUIRED,
                "Signed out locally. Revocation of this device's server session is still being confirmed. Your local diary is still here.")
            AuthOutcome.Success(stored)
        }
        val session = when (local) {
            is AuthOutcome.Failure -> return local
            is AuthOutcome.Success -> local.value
        } ?: return AuthOutcome.Success(SignOutResult(remoteRevocationConfirmed = false))
        val remote = withContext(NonCancellable) {
            call {
                val response = transport.request("POST", "/auth/v1/logout?scope=local", accessToken = session.accessToken)
                AuthOutcome.Success(response.status in 200..299)
            }
        }
        val result = when (remote) {
            is AuthOutcome.Success -> SignOutResult(remote.value)
            is AuthOutcome.Failure -> SignOutResult(remoteRevocationConfirmed = false)
        }
        mutableAccessMessage.value = result.message
        return AuthOutcome.Success(result)
    }

    /** Best-effort cold-start check. Transient outages preserve a verified non-anonymous login offline. */
    internal suspend fun evaluateStoredAppAccess(): StoredAccessEvaluation {
        val actionEpoch = authActionEpoch.get()
        return sessionMutex.withLock {
        val stored = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            return@withLock StoredAccessEvaluation.Locked(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE,
                error.message ?: "Secure sign-in storage is unavailable. Retry after unlocking the device.")
        } ?: return@withLock StoredAccessEvaluation.Locked(AppAccessLockReason.SIGN_IN_REQUIRED, "Sign in with email to continue.")
        if (!isVerifiedEmailSession(stored)) {
            return@withLock clearIneligibleSessionLocked()
        }
        publishObserved(stored)
        val evaluated = if (stored.expiresAtEpochSeconds - clock.nowSeconds() <= REFRESH_SKEW_SECONDS) {
            refreshForAccessLocked(stored)
        } else {
            when (val checked = call {
                val response = transport.request("GET", "/auth/v1/user", accessToken = stored.accessToken)
                if (response.status in 200..299) AuthOutcome.Success(parseUserCheck(response, stored)) else response.failureOutcome()
            }) {
                is AuthOutcome.Success -> when (val user = checked.value) {
                    is UserCheck.Verified -> {
                        if (user.session != stored) persist(user.session)
                        StoredAccessEvaluation.Granted(user.session, offline = false)
                    }
                    UserCheck.Ineligible -> clearIneligibleSessionLocked()
                    UserCheck.Malformed -> StoredAccessEvaluation.Granted(stored, offline = true)
                }
                is AuthOutcome.Failure -> if (checked.error.kind == AuthError.Kind.UNAUTHORIZED) {
                    refreshForAccessLocked(stored, force = true)
                } else if (checked.error.kind == AuthError.Kind.LOCAL_STORAGE) {
                    StoredAccessEvaluation.Locked(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE, checked.error.message)
                } else StoredAccessEvaluation.Granted(stored, offline = true)
            }
        }
        if (authActionEpoch.get() != actionEpoch) {
            StoredAccessEvaluation.Locked(AppAccessLockReason.SIGN_IN_REQUIRED, "Signed out on this device. Your local diary is still here.")
        } else evaluated
    }
    }

    private sealed interface UserCheck {
        data class Verified(val session: AuthSession) : UserCheck
        data object Ineligible : UserCheck
        data object Malformed : UserCheck
    }

    private fun parseUserCheck(response: AuthHttpResponse, stored: AuthSession): UserCheck = try {
        val user = JSONObject(response.body)
        val id = user.getString("id")
        val email = user.optString("email").takeIf { it.isNotBlank() }
        val anonymous = user.optBoolean("is_anonymous", false)
        val confirmedValue = user.opt("email_confirmed_at") as? String
        val confirmed = confirmedValue != null && runCatching { Instant.parse(confirmedValue) }.isSuccess
        if (id != stored.userId) UserCheck.Ineligible
        else if (anonymous || email == null || normalizeEmail(email) == null || !confirmed) UserCheck.Ineligible
        else UserCheck.Verified(stored.copy(email = email, isAnonymous = false))
    } catch (_: Exception) { UserCheck.Malformed }

    /** Token-fenced response to a definitive authenticated API rejection. */
    suspend fun onAuthRejected(rejectedAccessToken: String): AuthRejectionResult = sessionMutex.withLock {
        val stored = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return@withLock AuthRejectionResult.STORAGE_ERROR }
            ?: return@withLock AuthRejectionResult.SESSION_REVOKED
        if (!java.security.MessageDigest.isEqual(stored.accessToken.toByteArray(Charsets.UTF_8), rejectedAccessToken.toByteArray(Charsets.UTF_8))) {
            return@withLock AuthRejectionResult.STALE_TOKEN
        }
        when (val refreshed = refreshLocked(stored, force = true)) {
            is AuthOutcome.Success -> {
                val session = refreshed.value
                if (session != null && isVerifiedEmailSession(session)) AuthRejectionResult.RECOVERED
                else {
                    if (clearSessionLocked(AppAccessLockReason.SESSION_REVOKED)) AuthRejectionResult.SESSION_REVOKED else AuthRejectionResult.STORAGE_ERROR
                }
            }
            is AuthOutcome.Failure -> when (refreshed.error.kind) {
                AuthError.Kind.UNAUTHORIZED -> AuthRejectionResult.SESSION_REVOKED
                AuthError.Kind.LOCAL_STORAGE -> AuthRejectionResult.STORAGE_ERROR
                else -> AuthRejectionResult.TRANSIENT_FAILURE
            }
        }
    }

    private suspend fun refreshForAccessLocked(stored: AuthSession, force: Boolean = false): StoredAccessEvaluation {
        return when (val refreshed = refreshLocked(stored, force)) {
            is AuthOutcome.Success -> {
                val session = refreshed.value
                if (session != null && isVerifiedEmailSession(session)) {
                    StoredAccessEvaluation.Granted(session, offline = false)
                } else if (clearSessionLocked(AppAccessLockReason.SESSION_REVOKED)) {
                    StoredAccessEvaluation.Locked(AppAccessLockReason.SESSION_REVOKED, "Your session is no longer valid. Sign in again.")
                } else StoredAccessEvaluation.Locked(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE,
                    "The invalid session could not be removed safely. Retry after unlocking the device.")
            }
            is AuthOutcome.Failure -> when (refreshed.error.kind) {
                AuthError.Kind.UNAUTHORIZED -> StoredAccessEvaluation.Locked(
                    AppAccessLockReason.SESSION_REVOKED, "Your session was revoked or expired. Sign in again.")
                AuthError.Kind.LOCAL_STORAGE -> StoredAccessEvaluation.Locked(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE, refreshed.error.message)
                else -> StoredAccessEvaluation.Granted(stored, offline = true)
            }
        }
    }

    private suspend fun clearIneligibleSessionLocked(): StoredAccessEvaluation = if (clearSessionLocked(AppAccessLockReason.EMAIL_VERIFICATION_REQUIRED)) {
        StoredAccessEvaluation.Locked(AppAccessLockReason.EMAIL_VERIFICATION_REQUIRED,
            "Sign in with a verified email account to continue.")
    } else StoredAccessEvaluation.Locked(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE,
        "The unsupported saved session could not be removed safely. Retry after unlocking the device.")

    private suspend fun clearSessionLocked(reason: AppAccessLockReason): Boolean = try {
        sessionStore.clear()
        publishCleared(reason, when (reason) {
            AppAccessLockReason.SESSION_REVOKED -> "Your session was revoked or expired. Sign in again."
            AppAccessLockReason.EMAIL_VERIFICATION_REQUIRED -> "Sign in with a verified email account to continue."
            AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE -> "Secure sign-in storage is unavailable. Retry after unlocking the device."
            AppAccessLockReason.SIGN_IN_REQUIRED -> "Sign in with email to continue."
        })
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    private fun isVerifiedEmailSession(session: AuthSession): Boolean = !session.isAnonymous &&
        !session.email.isNullOrBlank() && normalizeEmail(session.email) != null

    suspend fun deleteAccount(): AuthOutcome<Unit> = withStoredSession { session ->
        val response = transport.request(
            "POST", "/functions/v1/nearby-private-media",
            JSONObject().put("action", "delete_account").toString(),
            session.accessToken,
        )
        val status = if (response.status in 200..299) runCatching { JSONObject(response.body).optString("status") }.getOrNull() else null
        if (status == "ACCOUNT_DELETED") {
            sessionStore.clear()
            publishCleared()
            AuthOutcome.Success(Unit)
        } else if (status == "DELETE_RETRY_REQUIRED") {
            AuthOutcome.Failure(AuthError(AuthError.Kind.SERVER, "Account deletion is still being completed. Please retry."))
        } else response.failureOutcome()
    }

    private suspend fun <T> withStoredSession(block: suspend (AuthSession) -> AuthOutcome<T>): AuthOutcome<T> = sessionMutex.withLock {
        var session = try { sessionStore.read() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE, "Saved sign-in is unavailable."))
        } ?: return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED, "Sign in to continue."))
        if (session.expiresAtEpochSeconds - clock.nowSeconds() <= REFRESH_SKEW_SECONDS) {
            when (val refreshed = refreshLocked(session)) {
                is AuthOutcome.Failure -> return@withLock refreshed
                is AuthOutcome.Success -> session = refreshed.value
                    ?: return@withLock AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED, "Sign in to continue."))
            }
        }
        call { block(session) }
    }

    private suspend fun persist(session: AuthSession) {
        sessionStore.write(session)
        mutableAccessLockReason.value = if (isVerifiedEmailSession(session)) AppAccessLockReason.SIGN_IN_REQUIRED
            else AppAccessLockReason.EMAIL_VERIFICATION_REQUIRED
        mutableAccountIdentity.value = AccountIdentity(session.userId, session.email, session.isAnonymous)
        mutableSessionRevision.value = mutableSessionRevision.value + 1
    }

    private fun publishObserved(session: AuthSession) {
        val next = AccountIdentity(session.userId, session.email, session.isAnonymous)
        if (mutableAccountIdentity.value != next) {
            mutableAccountIdentity.value = next
            mutableSessionRevision.value = mutableSessionRevision.value + 1
        }
    }

    private fun publishCleared(
        reason: AppAccessLockReason = AppAccessLockReason.SIGN_IN_REQUIRED,
        message: String = "Sign in with email to continue.",
    ) {
        mutableAccessLockReason.value = reason
        mutableAccessMessage.value = message
        mutableAccountIdentity.value = null
        mutableSessionRevision.value = mutableSessionRevision.value + 1
    }

    private suspend fun <T> withValidSession(block: suspend (AuthSession) -> AuthOutcome<T>): AuthOutcome<T> {
        return when (val outcome = currentSession(refreshIfNeeded = true)) {
            is AuthOutcome.Failure -> outcome
            is AuthOutcome.Success -> outcome.value?.let { call { block(it) } }
                ?: AuthOutcome.Failure(AuthError(AuthError.Kind.UNAUTHORIZED, "Sign in to continue."))
        }
    }

    private suspend fun <T> call(block: suspend () -> AuthOutcome<T>): AuthOutcome<T> = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AuthNetworkException) {
            AuthOutcome.Failure(AuthError(AuthError.Kind.NETWORK, "Check your connection and try again."))
        } catch (error: SessionStorageException) {
            AuthOutcome.Failure(AuthError(AuthError.Kind.LOCAL_STORAGE, error.message ?: "Secure storage failed."))
        } catch (error: Exception) {
            AuthOutcome.Failure(AuthError(AuthError.Kind.SERVER, "The account service returned an unexpected response."))
        }
    }

    private fun AuthHttpResponse.unitOutcome(): AuthOutcome<Unit> =
        if (status in 200..299) AuthOutcome.Success(Unit) else failureOutcome()

    private fun AuthHttpResponse.parsedSession(): AuthSession? = runCatching {
        val json = JSONObject(body)
        val user = json.getJSONObject("user")
        val expiresAt = json.optLong("expires_at", 0L).takeIf { it > 0 }
            ?: (clock.nowSeconds() + json.getLong("expires_in"))
        AuthSession(
            userId = user.getString("id"),
            email = user.optString("email").takeIf { it.isNotBlank() },
            isAnonymous = user.optBoolean("is_anonymous", false),
            accessToken = json.getString("access_token"),
            refreshToken = json.getString("refresh_token"),
            expiresAtEpochSeconds = expiresAt,
        )
    }.getOrNull()

    private fun AuthHttpResponse.sessionOutcome(): AuthOutcome.Success<AuthSession>? =
        if (status in 200..299) parsedSession()?.let { AuthOutcome.Success(it) } else null

    private fun AuthHttpResponse.failureOutcome(): AuthOutcome.Failure {
        val serverMessage = runCatching {
            val json = JSONObject(body)
            json.optString("msg").ifBlank { json.optString("message") }.ifBlank { json.optString("error_description") }
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val kind = when (status) {
            400, 401, 403, 422 -> AuthError.Kind.UNAUTHORIZED
            429 -> AuthError.Kind.RATE_LIMITED
            in 500..599 -> AuthError.Kind.SERVER
            else -> AuthError.Kind.SERVER
        }
        val retryAfter = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.toLongOrNull()
        return AuthOutcome.Failure(AuthError(kind, serverMessage ?: defaultError(kind), retryAfter))
    }

    private fun AuthHttpResponse.isOtpExpired(): Boolean {
        if (status != 400 && status != 403) return false
        return runCatching { JSONObject(body).optString("error_code") }
            .getOrNull()?.equals("otp_expired", ignoreCase = true) == true
    }

    private fun AuthHttpResponse.isDefinitiveRefreshRejection(): Boolean {
        if (status == 401 || status == 403) return true
        if (status != 400 && status != 422) return false
        val code = runCatching {
            val json = JSONObject(body)
            sequenceOf("error_code", "code", "error").map { json.optString(it).lowercase(Locale.ROOT) }
                .firstOrNull { it.isNotBlank() }
        }.getOrNull()
        return code in DEFINITIVE_REFRESH_CODES
    }

    private fun normalizeEmail(value: String): String? = value.trim().lowercase(Locale.ROOT)
        .takeIf { it.length <= 254 && EMAIL.matches(it) }

    private fun <T> invalidEmail(): AuthOutcome<T> =
        AuthOutcome.Failure(AuthError(AuthError.Kind.INVALID_INPUT, "Enter a valid email address."))

    private fun defaultError(kind: AuthError.Kind) = when (kind) {
        AuthError.Kind.UNAUTHORIZED -> "The code or sign-in is no longer valid. Request a new code and try again."
        AuthError.Kind.ACCOUNT_EXISTS -> "An account already uses this email. Sign in and request a new code."
        AuthError.Kind.RATE_LIMITED -> "Too many attempts. Wait before trying again."
        else -> "The account service is unavailable. Try again later."
    }

    private companion object {
        const val REFRESH_SKEW_SECONDS = 60L
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val DEFINITIVE_REFRESH_CODES = setOf(
            "refresh_token_not_found", "refresh_token_already_used", "refresh_token_reuse_detected",
            "session_not_found", "session_expired", "user_not_found",
        )
    }
}
