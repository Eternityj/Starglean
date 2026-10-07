package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GallerySamples
import com.starglean.app.core.stars.ConstellationStore
import com.starglean.app.core.stars.StarCollectionState
import com.starglean.app.core.online.ReactionNotice
import com.starglean.app.core.online.ReactionNoticeState
import com.starglean.app.ui.components.DiscoverStarfield
import com.starglean.app.ui.components.GalleryConstellationScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class CollectionPanel { Menu, Browse, Home, Place, Add, About, Devices }
private enum class CollectionTab { Sky, Entries }

/** Real local entries only. The illustrated atlas is a template, never a source of lit stars. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalConstellationsScreen(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    reactionNotices: ReactionNoticeState = ReactionNoticeState(),
    consumeReactionAnimation: (String) -> ReactionNotice? = { null },
) {
    val context = LocalContext.current
    val store = remember(context) { ConstellationStore(context) }
    val diaries = remember(context) { OwnDiaryStore(context) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    var collection by remember { mutableStateOf<StarCollectionState?>(null) }
    var records by remember { mutableStateOf<List<OwnDiaryRecord>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<CollectionPanel?>(null) }
    var homeDraft by remember { mutableStateOf<Set<String>>(emptySet()) }
    var placementTarget by remember { mutableStateOf<String?>(null) }
    var chosenDiary by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var onlinePanel by remember { mutableStateOf<String?>(null) }
    var readingId by remember { mutableStateOf<String?>(null) }
    var readingFilter by remember { mutableStateOf<Set<String>?>(null) }
    var apparition by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableStateOf(CollectionTab.Sky) }
    // The pager follows the current constellation when it changes, e.g. after a new entry lights it.
    var followedTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var lastLit by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val items = collection?.galleryItems().orEmpty()
    val pager = rememberPagerState(pageCount = { items.size })
    val focused = items.getOrNull(pager.settledPage)
    val unplaced = records.filter { collection?.placementForDiary(it.diary.id) == null }
    val overlay = panel != null || reading || onlinePanel != null
    val motion = rememberMotionEnabled() && isActive && !overlay
    val unreadByDiary = reactionNotices.notices.filter { it.localDiaryId != null }.associateBy { requireNotNull(it.localDiaryId) }
    val unreadPlacements = collection?.placements.orEmpty().filter { it.diaryId in unreadByDiary }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val libraryRevision by com.starglean.app.core.library.LibraryStorageCoordinator.revisionFlow.collectAsState()
    LaunchedEffect(isActive, revision, libraryRevision) {
        if (!isActive) return@LaunchedEffect
        loading = true
        error = null
        try {
            val loaded = store.load()
            val loadedDiaries = diaries.load()
            collection = loaded
            records = loadedDiaries
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Your collection could not be opened. Existing entries are unchanged." }
        finally { loading = false }
    }
    LaunchedEffect(collection?.targetId, items.size, tab) {
        val target = collection?.targetId ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.id == target }
        if (tab == CollectionTab.Sky && target != followedTarget && index >= 0) {
            pager.scrollToPage(index)
            followedTarget = target
        }
    }
    LaunchedEffect(notice) { if (notice != null) { delay(5_500); notice = null } }
    LaunchedEffect(lastLit) { if (lastLit != null) { delay(3_400); lastLit = null } }

    fun update(action: suspend () -> StarCollectionState, success: String, after: () -> Unit = {}) {
        if (busy || loading || error != null) return
        busy = true
        scope.launch {
            try {
                collection = action()
                notice = success
                after()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "That change could not be confirmed. Your entries are safe. Please retry." }
            finally { busy = false }
        }
    }
    fun openEntries(id: String? = null, filter: Set<String>? = null) {
        panel = null
        readingId = id
        readingFilter = filter
        reading = true
    }
    fun openPlacement(instanceId: String?, diaryId: String? = null) {
        tab = CollectionTab.Sky // Placing scrolls the sky pager, which only exists on this tab.
        placementTarget = instanceId
        chosenDiary = diaryId
        panel = CollectionPanel.Place
    }
    fun place(diaryId: String, instanceId: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = store.placeDiary(diaryId, instanceId)
                collection = result.state
                val placed = result.placement
                if (placed != null) {
                    lastLit = if (result.newlyPlaced) placed.instanceId to placed.starIndex else null
                    panel = null
                    chosenDiary = null
                    val destination = result.state.galleryItems().indexOfFirst { it.id == placed.instanceId }
                    if (destination >= 0) pager.animateScrollToPage(destination)
                    notice = if (result.newlyPlaced && result.completed) "Constellation complete. You can now add it to your home sky."
                        else if (result.newlyPlaced) "A memory has found its star."
                        else "This entry already has a star. It has not moved."
                } else notice = "This constellation is full. Your entry is still waiting safely in Unplaced stars."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "Your entry is saved. Its star could not be placed yet; please retry." }
            finally { busy = false }
        }
    }
    BackHandler(enabled = isActive && overlay && !busy) {
        if (onlinePanel != null) onlinePanel = null else if (reading) reading = false else panel = null
    }

    if (onlinePanel == "account") {
        OnlineAccountScreen(onBack = { onlinePanel = null })
        return
    }
    if (onlinePanel == "cloud" || onlinePanel == "inbox") {
        CloudLibraryScreen(isActive = isActive, onAccount = { onlinePanel = "account" },
            onBack = { onlinePanel = null }, initialTab = if (onlinePanel == "inbox") "inbox" else "entries")
        return
    }
    if (onlinePanel == "privacy") {
        PrivacyDetailsScreen(onBack = { onlinePanel = null })
        return
    }
    if (onlinePanel == "sync") {
        ConstellationCloudScreen(isActive = isActive, onAccount = { onlinePanel = "account" }, onBack = { onlinePanel = null })
        return
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 620.dp || LocalDensity.current.fontScale > 1.2f
        DiscoverStarfield(motionEnabled = motion, subdued = true)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color(0x44050A15), .55f to Color.Transparent, 1f to Color(0xCC040812))))
        if (tab == CollectionTab.Entries) OwnDiariesSheet(isActive = isActive && !reading && onlinePanel == null,
            reactionNotices = unreadByDiary, consumeReactionAnimation = consumeReactionAnimation,
            onPlaceDiary = { diaryId -> openPlacement(null, diaryId) },
            onLibraryChanged = { revision++ },
            tabHeader = {
                CollectionHeader(tab, reactionNotices.hasUnread, compact, onTab = { tab = it })
            },
            listHeader = {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryShortcut("Kindness", if (reactionNotices.hasUnread) "${reactionNotices.unreadCount} new" else "Reactions",
                        reactionNotices.hasUnread, Modifier.weight(1f)) { onlinePanel = "inbox" }
                    EntryShortcut("Unplaced", "${unplaced.size} waiting", false, Modifier.weight(1f)) {
                        if (unplaced.isEmpty()) notice = "Every saved entry already has a star."
                        else openEntries(filter = unplaced.map { it.diary.id }.toSet())
                    }
                    EntryShortcut("Cloud", "Saved copies", false, Modifier.weight(1f)) { onlinePanel = "cloud" }
                }
            }, onBack = {})
        else Column(Modifier.fillMaxSize().then(if (overlay) Modifier.clearAndSetSemantics {} else Modifier)
            .padding(top = 12.dp, bottom = 6.dp)) {
            CollectionHeader(tab, reactionNotices.hasUnread, compact, Modifier.padding(start = 22.dp, end = 10.dp),
                onTab = { tab = it }, menuEnabled = !loading && !busy && error == null,
                onMenu = { panel = CollectionPanel.Menu })
            when {
                error != null -> Column(Modifier.fillMaxWidth().weight(1f).padding(28.dp), verticalArrangement = Arrangement.Center) {
                    Text(error.orEmpty(), color = StargleanColors.MutedText)
                    TextButton(onClick = { revision++ }) { Text("Retry", color = StargleanColors.Gold) }
                }
                items.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Opening your sky…", color = StargleanColors.MutedText)
                }
                else -> {
                    HorizontalPager(pager, key = { items[it].id }, beyondViewportPageCount = 1,
                        userScrollEnabled = !overlay && !busy, modifier = Modifier.fillMaxWidth().weight(1f)) { page ->
                        val item = items[page]
                        val offset = pager.currentPage - page + pager.currentPageOffsetFraction
                        Column(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - abs(offset) * .6f).coerceIn(.15f, 1f) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                GalleryConstellationScene(item, motion && page == pager.settledPage && !pager.isScrollInProgress,
                                    Modifier.fillMaxSize(), apparitionVisible = apparition, pageOffset = offset,
                                    selectedStar = lastLit?.takeIf { it.first == item.id }?.second
                                        ?: unreadPlacements.firstOrNull { it.instanceId == item.id }?.starIndex,
                                    lightingStar = lastLit?.takeIf { it.first == item.id }?.second,
                                    onStarSelected = { index ->
                                        if (!overlay && !busy && !pager.isScrollInProgress) {
                                            val diary = collection?.diaryAt(item.id, index)
                                            if (diary != null) openEntries(diary) else openPlacement(item.id)
                                        }
                                    })
                            }
                            Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(galleryFigureSubtitle(item.figure).uppercase(java.util.Locale.ENGLISH) +
                                    if (item.ordinal > 1) "  ·  ${item.ordinal}" else "",
                                    color = StargleanColors.Gold.copy(alpha = .7f), fontSize = 10.sp, letterSpacing = 2.sp,
                                    textAlign = TextAlign.Center, maxLines = 2)
                                Text(item.name, color = StargleanColors.WarmWhite, fontFamily = FontFamily.Serif,
                                    fontSize = if (compact) 32.sp else 38.sp, lineHeight = 44.sp,
                                    modifier = Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${item.litStars} of ${item.totalStars} stars lit" +
                                    if (item.id in collection!!.homeIds) " · In your home sky" else if (item.completed) " · Complete" else "",
                                    color = StargleanColors.MutedText, fontSize = 12.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 7.dp, bottom = if (compact) 12.dp else 20.dp))
                                val current = collection?.targetId == item.id
                                val actionLabel = when {
                                    item.completed -> "Read these memories"
                                    current && unplaced.isNotEmpty() -> "Place a waiting star"
                                    current -> "Your current constellation"
                                    else -> "Collect this constellation"
                                }
                                OutlinedButton(onClick = {
                                    when {
                                        item.completed -> openEntries(filter = collection!!.placements.filter { it.instanceId == item.id }.map { it.diaryId }.toSet())
                                        current && unplaced.isNotEmpty() -> openPlacement(item.id)
                                        current -> notice = "Your next saved entry will light the next available star in ${item.name}."
                                        else -> update({ store.setTarget(item.id) }, "New entries will light ${item.name}. Existing stars stay where they are.")
                                    }
                                }, enabled = !busy && !loading, shape = RoundedCornerShape(28.dp),
                                    border = androidx.compose.foundation.BorderStroke(.8.dp, StargleanColors.Gold.copy(alpha = if (current) .28f else .55f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StargleanColors.GoldBright),
                                    modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 320.dp).fillMaxWidth()) {
                                    Text(if (busy) "Saving…" else actionLabel, fontSize = 13.sp, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage((pager.settledPage - 1).coerceAtLeast(0)) } },
                            enabled = pager.settledPage > 0 && !busy, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                            Text("‹", color = StargleanColors.Gold.copy(alpha = if (pager.settledPage > 0) .8f else .2f), fontSize = 25.sp)
                        }
                        Text("${(pager.settledPage + 1).toString().padStart(2, '0')}  /  ${items.size.toString().padStart(2, '0')}",
                            color = StargleanColors.MutedText, fontSize = 10.sp, letterSpacing = 2.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clickable(role = Role.Button) { panel = CollectionPanel.Browse }.padding(14.dp))
                        TextButton(onClick = { scope.launch { pager.animateScrollToPage((pager.settledPage + 1).coerceAtMost(items.lastIndex)) } },
                            enabled = pager.settledPage < items.lastIndex && !busy, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                            Text("›", color = StargleanColors.Gold.copy(alpha = if (pager.settledPage < items.lastIndex) .8f else .2f), fontSize = 25.sp)
                        }
                    }
                }
            }
        }
        if (reading) OwnDiariesSheet(isActive = isActive, initialDiaryId = readingId, diaryIds = readingFilter,
            reactionNotices = unreadByDiary, consumeReactionAnimation = consumeReactionAnimation,
            onPlaceDiary = { diaryId -> reading = false; openPlacement(null, diaryId) },
            onBack = { reading = false; readingId = null; readingFilter = null; revision++ })
        AnimatedVisibility(notice != null && panel == null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(notice.orEmpty(), color = StargleanColors.WarmWhite, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.background(StargleanColors.InkRaised, RoundedCornerShape(16.dp)).padding(16.dp))
        }
    }

    panel?.let { activePanel ->
        ModalBottomSheet(onDismissRequest = { if (!busy) panel = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = StargleanColors.InkRaised, contentColor = StargleanColors.WarmWhite) {
            val title = when (activePanel) {
                CollectionPanel.Menu -> "Your sky"
                CollectionPanel.Browse -> "All constellations"
                CollectionPanel.Home -> "Arrange your home sky"
                CollectionPanel.Place -> if (chosenDiary != null || placementTarget == null) "Choose a constellation" else "Place a waiting star"
                CollectionPanel.Add -> "Begin another constellation"
                CollectionPanel.About -> "About your sky"
                CollectionPanel.Devices -> "Device & activity"
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif,
                    modifier = Modifier.padding(bottom = 16.dp).semantics { heading() })
                when (activePanel) {
                    CollectionPanel.Menu -> {
                        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                            // Entries, kindness and cloud copies live in the Entries tab; settings on the home screen.
                            CollectionMenuRow("All constellations", "${items.size} in your collection") { panel = CollectionPanel.Browse }
                            CollectionMenuRow("Arrange home", "${collection?.homeIds?.size ?: 0} of 8 places used") {
                                homeDraft = collection?.homeIds.orEmpty(); panel = CollectionPanel.Home
                            }
                            CollectionMenuRow("Begin another", "Up to five of each constellation") { panel = CollectionPanel.Add }
                            CollectionMenuRow(if (apparition) "Hide illustrations" else "Show illustrations", null) { apparition = !apparition; panel = null }
                            CollectionMenuRow("About", null) { panel = CollectionPanel.About }
                        }
                    }
                    CollectionPanel.Browse -> LazyColumn(Modifier.heightIn(max = 480.dp)) {
                        items(items, key = { it.id }) { item ->
                            CollectionMenuRow(instanceName(item), "${item.litStars} / ${item.totalStars}" + if (item.id == collection?.targetId) " · Current" else "") {
                                panel = null; scope.launch { pager.animateScrollToPage(items.indexOf(item)) }
                            }
                        }
                    }
                    CollectionPanel.Home -> {
                        Text("Choose up to 8 completed constellations. Nothing moves until you confirm.",
                            color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium)
                        val completed = items.filter { it.completed }
                        if (completed.isEmpty()) Text("Your first complete constellation will appear here.", color = StargleanColors.Gold,
                            modifier = Modifier.padding(vertical = 24.dp))
                        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 360.dp)) {
                            items(completed, key = { it.id }) { item ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(
                                    value = item.id in homeDraft, enabled = !busy, role = Role.Checkbox,
                                    onValueChange = { selected ->
                                        homeDraft = if (!selected) homeDraft - item.id else if (homeDraft.size < 8) homeDraft + item.id else homeDraft
                                    },
                                ), verticalAlignment = Alignment.CenterVertically) {
                                    Text(instanceName(item), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    Checkbox(checked = item.id in homeDraft, onCheckedChange = null,
                                        colors = CheckboxDefaults.colors(checkedColor = StargleanColors.Gold, checkmarkColor = StargleanColors.Ink))
                                }
                            }
                        }
                        Button(onClick = { update({ store.setHome(homeDraft) }, "Your home sky has been updated.") { panel = null } }, enabled = !busy,
                            colors = ButtonDefaults.buttonColors(containerColor = StargleanColors.Gold, contentColor = StargleanColors.Ink),
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Confirm · ${homeDraft.size} / 8") }
                    }
                    CollectionPanel.Place -> {
                        val target = items.firstOrNull { it.id == placementTarget }
                        if (chosenDiary != null || target == null) {
                            Text("An entry lights the next available star. Existing memories never move.", color = StargleanColors.MutedText,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(items.filter { !it.completed }, key = { it.id }) { item ->
                                    CollectionMenuRow(instanceName(item), "${item.totalStars - item.litStars} stars waiting", enabled = !busy) {
                                        val diary = chosenDiary
                                        if (diary != null) place(diary, item.id) else placementTarget = item.id
                                    }
                                }
                            }
                        } else if (unplaced.isEmpty()) {
                            Text("No entries are waiting. Choose ${target.name} as your target, then write and save a new memory.", color = StargleanColors.MutedText)
                            TextButton(onClick = { update({ store.setTarget(target.id) }, "Your next entry will light ${target.name}.") { panel = null } }, enabled = !busy) {
                                Text("Set as current constellation", color = StargleanColors.Gold)
                            }
                        } else {
                            Text("${instanceName(target)} · Next available star", color = StargleanColors.MutedText, modifier = Modifier.padding(bottom = 12.dp))
                            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                                items(unplaced, key = { it.diary.id }) { record ->
                                    CollectionMenuRow(record.diary.title?.takeIf { it.isNotBlank() } ?: "An untitled day", record.diary.date, enabled = !busy) {
                                        place(record.diary.id, target.id)
                                    }
                                }
                            }
                        }
                    }
                    CollectionPanel.Add -> {
                        Text("Each new constellation has its own stars. Your existing entries stay in place.", color = StargleanColors.MutedText,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                        LazyColumn(Modifier.heightIn(max = 420.dp)) {
                            items(GallerySamples.items, key = { it.id }) { template ->
                                val count = items.count { it.latinName == template.latinName }
                                CollectionMenuRow(template.name, "$count / 5 in your collection", enabled = !busy && count < 5) {
                                    update({ store.createInstance(template.id) }, "A new ${template.name} is waiting for your memories.") {
                                        panel = null
                                        scope.launch { pager.animateScrollToPage((collection?.galleryItems()?.size ?: 1) - 1) }
                                    }
                                }
                            }
                        }
                    }
                    CollectionPanel.About -> Text(
                        "One saved entry, one star. Set a current constellation for new entries, or place waiting memories whenever you like. Only you choose which completed constellations appear at home.\n\nOlder local memories are never uploaded automatically. Cloud saving, restoration and sharing are separate choices. Uninstalling or clearing data can erase this device's local-only collection.\n\nThe eight star patterns and illustrations are artistic simplifications, not precise astronomical maps.",
                        color = StargleanColors.MutedText, modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()))
                    CollectionPanel.Devices -> DeviceActivityPanel(isActive = isActive)
                }
                if (notice != null) Text(notice.orEmpty(), color = StargleanColors.Gold, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@Composable
private fun CollectionHeader(
    tab: CollectionTab,
    hasUnread: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onTab: (CollectionTab) -> Unit,
    menuEnabled: Boolean = false,
    onMenu: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("My constellations", color = StargleanColors.WarmWhite, fontFamily = FontFamily.Serif,
                fontSize = if (compact) 24.sp else 27.sp, lineHeight = 32.sp,
                modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 2)
            if (onMenu != null) TextButton(onClick = onMenu, enabled = menuEnabled,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "Collection options" },
                contentPadding = PaddingValues(0.dp)) {
                Canvas(Modifier.size(22.dp)) {
                    repeat(3) { drawCircle(StargleanColors.Gold, 1.7.dp.toPx(), Offset(size.width * (.18f + it * .32f), size.height / 2)) }
                }
            } else Spacer(Modifier.size(48.dp))
        }
        Row(Modifier.padding(top = 10.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CollectionTabPill("Sky", tab == CollectionTab.Sky, dot = false) { onTab(CollectionTab.Sky) }
            CollectionTabPill("Entries", tab == CollectionTab.Entries, dot = hasUnread) { onTab(CollectionTab.Entries) }
        }
    }
}

@Composable
private fun CollectionTabPill(label: String, selected: Boolean, dot: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.heightIn(min = 40.dp).clip(shape)
        .background(if (selected) StargleanColors.Gold.copy(alpha = .16f) else Color.Transparent)
        .border(.8.dp, StargleanColors.Gold.copy(alpha = if (selected) .7f else .22f), shape)
        .selectable(selected, role = Role.Tab, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = if (selected) StargleanColors.GoldBright else StargleanColors.WarmWhite.copy(alpha = .75f),
            style = MaterialTheme.typography.labelLarge)
        if (dot) Box(Modifier.size(6.dp).background(StargleanColors.GoldBright, CircleShape)
            .semantics { contentDescription = "New reactions" })
    }
}

@Composable
private fun EntryShortcut(title: String, detail: String, highlighted: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.heightIn(min = 60.dp).clip(shape).background(StargleanColors.InkRaised.copy(alpha = .92f))
        .border(if (highlighted) 1.2.dp else .7.dp, StargleanColors.Gold.copy(alpha = if (highlighted) .65f else .18f), shape)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(detail, color = if (highlighted) StargleanColors.GoldBright else StargleanColors.MutedText,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun instanceName(item: GalleryConstellation): String = item.name + if (item.ordinal > 1) " · ${item.ordinal}" else ""

@Composable
private fun CollectionMenuRow(title: String, detail: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = StargleanColors.WarmWhite.copy(alpha = if (enabled) 1f else .35f),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, color = StargleanColors.MutedText.copy(alpha = if (enabled) 1f else .35f), style = MaterialTheme.typography.bodySmall)
        }
        Text("›", color = StargleanColors.Gold.copy(alpha = if (enabled) .7f else .25f), fontSize = 22.sp, modifier = Modifier.padding(start = 12.dp))
    }
}
