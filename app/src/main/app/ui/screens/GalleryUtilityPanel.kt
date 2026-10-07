package com.starglean.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.BuildConfig
import com.starglean.app.core.model.DemoContent
import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.model.GalleryHomeSelection
import com.starglean.app.core.model.GallerySelectionPolicy
import com.starglean.app.ui.components.GalleryConstellationScene
import com.starglean.app.ui.components.DiaryReaction
import kotlinx.coroutines.launch

internal enum class GalleryPanel { Overview, Home, Unplaced, Responses }

/** Supporting pages only; the main constellation experience remains one unframed sky per page. */
@Composable
internal fun GalleryUtilityPanel(
    panel: GalleryPanel,
    items: List<GalleryConstellation>,
    focusedId: String,
    targetId: String?,
    home: GalleryHomeSelection,
    onBrowse: (String) -> Unit,
    onHomeDraftToggle: (GalleryConstellation) -> Unit,
    onHomeConfirm: () -> Unit,
    onClose: () -> Unit,
    onOpenDiary: (String, Int) -> Unit,
) {
    val title = when (panel) {
        GalleryPanel.Overview -> "Constellation atlas"
        GalleryPanel.Home -> "Arrange your sky"
        GalleryPanel.Unplaced -> "Unplaced stars"
        GalleryPanel.Responses -> "Responses"
    }
    val caption = when (panel) {
        GalleryPanel.Overview -> if (BuildConfig.LOCAL_DEVICE_EDITION) "Eight constellations. Take your time." else "A little time, kept among the stars."
        GalleryPanel.Home -> "Keep completed constellations beside your planet."
        GalleryPanel.Unplaced -> "Still waiting for a home. Still worth keeping."
        GalleryPanel.Responses -> "A little kindness found your page."
    }

    Box(
        Modifier.fillMaxSize()
            .background(StargleanColors.Ink)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GalleryPanelBack(onClose)
                Text(
                    title,
                    modifier = Modifier.weight(1f).padding(start = 5.dp),
                    color = StargleanColors.WarmWhite,
                    fontFamily = FontFamily.Serif,
                    fontSize = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                caption,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                color = StargleanColors.MutedText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (panel == GalleryPanel.Overview || panel == GalleryPanel.Home) {
                val arranging = panel == GalleryPanel.Home
                val shownItems = if (arranging) items.filter { it.completed } else items
                GalleryPagedGrid(
                    items = shownItems,
                    focusedId = focusedId,
                    targetId = targetId,
                    selectedIds = home.draftIds,
                    arranging = arranging,
                    onSelect = { item ->
                        if (arranging) onHomeDraftToggle(item) else onBrowse(item.id)
                    },
                    modifier = Modifier.weight(1f),
                )
                if (arranging) {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                "${home.draftIds.size} / ${GallerySelectionPolicy.MAX_HOME_INSTANCES} selected",
                                color = StargleanColors.GoldBright,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "An empty sky is fine, too.",
                                color = StargleanColors.MutedText,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        GalleryPanelAction(
                            "Confirm",
                            onHomeConfirm,
                            enabled = GallerySelectionPolicy.confirmHomeDraft(home.draftIds, items) != null,
                        )
                    }
                }
                Text(
                    if (BuildConfig.LOCAL_DEVICE_EDITION) "Illustrated atlas · Your entries stay separate in My entries" else "Sample layout only · Not synced to your home sky or collection",
                    Modifier.padding(top = 14.dp, bottom = 4.dp),
                    color = StargleanColors.MutedText,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        "Local samples · Not real entries or responses",
                        color = StargleanColors.Gold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (panel == GalleryPanel.Unplaced) {
                        val unplacedDiary = DemoContent.discoveryDiaries[0]
                        GalleryDiaryEntry(
                            eyebrow = "Unplaced · Sample entry",
                            title = unplacedDiary.title ?: "Untitled",
                            excerpt = unplacedDiary.body,
                            action = "Read this star",
                            onClick = { onOpenDiary("unplaced", 0) },
                        )
                        Text(
                            "Star placement is not connected to real entries yet. Reading this sample does not change constellation progress.",
                            color = StargleanColors.MutedText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        GalleryDiaryEntry(
                            eyebrow = "${DiaryReaction.Hug.label} · Sample response",
                            title = "A little kindness reached this star",
                            excerpt = "Someone sent a hug to ‘Watching a Cloud Drift By’.",
                            action = "Return to the entry",
                            onClick = { onOpenDiary("cygnus-1", 1) },
                        )
                        GalleryDiaryEntry(
                            eyebrow = "${DiaryReaction.Joy.label} · Sample response",
                            title = "A small joy was shared",
                            excerpt = "Someone sent a flower to ‘The World Grew Quiet’.",
                            action = "Return to the entry",
                            onClick = { onOpenDiary("cygnus-1", 3) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun GalleryPagedGrid(
    items: List<GalleryConstellation>,
    focusedId: String,
    targetId: String?,
    selectedIds: Set<String>,
    arranging: Boolean,
    onSelect: (GalleryConstellation) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // A short window or larger type shows fewer tiles per horizontal page, never a long list.
        val fontScale = LocalDensity.current.fontScale
        val pageSize = when {
            maxHeight < 410.dp || fontScale > 1.3f -> 2
            maxHeight < 600.dp -> 4
            // Four per overview page gives this eight-figure catalogue two balanced spreads
            // with room to see the artwork. Arrangement can keep all five completed fixtures together.
            else -> if (arranging) 6 else 4
        }
        val pages = items.chunked(pageSize).ifEmpty { listOf(emptyList()) }
        val focusedPage = if (arranging) 0 else items.indexOfFirst { it.id == focusedId }.coerceAtLeast(0) / pageSize
        val pager = rememberPagerState(initialPage = focusedPage.coerceIn(pages.indices), pageCount = { pages.size })
        val scope = rememberCoroutineScope()
        LaunchedEffect(arranging, pageSize) { pager.scrollToPage(focusedPage.coerceIn(pages.indices)) }

        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxWidth().weight(1f),
                pageSpacing = 18.dp,
                beyondViewportPageCount = 0,
            ) { page ->
                val rows = pages[page].chunked(2)
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (arranging) "No completed constellations yet.\nOnce lit, they can join your home sky." else "No constellations here yet.",
                            color = StargleanColors.MutedText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.forEach { row ->
                            Row(
                                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 300.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                row.forEach { item ->
                                    GalleryGridTile(
                                        item = item,
                                        selected = if (arranging) item.id in selectedIds else item.id == focusedId,
                                        isTarget = item.id == targetId,
                                        arranging = arranging,
                                        onClick = { onSelect(item) },
                                        modifier = Modifier.weight(1f).fillMaxSize(),
                                    )
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            if (pages.size > 1) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryPaginationButton("Previous", pager.currentPage > 0) {
                        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                    }
                    Text(
                        "${pager.currentPage + 1} / ${pages.size}",
                        color = StargleanColors.MutedText,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    GalleryPaginationButton("Next", pager.currentPage < pages.lastIndex) {
                        scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryGridTile(
    item: GalleryConstellation,
    selected: Boolean,
    isTarget: Boolean,
    arranging: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.background(if (selected) Color(0xFF111D2B) else Color(0xFF09111F), shape)
            .border(1.dp, if (selected) StargleanColors.Gold.copy(alpha = .55f) else StargleanColors.ColdBlue.copy(alpha = .13f), shape)
            .clickable(role = if (arranging) Role.Checkbox else Role.Button, onClick = onClick)
            .semantics {
                contentDescription = if (BuildConfig.LOCAL_DEVICE_EDITION) "${item.name}, constellation atlas"
                    else "${item.name}, instance ${item.ordinal}, ${item.litStars} of ${item.totalStars} stars lit"
                stateDescription = if (arranging) {
                    if (selected) "Selected" else "Not selected"
                } else if (isTarget) "Current target" else if (selected) "Currently viewing" else if (BuildConfig.LOCAL_DEVICE_EDITION) "Browse constellation" else "Collected constellation"
            }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (BuildConfig.LOCAL_DEVICE_EDITION) "Sky atlas" else "No. ${item.ordinal}",
                modifier = Modifier.weight(1f),
                color = StargleanColors.MutedText,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (arranging) {
                Box(
                    Modifier.size(22.dp).background(if (selected) StargleanColors.Gold else Color.Transparent, CircleShape)
                        .border(1.dp, StargleanColors.Gold.copy(alpha = .5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Canvas(Modifier.size(12.dp)) {
                            drawLine(StargleanColors.Ink, Offset(size.width * .1f, size.height * .52f), Offset(size.width * .39f, size.height * .8f), 1.7.dp.toPx(), StrokeCap.Round)
                            drawLine(StargleanColors.Ink, Offset(size.width * .39f, size.height * .8f), Offset(size.width * .9f, size.height * .18f), 1.7.dp.toPx(), StrokeCap.Round)
                        }
                    }
                }
            }
        }
        GalleryConstellationScene(
            item = item,
            motionEnabled = false,
            lightweight = true,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Text(
            item.name,
            color = StargleanColors.WarmWhite,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (BuildConfig.LOCAL_DEVICE_EDITION) "Tap to explore" else if (isTarget) "${item.litStars} / ${item.totalStars} · Target" else "${item.litStars} / ${item.totalStars} · Lit",
            color = if (isTarget) StargleanColors.GoldBright else StargleanColors.MutedText,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GalleryDiaryEntry(
    eyebrow: String,
    title: String,
    excerpt: String,
    action: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF0D1624), shape)
            .border(1.dp, StargleanColors.Gold.copy(alpha = .16f), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(eyebrow, color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium)
        Text(title, color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleLarge)
        Text(excerpt, color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium)
        Text(action + "  ›", color = StargleanColors.GoldBright, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GalleryPanelBack(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Back to constellations; discard unconfirmed choices" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val color = StargleanColors.WarmWhite
            drawLine(color, Offset(size.width * .62f, size.height * .18f), Offset(size.width * .3f, size.height * .5f), 1.5.dp.toPx(), StrokeCap.Round)
            drawLine(color, Offset(size.width * .3f, size.height * .5f), Offset(size.width * .62f, size.height * .82f), 1.5.dp.toPx(), StrokeCap.Round)
        }
    }
}

@Composable
private fun GalleryPanelAction(text: String, onClick: () -> Unit, enabled: Boolean) {
    Box(
        Modifier.heightIn(min = 48.dp)
            .background(StargleanColors.Gold.copy(alpha = if (enabled) 1f else .4f), RoundedCornerShape(30.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 27.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = StargleanColors.Ink, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GalleryPaginationButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) StargleanColors.GoldBright else StargleanColors.MutedText.copy(alpha = .4f),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
