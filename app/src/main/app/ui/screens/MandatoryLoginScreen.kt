package com.starglean.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starglean.app.R
import com.starglean.app.core.account.EmailOtpIntent
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.ui.components.DiscoverStarfield

/** No home/back/guest action: the primary app is not composed behind this screen. */
@Composable
internal fun MandatoryLoginScreen(
    state: AccountUiState,
    onAction: (AccountAction) -> Unit,
    onPrivacy: () -> Unit,
    accessMessage: String? = null,
) {
    val newAccount = state.mode == EmailOtpIntent.NEW_ACCOUNT
    val canSend = !state.busy && state.email.isNotBlank() && (!newAccount || state.signupConsent)
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(Modifier.fillMaxSize().background(StargleanColors.Ink)) {
        DiscoverStarfield(motionEnabled = rememberMotionEnabled(), subdued = true)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = if (keyboardVisible) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LoginAppIcon(Modifier.size(34.dp))
                Spacer(Modifier.width(10.dp))
                Text("Starglean", color = StargleanColors.WarmWhite, fontFamily = FontFamily.Serif,
                    fontSize = 23.sp, letterSpacing = .6.sp)
            }
            Spacer(Modifier.height(if (keyboardVisible) 16.dp else 48.dp))
            if (!keyboardVisible) Column(Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
                Text("YOUR OWN LITTLE UNIVERSE", color = StargleanColors.Gold,
                    fontSize = 10.sp, letterSpacing = 2.3.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(14.dp))
                Text("A place for\nyour moments.", fontFamily = FontFamily.Serif,
                    fontSize = 39.sp, lineHeight = 44.sp, color = StargleanColors.WarmWhite)
            }
            if (!keyboardVisible) Spacer(Modifier.height(32.dp))
            Column(
                Modifier.widthIn(max = 440.dp).fillMaxWidth()
                    .background(StargleanColors.InkRaised.copy(alpha = .93f), RoundedCornerShape(28.dp))
                    .border(1.dp, StargleanColors.Gold.copy(alpha = .2f), RoundedCornerShape(28.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!keyboardVisible) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoginMode("Sign in", !newAccount, !state.busy, Modifier.weight(1f)) {
                        onAction(AccountAction.ChooseMode(EmailOtpIntent.EXISTING_ACCOUNT))
                    }
                    LoginMode("Create account", newAccount, !state.busy, Modifier.weight(1f)) {
                        onAction(AccountAction.ChooseMode(EmailOtpIntent.NEW_ACCOUNT))
                    }
                }
                Text(if (state.otpSent) if (newAccount) "Confirm your registration" else "Check your inbox" else if (newAccount) "Begin your collection" else "Welcome back",
                    color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleLarge)
                if (!keyboardVisible) Text(if (state.otpSent && newAccount) "Enter the 8-digit code to finish creating your account."
                    else if (state.otpSent) "Enter the 8-digit code sent to your email. No password to remember."
                    else if (newAccount) "Create your private account with an email code."
                    else "Use the email linked to your account. We'll send you a sign-in code.",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.email, onValueChange = { onAction(AccountAction.EditEmail(it)) },
                    enabled = !state.busy && !state.otpSent, label = { Text("Email address") },
                    singleLine = true, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSend && !state.otpSent) onAction(AccountAction.RequestOtp) }),
                    colors = loginFieldColors(),
                )
                if (newAccount && !state.otpSent) {
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(state.signupConsent, { onAction(AccountAction.SetSignupConsent(it)) }, enabled = !state.busy,
                            modifier = Modifier.offset(x = (-10).dp), colors = CheckboxDefaults.colors(checkedColor = StargleanColors.Gold))
                        Text("I have read the privacy details and want to create an account.",
                            color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f).padding(top = 6.dp))
                    }
                }
                if (state.otpSent) {
                    OutlinedTextField(state.otp,
                        { onAction(AccountAction.EditOtp(it.filter(Char::isDigit).take(8))) },
                        enabled = !state.busy, label = { Text("8-digit code") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (!state.busy && state.otp.length == 8) onAction(AccountAction.VerifyOtp) }),
                        colors = loginFieldColors(),
                    )
                }
                val message = state.message ?: accessMessage
                if (message != null) Text(message, color = StargleanColors.GoldBright,
                    style = MaterialTheme.typography.labelMedium)
                Button(
                    onClick = { onAction(if (state.otpSent) AccountAction.VerifyOtp else AccountAction.RequestOtp) },
                    enabled = if (state.otpSent) !state.busy && state.otp.length == 8 else canSend,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StargleanColors.Gold,
                        contentColor = StargleanColors.Ink, disabledContainerColor = StargleanColors.Gold.copy(alpha = .32f)),
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), color = StargleanColors.Ink, strokeWidth = 2.dp)
                    else Text(if (state.otpSent) if (newAccount) "Verify & create account" else "Verify & enter" else if (newAccount) "Send registration code" else "Send sign-in code")
                }
                if (state.otpSent) TextButton(onClick = { onAction(AccountAction.ChooseMode(state.mode)) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Change email or request another code", color = StargleanColors.MutedText,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(22.dp))
            TextButton(onClick = onPrivacy, enabled = !state.busy) {
                Text("Privacy & sharing", color = StargleanColors.Gold, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LoginMode(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = modifier
        .background(if (selected) StargleanColors.Gold.copy(alpha = .13f) else Color.Transparent, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) {
        Text(label, color = if (selected) StargleanColors.GoldBright else StargleanColors.MutedText,
            style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = StargleanColors.Gold, unfocusedBorderColor = StargleanColors.ColdBlueMuted,
    focusedTextColor = StargleanColors.WarmWhite, unfocusedTextColor = StargleanColors.WarmWhite,
    focusedLabelColor = StargleanColors.Gold, unfocusedLabelColor = StargleanColors.MutedText,
)

@Composable
private fun LoginAppIcon(modifier: Modifier = Modifier) {
    Image(painter = painterResource(R.drawable.ic_starglean), contentDescription = "Starglean app icon",
        modifier = modifier.clip(RoundedCornerShape(8.dp)))
}

/** Kept for the separate private-library ownership screen; login uses the actual app icon. */
@Composable
internal fun LoginSkyMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width * .48f, size.height * .54f)
        val radius = size.minDimension * .25f
        drawCircle(StargleanColors.Gold.copy(alpha = .65f), radius, center, style = Stroke(1.dp.toPx()))
        val star = Path().apply {
            moveTo(center.x, center.y - radius * 1.55f)
            quadraticTo(center.x + radius * .2f, center.y - radius * .2f, center.x + radius, center.y)
            quadraticTo(center.x + radius * .2f, center.y + radius * .2f, center.x, center.y + radius * 1.55f)
            quadraticTo(center.x - radius * .2f, center.y + radius * .2f, center.x - radius, center.y)
            quadraticTo(center.x - radius * .2f, center.y - radius * .2f, center.x, center.y - radius * 1.55f)
            close()
        }
        drawPath(star, StargleanColors.GoldBright)
        drawCircle(StargleanColors.Gold, 1.3.dp.toPx(), Offset(size.width * .9f, size.height * .16f))
    }
}
