package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.starglean.app.BuildConfig
import com.starglean.app.core.design.StargleanColors

@Composable
fun SettingsScreen(isActive: Boolean = true, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler { if (page != null) page = null else onBack() }
    when (page) {
        "account" -> OnlineAccountScreen { page = null }
        "backup" -> BackupScreen(isActive) { page = null }
        "feedback" -> FeedbackSettingsScreen { page = null }
        "sync" -> ConstellationCloudScreen(isActive, { page = "account" }) { page = null }
        "privacy" -> PrivacyDetailsScreen { page = null }
        "device" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            TextButton(onClick = { page = null }) { Text("‹  Settings") }
            DeviceActivityPanel(isActive = isActive)
        }
        else -> Column(Modifier.fillMaxSize().background(StargleanColors.Ink).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("‹  Your sky") }
            Text("Settings", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineMedium)
            Text("Your memories. Your choices.", color = StargleanColors.MutedText, modifier = Modifier.padding(bottom = 12.dp))
            SettingsLink("Account & location", "Email, sign-in and location permission") { page = "account" }
            SettingsLink("Private backup", "Encrypted export and restore") { page = "backup" }
            SettingsLink("Constellation sync", "Keep your sky across devices") { page = "sync" }
            SettingsLink("Sound & touch", "Choose and test encounter feedback") { page = "feedback" }
            SettingsLink("Device & activity", "Sensor availability and movement") { page = "device" }
            SettingsLink("Privacy & sharing", "Where your memories are stored") { page = "privacy" }
            Text("Starglean · ${BuildConfig.VERSION_NAME}", color = StargleanColors.MutedText,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun SettingsLink(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(20.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(20.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = StargleanColors.MutedText, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = StargleanColors.Gold, modifier = Modifier.padding(start = 12.dp))
    }
}
