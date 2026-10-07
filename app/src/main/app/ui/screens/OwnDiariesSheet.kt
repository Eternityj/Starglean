package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.library.DiaryDeletionStore
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.stars.ConstellationStore
import com.starglean.app.core.stars.StarCollectionRules
import com.starglean.app.core.stars.StarCollectionState
import com.starglean.app.ui.components.DiaryPaper
import com.starglean.app.ui.components.DiaryPaperReaction
import com.starglean.app.ui.components.DiaryReaction
import com.starglean.app.core.network.ReactionKind
import com.starglean.app.core.online.ReactionNotice
import com.starglean.app.ui.components.DiaryWeatherBackground
import com.starglean.app.ui.components.MemoryCompassCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun filterOwnDiaries(records: List<OwnDiaryRecord>, diaryIds: Set<String>?, query: String = "",
    month: String? = null, zone: ZoneId = ZoneId.systemDefault()): List<OwnDiaryRecord> =
    if (diaryIds == null && query.isBlank() && month == null) records else records.filter { record ->
    (diaryIds == null || record.diary.id in diaryIds) &&
        (query.isBlank() || record.diary.title.orEmpty().contains(query.trim(), ignoreCase = true) ||
            record.diary.body.contains(query.trim(), ignoreCase = true)) &&
        (month == null || YearMonth.from(Instant.ofEpochMilli(record.savedAtMillis).atZone(zone)).toString() == month)
}

internal fun ownDiaryStarLabel(collection: StarCollectionState, diaryId: String): String {
    val placement = collection.placementForDiary(diaryId) ?: return "Waiting for a constellation"
    val item = collection.galleryItems().firstOrNull { it.id == placement.instanceId }
        ?: return "Star placement unavailable"
    return "${item.name} ${item.ordinal} · Star ${placement.starIndex + 1}"
}

internal fun ownDiaryDeleteMessage(collection: StarCollectionState?, diaryId: String): String {
    val placement = collection?.placementForDiary(diaryId)
    val home = collection != null && placement != null && placement.instanceId in collection.homeIds
    val homeName = if (home) collection.galleryItems().firstOrNull { it.id == placement?.instanceId }?.let { "${it.name} ${it.ordinal}" } else null
    return buildString {
        append("This permanently deletes the entry from this device and cannot be undone.")
        if (placement != null) append(" Its star will go dark.")
        else if (collection == null) append(" If it has a constellation star, that star will go dark; a completed constellation made incomplete will leave your home sky.")
        if (homeName != null) append(" $homeName will no longer be complete, so it will also be removed from your home sky.")
        append(" Photos used only by this entry will be removed. A photo still used by another saved entry or draft will be kept.")
    }
}

/** Only the private diary store supplies content; star indexes never synthesize diary records. */
@Composable
fun OwnDiariesSheet(
    isActive: Boolean,
    initialDiaryId: String? = null,
    diaryIds: Set<String>? = null,
    onPlaceDiary: ((String) -> Unit)? = null,
    onLibraryChanged: (() -> Unit)? = null,
    reactionNotices: Map<String, ReactionNotice> = emptyMap(),
    consumeReactionAnimation: (String) -> ReactionNotice? = { null },
    /** Stays visible above the scrolling list when this is a tab inside My constellations. */
    tabHeader: (@Composable () -> Unit)? = null,
    listHeader: (@Composable () -> Unit)? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { OwnDiaryStore(context) }
    val stars = remember(context) { ConstellationStore(context) }
    val deletionStore = remember(context) { DiaryDeletionStore(context) }
    val libraryRevision by LibraryStorageCoordinator.revisionFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf<List<OwnDiaryRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(initialDiaryId) { mutableStateOf(initialDiaryId) }
    var collection by remember { mutableStateOf<StarCollectionState?>(null) }
    var starReadFailed by remember { mutableStateOf(false) }
    var deleteCandidateId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var monthsOpen by remember { mutableStateOf(false) }
    val visibleRecords = filterOwnDiaries(records, diaryIds, search, selectedMonth)
    val months = remember(records) { records.map { YearMonth.from(Instant.ofEpochMilli(it.savedAtMillis).atZone(ZoneId.systemDefault())).toString() }.distinct().sortedDescending() }
    val motion = rememberMotionEnabled() && isActive
    var arrivingReaction by remember { mutableStateOf<ReactionNotice?>(null) }
    val reactionProgress = remember { Animatable(0f) }
    LaunchedEffect(isActive, revision, libraryRevision) {
        if (!isActive) return@LaunchedEffect
        loading = true
        error = false
        try {
            records = store.load().sortedByDescending { it.savedAtMillis }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        } finally { loading = false }
    }
    LaunchedEffect(isActive, selectedId, reactionNotices) {
        reactionProgress.snapTo(0f)
        arrivingReaction = if (isActive) selectedId?.let(consumeReactionAnimation) else null
        val notice = arrivingReaction ?: return@LaunchedEffect
        if (motion) reactionProgress.animateTo(1f, tween(notice.leadingKind.toDiaryReaction().durationMillis))
        else reactionProgress.snapTo(.7f)
    }
    LaunchedEffect(isActive, revision, libraryRevision) {
        if (!isActive) return@LaunchedEffect
        starReadFailed = false
        try { collection = stars.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { collection = null; starReadFailed = true }
    }
    // As a tab, Back only closes an open entry; otherwise it leaves the screen as usual.
    BackHandler(enabled = isActive && (tabHeader == null || selectedId != null || deleteCandidateId != null)) {
        if (deleting) return@BackHandler
        if (deleteCandidateId != null) deleteCandidateId = null
        else if (selectedId != null) selectedId = null else onBack()
    }
    // As a tab the shared sky stays visible behind the list.
    Box(Modifier.fillMaxSize().then(if (tabHeader == null) Modifier.background(StargleanColors.Ink) else Modifier)
        .pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
        val opened = visibleRecords.firstOrNull { it.diary.id == selectedId }
        if (opened != null) {
            DiaryWeatherBackground(opened.diary.weatherSnapshot, motion)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedId = null }) { Text("‹  My entries", color = StargleanColors.Gold) }
                    Spacer(Modifier.weight(1f))
                    Text("On this device · Local copy", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                }
                Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised.copy(alpha = .75f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(collection?.let { ownDiaryStarLabel(it, opened.diary.id) }
                        ?: if (starReadFailed) "Star placement could not be loaded" else "Checking its star…",
                        color = StargleanColors.Gold, style = MaterialTheme.typography.labelLarge)
                    if (starReadFailed) TextButton(onClick = { revision++ }) { Text("Retry star information", color = StargleanColors.Gold) }
                    if (collection != null && collection?.placementForDiary(opened.diary.id) == null) {
                        Text("This entry is safely saved. A place in your sky can come later.",
                            color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                        if (onPlaceDiary != null) TextButton(onClick = { onPlaceDiary(opened.diary.id) }) {
                            Text("Place in a constellation", color = StargleanColors.Gold)
                        }
                    }
                }
                MemoryCompassCard(opened.place, isActive)
                val reaction = arrivingReaction?.takeIf { it.localDiaryId == opened.diary.id }
                DiaryPaper(opened.diary, Modifier.fillMaxWidth(), showDemoBadge = false,
                    readingLayout = reaction != null,
                    minimumHeight = 350.dp,
                    placeLabel = if (opened.place == null) "No location saved" else "Writing location saved · Private",
                    reactionContent = reaction?.let { notice -> {
                        Box(Modifier.fillMaxWidth().height(132.dp), contentAlignment = Alignment.Center) {
                            DiaryPaperReaction(notice.leadingKind.toDiaryReaction(), reactionProgress.value,
                                Modifier.fillMaxWidth().height(116.dp), darkPaper = opened.diary.paper == com.starglean.app.core.model.PaperStyle.Midnight)
                            Text("${notice.unreadCount} new ${if (notice.unreadCount == 1) "reaction" else "reactions"}",
                                color = StargleanColors.PaperText.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomCenter))
                        }
                    } })
                Text("This is your on-device copy; manage shared copies in Cloud library. The Memory Compass suggests a direction, not a route.",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                deleteError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    enabled = !deleting,
                    onClick = { deleteError = null; deleteCandidateId = opened.diary.id },
                ) { Text("Delete entry", color = StargleanColors.MutedText) }
                Spacer(Modifier.height(12.dp))
            }
        } else Column(Modifier.fillMaxSize()) {
            if (tabHeader != null) Box(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 12.dp)) {
                tabHeader()
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
                item(key = "entry-controls") {
                    Column {
                        if (tabHeader == null) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = onBack) { Text("‹  Back to the stars", color = StargleanColors.Gold) }
                                Spacer(Modifier.weight(1f))
                                Text("Just for you", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                            }
                            Text(if (diaryIds == null) "Days to keep" else "Selected entries", color = StargleanColors.WarmWhite,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
                            Text("Your on-device copies. Manage sharing separately in Cloud library.", color = StargleanColors.MutedText,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                        }
                        listHeader?.invoke()
                        OutlinedTextField(search, { search = it.take(200) }, label = { Text("Search title or text") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                TextButton(onClick = { monthsOpen = true }) { Text(selectedMonth ?: "All dates") }
                                DropdownMenu(expanded = monthsOpen, onDismissRequest = { monthsOpen = false }) {
                                    DropdownMenuItem(text = { Text("All dates") }, onClick = { selectedMonth = null; monthsOpen = false })
                                    months.forEach { month ->
                                        DropdownMenuItem(text = { Text(month) }, onClick = { selectedMonth = month; monthsOpen = false })
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("${visibleRecords.size} ${if (visibleRecords.size == 1) "entry" else "entries"}",
                                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
                            if (search.isNotBlank() || selectedMonth != null) TextButton(onClick = { search = ""; selectedMonth = null }) {
                                Text("Clear")
                            }
                        }
                    }
                }
                when {
                    loading -> item { Text("Opening your memories…", color = StargleanColors.MutedText) }
                    error -> item {
                        Text("Could not load your entries. The original file is unchanged. Please retry.", color = StargleanColors.MutedText)
                        TextButton(onClick = { revision++ }) { Text("Retry", color = StargleanColors.Gold) }
                    }
                    selectedId != null -> item {
                        Text("This entry is not available in this selection. No sample has been substituted.", color = StargleanColors.MutedText)
                        TextButton(onClick = { selectedId = null }) { Text("Back to entries", color = StargleanColors.Gold) }
                    }
                    records.isEmpty() -> item {
                        Text("No entries saved yet", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleLarge)
                        Text("Open Write, preview your entry, then choose Save privately or Publish publicly.\nEach saved entry keeps its location, so a star can point back to that day.",
                            color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                    visibleRecords.isEmpty() -> item { Text("No entries match this selection.", color = StargleanColors.MutedText) }
                    else -> {
                        items(visibleRecords, key = { it.diary.id }) { record ->
                            val notice = reactionNotices[record.diary.id]
                            Column(Modifier.fillMaxWidth()
                                .background(StargleanColors.InkRaised, RoundedCornerShape(20.dp))
                                .border(if (notice != null) 1.2.dp else .7.dp,
                                    StargleanColors.Gold.copy(alpha = if (notice != null) .62f else .18f), RoundedCornerShape(20.dp))
                                .semantics { contentDescription = "Open your entry, ${record.diary.title ?: record.diary.date}" }
                                .clickable(role = Role.Button) { selectedId = record.diary.id }.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text(record.diary.date, color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                                Text(record.diary.title?.takeIf { it.isNotBlank() } ?: "An untitled day", color = StargleanColors.WarmWhite,
                                    style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(record.diary.body, color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(collection?.let { ownDiaryStarLabel(it, record.diary.id) }
                                    ?: if (starReadFailed) "Star placement unavailable" else "Checking its star…",
                                    color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                                Text(if (record.place == null) "No location saved  ›" else "A star remembers the way  ›",
                                    color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium)
                                if (notice != null) Text("${notice.unreadCount} new ${if (notice.unreadCount == 1) "reaction" else "reactions"}  ·  Open to see",
                                    color = StargleanColors.GoldBright, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    val candidate = records.firstOrNull { it.diary.id == deleteCandidateId }
    if (candidate != null) AlertDialog(
        onDismissRequest = { if (!deleting) deleteCandidateId = null },
        containerColor = StargleanColors.InkRaised,
        title = { Text("Delete this entry?", color = StargleanColors.WarmWhite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ownDiaryDeleteMessage(collection, candidate.diary.id), color = StargleanColors.MutedText)
                deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !deleting, onClick = {
                if (deleting) return@TextButton
                deleting = true
                deleteError = null
                scope.launch {
                    try {
                        val result = deletionStore.deleteDiary(candidate.diary.id)
                        if (result.deleted) {
                            records = records.filterNot { it.diary.id == candidate.diary.id }
                            collection = collection?.let { StarCollectionRules.removeDiary(it, candidate.diary.id).state }
                            selectedId = null
                            deleteCandidateId = null
                            onLibraryChanged?.invoke()
                        } else {
                            deleteError = "This entry was already unavailable. Your other entries were not changed."
                            revision++
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        deleteError = failure.message ?: "The entry could not be deleted. Your existing files were preserved."
                    } finally {
                        deleting = false
                    }
                }
            }) { Text(if (deleting) "Deleting…" else "Delete permanently", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = { deleteCandidateId = null; deleteError = null }) {
                Text("Keep entry", color = StargleanColors.Gold)
            }
        },
    )
}

private fun ReactionKind.toDiaryReaction(): DiaryReaction = when (this) {
    ReactionKind.Hug -> DiaryReaction.Hug
    ReactionKind.Understand -> DiaryReaction.Understand
    ReactionKind.Thanks -> DiaryReaction.Thanks
    ReactionKind.Joy -> DiaryReaction.Joy
}
