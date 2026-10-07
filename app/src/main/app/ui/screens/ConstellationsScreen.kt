package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starglean.app.BuildConfig
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.model.*
import com.starglean.app.ui.components.DemoBadge
import com.starglean.app.ui.components.DiaryPaper
import com.starglean.app.ui.components.DiaryWeatherBackground
import com.starglean.app.ui.components.DiscoverStarfield
import com.starglean.app.ui.components.GalleryConstellationScene
import com.starglean.app.ui.components.MemoryCompassCard
import com.starglean.app.core.online.ReactionNotice
import com.starglean.app.core.online.ReactionNoticeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private data class GalleryDiary(val owner: String, val index: Int, val diary: DiaryDemo)

internal fun galleryFigureSubtitle(figure: GalleryFigure?): String = when (figure) {
    GalleryFigure.Cygnus -> "The Swan"
    GalleryFigure.Orion -> "The Hunter"
    GalleryFigure.Lyra -> "The Lyre"
    GalleryFigure.Cassiopeia -> "The Queen"
    GalleryFigure.Leo -> "The Lion"
    GalleryFigure.Scorpius -> "The Scorpion"
    GalleryFigure.Pegasus -> "The Winged Horse"
    GalleryFigure.Delphinus -> "The Dolphin"
    null -> "Among the Stars"
}

internal fun galleryFigureReflection(figure: GalleryFigure?): String = when (figure) {
    GalleryFigure.Cygnus -> "A little of you lives in every light."
    GalleryFigure.Orion -> "Even in a long night, your light remains."
    GalleryFigure.Lyra -> "Let ordinary days become a quiet song."
    GalleryFigure.Cassiopeia -> "Rest awhile. Let the stars say goodnight."
    GalleryFigure.Leo -> "Gentleness and courage can shine together."
    GalleryFigure.Scorpius -> "Even your unspoken thoughts hold light."
    GalleryFigure.Pegasus -> "Leave your unfinished dreams with the stars."
    GalleryFigure.Delphinus -> "A little lightness in an ordinary day."
    null -> "Every small light is worth keeping."
}

/** Illustrated gallery. Every count, diary and layout choice here is explicitly local. */
@Composable
fun ConstellationsScreen(
    modifier: Modifier = Modifier,
    demoAutoLight: Boolean = false,
    isActive: Boolean = true,
    demoTour: Boolean = false,
    demoPanel: String? = null,
    demoStar: Int? = null,
    demoPage: Int = 0,
    demoMemoryCompass: Boolean = false,
    reactionNotices: ReactionNoticeState = ReactionNoticeState(),
    consumeReactionAnimation: (String) -> ReactionNotice? = { null },
) {
    if (BuildConfig.LOCAL_DEVICE_EDITION) {
        LocalConstellationsScreen(modifier, isActive, reactionNotices, consumeReactionAnimation)
        return
    }
    val items = GallerySamples.items
    val pager = rememberPagerState(initialPage = if (BuildConfig.DEBUG) demoPage.coerceIn(items.indices) else 0,
        pageCount = { items.size })
    val scope = rememberCoroutineScope()
    var targetId by rememberSaveable { mutableStateOf<String?>(if (BuildConfig.LOCAL_DEVICE_EDITION) null else GallerySamples.initialTargetId) }
    var homeIds by rememberSaveable { mutableStateOf(if (BuildConfig.LOCAL_DEVICE_EDITION) emptyList() else GallerySamples.initialHomeIds.toList()) }
    var home by remember { mutableStateOf(GalleryHomeSelection(homeIds.toSet())) }
    var panel by remember { mutableStateOf<GalleryPanel?>(null) }
    var apparitionVisible by rememberSaveable { mutableStateOf(true) }
    var reading by remember { mutableStateOf<GalleryDiary?>(null) }
    var openStar by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var aboutSample by remember { mutableStateOf(false) }
    var ownDiariesOpen by remember { mutableStateOf(false) }
    val motionEnabled = rememberMotionEnabled()
    val focused = items[pager.settledPage.coerceIn(items.indices)]
    val currentTarget = items.firstOrNull { it.id == targetId }
    val overlayOpen = panel != null || reading != null || openStar != null || aboutSample || ownDiariesOpen

    fun closePanel() {
        home = home.cancel()
        panel = null
    }
    fun openPanel(next: GalleryPanel) {
        if (BuildConfig.LOCAL_DEVICE_EDITION && next != GalleryPanel.Overview) {
            notice = "Star placement, home layouts and online responses are not connected in this local edition."
            return
        }
        if (next == GalleryPanel.Home) home = GalleryHomeSelection(homeIds.toSet()).begin()
        panel = next
    }
    fun openDiary(owner: String, star: Int) {
        if (BuildConfig.LOCAL_DEVICE_EDITION) {
            ownDiariesOpen = true
            return
        }
        val item = items.firstOrNull { it.id == owner }
        if (item != null && (star !in item.points.indices || star !in item.litStarIndices)) {
            if (star in item.points.indices) openStar = owner to star
            return
        }
        if (item == null && owner != "unplaced") return
        val sample = DemoContent.discoveryDiaries[star.mod(DemoContent.discoveryDiaries.size)]
        val name = item?.name ?: "Unplaced star"
        reading = GalleryDiary(name, star, sample.copy(
            id = item?.diaryIdForStar(star) ?: "demo:unplaced:diary",
            date = "${10 + star.mod(5)} Sep · A memory",
            blurredPlace = "Sample place · Not a real location",
            font = DiaryFont.Serif,
        ))
    }

    BackHandler(enabled = overlayOpen) {
        when {
            ownDiariesOpen -> ownDiariesOpen = false
            reading != null -> reading = null
            openStar != null -> openStar = null
            aboutSample -> aboutSample = false
            else -> closePanel()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) { delay(3_200); notice = null }
    }
    // Debug-only, finite visual rehearsal. Normal browsing never follows a timer.
    LaunchedEffect(demoTour, demoPanel, demoStar, demoPage, demoMemoryCompass, isActive) {
        if (!BuildConfig.DEBUG || !isActive) return@LaunchedEffect
        if (demoPanel != null) {
            when (demoPanel) {
                "overview" -> openPanel(GalleryPanel.Overview)
                "home" -> openPanel(GalleryPanel.Home)
                "unplaced" -> openPanel(GalleryPanel.Unplaced)
                "responses" -> openPanel(GalleryPanel.Responses)
                "own_diaries" -> ownDiariesOpen = true
            }
        }
        val demoItem = items[if (BuildConfig.DEBUG) demoPage.coerceIn(items.indices) else 0]
        demoStar?.takeIf { it in demoItem.points.indices }?.let {
            delay(700)
            openDiary(demoItem.id, it)
        }
        if (demoMemoryCompass) openDiary(demoItem.id, demoItem.litStarIndices.minOrNull() ?: 0)
        if (demoAutoLight) notice = "Gallery preview only. No real entry was saved or star lit."
        if (demoTour) {
            delay(4_200)
            apparitionVisible = false
            delay(1_600)
            apparitionVisible = true
            delay(2_800)
            val tourStart = demoPage.coerceIn(items.indices)
            for (page in (tourStart + 1)..items.lastIndex) {
                pager.animateScrollToPage(page, animationSpec = tween(1_000))
                delay(9_600)
            }
            pager.animateScrollToPage(tourStart, animationSpec = tween(1_200))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 680.dp || LocalDensity.current.fontScale > 1.15f
        val breathingSky = motionEnabled && isActive && !overlayOpen
        DiscoverStarfield(motionEnabled = breathingSky, subdued = true)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color(0x66030812), .28f to Color.Transparent, .78f to Color(0x33040812), 1f to Color(0xCC040812),
        )))
        Column(Modifier.fillMaxSize()
            .then(if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier)
            .padding(top = if (compact) 10.dp else 22.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("My constellations", color = StargleanColors.WarmWhite,
                        style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (compact) 24.sp else 27.sp, letterSpacing = .4.sp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (BuildConfig.LOCAL_DEVICE_EDITION) "Eight constellations · Entries stay on this device" else "Eight sample skies · Your entries stay separate", color = StargleanColors.MutedText,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable(role = Role.Button) { aboutSample = true }.padding(vertical = 4.dp))
                }
                Box(Modifier.size(48.dp).semantics { contentDescription = "Browse all constellations"; role = Role.Button }
                    .clickable { openPanel(GalleryPanel.Overview) }, contentAlignment = Alignment.Center) {
                    GallerySymbol(GalleryMark.Grid, Modifier.size(23.dp), StargleanColors.Gold)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = if (compact) 8.dp else 15.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                if (BuildConfig.LOCAL_DEVICE_EDITION) {
                    GalleryShortcut("My entries", null, GalleryMark.Star, Modifier.weight(1f)) { ownDiariesOpen = true }
                    GalleryShortcut("All stars", null, GalleryMark.Grid, Modifier.weight(1f)) { openPanel(GalleryPanel.Overview) }
                    GalleryShortcut("About", null, GalleryMark.Orbit, Modifier.weight(1f)) { aboutSample = true }
                } else {
                    GalleryShortcut("Unplaced", "1", GalleryMark.Star, Modifier.weight(1f)) { openPanel(GalleryPanel.Unplaced) }
                    GalleryShortcut("Responses", "2", GalleryMark.Heart, Modifier.weight(1f)) { openPanel(GalleryPanel.Responses) }
                    GalleryShortcut("Arrange", null, GalleryMark.Orbit, Modifier.weight(1f)) { openPanel(GalleryPanel.Home) }
                }
            }

            HorizontalPager(state = pager, key = { items[it].id }, modifier = Modifier.fillMaxWidth().weight(1f),
                userScrollEnabled = !overlayOpen, beyondViewportPageCount = 1) { page ->
                val item = items[page]
                val pageOffset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val pageAlpha = (1f - abs(pageOffset) * .58f).coerceIn(.18f, 1f)
                Column(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        GalleryConstellationScene(
                            item = item, motionEnabled = breathingSky && page == pager.settledPage && !pager.isScrollInProgress,
                            modifier = Modifier.fillMaxSize(), apparitionVisible = apparitionVisible,
                            selectedStar = if (reading?.owner == item.name) reading?.index else null,
                            pageOffset = pageOffset,
                            onStarSelected = { if (!overlayOpen && !pager.isScrollInProgress) openDiary(item.id, it) },
                        )
                        if (item.hasIllustration) Row(
                            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 4.dp).heightIn(min = 44.dp)
                                .semantics {
                                    contentDescription = if (apparitionVisible) "Hide ${item.name} illustration" else "Show ${item.name} illustration"
                                    role = Role.Switch
                                    stateDescription = if (apparitionVisible) "On" else "Off"
                                }
                                .clickable { apparitionVisible = !apparitionVisible }.padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(Modifier.size(4.dp).background(if (apparitionVisible) StargleanColors.Gold else StargleanColors.MutedText, CircleShape))
                            Text(if (apparitionVisible) "Illustration on" else "Stars only", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp)) {
                        Text(if (BuildConfig.LOCAL_DEVICE_EDITION) galleryFigureSubtitle(item.figure).uppercase(java.util.Locale.ROOT)
                            else "${galleryFigureSubtitle(item.figure).uppercase(java.util.Locale.ROOT)}  /  NO. ${item.ordinal}",
                            color = StargleanColors.Gold.copy(alpha = .78f), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(item.name, color = StargleanColors.WarmWhite,
                            style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (compact) 28.sp else 33.sp, lineHeight = 40.sp, letterSpacing = 1.2.sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (BuildConfig.LOCAL_DEVICE_EDITION) "Constellation atlas · Entries not linked to stars yet"
                            else "${item.litStars} / ${item.totalStars} stars lit  ·  ${if (item.completed) "Complete" else "Collecting"}",
                            color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(galleryFigureReflection(item.figure),
                            color = StargleanColors.WarmWhite.copy(alpha = .68f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = if (compact) 0.dp else 5.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = {
                                if (BuildConfig.LOCAL_DEVICE_EDITION) ownDiariesOpen = true
                                else if (item.completed) openDiary(item.id, item.litStarIndices.minOrNull() ?: 0)
                                else {
                                    targetId = GallerySelectionPolicy.selectTarget(targetId, item)
                                    notice = "${item.name} is now the sample target. Existing stars have not moved."
                                }
                            }) {
                                Text(if (BuildConfig.LOCAL_DEVICE_EDITION) "My entries  ↗"
                                    else if (item.completed) "Read star entries  ↗" else if (targetId == item.id) "Current target" else "Set as target",
                                    color = StargleanColors.GoldBright, style = MaterialTheme.typography.labelLarge)
                            }
                            if (!BuildConfig.LOCAL_DEVICE_EDITION && item.completed && item.id in homeIds) Text("·  Home preview", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                GalleryArrow(previous = true, enabled = pager.settledPage > 0) {
                    scope.launch { pager.animateScrollToPage((pager.settledPage - 1).coerceAtLeast(0), animationSpec = tween(700)) }
                }
                Text("${(pager.settledPage + 1).toString().padStart(2, '0')}  /  ${items.size.toString().padStart(2, '0')}   ·   Swipe",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 2)
                GalleryArrow(previous = false, enabled = pager.settledPage < items.lastIndex) {
                    scope.launch { pager.animateScrollToPage((pager.settledPage + 1).coerceAtMost(items.lastIndex), animationSpec = tween(700)) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 26.dp).heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("My entries  ↗", color = StargleanColors.Gold,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { ownDiariesOpen = true }.wrapContentHeight())
                Text(if (BuildConfig.LOCAL_DEVICE_EDITION) "Memories, just for you" else "Target: ${currentTarget?.name ?: "None"}",
                    color = StargleanColors.MutedText.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, textAlign = TextAlign.End, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable {
                        val index = items.indexOfFirst { it.id == targetId }
                        if (index >= 0) scope.launch { pager.animateScrollToPage(index, animationSpec = tween(800)) }
                    }.padding(vertical = 8.dp))
            }
        }

        panel?.let { activePanel ->
            Box(Modifier.fillMaxSize().then(if (reading != null) Modifier.clearAndSetSemantics {} else Modifier)) {
            GalleryUtilityPanel(activePanel, items, focused.id, targetId, home,
                onBrowse = { id ->
                    val index = items.indexOfFirst { it.id == id }
                    if (index >= 0) {
                        closePanel()
                        scope.launch { pager.animateScrollToPage(index, animationSpec = tween(650)) }
                    }
                },
                onHomeDraftToggle = { home = home.toggle(it) },
                onHomeConfirm = {
                    home = home.confirm(items)
                    homeIds = home.committedIds.toList()
                    panel = null
                    notice = "Gallery preview confirmed. Your home sky has not changed."
                },
                onClose = { closePanel() },
                onOpenDiary = { owner, star -> openDiary(owner, star) },
            )
            }
        }

        reading?.let { opened ->
            GalleryDiarySheet(opened, motionEnabled && isActive, isActive,
                BuildConfig.DEBUG && demoMemoryCompass) { reading = null }
        }
        if (ownDiariesOpen) OwnDiariesSheet(isActive) { ownDiariesOpen = false }
        AnimatedVisibility(notice != null, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp)) {
            Text(notice.orEmpty(), color = StargleanColors.WarmWhite, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(StargleanColors.InkRaised, RoundedCornerShape(16.dp))
                    .border(1.dp, StargleanColors.Gold.copy(alpha = .25f), RoundedCornerShape(16.dp)).padding(16.dp))
        }
    }

    openStar?.let { (owner, _) ->
        val item = items.first { it.id == owner }
        AlertDialog(onDismissRequest = { openStar = null }, containerColor = StargleanColors.InkRaised,
            title = { Text("A star waiting for a story", color = StargleanColors.WarmWhite) },
            text = { Text("This star has no entry yet. You can choose ${item.name} as the sample target. This preview does not create or move real entries.", color = StargleanColors.MutedText) },
            confirmButton = { TextButton(onClick = {
                targetId = GallerySelectionPolicy.selectTarget(targetId, item)
                openStar = null
            }) { Text("Set as target", color = StargleanColors.Gold) } },
            dismissButton = { TextButton(onClick = { openStar = null }) { Text("Keep exploring", color = StargleanColors.MutedText) } })
    }
    if (aboutSample) AlertDialog(onDismissRequest = { aboutSample = false }, containerColor = StargleanColors.InkRaised,
        title = { Text("About this sky", color = StargleanColors.WarmWhite) },
        text = { Text(if (BuildConfig.LOCAL_DEVICE_EDITION)
            "Starglean ${BuildConfig.VERSION_NAME} · Local edition\n\nYour entries and optional memory locations stay on this device. The compass runs only when you enable it while reading. It is not for navigation.\n\nWeather is opt-in and sends your current precise coordinates to Open-Meteo, never your entries or saved memory locations." +
                " With your consent, current precise coordinates also go to Android's system geocoding service for city and district names." +
                "\n\nThis is an illustrated constellation atlas. Entries are not yet linked to individual stars. Online publishing, discovery and responses are not available.\n\nThere is no cloud backup. Uninstalling or clearing app data can erase your entries. Update in place with a package signed by the same developer."
            else "${items.size} constellations: ${items.joinToString(", ") { it.name }}. Each has an illustration and gentle motion.\n\nStar patterns are artistic simplifications, not complete star maps or precise astronomical projections. Existing home patterns stay in place; new figures appear in this gallery only.\n\nStar entries, responses and layout choices are samples. Your saved entries stay separate in My entries; star placement and home syncing are not connected yet.",
            color = StargleanColors.MutedText, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { aboutSample = false }) { Text("Got it", color = StargleanColors.Gold) } })
}

private enum class GalleryMark { Grid, Star, Heart, Orbit }

@Composable
private fun GalleryShortcut(label: String, count: String?, mark: GalleryMark, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        GallerySymbol(mark, Modifier.size(18.dp), StargleanColors.Gold.copy(alpha = .8f))
        Spacer(Modifier.width(7.dp))
        Text(label, color = StargleanColors.WarmWhite.copy(alpha = .85f), style = MaterialTheme.typography.labelMedium,
            maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (count != null) Text(" $count", color = StargleanColors.Gold, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun GallerySymbol(mark: GalleryMark, modifier: Modifier, tint: Color) {
    Canvas(modifier) {
        val stroke = Stroke(width = 1.05.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        when (mark) {
            GalleryMark.Grid -> repeat(4) {
                val x = if (it % 2 == 0) .28f else .72f
                val y = if (it < 2) .28f else .72f
                drawCircle(tint, w * .09f, Offset(w * x, h * y), style = stroke)
            }
            GalleryMark.Star -> {
                drawCircle(tint, w * .10f, center)
                drawArc(tint.copy(alpha = .6f), 20f, 140f, false, Offset(w * .1f, h * .30f),
                    androidx.compose.ui.geometry.Size(w * .8f, h * .52f), style = stroke)
                drawLine(tint, Offset(w * .5f, h * .05f), Offset(w * .5f, h * .21f), 1.dp.toPx(), StrokeCap.Round)
            }
            GalleryMark.Heart -> {
                val heart = Path().apply {
                    moveTo(w * .5f, h * .84f)
                    cubicTo(w * .1f, h * .6f, w * .03f, h * .26f, w * .24f, h * .19f)
                    cubicTo(w * .36f, h * .13f, w * .46f, h * .2f, w * .5f, h * .32f)
                    cubicTo(w * .62f, h * .04f, w * .94f, h * .15f, w * .89f, h * .40f)
                    cubicTo(w * .86f, h * .57f, w * .67f, h * .73f, w * .5f, h * .84f)
                }
                drawPath(heart, tint, style = stroke)
            }
            GalleryMark.Orbit -> {
                drawCircle(tint, w * .25f, center, style = stroke)
                drawOval(tint.copy(alpha = .7f), Offset(w * .02f, h * .33f),
                    androidx.compose.ui.geometry.Size(w * .96f, h * .35f), style = stroke)
            }
        }
    }
}

@Composable
private fun GalleryArrow(previous: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).semantics { contentDescription = if (previous) "Previous constellation" else "Next constellation" }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val middleX = if (previous) .32f else .68f
            val otherX = if (previous) .65f else .35f
            val color = StargleanColors.Gold.copy(alpha = if (enabled) .75f else .16f)
            drawLine(color, Offset(size.width * otherX, size.height * .2f), Offset(size.width * middleX, size.height * .5f), 1.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(size.width * middleX, size.height * .5f), Offset(size.width * otherX, size.height * .8f), 1.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun GalleryDiarySheet(opened: GalleryDiary, motionEnabled: Boolean, isActive: Boolean,
    demoMemoryCompass: Boolean, onBack: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = {}) }) {
        val paperHeight = (maxHeight * .62f).coerceAtLeast(330.dp)
        DiaryWeatherBackground(opened.diary.weatherSnapshot, motionEnabled)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹  Back to the stars", color = StargleanColors.Gold) }
                Spacer(Modifier.weight(1f))
                DemoBadge(text = "Sample entry")
            }
            Text(opened.owner, color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineMedium)
            Text(if (opened.owner == "Unplaced star") "Not placed yet · Ready to read" else "Star ${opened.index + 1} · A page from the past",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
            MemoryCompassCard(place = null, isActive = isActive, demoInitially = demoMemoryCompass)
            DiaryPaper(opened.diary, Modifier.fillMaxWidth(), showDemoBadge = false,
                minimumHeight = paperHeight, placeLabel = opened.diary.blurredPlace)
            Text("Sample content shows how stars and entries might connect. No real entry was saved or response received.",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
        }
    }
}
