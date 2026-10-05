package com.starglean.app.core.account

import com.starglean.app.ui.screens.AccountAction
import com.starglean.app.ui.screens.AccountUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * The non-secret part of the signed-out form that may survive a UI recreation.
 * OTPs, operation state, sessions and tokens deliberately cannot be represented here.
 */
data class AccountFormDraft(
    val mode: EmailOtpIntent = EmailOtpIntent.EXISTING_ACCOUNT,
    val email: String = "",
    val signupConsent: Boolean = false,
) {
    internal fun normalized(): AccountFormDraft {
        val safeEmail = email.trim().filterNot { it == '\r' || it == '\n' }.take(MAX_EMAIL_CHARS)
        return copy(
            email = safeEmail,
            signupConsent = signupConsent && mode == EmailOtpIntent.NEW_ACCOUNT,
        )
    }

    private companion object { const val MAX_EMAIL_CHARS = 320 }
}

/** UI coordinator. Call [loadLocalSession] only when the account page opens. */
class AccountController(
    private val auth: SupabaseAuthClient,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
    anonymousEnrollmentAvailable: Boolean = false,
    initialFormDraft: AccountFormDraft = AccountFormDraft(),
    private val onFormDraftChanged: (AccountFormDraft) -> Unit = {},
) {
    private val restoredDraft = initialFormDraft.normalized()
    private val mutableState = MutableStateFlow(AccountUiState(
        mode = restoredDraft.mode,
        email = restoredDraft.email,
        signupConsent = restoredDraft.signupConsent,
        anonymousEnrollmentAvailable = anonymousEnrollmentAvailable,
    ))
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var closed = false
    private var activeJob: Job? = null

    /** Disk-only: deliberately does not refresh or perform any startup network request. */
    fun loadLocalSession() = launchBusy {
        when (val result = auth.currentSession(refreshIfNeeded = false)) {
            is AuthOutcome.Success -> mutableState.value = mutableState.value.copy(
                signedInEmail = result.value?.email,
                anonymous = result.value?.isAnonymous == true,
                busy = false,
                message = null,
            )
            is AuthOutcome.Failure -> fail(result.error)
        }
    }

    fun dispatch(action: AccountAction) {
        when (action) {
            AccountAction.Back -> { close(); onBack() }
            is AccountAction.ChooseMode -> if (!mutableState.value.busy) updateForm {
                it.copy(mode = action.value, signupConsent = it.signupConsent && action.value == EmailOtpIntent.NEW_ACCOUNT,
                    otpSent = false, otp = "", message = null)
            }
            is AccountAction.EditEmail -> if (!mutableState.value.busy) updateForm { it.copy(email = action.value, message = null) }
            is AccountAction.EditOtp -> if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(otp = action.value, message = null)
            is AccountAction.SetSignupConsent -> if (!mutableState.value.busy) updateForm {
                it.copy(signupConsent = action.value && it.mode == EmailOtpIntent.NEW_ACCOUNT)
            }
            AccountAction.RequestOtp -> requestOtp()
            AccountAction.VerifyOtp -> verifyOtp()
            AccountAction.EnrollAnonymous -> enrollAnonymous()
            is AccountAction.EditNewEmail -> if (!mutableState.value.busy) mutableState.value = mutableState.value.copy(newEmail = action.value, message = null)
            AccountAction.RequestEmailChange -> requestEmailChange()
            is AccountAction.EditEmailChangeCode -> if (!mutableState.value.busy) mutableState.value = when (action.side) {
                EmailChangeSide.CURRENT_EMAIL -> mutableState.value.copy(currentEmailCode = action.value, message = null)
                EmailChangeSide.NEW_EMAIL -> mutableState.value.copy(newEmailCode = action.value, message = null)
            }
            is AccountAction.VerifyEmailChange -> verifyEmailChange(action.side)
            AccountAction.SignOut -> signOut()
            AccountAction.ShowDeleteConfirmation -> mutableState.value = mutableState.value.copy(destructiveConfirmationVisible = true)
            AccountAction.HideDeleteConfirmation -> mutableState.value = mutableState.value.copy(destructiveConfirmationVisible = false)
            AccountAction.ConfirmDelete -> deleteAccount()
        }
    }

    private inline fun updateForm(update: (AccountUiState) -> AccountUiState) {
        mutableState.value = update(mutableState.value)
        val current = mutableState.value
        onFormDraftChanged(AccountFormDraft(current.mode, current.email, current.signupConsent).normalized())
    }

    private fun requestOtp() = launchBusy {
        val snapshot = mutableState.value
        if (snapshot.mode == EmailOtpIntent.NEW_ACCOUNT && !snapshot.signupConsent) {
            return@launchBusy fail(AuthError(AuthError.Kind.INVALID_INPUT, "Confirm that you want to create an account."))
        }
        when (val result = auth.requestEmailOtp(snapshot.email, snapshot.mode)) {
            is AuthOutcome.Success -> mutableState.value = mutableState.value.copy(otpSent = true, busy = false, message = null)
            is AuthOutcome.Failure -> fail(result.error)
        }
    }

    private fun verifyOtp() = launchBusy {
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(otp = "")
        when (val result = auth.verifyEmailOtp(snapshot.email, snapshot.otp, snapshot.mode)) {
            is AuthOutcome.Success -> mutableState.value = AccountUiState(
                signedInEmail = result.value.email, anonymous = result.value.isAnonymous,
                message = "Signed in. Your existing local diary has not been uploaded.",
            )
            is AuthOutcome.Failure -> if (result.error.kind == AuthError.Kind.ACCOUNT_EXISTS) {
                updateForm {
                    it.copy(
                        mode = EmailOtpIntent.EXISTING_ACCOUNT,
                        otp = "",
                        otpSent = false,
                        signupConsent = false,
                        busy = false,
                        message = result.error.message,
                    )
                }
            } else fail(result.error)
        }
    }

    private fun enrollAnonymous() = launchBusy {
        when (val result = auth.enrollAnonymous()) {
            is AuthOutcome.Success -> mutableState.value = AccountUiState(
                anonymous = true,
                anonymousEnrollmentAvailable = mutableState.value.anonymousEnrollmentAvailable,
                message = "Temporary account created. Add an email if you want to recover it on another device.",
            )
            is AuthOutcome.Failure -> fail(result.error)
        }
    }

    private fun requestEmailChange() = launchBusy {
        when (val result = auth.requestEmailChange(mutableState.value.newEmail)) {
            is AuthOutcome.Success -> mutableState.value = mutableState.value.copy(
                emailChangeRequested = true,
                currentEmailConfirmed = mutableState.value.anonymous,
                busy = false,
                message = null,
            )
            is AuthOutcome.Failure -> fail(result.error)
        }
    }

    private fun verifyEmailChange(side: EmailChangeSide) = launchBusy {
        val snapshot = mutableState.value
        val (email, code) = when (side) {
            EmailChangeSide.CURRENT_EMAIL -> snapshot.signedInEmail.orEmpty() to snapshot.currentEmailCode
            EmailChangeSide.NEW_EMAIL -> snapshot.newEmail to snapshot.newEmailCode
        }
        mutableState.value = when (side) {
            EmailChangeSide.CURRENT_EMAIL -> snapshot.copy(currentEmailCode = "")
            EmailChangeSide.NEW_EMAIL -> snapshot.copy(newEmailCode = "")
        }
        when (val result = auth.verifyEmailChange(email, code, side)) {
            is AuthOutcome.Failure -> fail(result.error)
            is AuthOutcome.Success -> {
                val currentDone = snapshot.currentEmailConfirmed || side == EmailChangeSide.CURRENT_EMAIL
                val newDone = snapshot.newEmailConfirmed || side == EmailChangeSide.NEW_EMAIL
                val authoritative = result.value
                mutableState.value = mutableState.value.copy(
                    signedInEmail = authoritative?.email ?: snapshot.signedInEmail,
                    anonymous = authoritative?.isAnonymous ?: snapshot.anonymous,
                    currentEmailConfirmed = currentDone,
                    newEmailConfirmed = newDone,
                    busy = false,
                    message = when {
                        authoritative?.email == snapshot.newEmail -> "Email change confirmed."
                        currentDone && newDone -> "Both codes were accepted. The service has not confirmed the new address yet."
                        else -> "One address is confirmed. Confirm the other address to finish."
                    },
                )
            }
        }
    }

    private fun signOut() = launchBusy {
        when (val result = auth.signOut()) {
            is AuthOutcome.Success -> mutableState.value = AccountUiState(message = result.value.message)
            is AuthOutcome.Failure -> fail(result.error)
        }
    }

    private fun deleteAccount() = launchBusy {
        when (val result = auth.deleteAccount()) {
            is AuthOutcome.Success -> mutableState.value = AccountUiState(
                message = "Account deleted.", // private local library was not erased, but another account cannot open it.
            )
            is AuthOutcome.Failure -> fail(result.error)
        }
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (closed || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        val started = generation
        activeJob = scope.launch {
            try { block() } finally {
                if (!closed && generation == started && mutableState.value.busy) {
                    mutableState.value = mutableState.value.copy(busy = false)
                }
            }
        }
    }

    fun close() { closed = true; generation++; activeJob?.cancel() }

    private fun fail(error: AuthError) {
        mutableState.value = mutableState.value.copy(busy = false, message = error.message)
    }
}
