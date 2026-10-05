package com.starglean.app.core.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Process-level mandatory-login gate. [Loading] is the cold-start default, so app content cannot
 * flash before encrypted session storage has been evaluated.
 */
class AppAccessController(
    private val auth: SupabaseAuthClient,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<AppAccessState>(AppAccessState.Loading)
    val state: StateFlow<AppAccessState> = mutableState.asStateFlow()
    private var generation = 0L
    private var operation: Job? = null
    private var closed = false
    private val identityJob: Job

    init {
        identityJob = scope.launch {
            combine(auth.accountIdentity, auth.accessLockReason, auth.accessMessage) { identity, reason, message ->
                Triple(identity, reason, message)
            }.collect { (identity, lockReason, accessMessage) ->
                if (closed || mutableState.value is AppAccessState.Loading) return@collect
                if (identity != null && !identity.isAnonymous && !identity.email.isNullOrBlank()) {
                    val existing = mutableState.value as? AppAccessState.Granted
                    mutableState.value = AppAccessState.Granted(identity,
                        existing?.takeIf { it.account.userId == identity.userId }?.connectivity ?: AccessConnectivity.ONLINE_SESSION)
                } else if (mutableState.value is AppAccessState.Granted) {
                    val reason = if (identity?.isAnonymous == true) AppAccessLockReason.EMAIL_VERIFICATION_REQUIRED else lockReason
                    mutableState.value = AppAccessState.Locked(reason,
                        if (reason == AppAccessLockReason.SESSION_REVOKED) "Your session was revoked or expired. Sign in again."
                        else if (reason == AppAccessLockReason.EMAIL_VERIFICATION_REQUIRED) "Sign in with a verified email account to continue."
                        else accessMessage)
                } else if (mutableState.value is AppAccessState.Locked && identity == null) {
                    mutableState.value = AppAccessState.Locked(lockReason, accessMessage)
                }
            }
        }
    }

    /** Call once from the process gate. Repeated calls cancel and replace an earlier cold-start load. */
    fun load() {
        if (closed) return
        val run = ++generation
        operation?.cancel()
        mutableState.value = AppAccessState.Loading
        operation = scope.launch { evaluate(run) }
    }

    /** Best-effort validation for ON_RESUME/reconnect; transient outages retain offline access. */
    fun onForeground() {
        if (closed || mutableState.value !is AppAccessState.Granted || operation?.isActive == true) return
        val run = ++generation
        operation = scope.launch { evaluate(run) }
    }

    /** Locks immediately, then clears local auth and attempts truthful this-device revocation. */
    fun signOut() {
        if (closed) return
        val run = ++generation
        operation?.cancel()
        mutableState.value = AppAccessState.Locked(AppAccessLockReason.SIGN_IN_REQUIRED,
            "Signed out on this device. Your local diary is still here.")
        operation = scope.launch {
            when (val result = auth.signOut()) {
                is AuthOutcome.Success -> if (current(run)) mutableState.value = AppAccessState.Locked(
                    AppAccessLockReason.SIGN_IN_REQUIRED, result.value.message)
                is AuthOutcome.Failure -> if (current(run)) mutableState.value = AppAccessState.Locked(
                    if (result.error.kind == AuthError.Kind.LOCAL_STORAGE) AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE
                    else AppAccessLockReason.SIGN_IN_REQUIRED, result.error.message)
            }
        }
    }

    fun close() { closed = true; generation++; operation?.cancel(); identityJob.cancel() }

    private suspend fun evaluate(run: Long) {
        try {
            val evaluated = auth.evaluateStoredAppAccess()
            if (!current(run)) return
            mutableState.value = when (evaluated) {
                is StoredAccessEvaluation.Granted -> AppAccessState.Granted(
                    AccountIdentity(evaluated.session.userId, evaluated.session.email, evaluated.session.isAnonymous),
                    if (evaluated.offline) AccessConnectivity.OFFLINE_VERIFIED_SESSION else AccessConnectivity.ONLINE_SESSION,
                )
                is StoredAccessEvaluation.Locked -> AppAccessState.Locked(evaluated.reason, evaluated.message)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (current(run)) mutableState.value = AppAccessState.Locked(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE,
                "Secure sign-in storage could not be checked. Retry after unlocking the device.")
        }
    }

    private fun current(run: Long): Boolean = !closed && generation == run
}
