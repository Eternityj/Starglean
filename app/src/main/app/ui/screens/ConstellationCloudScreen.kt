package com.starglean.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.online.ConstellationCloudRuntime
import com.starglean.app.core.online.LocalConstellationSync
import com.starglean.app.core.online.OnlineRuntime

@Composable
fun ConstellationCloudScreen(isActive: Boolean, onAccount: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { OnlineRuntime.get(context) }
    val identity by runtime.auth.accountIdentity.collectAsState()
    val shared = LocalConstellationSync.current
    val fallback = remember(context, identity?.userId, shared) { if (shared == null) ConstellationCloudRuntime(context) else null }
    val controller = shared ?: requireNotNull(fallback).controller
    val state by controller.state.collectAsState()
    LaunchedEffect(isActive) { if (isActive) runtime.auth.currentSession(refreshIfNeeded = false) }
    LaunchedEffect(isActive, identity, controller) { if (shared == null) controller.setActive(isActive, identity) }
    androidx.compose.runtime.DisposableEffect(fallback) { onDispose { fallback?.close() } }

    Column(
        Modifier.fillMaxSize().background(StargleanColors.Ink).padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Constellation sync", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineMedium)
        }
        if (identity == null) {
            Text("Sign in before choosing whether to sync constellation layout.", color = StargleanColors.WarmWhite)
            Button(onClick = onAccount) { Text("Open account") }
        } else {
            Text(
                if (state.enabled) "Sync is on while the app is open." else "Sync is off. Your device constellation stays unchanged.",
                color = StargleanColors.GoldBright, style = MaterialTheme.typography.titleLarge,
            )
            Text(
                "Only constellation instances, star slots and home selection are synced. Old diary text, photos and locations are never uploaded.",
                color = StargleanColors.MutedText,
            )
            state.message?.let { Text(it, color = if (state.conflict == null) StargleanColors.MutedText else StargleanColors.Error) }
            if (state.busy) CircularProgressIndicator(color = StargleanColors.Gold)
            else if (state.conflict != null) {
                Button(onClick = controller::restoreCloudCopy, modifier = Modifier.fillMaxWidth()) { Text("Use cloud constellation on this device") }
                TextButton(onClick = controller::keepDeviceCopy, modifier = Modifier.fillMaxWidth()) { Text("Keep this device copy in cloud") }
                Text("Cloud restore requires each referenced cloud entry to be explicitly restored in Cloud library first.",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
            } else if (!state.enabled) {
                Button(onClick = controller::enable, modifier = Modifier.fillMaxWidth()) { Text("Enable for this account") }
            } else {
                TextButton(onClick = controller::retry, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                TextButton(onClick = controller::disable, modifier = Modifier.fillMaxWidth()) { Text("Turn off sync") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Signing out never deletes this device's constellation.", color = StargleanColors.MutedText,
            style = MaterialTheme.typography.labelMedium)
    }
}
