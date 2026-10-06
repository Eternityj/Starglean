package com.starglean.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.model.GalleryConstellation
import com.starglean.app.core.stars.ConstellationStore
import com.starglean.app.core.stars.StarCollectionState
import com.starglean.app.core.stars.StarPlacementResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The page after a saved entry: its star lights up in the current constellation. With no
 * current constellation (or a full one) the writer first swipes to choose where it shines.
 * Mount only after the private diary store has confirmed a real entry's save.
 */
@Composable
internal fun PublishedStarPage(
    diaryId: String,
    isActive: Boolean,
    published: Boolean,
    onSeeConstellations: () -> Unit,
    onWriteAnother: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { ConstellationStore(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val latestActive by rememberUpdatedState(isActive)
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var collection by remember(diaryId) { mutableStateOf<StarCollectionState?>(null) }
    var error by remember(diaryId) { mutableStateOf<String?>(null) }
    var busy by remember(diaryId) { mutableStateOf(false) }
    var autoAttempted by rememberSaveable(diaryId) { mutableStateOf(false) }
    var animateArrival by remember(diaryId) { mutableStateOf(false) }
    var operation by remember(diaryId) { mutableStateOf<Job?>(null) }
    val requests = remember(diaryId) { DiaryStarRequestGate() }
    var refresh by remember(diaryId) { mutableIntStateOf(0) }
    val motion = rememberMotionEnabled() && isActive && foreground

    fun active() = latestActive && foreground && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun ownsForeground(token: Long) = requests.owns(token) && active()
    fun finish(token: Long) {
        if (requests.finish(token)) { busy = false; operation = null }
    }
    fun cancelWork() {
        requests.invalidate()
        val previous = operation
        operation = null
        busy = false
        previous?.cancel()
    }
    fun accept(result: StarPlacementResult) {
        collection = result.state
        animateArrival = result.newlyPlaced && result.placement != null
        error = null
    }
    /** Makes [instanceId] the current constellation, then lights this entry's star in it. */
    fun placeIn(instanceId: String) {
        if (busy || !active()) return
        val token = requests.tryBegin() ?: return
        busy = true
        error = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!ownsForeground(token)) return@launch
                val targeted = store.setTarget(instanceId)
                currentCoroutineContext().ensureActive()
                if (!ownsForeground(token)) return@launch
                collection = targeted
                val result = store.placeDiary(diaryId, instanceId)
                currentCoroutineContext().ensureActive()
                if (ownsForeground(token)) accept(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                if (ownsForeground(token)) error = "Your entry is safely saved. Its star could not be placed yet. Please retry."
            } finally { finish(token) }
        }
        operation = job
        job.start()
    }

    DisposableEffect(lifecycle, diaryId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { foreground = true; refresh++ }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    foreground = false
                    cancelWork()
                    animateArrival = false
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { cancelWork(); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isActive) {
        if (!isActive) { cancelWork(); animateArrival = false }
    }
    LaunchedEffect(diaryId, isActive, foreground, refresh) {
        if (!active()) return@LaunchedEffect
        // Claim before cancelling: an older finally must not release this new request's gate.
        val token = requests.replace()
        val previous = operation
        operation = currentCoroutineContext()[Job]
        busy = true
        previous?.cancel()
        try {
            val current = store.load()
            currentCoroutineContext().ensureActive()
            if (!ownsForeground(token)) return@LaunchedEffect
            collection = current
            error = null
            if (!autoAttempted && current.placementForDiary(diaryId) == null) {
                // Without a current constellation this places nothing and the chooser appears.
                val result = store.placeDiary(diaryId)
                currentCoroutineContext().ensureActive()
                if (ownsForeground(token)) {
                    autoAttempted = true
                    accept(result)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (ownsForeground(token)) {
                autoAttempted = true
                error = "Your entry is safely saved. Its star could not be placed yet. Please retry."
            }
        } finally { finish(token) }
    }

    val placement = collection?.placementForDiary(diaryId)
    val items = collection?.galleryItems().orEmpty()
    val placedIn = items.firstOrNull { it.id == placement?.instanceId }
    val choices = items.filterNot { it.completed }
    val choosing = collection != null && placement == null && error == null && !busy
    val pageMode = when {
        placedIn != null && placement != null -> 1
        choosing && choices.isNotEmpty() -> 2
        else -> 3
    }
    // A chooser may be scrolled to its button on a compact display. Show the new star from
    // the top when placement changes the page, including its first animation frame.
    val pageScroll = key(pageMode) { rememberScrollState() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sceneHeight = (maxHeight - 245.dp).coerceIn(200.dp, 620.dp)
        val chooserHeight = (maxHeight - 330.dp).coerceIn(200.dp, 600.dp)
        Column(Modifier.fillMaxSize().verticalScroll(pageScroll)
            .padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (published) "A moment, shared" else "A moment, kept", color = StargleanColors.WarmWhite,
                fontFamily = FontFamily.Serif, style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text(if (published) "Published · People nearby can now find it" else "Saved privately · Only you can read it",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            when {
                placedIn != null && placement != null -> {
                    Box(Modifier.fillMaxWidth().height(sceneHeight), contentAlignment = Alignment.Center) {
                        GalleryConstellationScene(placedIn, motion, Modifier.fillMaxSize(),
                            selectedStar = placement.starIndex,
                            lightingStar = placement.starIndex.takeIf { animateArrival })
                    }
                    Text("${starInstanceName(placedIn)} · Star ${placement.starIndex + 1} of ${placedIn.totalStars}",
                        color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text(if (placedIn.completed) "Constellation complete · You can add it to your home sky"
                        else "${placedIn.litStars} of ${placedIn.totalStars} stars lit · A little more of your sky",
                        color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                    PrimaryStarButton("See my constellations", onClick = onSeeConstellations)
                    TextButton(onClick = onWriteAnother, modifier = Modifier.fillMaxWidth()) {
                        Text("Write another", color = StargleanColors.Gold)
                    }
                }
                choosing && choices.isNotEmpty() -> ConstellationChooser(choices, collection?.targetId, motion, chooserHeight,
                    onChoose = { placeIn(it.id) }, onLater = onWriteAnother)
                else -> {
                    Spacer(Modifier.height(72.dp))
                    Text(when {
                        error != null -> checkNotNull(error)
                        choosing -> "Every constellation is complete. Begin another in My constellations, then place this entry there."
                        else -> "Placing this entry in your sky…"
                    }, color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    if (error != null) TextButton(onClick = { autoAttempted = false; refresh++ }) {
                        Text("Retry", color = StargleanColors.Gold)
                    }
                    Spacer(Modifier.height(72.dp))
                    if (choosing) PrimaryStarButton("See my constellations", onClick = onSeeConstellations)
                    TextButton(onClick = onWriteAnother, modifier = Modifier.fillMaxWidth()) {
                        Text("Write another", color = StargleanColors.Gold)
                    }
                }
            }
        }
    }
}

/** Swipe through the constellations that still have room; the chosen one becomes current. */
@Composable
private fun ConstellationChooser(
    choices: List<GalleryConstellation>,
    currentId: String?,
    motion: Boolean,
    sceneHeight: Dp,
    onChoose: (GalleryConstellation) -> Unit,
    onLater: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = choices.indexOfFirst { it.id == currentId }.coerceAtLeast(0),
        pageCount = { choices.size })
    val focused = choices[pager.settledPage.coerceIn(choices.indices)]
    Text("Choose where it shines", color = StargleanColors.Gold, style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 18.dp))
    Text("Your next entries will keep lighting this constellation. You can change it any time.",
        color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp))
    HorizontalPager(pager, key = { choices[it].id }, beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxWidth().height(sceneHeight)) { page ->
        GalleryConstellationScene(choices[page], motion && page == pager.settledPage && !pager.isScrollInProgress,
            Modifier.fillMaxSize(), pageOffset = pager.currentPage - page + pager.currentPageOffsetFraction)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { scope.launch { pager.animateScrollToPage((pager.settledPage - 1).coerceAtLeast(0), animationSpec = tween(500)) } },
            enabled = pager.settledPage > 0) { Text("‹", color = StargleanColors.Gold, style = MaterialTheme.typography.titleLarge) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(starInstanceName(focused), color = StargleanColors.WarmWhite, fontFamily = FontFamily.Serif,
                style = MaterialTheme.typography.headlineSmall)
            Text("${focused.litStars} of ${focused.totalStars} stars lit", color = StargleanColors.MutedText,
                style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = { scope.launch { pager.animateScrollToPage((pager.settledPage + 1).coerceAtMost(choices.lastIndex), animationSpec = tween(500)) } },
            enabled = pager.settledPage < choices.lastIndex) { Text("›", color = StargleanColors.Gold, style = MaterialTheme.typography.titleLarge) }
    }
    Spacer(Modifier.height(14.dp))
    PrimaryStarButton("Light a star in ${starInstanceName(focused)}", enabled = !pager.isScrollInProgress) { onChoose(focused) }
    TextButton(onClick = onLater, modifier = Modifier.fillMaxWidth()) {
        Text("Later · It waits safely in Unplaced stars", color = StargleanColors.MutedText)
    }
}

@Composable
private fun PrimaryStarButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = StargleanColors.Gold, contentColor = StargleanColors.Ink),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}

private fun starInstanceName(item: GalleryConstellation): String =
    item.name + if (item.ordinal > 1) " · ${item.ordinal}" else ""
