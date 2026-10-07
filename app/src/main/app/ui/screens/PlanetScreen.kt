package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.starglean.app.core.library.LibraryStorageCoordinator
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.BuildConfig
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.model.DemoContent
import com.starglean.app.core.stars.ConstellationStore
import com.starglean.app.ui.components.DiaryPaper
import com.starglean.app.ui.components.MemoryCompassCard
import com.starglean.app.ui.space.CelestialGeometry
import com.starglean.app.ui.space.CelestialScene
import com.starglean.app.ui.space.HomeSkySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PlanetScreen(
    modifier: Modifier = Modifier,
    demoCygnusPreview: Boolean = false,
    demoConstellationsPreview: Boolean = false,
    isActive: Boolean = true,
    onOpenSettings: () -> Unit = {},
) {
    var selectedInstanceId by rememberSaveable { mutableStateOf<String?>(null) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var diaryOpen by rememberSaveable { mutableStateOf(false) }
    var initialDiaryId by rememberSaveable { mutableStateOf<String?>(null) }
    var diaryIdScope by rememberSaveable { mutableStateOf<ArrayList<String>?>(null) }
    val localEdition = BuildConfig.LOCAL_DEVICE_EDITION
    val context = LocalContext.current
    val store = remember(context) { ConstellationStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var refreshRevision by remember { mutableIntStateOf(0) }
    var ownSky by remember { mutableStateOf(HomeSkySnapshot.Empty) }
    var collectionLoading by remember { mutableStateOf(localEdition) }
    var collectionError by remember { mutableStateOf(false) }
    val snapshot = if (localEdition) ownSky else if (BuildConfig.DEBUG) HomeSkySnapshot.Demo else HomeSkySnapshot.Empty
    val activeSelection = selectedInstanceId?.takeIf { snapshot.indexOf(it) != null }
    val focusedFigure = snapshot.indexOf(activeSelection)?.let { snapshot.figures[it] }
    val configuration = LocalConfiguration.current
    val compactHeader = configuration.screenWidthDp < 400 || configuration.fontScale > 1.15f
    val motionEnabled = rememberMotionEnabled()
    val focusedName = focusedFigure?.name
    val canInteract = isActive && foreground && !diaryOpen && !collectionLoading
    val orbitIsPaused = paused || activeSelection != null || diaryOpen || !isActive || !foreground || !motionEnabled
    val demoEnabled = BuildConfig.DEBUG && !localEdition && (demoCygnusPreview || demoConstellationsPreview)
    var demoStarted by rememberSaveable(demoEnabled) { mutableStateOf(false) }
    var demoCancelled by rememberSaveable(demoEnabled) { mutableStateOf(false) }
    fun cancelDemo() {
        if (demoEnabled) demoCancelled = true
    }
    val libraryRevision by LibraryStorageCoordinator.revisionFlow.collectAsState()
    LaunchedEffect(localEdition, isActive, foreground, diaryOpen, refreshRevision, libraryRevision) {
        if (!localEdition || !isActive || !foreground || diaryOpen) return@LaunchedEffect
        collectionLoading = true
        collectionError = false
        try {
            val state = store.load()
            val loaded = withContext(Dispatchers.Default) {
                HomeSkySnapshot.fromGallery(state.galleryItems(), state::diaryAt)
            }
            ownSky = loaded
            if (loaded.indexOf(selectedInstanceId) == null) selectedInstanceId = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed private-store read must never fall back to demonstration stars.
            ownSky = HomeSkySnapshot.Empty
            selectedInstanceId = null
            collectionError = true
        } finally {
            collectionLoading = false
        }
    }

    // A one-shot recording aid, never an alternative selection or touch-test path.
    LaunchedEffect(demoEnabled, demoConstellationsPreview, motionEnabled, demoCancelled) {
        if (!demoEnabled || !motionEnabled || demoCancelled || demoStarted) return@LaunchedEffect
        demoStarted = true
        if (demoConstellationsPreview) {
            // Five figures: 5 s overview + 5 × 4.5 s focus + 4 × 2 s return + 4.5 s overview = 40 s.
            delay(5_000)
            for (index in CelestialGeometry.constellations.indices) {
                if (demoCancelled) return@LaunchedEffect
                selectedInstanceId = HomeSkySnapshot.Demo.figures[index].instanceId
                delay(4_500)
                if (demoCancelled) return@LaunchedEffect
                selectedInstanceId = null
                if (index != CelestialGeometry.constellations.lastIndex) delay(2_000)
            }
            delay(4_500)
        } else {
            delay(6_000)
            if (demoCancelled) return@LaunchedEffect
            selectedInstanceId = HomeSkySnapshot.Demo.figures[0].instanceId
            delay(8_000)
            if (!demoCancelled) selectedInstanceId = null
        }
    }

    DisposableEffect(lifecycleOwner, demoEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foreground = true
                refreshRevision++
            }
            if (event == Lifecycle.Event.ON_PAUSE) foreground = false
            // Back from the unselected home screen leaves the Activity; cancel its pending cue too.
            if (event == Lifecycle.Event.ON_STOP) cancelDemo()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = isActive && (diaryOpen || activeSelection != null)) {
        cancelDemo()
        if (diaryOpen) diaryOpen = false else selectedInstanceId = null
    }

    val demoTouchCancellation = if (demoEnabled) Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.changedToDownIgnoreConsumed() }) cancelDemo()
            }
        }
    } else Modifier

    Box(modifier = modifier.fillMaxSize().then(demoTouchCancellation)) {
        CelestialScene(
            modifier = Modifier.fillMaxSize(),
            selectedInstanceId = activeSelection,
            snapshot = snapshot,
            paused = paused || diaryOpen || !isActive || !foreground,
            motionEnabled = motionEnabled && isActive && foreground && !diaryOpen,
            onSelection = {
                cancelDemo()
                if (canInteract) selectedInstanceId = it
            },
            onStarTap = { instanceId, starIndex ->
                cancelDemo()
                if (canInteract && activeSelection == instanceId) {
                    if (localEdition) {
                        snapshot.diaryAt(instanceId, starIndex)?.let { diaryId ->
                            initialDiaryId = diaryId
                            diaryIdScope = focusedFigure?.diaryIds?.filterNotNull()?.let(::ArrayList)
                            diaryOpen = true
                        }
                    } else if (BuildConfig.DEBUG) diaryOpen = true
                }
            },
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = 26.dp, end = 18.dp, top = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Starglean",
                color = StargleanColors.WarmWhite,
                fontFamily = FontFamily.Serif,
                fontSize = if (compactHeader) 24.sp else 28.sp,
                lineHeight = 36.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp).semantics { heading() },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Account, cloud, backup, sound and privacy live here rather than in My constellations.
                Surface(
                    onClick = { cancelDemo(); onOpenSettings() },
                    enabled = !diaryOpen,
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Settings" },
                    shape = CircleShape,
                    color = Color(0x65090E19),
                    border = BorderStroke(.7.dp, StargleanColors.Gold.copy(alpha = .18f)),
                ) {
                    // A gear: a thick ring with eight square teeth.
                    Canvas(Modifier.padding(14.dp)) {
                        val radius = size.minDimension / 2
                        val tint = StargleanColors.WarmWhite.copy(alpha = .72f)
                        drawCircle(tint, radius * .62f, style = Stroke(radius * .22f))
                        repeat(8) { tooth ->
                            val angle = tooth * PI / 4
                            val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                            drawLine(tint, center + direction * (radius * .66f), center + direction * radius,
                                radius * .28f, StrokeCap.Butt)
                        }
                    }
                }
                Surface(
                    onClick = {
                        cancelDemo()
                        if (activeSelection != null) {
                            selectedInstanceId = null
                            paused = false
                        } else {
                            paused = !paused
                        }
                    },
                    enabled = motionEnabled && isActive && foreground && !diaryOpen,
                    modifier = Modifier
                        .widthIn(min = 68.dp)
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = if (orbitIsPaused) "Resume the orbit" else "Pause the orbit"
                            stateDescription = if (!motionEnabled) "Motion disabled in system settings" else if (orbitIsPaused) "Paused" else "Moving"
                        },
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0x65090E19),
                    border = BorderStroke(.7.dp, StargleanColors.Gold.copy(alpha = .18f)),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 17.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (orbitIsPaused) "Resume" else "Pause",
                            color = StargleanColors.WarmWhite.copy(alpha = if (motionEnabled) .66f else .35f),
                            fontSize = 11.sp,
                            letterSpacing = 1.2.sp,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = activeSelection == null && !diaryOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(350)),
            exit = fadeOut(tween(150)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        collectionLoading -> "Bringing your constellations into the sky…"
                        collectionError -> "Your constellations could not be loaded. Your entries are unchanged."
                        localEdition && snapshot.figures.isEmpty() -> "Complete a constellation to bring it here."
                        else -> "Drag to explore · Tap a constellation"
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 21.dp),
                    color = StargleanColors.WarmWhite.copy(alpha = .50f),
                    fontSize = 10.sp,
                    letterSpacing = .4.sp,
                    textAlign = TextAlign.Center,
                )
                if (localEdition && collectionError && !collectionLoading) {
                    Surface(
                        onClick = { refreshRevision++ },
                        enabled = isActive && foreground,
                        modifier = Modifier.padding(bottom = 12.dp).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp), color = Color(0x65090E19),
                        border = BorderStroke(.7.dp, StargleanColors.Gold.copy(alpha = .18f)),
                    ) {
                        Box(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text("Retry", color = StargleanColors.GoldBright, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = focusedName != null && !diaryOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(330)) + slideInVertically(tween(380)) { it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(220)) { it / 4 },
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .widthIn(max = 440.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = Color(0xE30B101B),
                border = BorderStroke(.7.dp, StargleanColors.Gold.copy(alpha = .24f)),
            ) {
                Column(
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 17.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = focusedName.orEmpty(),
                        color = StargleanColors.GoldBright,
                        fontFamily = FontFamily.Serif,
                        fontSize = 21.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = if (localEdition) "Tap a star to return to the entry that lit it." else "Every little light holds a memory.",
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                        color = StargleanColors.WarmWhite.copy(alpha = .55f),
                        fontSize = 11.sp,
                        letterSpacing = .6.sp,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = {
                                cancelDemo()
                                selectedInstanceId = null
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = Color.Transparent,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Back to the sky",
                                    color = StargleanColors.WarmWhite.copy(alpha = .62f),
                                    fontSize = 12.sp,
                                    letterSpacing = .8.sp,
                                )
                            }
                        }
                        Surface(
                            onClick = {
                                cancelDemo()
                                initialDiaryId = null
                                diaryIdScope = if (localEdition) focusedFigure?.diaryIds?.filterNotNull()?.let(::ArrayList) else null
                                diaryOpen = true
                            },
                            enabled = canInteract,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = StargleanColors.Gold.copy(alpha = .11f),
                            border = BorderStroke(.7.dp, StargleanColors.Gold.copy(alpha = .26f)),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (localEdition) "Constellation entries" else "Open memory",
                                    color = StargleanColors.GoldBright,
                                    fontSize = 12.sp,
                                    letterSpacing = .8.sp,
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = diaryOpen && !localEdition && BuildConfig.DEBUG,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(260)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xAA050913))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Close memory",
                    ) {
                        cancelDemo()
                        diaryOpen = false
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 24.dp)
                        .pointerInput(Unit) { detectTapGestures { } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Memory preview · Sample",
                            color = StargleanColors.WarmWhite.copy(alpha = .7f),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 64.dp, height = 48.dp)
                                .clickable(role = Role.Button, onClickLabel = "Close memory") {
                                    cancelDemo()
                                    diaryOpen = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Close", color = StargleanColors.GoldBright, fontSize = 13.sp)
                        }
                    }
                    MemoryCompassCard(place = null, isActive = isActive && diaryOpen)
                    DiaryPaper(
                        diary = DemoContent.ownDiary,
                        modifier = Modifier.fillMaxWidth(),
                        showDemoBadge = false,
                    )
                }
            }
        }
        // Actual star taps open their exact stored entry; decorative dust only selects a figure.
        // This sheet owns its nested Back handling (record → library → sky) and empty/error UI.
        if (localEdition && diaryOpen) OwnDiariesSheet(isActive = isActive && foreground && diaryOpen,
            initialDiaryId = initialDiaryId, diaryIds = diaryIdScope?.toSet()) {
            diaryOpen = false
        }
    }
}
