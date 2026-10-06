package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.starglean.app.core.backup.BackupPreview
import com.starglean.app.core.backup.LocalBackupStore
import com.starglean.app.core.design.StargleanColors
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*

@Composable
fun BackupScreen(isActive: Boolean = true, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalBackupStore(context) }
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pickerOutstanding by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    val latestPreview by rememberUpdatedState(preview)
    val latestJob by rememberUpdatedState(job)
    fun discardLater(pending: Job?, checked: BackupPreview?) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            pending?.cancelAndJoin()
            // A failed best-effort cleanup is retried when this account next opens.
            checked?.let { runCatching { store.discard(it) } }
        }
    }
    fun stopForPrivacy() {
        password = ""; confirmation = ""
        val checked = preview; preview = null
        job?.cancel()
        discardLater(job, checked)
        message = "Backup paused. Enter your password again to continue. An interrupted export may leave an incomplete file; create a new backup before relying on it."
    }
    LaunchedEffect(isActive) { if (!isActive) stopForPrivacy() }
    DisposableEffect(owner, store) {
        val observer = LifecycleEventObserver { _, event ->
            // Android's document picker is part of this operation, not an abandoned session.
            if (event == Lifecycle.Event.ON_STOP && !pickerOutstanding) stopForPrivacy()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(store) {
        onDispose {
            val pending = latestJob
            discardLater(pending, latestPreview)
        }
    }
    BackHandler(busy) { message = "Please wait, or use Stop backup below. Existing entries will not be overwritten." }
    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        job = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "The backup operation was not confirmed. Check the password, file and free storage. Existing entries were not overwritten. You can safely retry the same file." }
            finally { busy = false; password = ""; confirmation = "" }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pickerOutstanding = false
        if (uri != null && isActive) {
            if (password.length < 12 || password != confirmation) message = "The password was cleared while choosing a file. Enter it again and choose a new backup file. Nothing was exported."
            else {
                val secret = password.toCharArray()
                runOperation {
                    val count = store.export(uri, secret)
                    message = "Encrypted backup saved · $count entries. Keep both the file and its password safe."
                }
            }
        }
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOutstanding = false
        if (uri != null && isActive) {
            if (password.length < 12) message = "The password was cleared while choosing a file. Enter it again and reopen the backup. Nothing was imported."
            else {
                val secret = password.toCharArray()
                runOperation {
                    preview?.let { store.discard(it) }; preview = null
                    preview = store.inspect(uri, secret)
                    message = "Backup verified. Review it before importing. Nothing has been added yet."
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack, enabled = !busy) { Text("‹  Settings") }
        Text("Keep your memories safe", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineMedium)
        Text("A private, password-protected copy", color = StargleanColors.Gold)
        Text("Includes saved diary text, photos, weather and any remembered precise locations. It does not include your current draft, constellation layout, sign-in, permissions or cloud links.", color = StargleanColors.MutedText)
        Text("Choose where to save the encrypted file. The app does not upload it itself. A file provider you choose may sync it. There is no password recovery.", color = StargleanColors.MutedText)
        if (preview == null) {
            OutlinedTextField(password, { password = it.take(256) }, enabled = !busy,
                label = { Text("Backup password · at least 12 characters") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmation, { confirmation = it.take(256) }, enabled = !busy,
                label = { Text("Repeat password for a new backup") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy && password.length >= 12 && password == confirmation,
                onClick = { pickerOutstanding = true; export.launch("Starglean-${java.time.LocalDate.now()}.starglean") }, modifier = Modifier.fillMaxWidth()) { Text("Create encrypted backup") }
            OutlinedButton(enabled = !busy && password.length >= 12, onClick = { pickerOutstanding = true; choose.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()) { Text("Open a backup to review") }
        } else {
            val checked = requireNotNull(preview)
            Text("${checked.records.size} entries · ${checked.photoCount} photos", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleLarge)
            Text("Import adds missing entries to this account's device library only. Identical entries are skipped; conflicting versions are not overwritten. Entries previously deleted on this device stay deleted. Imported entries are not published or automatically placed in constellations.", color = StargleanColors.MutedText)
            Button(enabled = !busy, onClick = { runOperation {
                val result = store.import(checked)
                preview = null
                message = "Imported ${result.added} entries · ${result.alreadyPresent} already present · ${result.skippedDeleted} previously deleted and skipped."
            } }, modifier = Modifier.fillMaxWidth()) { Text("Confirm import into this account") }
            TextButton(enabled = !busy, onClick = { runOperation { store.discard(checked); preview = null } }) { Text("Discard preview") }
        }
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { stopForPrivacy() }) { Text("Stop backup") }
        }
        message?.let { Text(it, color = StargleanColors.GoldBright) }
        Text("Up to 4 GB per backup. Keep an extra copy outside this phone; uninstalling removes local app data.",
            style = MaterialTheme.typography.labelMedium, color = StargleanColors.MutedText)
    }
}
