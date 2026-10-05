package com.starglean.app.core.account

data class AuthSession(
    val userId: String,
    val email: String?,
    val isAnonymous: Boolean,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
) {
    override fun toString(): String = "AuthSession(userId=$userId, email=$email, isAnonymous=$isAnonymous, accessToken=<redacted>, refreshToken=<redacted>, expiresAtEpochSeconds=$expiresAtEpochSeconds)"
}

enum class EmailOtpIntent { EXISTING_ACCOUNT, NEW_ACCOUNT }

enum class EmailChangeSide { CURRENT_EMAIL, NEW_EMAIL }

sealed interface AuthOutcome<out T> {
    data class Success<T>(val value: T) : AuthOutcome<T>
    data class Failure(val error: AuthError) : AuthOutcome<Nothing>
}

data class AuthError(
    val kind: Kind,
    val message: String,
    val retryAfterSeconds: Long? = null,
) {
    enum class Kind { INVALID_INPUT, UNAUTHORIZED, ACCOUNT_EXISTS, RATE_LIMITED, NETWORK, SERVER, CONFIGURATION, LOCAL_STORAGE }
}

data class AccountIdentity(val userId: String, val email: String?, val isAnonymous: Boolean)

enum class AccessConnectivity { ONLINE_SESSION, OFFLINE_VERIFIED_SESSION }
enum class AppAccessLockReason { SIGN_IN_REQUIRED, SESSION_REVOKED, SECURE_STORAGE_UNAVAILABLE, EMAIL_VERIFICATION_REQUIRED }

sealed interface AppAccessState {
    data object Loading : AppAccessState
    data class Granted(val account: AccountIdentity, val connectivity: AccessConnectivity) : AppAccessState
    data class Locked(val reason: AppAccessLockReason, val message: String) : AppAccessState
}

internal sealed interface StoredAccessEvaluation {
    data class Granted(val session: AuthSession, val offline: Boolean) : StoredAccessEvaluation
    data class Locked(val reason: AppAccessLockReason, val message: String) : StoredAccessEvaluation
}

enum class AuthRejectionResult { STALE_TOKEN, RECOVERED, SESSION_REVOKED, TRANSIENT_FAILURE, STORAGE_ERROR }

data class SignOutResult(val remoteRevocationConfirmed: Boolean) {
    val message: String = if (remoteRevocationConfirmed) {
        "Signed out." // keep local diary
    } else {
        "Signed out(offline)." // keep local diary
    }
}

data class AuthOperationLease(val userId: String, val sessionRevision: Long, val accessToken: String) {
    override fun toString() = "AuthOperationLease(userId=$userId, sessionRevision=$sessionRevision, accessToken=<redacted>)"
}

interface SessionStore {
    suspend fun read(): AuthSession?
    suspend fun write(session: AuthSession)
    suspend fun clear()
}

fun interface EpochClock {
    fun nowSeconds(): Long
}
