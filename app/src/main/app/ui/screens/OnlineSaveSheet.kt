package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.starglean.app.core.account.AuthOutcome
import com.starglean.app.core.account.AuthOperationLease
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.discovery.NearbyLocationPurpose
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.draft.WritingDraftStore
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.memory.MemoryLocationFix
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.*
import com.starglean.app.core.network.*
import com.starglean.app.core.online.*
import com.starglean.app.ui.components.DiaryPaper
import com.starglean.app.ui.components.PublishedStarPage
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class PublicationLocationNotReady : Exception()

@Composable
internal fun OnlineSaveSheet(
    preview: WritingPreview,
    isActive: Boolean,
    canSaveDraft: () -> Boolean,
    reconciliationFailure: String?,
    onRecheck: () -> Unit,
    onBack: () -> Unit,
    flushDraft: suspend () -> WritingDraftState,
    onSaved: (WritingDraftState) -> Unit,
    onSavedReturn: () -> Unit,
    onSeeConstellations: () -> Unit,
) {
    val context = LocalContext.current
    val appLocationAccess = LocalAppLocationAccess.current
    val locationAllowed = isManagedLocationAllowed(appLocationAccess.managed, appLocationAccess.allowed)
    val currentLocationAllowed by rememberUpdatedState(locationAllowed)
    val runtime = remember(context) { OnlineRuntime.get(context) }
    val journal = remember(context) { AndroidPendingPublicationStore(context) }
    val photoStore = remember(context) { AndroidPhotoStore(context) }
    val ownStore = remember(context) { OwnDiaryStore(context) }
    val draftStore = remember(context) { WritingDraftStore(context) }
    val cloudLinks = runtime.cloudEntryLinks
    val scope = rememberCoroutineScope()
    var visibility by rememberSaveable(preview.diary.id) { mutableStateOf(EntryVisibility.Public) }
    var busy by remember(preview.diary.id) { mutableStateOf(false) }
    var completed by remember(preview.diary.id) { mutableStateOf(false) }
    var consent by rememberSaveable(preview.diary.id) { mutableStateOf(false) }
    var message by remember(preview.diary.id) { mutableStateOf<String?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var pendingRecovery by remember { mutableStateOf(false) }
    var pendingLoaded by remember { mutableStateOf(false) }
    var loadedPending by remember { mutableStateOf<PendingPublication?>(null) }
    var showEditConfirmation by remember { mutableStateOf(false) }
    var savedRecord by remember { mutableStateOf<OwnDiaryRecord?>(null) }
    var terminalRejection by remember(preview.diary.id) { mutableStateOf<String?>(null) }
    val location = rememberForegroundNearbyState(isActive && !showAccount && !showPrivacy && !completed,
        NearbyLocationPurpose.Publishing)

    LaunchedEffect(journal) {
        loadedPending = runCatching { journal.read() }.getOrNull()
        pendingRecovery = loadedPending != null
        loadedPending?.let {
            visibility = it.frozen.request.visibility
            consent = true // The persisted request already contains the user's final consent; this only permits reconciliation.
            message = "A previous save is awaiting confirmation. Sign in and confirm to reconcile it; nothing is resent automatically."
        }
        pendingLoaded = true
    }

    fun beginLocation() {
        if (locationAllowed) location.start() else appLocationAccess.openSettings()
    }
    fun coordinatorFor(lease: AuthOperationLease, repository: NearbyRepository) =
        PublicationCoordinator(journal, NearbyPublicationGateway(repository),
            PublicationPhotoSource { ref -> withContext(Dispatchers.IO) { photoStore.open(ref).use { it.readBytes() } } },
            isIdentityCurrent = { runtime.auth.isOperationLeaseCurrent(lease) })
    fun requestEdit() {
        if (busy || !pendingLoaded) return
        if (loadedPending != null || pendingRecovery) showEditConfirmation = true else onBack()
    }
    fun cancelPendingAndEdit() {
        if (busy || !pendingLoaded || (!pendingRecovery && loadedPending == null)) return
        showEditConfirmation = false
        busy = true
        message = null
        scope.launch {
            try {
                val lease = when (val value = runtime.auth.operationLease()) {
                    is AuthOutcome.Success -> value.value
                    is AuthOutcome.Failure -> null
                } ?: run {
                    showAccount = true
                    message = "Sign in to the account that started this save before cancelling it."
                    return@launch
                }
                val accountId = UUID.fromString(lease.userId)
                val scopedRepository = runtime.nearby.scoped(lease.accessToken)
                val coordinator = coordinatorFor(lease, scopedRepository)
                when (val result = coordinator.cancelForEditing(accountId)) {
                    PublicationEditResult.Ready -> {
                        loadedPending = null
                        pendingRecovery = false
                        message = null
                        terminalRejection = null
                        onBack()
                    }
                    is PublicationEditResult.NeedsAccount -> {
                        showAccount = true
                        message = result.reason
                    }
                    is PublicationEditResult.Failed -> message = result.message
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                message = "Cancellation could not be confirmed. Your draft and pending save are preserved. Try again."
            } finally { busy = false }
        }
    }
    fun publish() {
        if (!pendingLoaded || busy || completed || !consent ||
            (!pendingRecovery && (!currentLocationAllowed || !canSaveDraft() || !location.ready))) return
        busy = true; message = null
        scope.launch {
            try {
                suspend fun rejected(result: PublicationStep.Rejected) {
                    loadedPending = journal.read()
                    pendingRecovery = loadedPending != null
                    // A definite location rejection is recoverable with a fresh fix and
                    // a new request identity. Never thaw an unknown publication outcome.
                    terminalRejection = result.code.takeUnless { it == "LOCATION_UNRELIABLE" }
                    location.stop()
                    message = if (result.code == "LOCATION_UNRELIABLE")
                        "The location could not be confirmed in time. Your draft is safe. Use fresh location, then confirm again."
                    else result.message
                }
                suspend fun finish(coordinator: PublicationCoordinator, lease: AuthOperationLease, result: PublicationStep.Complete) {
                    val pending = result.pending
                    val entryId = pending.entryId ?: error("Missing cloud entry identity")
                    if (!runtime.auth.isOperationLeaseCurrent(lease) || lease.userId != pending.frozen.accountId.toString()) error("Signed-in account changed")
                    ownStore.save(pending.frozen.localRecord)
                    savedRecord = pending.frozen.localRecord
                    if (!runtime.auth.isOperationLeaseCurrent(lease)) error("Signed-in account changed")
                    cloudLinks.put(CloudEntryLink(pending.frozen.accountId.toString(), pending.frozen.localRecord.diary.id, entryId))
                    if (!runtime.auth.isOperationLeaseCurrent(lease)) error("Signed-in account changed")
                    onSaved(pending.frozen.draftToken)
                    var retired = false
                    repeat(20) {
                        if (draftStore.load() != pending.frozen.draftToken) { retired = true; return@repeat }
                        delay(100)
                    }
                    if (!retired) error("Draft retirement was not confirmed")
                    coordinator.acknowledgeComplete(pending.frozen.request.requestId)
                    completed = true; pendingRecovery = false; location.stop(); message = "Saved online and on this device."
                }
                val lease = when (val value = runtime.auth.operationLease()) {
                    is AuthOutcome.Success -> value.value
                    is AuthOutcome.Failure -> null
                } ?: run { showAccount = true; message = "Sign in before saving online."; return@launch }
                val accountId = UUID.fromString(lease.userId)
                val scopedRepository = runtime.nearby.scoped(lease.accessToken)
                val account = scopedRepository.ensureAccount(ZoneId.systemDefault().id)
                if (account !is NetworkResult.Success || account.value.status != "ACCOUNT_READY") {
                    error("Account readiness was not confirmed")
                }
                if (!runtime.auth.isOperationLeaseCurrent(lease)) error("Signed-in account changed")
                val coordinator = coordinatorFor(lease, scopedRepository)
                var replaceCancelledAttempt = false
                if (pendingRecovery) {
                    when (val recovered = coordinator.recover(accountId)) {
                        is PublicationStep.Complete -> {
                            finish(coordinator, lease, recovered)
                            return@launch
                        }
                        is PublicationStep.NeedsExplicitSubmit -> {
                            if (!currentLocationAllowed) {
                                message = "Location access is off. Nothing was resent; use Location settings before confirming again."
                                return@launch
                            }
                            if (recovered.pending.entryId != null) when (val result = coordinator.submit(accountId)) {
                                is PublicationStep.Complete -> {
                                    finish(coordinator, lease, result)
                                    return@launch
                                }
                                is PublicationStep.Failed -> { message = result.message; return@launch }
                                is PublicationStep.Rejected -> { rejected(result); return@launch }
                                is PublicationStep.NeedsAccount -> { showAccount = true; message = result.reason; return@launch }
                                else -> Unit
                            } else {
                                loadedPending = recovered.pending
                                if (!location.ready) {
                                    message = "The earlier attempt was not committed. Use a fresh settled location, then confirm to cancel it and create a new retry identity."
                                    if (!location.optedIn) beginLocation()
                                    return@launch
                                }
                                replaceCancelledAttempt = true
                            }
                        }
                        is PublicationStep.Failed -> { message = recovered.message; return@launch }
                        is PublicationStep.Rejected -> { rejected(recovered); return@launch }
                        is PublicationStep.NeedsAccount -> { showAccount = true; message = recovered.reason; return@launch }
                        else -> Unit
                    }
                }
                val token = flushDraft()
                val draftUuid = UUID.fromString(preview.diary.id.removePrefix("own-"))
                val manifests = withContext(Dispatchers.IO) { preview.diary.photos.map { photo ->
                    val bytes = photoStore.open(photo.ref).use { it.readBytes() }
                    MediaManifestItem(UUID.nameUUIDFromBytes("$draftUuid:${photo.ref}".toByteArray()), photo.mimeType, bytes.size,
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                } }
                val weather = preview.diary.weatherSnapshot
                require(!weather.isDemo) { "Sample weather cannot be published" }
                if (!currentLocationAllowed) throw PublicationLocationNotReady()
                when (val result = coordinator.submitFresh(accountId, draftUuid, replaceCancelledAttempt) {
                    // Called after photo IO and any old-request cancellation. There is no
                    // suspension between selecting this real fix and freezing the request.
                    if (!currentLocationAllowed || !location.ready) throw PublicationLocationNotReady()
                    val fix = location.fix ?: throw PublicationLocationNotReady()
                    val readerLocation = try { fix.toNetworkLocation() }
                        catch (_: IllegalArgumentException) { throw PublicationLocationNotReady() }
                    val request = PublishEntryRequest(UUID.randomUUID(), preview.diary.title.orEmpty(), preview.diary.body,
                        preview.diary.paper.name.lowercase().replace("warmwhite", "warm_white"), preview.diary.font.name.lowercase(),
                        WeatherSnapshot(com.starglean.app.core.network.WeatherKind.valueOf(weather.kind.name),
                            com.starglean.app.core.network.Daylight.valueOf(weather.daylight.name), weather.temperatureC,
                            weather.capturedAt?.let(Instant::parse)), manifests, visibility, readerLocation, shareConfirmed = true)
                    val place = MemoryLocationFix(fix.latitude, fix.longitude, fix.quality.horizontalAccuracyMeters.toFloat(), readerLocation.capturedAt.toEpochMilli())
                    val record = OwnDiaryRecord(preview.diary.copy(blurredPlace = "Location shared with Starglean"), place, System.currentTimeMillis())
                    FrozenPublication(accountId, draftUuid, token, request, record)
                }) {
                    is PublicationStep.Complete -> finish(coordinator, lease, result)
                    is PublicationStep.Failed -> { pendingRecovery = journal.read() != null; message = result.message }
                    is PublicationStep.Rejected -> rejected(result)
                    is PublicationStep.NeedsAccount -> { showAccount = true; message = result.reason }
                    else -> message = "Review and confirm again to publish."
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: PublicationLocationNotReady) {
                pendingRecovery = journal.read() != null
                message = if (!currentLocationAllowed)
                    "Location access is off. Nothing was sent; use Location settings before confirming again."
                else "Waiting for a fresh, steady location. Your draft is safe; confirm again when location is ready."
            }
            catch (_: Exception) { pendingRecovery = journal.read() != null; message = "Saving could not be confirmed. Your draft and pending publication are preserved." }
            finally { busy = false }
        }
    }

    if (showAccount) { OnlineAccountScreen { showAccount = false }; return }
    if (showPrivacy) { PrivacyDetailsScreen { showPrivacy = false }; return }
    BackHandler(enabled = isActive && !completed && (busy || pendingRecovery || loadedPending != null || !pendingLoaded)) {
        requestEdit()
    }
    val saved = savedRecord
    if (completed && saved != null) {
        PublishedStarPage(diaryId = saved.diary.id, isActive = isActive,
            published = visibility == EntryVisibility.Public,
            onSeeConstellations = onSeeConstellations, onWriteAnother = onSavedReturn)
        return
    }
    val displayedDiary = loadedPending?.frozen?.localRecord?.diary ?: preview.diary
    val editable = pendingLoaded && !busy && !pendingRecovery
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 22.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::requestEdit, enabled = pendingLoaded && !busy) { Text("‹  Edit", color = StargleanColors.Gold) }
            Spacer(Modifier.weight(1f))
            Text("Ready to save", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Own entries carry a location status, not a place name, so DiaryPaper's "Near …" default does not fit.
            val placeLabel = if (displayedDiary === preview.diary) "Your fresh location is added when you save" else displayedDiary.blurredPlace
            // Sized to the entry, so the choices below are visible without scrolling past blank paper.
            DiaryPaper(displayedDiary, Modifier.fillMaxWidth(), showDemoBadge = false, minimumHeight = 200.dp,
                placeLabel = placeLabel)
            Text("Who can find it?", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VisibilityChoice("Public", "People within 200 m can find it", visibility == EntryVisibility.Public, editable,
                    Modifier.weight(1f).fillMaxHeight()) { visibility = EntryVisibility.Public }
                VisibilityChoice("Private", "Only you can read it", visibility == EntryVisibility.Private, editable,
                    Modifier.weight(1f).fillMaxHeight()) { visibility = EntryVisibility.Private }
            }
            Text("Both keep your fresh location to anchor the entry. Strangers never see exact coordinates.",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
            PublishingLocationCard(
                message = location.message,
                ready = location.ready,
                optedIn = location.optedIn,
                busy = busy,
                onRetry = ::beginLocation,
                actionLabel = if (locationAllowed) "Refresh location" else "Location settings",
            )
            Row(Modifier.fillMaxWidth().toggleable(consent, enabled = editable, role = Role.Checkbox) { consent = it },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, onCheckedChange = null, enabled = editable,
                    colors = CheckboxDefaults.colors(checkedColor = StargleanColors.Gold, checkmarkColor = StargleanColors.Ink))
                Text("I confirm this final entry, weather, photos and location may be sent to Starglean.",
                    color = StargleanColors.WarmWhite, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp))
            }
            TextButton(onClick = { showPrivacy = true }) { Text("Privacy and deletion details", color = StargleanColors.Gold) }
            message?.let { Text(it, color = StargleanColors.Gold) }
            if (!canSaveDraft()) { Text(reconciliationFailure ?: "Checking the durable draft…", color = StargleanColors.Gold); TextButton(onClick = onRecheck) { Text("Recheck") } }
        }
        // Always on screen, so publishing never needs a scroll to find.
        Box(Modifier.fillMaxWidth().background(StargleanColors.Ink.copy(alpha = .9f)).padding(horizontal = 22.dp, vertical = 12.dp)) {
            Button(onClick = ::publish, enabled = pendingLoaded && terminalRejection == null && !busy && consent &&
                (pendingRecovery || (locationAllowed && location.ready && canSaveDraft())),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StargleanColors.Gold, contentColor = StargleanColors.Ink),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = StargleanColors.Ink, strokeWidth = 2.dp) else Text(when {
                    !pendingLoaded -> "Checking previous saves…"
                    pendingRecovery -> "Reconcile pending save"
                    !consent -> "Tick the box above to continue"
                    !locationAllowed -> "Enable location to continue"
                    !location.ready && !pendingRecovery -> "Waiting for your location…"
                    visibility == EntryVisibility.Public -> "Publish publicly"
                    else -> "Save privately"
                })
            }
        }
    }
    if (showEditConfirmation) PendingSaveEditDialog(onConfirm = ::cancelPendingAndEdit,
        onDismiss = { showEditConfirmation = false })
}

@Composable
internal fun PendingSaveEditDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = StargleanColors.InkRaised,
        title = { Text("Edit this draft?", color = StargleanColors.WarmWhite) },
        text = { Text("Cancel this pending online save and remove any cloud copy from this attempt. Your local draft and photos stay on this device, ready to edit.",
            color = StargleanColors.WarmWhite) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Cancel save and edit") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep pending save") } },
    )
}

@Composable
private fun VisibilityChoice(title: String, detail: String, selected: Boolean, enabled: Boolean,
    modifier: Modifier = Modifier, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.clip(shape)
        .background(if (selected) StargleanColors.Gold.copy(alpha = .14f) else StargleanColors.InkRaised.copy(alpha = .9f))
        .border(if (selected) 1.4.dp else .7.dp, StargleanColors.Gold.copy(alpha = if (selected) .8f else .2f), shape)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = if (selected) StargleanColors.GoldBright else StargleanColors.WarmWhite,
            style = MaterialTheme.typography.titleMedium)
        Text(detail, color = StargleanColors.MutedText, style = MaterialTheme.typography.bodySmall)
    }
}
