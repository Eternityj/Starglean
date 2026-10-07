package com.starglean.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.starglean.app.core.account.AuthOutcome
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.library.DiaryDeletionStore
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.draft.WritingDraftStore
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.network.EntryVisibility
import com.starglean.app.core.network.OwnEntry
import com.starglean.app.core.network.ReactionKind
import com.starglean.app.core.network.ReactionSummary
import com.starglean.app.core.online.CloudLibraryController
import com.starglean.app.core.online.OnlineRuntime
import com.starglean.app.ui.components.DiaryPaper
import com.starglean.app.ui.components.DiaryReaction
import com.starglean.app.ui.components.DiaryReactionIcon
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CloudLibraryScreen(
    isActive: Boolean,
    onAccount: () -> Unit,
    onBack: () -> Unit,
    initialTab: String = "entries",
) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { OnlineRuntime.get(context) }
    val scope = rememberCoroutineScope()
    val controller = remember(runtime) {
        CloudLibraryController(runtime.nearby, runtime.cloudEntryLinks, DiaryDeletionStore(context), scope,
            OwnDiaryStore(context), AndroidPhotoStore(context), WritingDraftStore(context), runtime.auth,
            onInboxAcknowledged = runtime.reactions::observeAcknowledgedInbox)
    }
    val identity by runtime.auth.accountIdentity.collectAsState()
    val state by controller.state.collectAsState()
    var tab by remember(initialTab) { mutableStateOf(if (initialTab == "inbox") "inbox" else "entries") }
    var deleting by remember { mutableStateOf<OwnEntry?>(null) }
    var openedInboxEntry by remember { mutableStateOf<OwnEntry?>(null) }

    LaunchedEffect(isActive) {
        if (isActive) runtime.auth.currentSession(refreshIfNeeded = false)
        else controller.setActive(false, null)
    }
    LaunchedEffect(isActive, identity) { if (isActive) controller.setActive(true, identity) }
    LaunchedEffect(isActive, tab, state.inbox) {
        if (isActive && tab == "inbox" && state.inbox != null) controller.inboxDisplayed()
    }
    DisposableEffect(controller) { onDispose { controller.setActive(false, null) } }

    Box(Modifier.fillMaxSize().background(StargleanColors.Ink)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { controller.setActive(false, null); onBack() }) { Text("Back") }
                Text("Cloud library", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineMedium)
            }
            if (identity == null) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sign in to see your own cloud entries and reactions.", color = StargleanColors.WarmWhite)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAccount) { Text("Open account") }
                    Text("Existing local entries remain on this device and are not uploaded automatically.", color = StargleanColors.MutedText,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloudTab("My cloud entries", tab == "entries", Modifier.weight(1f)) { tab = "entries" }
                    CloudTab("Reaction inbox", tab == "inbox", Modifier.weight(1f)) { tab = "inbox" }
                }
                state.message?.let { Text(it, color = StargleanColors.Error, modifier = Modifier.padding(vertical = 10.dp)) }
                val initialLoading = if (tab == "entries") state.entriesLoading && !state.entriesLoaded
                    else state.inboxLoading && !state.inboxLoaded
                if (initialLoading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StargleanColors.Gold)
                } else LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (tab == "entries") {
                        state.entriesMessage?.let { message -> item {
                            LoadFailure(message, "Retry entries", controller::refreshEntries)
                        } }
                        if (state.entriesLoaded && state.entries.isEmpty()) item { Text("No cloud entries yet.", color = StargleanColors.MutedText) }
                        items(state.entries, key = { it.entryId }) { entry -> CloudEntryCard(
                            entry, state.operationEntryId == entry.entryId,
                            runtime = runtime,
                            accountId = identity!!.userId,
                            onVisibility = { controller.setVisibility(entry, it) },
                            onRestore = { controller.restore(entry) },
                            onDelete = { deleting = entry },
                        ) }
                        if (state.entriesLoading) item { InlineLoading("Loading more cloud entries…") }
                        else if (state.hasMoreEntries) item {
                            TextButton(onClick = controller::loadMoreEntries, modifier = Modifier.fillMaxWidth()) { Text("Load more") }
                        }
                    } else {
                        state.inboxMessage?.let { message -> item {
                            LoadFailure(message, "Retry inbox", controller::refreshInbox)
                        } }
                        val reactions = state.inbox?.items.orEmpty()
                        if (state.inboxLoaded && reactions.isEmpty()) item { Text("No reactions yet.", color = StargleanColors.MutedText) }
                        items(reactions, key = { "${it.entryId}:${it.kind}" }) { summary ->
                            ReactionInboxRow(
                                summary = summary,
                                entry = state.inboxEntries[summary.entryId],
                                loading = summary.entryId in state.inboxEntryLoading,
                                failed = summary.entryId in state.inboxEntryErrors,
                                onOpen = { state.inboxEntries[summary.entryId]?.let { openedInboxEntry = it } },
                                onRetry = { controller.resolveInboxEntry(summary.entryId) },
                            )
                        }
                    }
                }
                TextButton(onClick = { if (tab == "entries") controller.refreshEntries() else controller.refreshInbox() },
                    enabled = if (tab == "entries") !state.entriesLoading else !state.inboxLoading,
                    modifier = Modifier.fillMaxWidth()) { Text(if (tab == "entries") "Refresh entries" else "Refresh inbox") }
            }
        }
        deleting?.let { entry -> ConfirmCloudDelete(
            busy = state.operationEntryId == entry.entryId,
            onCancel = { if (state.operationEntryId == null) deleting = null },
            onConfirm = { controller.delete(entry); deleting = null },
        ) }
        openedInboxEntry?.let { entry -> OpenedInboxEntry(entry) { openedInboxEntry = null } }
    }
}

@Composable private fun LoadFailure(message: String, retryLabel: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(message, color = StargleanColors.Error)
        TextButton(onClick = onRetry) { Text(retryLabel) }
    }
}

@Composable private fun InlineLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(color = StargleanColors.Gold)
        Text(label, color = StargleanColors.MutedText, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable private fun CloudTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.textButtonColors(contentColor = if (selected) StargleanColors.GoldBright else StargleanColors.MutedText)) { Text(label) }
}

@Composable private fun CloudEntryCard(entry: OwnEntry, busy: Boolean, runtime: OnlineRuntime, accountId: String,
    onVisibility: (EntryVisibility) -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        DiaryPaper(entry.toDiary(), showDemoBadge = false, placeLabel = "Original saved place",
            photoContent = if (entry.media.isEmpty()) null else { { CloudMediaStrip(entry, runtime, accountId) } })
        Text("${entry.visibility.replaceFirstChar(Char::uppercase)} · ${entry.publicationState.replace('_', ' ')} · ${entry.media.size} cloud photo${if (entry.media.size == 1) "" else "s"}",
            color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onVisibility(if (entry.visibility == "public") EntryVisibility.Private else EntryVisibility.Public) }, enabled = !busy) {
                Text(if (entry.visibility == "public") "Make private" else "Make public")
            }
            TextButton(onClick = onRestore, enabled = !busy) { Text("Restore to device") }
        }
        TextButton(onClick = onDelete, enabled = !busy) { Text("Delete cloud entry", color = StargleanColors.Error) }
        Text("Visibility changes keep the original anchor; no new location is collected.", color = StargleanColors.MutedText,
            style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun CloudMediaStrip(entry: OwnEntry, runtime: OnlineRuntime, accountId: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        entry.media.sortedBy { it.ordinal }.forEach { media ->
            val bitmap by produceState<android.graphics.Bitmap?>(null, media.id, accountId) {
                val lease = (runtime.auth.operationLease(accountId) as? AuthOutcome.Success)?.value ?: return@produceState
                val result = runtime.nearby.scoped(lease.accessToken).readMedia(media.id, media.bytes)
                if (!runtime.auth.isOperationLeaseCurrent(lease)) return@produceState
                val read = (result as? com.starglean.app.core.network.NetworkResult.Success)?.value ?: return@produceState
                if (read.sha256 != media.sha256 || read.contentType != media.contentType || read.bytes.size != media.bytes) return@produceState
                val width = media.width ?: return@produceState
                val height = media.height ?: return@produceState
                if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > 16_000_000L) return@produceState
                val sample = if (maxOf(width, height) > 2048) 2 else 1
                value = android.graphics.BitmapFactory.decodeByteArray(read.bytes, 0, read.bytes.size,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                awaitDispose { value?.recycle() }
            }
            bitmap?.let { image -> Image(image.asImageBitmap(), contentDescription = "Cloud diary photo",
                modifier = Modifier.weight(1f).height(150.dp), contentScale = ContentScale.Crop) }
        }
    }
}

@Composable private fun ReactionInboxRow(summary: ReactionSummary, entry: OwnEntry?, loading: Boolean, failed: Boolean,
    onOpen: () -> Unit, onRetry: () -> Unit) {
    val reaction = when (summary.kind) {
        ReactionKind.Hug -> DiaryReaction.Hug
        ReactionKind.Understand -> DiaryReaction.Understand
        ReactionKind.Thanks -> DiaryReaction.Thanks
        ReactionKind.Joy -> DiaryReaction.Joy
    }
    Row(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DiaryReactionIcon(reaction)
        Column(Modifier.weight(1f)) {
            Text(reaction.label, color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleLarge)
            Text(entry?.title?.takeIf(String::isNotBlank) ?: if (loading) "Loading diary title…" else "Diary title unavailable",
                color = StargleanColors.WarmWhite, style = MaterialTheme.typography.bodyLarge)
            Text("${summary.count} total · ${summary.unread} new", color = StargleanColors.MutedText)
            when {
                entry != null -> TextButton(onClick = onOpen) { Text("Open diary") }
                failed -> TextButton(onClick = onRetry) { Text("Retry diary") }
            }
        }
    }
}

@Composable private fun OpenedInboxEntry(entry: OwnEntry, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .82f)).padding(20.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DiaryPaper(entry.toDiary(), showDemoBadge = false, placeLabel = "Original saved place")
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Close diary") }
        }
    }
}

@Composable private fun ConfirmCloudDelete(busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = .72f)).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(20.dp)).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Delete this cloud entry?", color = StargleanColors.Error, style = MaterialTheme.typography.titleLarge)
            Text("The linked local diary and constellation star are removed only after the server confirms cloud deletion.", color = StargleanColors.WarmWhite)
            Button(onClick = onConfirm, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Delete cloud entry") }
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

private fun OwnEntry.toDiary(): DiaryDemo = DiaryDemo(
    id = "cloud-$entryId", title = title.takeIf(String::isNotBlank), body = body,
    date = runCatching { DateTimeFormatter.ofPattern("d MMM · h:mm a").withZone(ZoneId.systemDefault()).format(createdAt) }.getOrDefault("Cloud entry"),
    weatherSnapshot = DiaryWeatherSnapshot(
        kind = when (weather.kind.name) { "Clear" -> WeatherKind.Clear; "Cloudy" -> WeatherKind.Cloudy; "Rain" -> WeatherKind.Rain; "Snow" -> WeatherKind.Snow; "Fog" -> WeatherKind.Fog; else -> WeatherKind.Unknown },
        daylight = when (weather.daylight.name) { "Day" -> Daylight.Day; "Dusk" -> Daylight.Dusk; else -> Daylight.Night },
        temperatureC = weather.temperatureCelsius, capturedAt = weather.capturedAt?.toString(), isDemo = false,
    ), blurredPlace = "private cloud anchor",
    paper = PaperStyle.entries.firstOrNull { it.name.equals(paperStyle, true) || it.label.equals(paperStyle, true) } ?: PaperStyle.WarmWhite,
    font = DiaryFont.entries.firstOrNull { it.name.equals(fontStyle, true) || it.label.equals(fontStyle, true) } ?: DiaryFont.Clear,
)
