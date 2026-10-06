package com.starglean.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.feedback.AndroidEncounterFeedback
import com.starglean.app.core.feedback.EncounterFeedbackSignal
import com.starglean.app.core.feedback.FeedbackPreferenceRead
import com.starglean.app.core.feedback.FeedbackPreferenceState
import com.starglean.app.core.feedback.FeedbackPreferences
import com.starglean.app.core.online.OnlineRuntime
import com.starglean.app.core.storage.AccountStorageContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FeedbackSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember(context) { OnlineRuntime.get(context.applicationContext) }
    val identity by runtime.auth.accountIdentity.collectAsState()
    val accountId = (context.applicationContext as? AccountStorageContext)?.accountId ?: identity?.userId
    val store = remember(context, accountId) { accountId?.let { FeedbackPreferences(context, it) } }
    val player = remember(context, accountId) { AndroidEncounterFeedback(context.applicationContext) }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var settings by remember(store) { mutableStateOf<FeedbackPreferenceState?>(null) }
    var message by remember(store) { mutableStateOf<String?>(null) }
    var playing by remember(player) { mutableStateOf(false) }
    var saving by remember(store) { mutableStateOf(false) }

    LaunchedEffect(store) {
        settings = when (val read = withContext(Dispatchers.IO) { store?.read() ?: FeedbackPreferenceRead.Unavailable }) {
            is FeedbackPreferenceRead.Success -> read.value
            FeedbackPreferenceRead.Unavailable -> null
        }
        if (store != null && settings == null) message = "Feedback settings could not be read. Nothing was changed."
    }
    LaunchedEffect(playing) {
        if (playing) {
            delay(EncounterFeedbackSignal.DURATION_MILLIS + 350L)
            playing = false
        }
    }
    DisposableEffect(player, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event in setOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY)) {
                player.stop()
                playing = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    fun persist(next: FeedbackPreferenceState) {
        if (saving) return
        player.stop()
        playing = false
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store?.save(next) == true }
            val readback = withContext(Dispatchers.IO) { store?.read() ?: FeedbackPreferenceRead.Unavailable }
            settings = (readback as? FeedbackPreferenceRead.Success)?.value
            saving = false
            message = when {
                saved && settings == next -> "Feedback settings saved for this account."
                settings != null -> "Saving could not be confirmed. The choices shown are the settings currently active on this device."
                else -> "Feedback settings could not be saved or verified. Preview is unavailable until they can be read safely."
            }
        }
    }

    Column(Modifier.fillMaxSize().background(StargleanColors.Ink).verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { player.stop(); onBack() }) { Text("Back") }
            Text("Sound & vibration", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineMedium)
        }
        Text("Choose how Starglean marks a nearby diary encounter. These choices are kept separately for each account.",
            color = StargleanColors.MutedText, modifier = Modifier.padding(vertical = 18.dp))
        val current = settings
        if (accountId == null) {
            Text("Sign in to change feedback settings.", color = StargleanColors.MutedText)
        } else if (current == null && message == null) {
            CircularProgressIndicator(color = StargleanColors.Gold)
        } else if (current != null) {
            FeedbackToggle("Encounter sound", "A short sound plays only when system audio policy allows it.", current.soundEnabled, !saving) {
                persist(current.copy(soundEnabled = it))
            }
            FeedbackToggle("Encounter vibration", "Vibrates as a found diary appears. Never overrides silent mode, Do Not Disturb, or calls.", current.hapticEnabled, !saving) {
                persist(current.copy(hapticEnabled = it))
            }
            Spacer(Modifier.height(18.dp))
            Button(onClick = {
                if (playing) {
                    player.stop()
                    playing = false
                    message = "Preview stopped."
                } else {
                    val result = player.playPreview()
                    playing = result.started
                    message = result.message
                }
            }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (playing) "Stop preview" else "Play preview") }
            Text("Preview uses the real encounter sound and vibration. Calls, silent mode, Do Not Disturb, muted media and other music can suppress part or all of it.",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 12.dp))
        }
        message?.let { Text(it, color = if (it.contains("could not") || it.contains("unavailable")) StargleanColors.Error else StargleanColors.MutedText,
            modifier = Modifier.padding(top = 16.dp)) }
    }
}

@Composable
private fun FeedbackToggle(label: String, detail: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}
