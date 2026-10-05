package com.starglean.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starglean.app.BuildConfig
import com.starglean.app.core.account.EmailChangeSide
import com.starglean.app.core.account.EmailOtpIntent
import com.starglean.app.core.design.StargleanColors

data class AccountUiState(
    val signedInEmail: String? = null,
    val anonymous: Boolean = false,
    val mode: EmailOtpIntent = EmailOtpIntent.EXISTING_ACCOUNT,
    val email: String = "",
    val otp: String = "",
    val otpSent: Boolean = false,
    val signupConsent: Boolean = false,
    val newEmail: String = "",
    val currentEmailCode: String = "",
    val newEmailCode: String = "",
    val emailChangeRequested: Boolean = false,
    val currentEmailConfirmed: Boolean = false,
    val newEmailConfirmed: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val destructiveConfirmationVisible: Boolean = false,
    val anonymousEnrollmentAvailable: Boolean = false,
) {
    override fun toString(): String = "AccountUiState(signedIn=${signedInEmail != null || anonymous}, mode=$mode, otp=<redacted>, busy=$busy, emailChangeRequested=$emailChangeRequested)"
}

sealed interface AccountAction {
    data object Back : AccountAction
    data class ChooseMode(val value: EmailOtpIntent) : AccountAction
    data class EditEmail(val value: String) : AccountAction
    data class EditOtp(val value: String) : AccountAction
    data class SetSignupConsent(val value: Boolean) : AccountAction
    data object RequestOtp : AccountAction
    data object VerifyOtp : AccountAction
    data object EnrollAnonymous : AccountAction
    data class EditNewEmail(val value: String) : AccountAction
    data object RequestEmailChange : AccountAction
    data class EditEmailChangeCode(val side: EmailChangeSide, val value: String) : AccountAction
    data class VerifyEmailChange(val side: EmailChangeSide) : AccountAction
    data object SignOut : AccountAction
    data object ShowDeleteConfirmation : AccountAction
    data object HideDeleteConfirmation : AccountAction
    data object ConfirmDelete : AccountAction
}

@Composable
fun AccountScreen(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit,
    onPrivacy: () -> Unit = {},
    modifier: Modifier = Modifier,
    onLocationSettings: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().background(StargleanColors.Ink)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = { onAction(AccountAction.Back) }) { Text("Back") }
            if (state.signedInEmail == null && !state.anonymous) SignedOutAccount(state, onAction, onPrivacy)
            else SignedInAccount(state, onAction)
            if (onLocationSettings != null) TextButton(onClick = onLocationSettings, enabled = !state.busy) {
                Text("Location settings", color = StargleanColors.Gold)
            }
            Text("Starglean · ${BuildConfig.VERSION_NAME.removeSuffix("-en")}",
                style = MaterialTheme.typography.labelSmall, color = StargleanColors.MutedText)
        }
        if (state.destructiveConfirmationVisible) DeleteConfirmation(state.busy, onAction)
    }
}

@Composable
private fun SignedOutAccount(state: AccountUiState, onAction: (AccountAction) -> Unit, onPrivacy: () -> Unit) {
    Text("Carry your sky with you", style = MaterialTheme.typography.titleLarge, color = StargleanColors.GoldBright)
    Text(
        "Sign in with email to enter your sky. Once signed in, local memories and drafts remain available offline. Existing local entries are not uploaded automatically.",
        color = StargleanColors.MutedText,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ModeChoice("Existing account", state.mode == EmailOtpIntent.EXISTING_ACCOUNT, Modifier.weight(1f)) {
            onAction(AccountAction.ChooseMode(EmailOtpIntent.EXISTING_ACCOUNT))
        }
        ModeChoice("New account", state.mode == EmailOtpIntent.NEW_ACCOUNT, Modifier.weight(1f)) {
            onAction(AccountAction.ChooseMode(EmailOtpIntent.NEW_ACCOUNT))
        }
    }
    Text(
        if (state.mode == EmailOtpIntent.NEW_ACCOUNT)
            "This creates a new cloud identity after you verify your email. It does not publish or upload existing entries."
        else "This only signs in an existing account. It will not create a new one.",
        color = StargleanColors.WarmWhite,
        style = MaterialTheme.typography.bodyMedium,
    )
    AccountField(state.email, { onAction(AccountAction.EditEmail(it)) }, "Email", KeyboardType.Email)
    TextButton(onClick = onPrivacy, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
        Text("Read privacy & sharing details", color = StargleanColors.ColdBlue)
    }
    if (state.mode == EmailOtpIntent.NEW_ACCOUNT) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.signupConsent,
                onCheckedChange = { onAction(AccountAction.SetSignupConsent(it)) },
                colors = CheckboxDefaults.colors(checkedColor = StargleanColors.Gold),
            )
            Text("I understand this creates an account using this email.", color = StargleanColors.WarmWhite)
        }
    }
    if (!state.otpSent) {
        AccountButton(
            if (state.mode == EmailOtpIntent.NEW_ACCOUNT) "Send registration code" else "Send sign-in code",
            enabled = !state.busy && (state.mode != EmailOtpIntent.NEW_ACCOUNT || state.signupConsent),
        ) { onAction(AccountAction.RequestOtp) }
    } else {
        Text(
            if (state.mode == EmailOtpIntent.NEW_ACCOUNT)
                "Enter the 8-digit code to finish creating your account. It expires after one hour."
            else "Enter the 8-digit code to sign in. It expires after one hour.",
            color = StargleanColors.MutedText,
        )
        AccountField(state.otp, { onAction(AccountAction.EditOtp(it.filter(Char::isDigit).take(8))) }, "8-digit code", KeyboardType.NumberPassword)
        AccountButton("Verify and continue", enabled = !state.busy && state.otp.length == 8) { onAction(AccountAction.VerifyOtp) }
    }
    AccountStatus(state)
    if (state.anonymousEnrollmentAvailable) {
        TextButton(onClick = { onAction(AccountAction.EnrollAnonymous) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text("Continue with a temporary account", color = StargleanColors.ColdBlue)
        }
    }
}

@Composable
private fun SignedInAccount(state: AccountUiState, onAction: (AccountAction) -> Unit) {
    Text(if (state.anonymous) "Temporary account" else "Account", style = MaterialTheme.typography.titleLarge, color = StargleanColors.GoldBright)
    Text(state.signedInEmail ?: "No email attached", color = StargleanColors.WarmWhite)
    if (state.anonymous) {
        Text("Add an email before relying on this account across devices.", color = StargleanColors.MutedText)
    }
    Spacer(Modifier.height(4.dp))
    Text("Change email", style = MaterialTheme.typography.titleLarge, color = StargleanColors.WarmWhite)
    AccountField(state.newEmail, { onAction(AccountAction.EditNewEmail(it)) }, "New email", KeyboardType.Email)
    if (!state.emailChangeRequested) {
        AccountButton("Send confirmation codes", enabled = !state.busy) { onAction(AccountAction.RequestEmailChange) }
    } else {
        if (!state.anonymous) {
            EmailChangeCode("Code sent to current email", state.currentEmailCode, state.currentEmailConfirmed, state.busy,
                { onAction(AccountAction.EditEmailChangeCode(EmailChangeSide.CURRENT_EMAIL, it)) },
                { onAction(AccountAction.VerifyEmailChange(EmailChangeSide.CURRENT_EMAIL)) })
        }
        EmailChangeCode("Code sent to new email", state.newEmailCode, state.newEmailConfirmed, state.busy,
            { onAction(AccountAction.EditEmailChangeCode(EmailChangeSide.NEW_EMAIL, it)) },
            { onAction(AccountAction.VerifyEmailChange(EmailChangeSide.NEW_EMAIL)) })
        Text("The email is changed only after every required confirmation is accepted.", color = StargleanColors.Gold)
    }
    AccountStatus(state)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onAction(AccountAction.SignOut) }, enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = StargleanColors.Gold,
                disabledContentColor = StargleanColors.Gold.copy(alpha = .38f))) {
            Text("Sign out", style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = { onAction(AccountAction.ShowDeleteConfirmation) }, enabled = !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = StargleanColors.Error,
                disabledContentColor = StargleanColors.Error.copy(alpha = .38f))) {
            Text("Delete account", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EmailChangeCode(label: String, value: String, confirmed: Boolean, busy: Boolean, onEdit: (String) -> Unit, onVerify: () -> Unit) {
    if (confirmed) Text("$label — confirmed", color = StargleanColors.GoldBright)
    else {
        AccountField(value, { onEdit(it.filter(Char::isDigit).take(8)) }, label, KeyboardType.NumberPassword)
        AccountButton("Confirm this address", enabled = !busy && value.length == 8, onClick = onVerify)
    }
}

@Composable
private fun ModeChoice(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border = if (selected) StargleanColors.Gold else StargleanColors.ColdBlueMuted
    Box(modifier.border(1.dp, border, RoundedCornerShape(14.dp)).clickable(role = Role.RadioButton, onClick = onClick).padding(14.dp)) {
        Text(label, color = if (selected) StargleanColors.GoldBright else StargleanColors.MutedText, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AccountField(value: String, onValueChange: (String) -> Unit, label: String, keyboardType: KeyboardType) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (keyboardType == KeyboardType.NumberPassword) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = StargleanColors.WarmWhite, unfocusedTextColor = StargleanColors.WarmWhite,
            focusedBorderColor = StargleanColors.Gold, unfocusedBorderColor = StargleanColors.ColdBlueMuted,
            focusedLabelColor = StargleanColors.Gold, unfocusedLabelColor = StargleanColors.MutedText,
        ),
    )
}

@Composable
private fun AccountButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp),
        colors = ButtonDefaults.buttonColors(containerColor = StargleanColors.Gold, contentColor = StargleanColors.Ink),
    ) { Text(label) }
}

@Composable
private fun AccountStatus(state: AccountUiState) {
    if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(color = StargleanColors.Gold)
        Text("Contacting the account service…", color = StargleanColors.MutedText)
    }
    state.message?.let { Text(it, color = StargleanColors.Error, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun DeleteConfirmation(busy: Boolean, onAction: (AccountAction) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(20.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Delete this account?", style = MaterialTheme.typography.titleLarge, color = StargleanColors.Error)
            Text(
                "This permanently deletes the cloud account and its cloud data. Its private local library is not erased, " +
                    "but a different account cannot open it after this account is deleted.",
                color = StargleanColors.WarmWhite,
            )
            AccountButton("Permanently delete account", enabled = !busy) { onAction(AccountAction.ConfirmDelete) }
            TextButton(onClick = { onAction(AccountAction.HideDeleteConfirmation) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Keep account", color = StargleanColors.WarmWhite)
            }
        }
    }
}
