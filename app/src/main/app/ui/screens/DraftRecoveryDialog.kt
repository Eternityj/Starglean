package com.starglean.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.starglean.app.core.draft.DraftRecovery
import com.starglean.app.core.draft.DraftRecoverySnapshot
import com.starglean.app.core.design.StargleanColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DraftRecoveryDialog(session: WritingDraftSession, onClose: () -> Unit) {
    val context = LocalContext.current
    val recovery = remember(context) { DraftRecovery(context) }
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<DraftRecoverySnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var startEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(recovery) {
        try { snapshot = recovery.inspect() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The recovery files could not be inspected. Nothing was changed." }
    }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, containerColor = StargleanColors.InkRaised,
        title = { Text("Recover your draft", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineMedium) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Original files and available photos will be kept privately on this device. Choose a verified version to reopen as a new draft. Copy any unsaved text before continuing.")
            snapshot?.let { inspected ->
                if (inspected.candidates.isEmpty()) Text("No complete version could be verified. Originals will still be preserved.")
                inspected.candidates.forEach { candidate ->
                    TextButton(enabled = !busy, onClick = { selected = candidate.source; startEmpty = false },
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, StargleanColors.Gold.copy(alpha = if (selected == candidate.source) .8f else .2f)),
                        contentPadding = PaddingValues(14.dp)) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text((if (selected == candidate.source) "✓ " else "") + when {
                                candidate.source.endsWith(".new") -> "Interrupted write"
                                candidate.source.endsWith(".bak") -> "Previous version"
                                else -> "Saved version"
                            })
                            Text(candidate.state.draft?.title?.ifBlank { "Untitled draft" } ?: "Empty draft", color = StargleanColors.WarmWhite)
                            Text(candidate.state.draft?.body?.take(160).orEmpty(), maxLines = 4,
                                color = StargleanColors.MutedText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = { selected = null; startEmpty = true }) {
                    Text((if (startEmpty) "✓ " else "") + "Keep originals and start an empty draft")
                }
            }
            if (snapshot == null && error == null) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(enabled = !busy && snapshot != null && (selected != null || startEmpty), onClick = {
            busy = true; error = null
            scope.launch {
                try { session.recover { recovery.recover(requireNotNull(snapshot), selected) }; onClose() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Recovery was not confirmed. Originals are preserved. Close and inspect the versions again." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Recovering…" else "Confirm recovery") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Keep unchanged") } })
}
