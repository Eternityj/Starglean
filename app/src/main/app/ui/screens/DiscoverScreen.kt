package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.BuildConfig
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.model.DemoContent
import com.starglean.app.core.model.DiscoveryUiState
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.sensor.ShakeEffect
import com.starglean.app.ui.components.DemoBadge
import com.starglean.app.ui.components.DiaryPaper
import com.starglean.app.ui.components.DiaryPaperReaction
import com.starglean.app.ui.components.DiaryReaction
import com.starglean.app.ui.components.DiaryReactionIcon
import com.starglean.app.ui.components.DiaryWeatherBackground
import com.starglean.app.ui.components.DiscoverStarfield
import com.starglean.app.ui.components.DiscoveryFlyingStar
import com.starglean.app.ui.components.DiscoveryArrivalLight
import com.starglean.app.ui.space.DiscoveryArrival
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    demoAutoTrigger: Boolean,
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    demoAutoReaction: Boolean = false,
    demoWeather: String? = null,
    demoReaction: String? = null,
) {
    // Real builds never compose fixture diaries or development-only reveal controls.
    if (BuildConfig.LOCAL_DEVICE_EDITION || !BuildConfig.DEBUG) {
        LiveDiscoverScreen(modifier = modifier, isActive = isActive)
        return
    }
    val automaticReaction = remember(demoReaction, demoAutoReaction) {
        if (!BuildConfig.DEBUG) null else DiaryReaction.entries.firstOrNull {
            it.name.equals(demoReaction, ignoreCase = true)
        } ?: DiaryReaction.Hug.takeIf { demoAutoReaction }
    }
    val initialDiary = remember(demoWeather) {
        if (BuildConfig.DEBUG) DemoContent.discoveryDiaries.indexOfFirst {
            it.weatherSnapshot.kind.name.equals(demoWeather, ignoreCase = true)
        }.coerceAtLeast(0) else 0
    }
    var selectedDiaryIndex by remember { mutableIntStateOf(initialDiary) }
    var diary by remember { mutableStateOf(DemoContent.discoveryDiaries[initialDiary]) }
    var state by remember { mutableStateOf(DiscoveryUiState.Idle) }
    var sensorAvailable by remember { mutableStateOf(true) }
    var revealKey by remember { mutableIntStateOf(0) }
    var reaction by remember { mutableStateOf<DiaryReaction?>(null) }
    var reactionPreviewComplete by remember { mutableStateOf(false) }
    var reactionJob by remember { mutableStateOf<Job?>(null) }
    var closingReading by remember { mutableStateOf(false) }
    var arrivalRunning by remember { mutableStateOf(false) }
    var departureOrigin by remember { mutableStateOf(Offset(.74f, .25f)) }
    var demoStarted by remember { mutableStateOf(false) }
    var demoReactionStarted by remember { mutableStateOf(false) }
    val approach = remember { Animatable(0f) }
    val paperReveal = remember { Animatable(0f) }
    val reactionProgress = remember { Animatable(0f) }
    val paperReactionRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val motionEnabled = rememberMotionEnabled()
    val haptic = LocalHapticFeedback.current
    val reactionColumns = if (LocalDensity.current.fontScale > 1.25f) 1 else 2
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE) foreground = false
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun requestReveal(localPreview: Boolean) {
        val allowed = if (localPreview) {
            BuildConfig.DEBUG && (state == DiscoveryUiState.Idle || state == DiscoveryUiState.Unavailable)
        } else state == DiscoveryUiState.Idle && sensorAvailable
        if (!allowed || closingReading || !isActive || !foreground) return
        reactionJob?.cancel()
        reactionJob = null
        reaction = null
        reactionPreviewComplete = false
        // Freeze the selected diary and its saved snapshot for this reading session.
        diary = if (localPreview) DemoContent.discoveryDiaries[selectedDiaryIndex] else DemoContent.strangerDiary
        state = DiscoveryUiState.Requesting
        revealKey += 1
    }

    fun closeReading() {
        if (state != DiscoveryUiState.Reading || closingReading || arrivalRunning) return
        closingReading = true
        reactionJob?.cancel()
        reactionJob = null
        scope.launch {
            reactionProgress.snapTo(1f)
            if (motionEnabled) paperReveal.animateTo(0f, tween(340, easing = FastOutSlowInEasing))
            else paperReveal.snapTo(0f)
            state = if (sensorAvailable) DiscoveryUiState.Idle else DiscoveryUiState.Unavailable
            reaction = null
            reactionPreviewComplete = false
            closingReading = false
        }
    }

    fun canPreviewReaction(): Boolean =
        (reaction == null || (BuildConfig.DEBUG && reactionPreviewComplete)) &&
            !closingReading && !arrivalRunning && isActive && foreground && state == DiscoveryUiState.Reading

    fun previewReaction(option: DiaryReaction) {
        if (!canPreviewReaction()) return
        reactionJob?.cancel()
        reaction = option // Synchronous guard against repeat taps. Local preview, never an API receipt.
        reactionPreviewComplete = false
        reactionJob = scope.launch {
            reactionProgress.snapTo(0f)
            if (motionEnabled) {
                try {
                    paperReactionRequester.bringIntoView()
                } catch (_: CancellationException) {
                    // A user's scroll can supersede relocation without cancelling the response.
                    // Closing/backgrounding cancels this job itself and must still stop it.
                    currentCoroutineContext().ensureActive()
                }
            }
            // This confirms the local selection only, not delivery to another person.
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            if (motionEnabled) reactionProgress.animateTo(1f, tween(option.durationMillis, easing = LinearEasing))
            else reactionProgress.snapTo(1f)
            reactionPreviewComplete = true
        }
    }

    LaunchedEffect(isActive, foreground) {
        if (!isActive || !foreground) {
            reactionJob?.cancel()
            reactionJob = null
            if (reaction != null) {
                reactionProgress.snapTo(1f)
                reactionPreviewComplete = true // Static selected state; never replay after resume.
            }
        }
    }

    ShakeEffect(
        enabled = isActive && foreground && state == DiscoveryUiState.Idle,
        onAvailability = { available ->
            sensorAvailable = available
            if (!available && state == DiscoveryUiState.Idle) state = DiscoveryUiState.Unavailable
            if (available && state == DiscoveryUiState.Unavailable) state = DiscoveryUiState.Idle
        },
        onShake = { requestReveal(localPreview = false) },
    )

    LaunchedEffect(demoAutoTrigger, isActive, foreground) {
        if (BuildConfig.DEBUG && demoAutoTrigger && isActive && foreground && !demoStarted) {
            delay(850)
            demoStarted = true
            requestReveal(localPreview = true)
        }
    }

    LaunchedEffect(revealKey, motionEnabled, isActive, foreground) {
        if (revealKey == 0 || state != DiscoveryUiState.Requesting || !isActive || !foreground) return@LaunchedEffect
        arrivalRunning = true
        try {
            approach.snapTo(0f)
            paperReveal.snapTo(0f)
            if (motionEnabled) {
                delay(220) // Lets the background hand over one real sky star; no central status orb.
                state = DiscoveryUiState.Revealing
                // One continuous timeline drives BOTH the diary and its weather layer.
                // Keep Revealing until the fade completes, so interruption never strands a half-page.
                approach.animateTo(1f, tween(DiscoveryArrival.TOTAL_MILLIS, easing = LinearEasing))
                paperReveal.snapTo(1f)
                state = DiscoveryUiState.Reading
            } else {
                paperReveal.snapTo(1f)
                state = DiscoveryUiState.Reading
            }
        } finally {
            arrivalRunning = false
            if (state == DiscoveryUiState.Requesting || state == DiscoveryUiState.Revealing) {
                state = if (sensorAvailable) DiscoveryUiState.Idle else DiscoveryUiState.Unavailable
            }
        }
    }

    // Existing debug reveal plus the same reaction callback makes the full sequence recordable.
    LaunchedEffect(automaticReaction, state, arrivalRunning, isActive, foreground) {
        if (BuildConfig.DEBUG && automaticReaction != null && state == DiscoveryUiState.Reading &&
            !arrivalRunning && isActive && foreground && !demoReactionStarted) {
            delay(600)
            demoReactionStarted = true
            // A manual choice always wins, even if it finished while this demo was waiting.
            if (reaction == null) previewReaction(automaticReaction)
        }
    }

    BackHandler(enabled = isActive && foreground &&
        (state == DiscoveryUiState.Reading || state == DiscoveryUiState.Requesting || state == DiscoveryUiState.Revealing)) {
        if (arrivalRunning || state != DiscoveryUiState.Reading) {
            state = if (sensorAvailable) DiscoveryUiState.Idle else DiscoveryUiState.Unavailable
            revealKey += 1
        } else closeReading()
    }

    // Read animation state inside graphicsLayer: the large reading tree needn't recompose to fade.
    fun readingAlpha(): Float = when (state) {
        DiscoveryUiState.Revealing -> DiscoveryArrival.sample(approach.value).revealAlpha
        DiscoveryUiState.Reading -> paperReveal.value
        else -> 0f
    }

    Box(modifier.fillMaxSize()) {
        DiscoverStarfield(
            motionEnabled = motionEnabled && isActive && (state != DiscoveryUiState.Reading || closingReading),
            subdued = state == DiscoveryUiState.Revealing || state == DiscoveryUiState.Reading,
            departureKey = revealKey,
            reserveDeparture = state != DiscoveryUiState.Idle && state != DiscoveryUiState.Unavailable,
            hideDeparture = state == DiscoveryUiState.Revealing || state == DiscoveryUiState.Reading,
            onDepartureOrigin = { departureOrigin = it },
        )
        if (state == DiscoveryUiState.Requesting || state == DiscoveryUiState.Revealing || state == DiscoveryUiState.Reading) {
            DiaryWeatherBackground(
                snapshot = diary.weatherSnapshot,
                motionEnabled = motionEnabled && isActive && foreground &&
                    (state == DiscoveryUiState.Revealing || state == DiscoveryUiState.Reading),
                // Prepare cloud material during the fly-in, before it becomes visible.
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = readingAlpha()
                },
            )
        }

        if (state != DiscoveryUiState.Reading && state != DiscoveryUiState.Revealing) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                DemoBadge(text = "Local demo")
                Text("10 stars", color = StargleanColors.GoldBright, style = MaterialTheme.typography.labelMedium)
            }
        }
        AnimatedVisibility(
            visible = state != DiscoveryUiState.Reading && state != DiscoveryUiState.Revealing,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 28.dp),
            enter = if (motionEnabled) fadeIn() else EnterTransition.None,
            exit = if (motionEnabled) fadeOut(tween(150)) else ExitTransition.None,
        ) {
            Text("Shake for a star", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center)
        }
        if (state == DiscoveryUiState.Revealing) {
            DiscoveryFlyingStar({ approach.value }, departureOrigin, Modifier.fillMaxSize())
        }

        if (BuildConfig.DEBUG && state != DiscoveryUiState.Reading && state != DiscoveryUiState.Revealing) {
            val available = !closingReading && (state == DiscoveryUiState.Idle || state == DiscoveryUiState.Unavailable)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Developer preview · Sample diary weather", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center)
                FlowRow(Modifier.widthIn(max = 420.dp).fillMaxWidth(), maxItemsInEachRow = 3,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DemoContent.discoveryDiaries.forEachIndexed { index, sample ->
                        val label = sample.weatherSnapshot.kind.label
                        val active = selectedDiaryIndex == index
                        Box(Modifier.heightIn(min = 48.dp)
                            .semantics { selected = active }
                            .border(1.dp, if (active) StargleanColors.Gold.copy(alpha = .6f) else Color.White.copy(alpha = .1f), RoundedCornerShape(18.dp))
                            .background(if (active) StargleanColors.Gold.copy(alpha = .12f) else StargleanColors.InkRaised.copy(alpha = .65f), RoundedCornerShape(18.dp))
                            .clickable(enabled = available, role = Role.Button) { selectedDiaryIndex = index }
                            .padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(label, color = if (active) StargleanColors.GoldBright else StargleanColors.MutedText,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                OutlinedButton(onClick = { requestReveal(localPreview = true) }, enabled = available,
                    modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StargleanColors.GoldBright,
                        containerColor = StargleanColors.InkRaised.copy(alpha = .7f))) {
                    Text("Preview a diary")
                }
            }
        }

        AnimatedVisibility(visible = state == DiscoveryUiState.Revealing || state == DiscoveryUiState.Reading,
            modifier = Modifier.fillMaxSize(),
            enter = EnterTransition.None, exit = if (motionEnabled) fadeOut(tween(100)) else ExitTransition.None) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // A diary is a full reading surface, not a content-sized preview tile.
                // On compact screens and large fonts the whole composition remains scrollable.
                val minimumPaperHeight = (maxHeight - 222.dp).coerceAtLeast(360.dp)
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp).graphicsLayer { alpha = readingAlpha() },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("A moment, found", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleMedium)
                            Text(diary.weatherSnapshot.sourceLabel, color = StargleanColors.WarmWhite,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    shadow = Shadow(Color(0x99020C16), Offset(0f, 1f), 5f)))
                        }
                        Text("Close diary", color = StargleanColors.GoldBright,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.heightIn(min = 48.dp).clickable(enabled = !closingReading && !arrivalRunning, role = Role.Button,
                                onClick = ::closeReading).padding(horizontal = 10.dp, vertical = 14.dp))
                    }
                    DiaryPaper(
                        diary, Modifier.fillMaxWidth(), readingLayout = true, minimumHeight = minimumPaperHeight,
                        reactionContent = {
                            Box(Modifier.fillMaxWidth().height(132.dp).bringIntoViewRequester(paperReactionRequester),
                                contentAlignment = Alignment.Center) {
                                reaction?.takeIf { motionEnabled && !reactionPreviewComplete && !closingReading }?.let {
                                    DiaryPaperReaction(it, reactionProgress.value, Modifier.fillMaxSize(),
                                        darkPaper = diary.paper == PaperStyle.Midnight)
                                }
                            }
                        }
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        DiaryReaction.entries.chunked(reactionColumns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { option ->
                                    val chosen = reaction == option
                                    Row(Modifier.weight(1f).heightIn(min = 48.dp)
                                        .semantics { selected = chosen }
                                        .border(1.dp, if (chosen) StargleanColors.Gold.copy(alpha = .8f) else Color.White.copy(alpha = .14f), RoundedCornerShape(24.dp))
                                        .background(if (chosen) Color(0xFF28313A) else StargleanColors.InkRaised.copy(alpha = .68f), RoundedCornerShape(24.dp))
                                        .clickable(enabled = canPreviewReaction(),
                                            role = Role.Button) { previewReaction(option) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(option.label, color = if (chosen) StargleanColors.GoldBright else StargleanColors.WarmWhite,
                                            style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                        DiaryReactionIcon(option, Modifier.size(24.dp),
                                            color = if (chosen) StargleanColors.GoldBright else StargleanColors.Gold)
                                    }
                                }
                            }
                        }
                        Text(if (BuildConfig.DEBUG && reactionPreviewComplete) "Tap to replay · ${reaction?.label} · Not sent"
                            else if (reaction != null) "Selected · ${reaction?.label} · Not sent" else "Local preview · Reactions are not sent",
                            color = if (reaction != null) StargleanColors.GoldBright else Color(0xFFB4BFCB),
                            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 3.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
        if (state == DiscoveryUiState.Revealing) {
            DiscoveryArrivalLight({ approach.value }, Modifier.fillMaxSize())
        }
    }
}

/** The approved moving sky remains, but it does not imply nearby people or a working inbox. */
@Composable
private fun LocalDiscoveryUnavailable(modifier: Modifier, isActive: Boolean) {
    val motionEnabled = rememberMotionEnabled()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        DiscoverStarfield(
            motionEnabled = motionEnabled && isActive,
            subdued = false,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewportHeight)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                DemoBadge(text = "On-device edition")
                Text("Discover", color = StargleanColors.WarmWhite.copy(alpha = .72f),
                    style = MaterialTheme.typography.labelLarge)
            }
            Column(
                Modifier.widthIn(max = 400.dp).fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Discovery isn't connected yet", color = StargleanColors.WarmWhite,
                    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text("This on-device edition doesn't include anonymous discovery or reactions.\nYou won't receive other people's diaries or send reactions.",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
                Text("These stars are simply scenery. Your diaries stay on this device.",
                    color = StargleanColors.WarmWhite.copy(alpha = .60f),
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            }
            Text("Write about today, revisit your diaries,\nor open Memory Compass to find the way back.",
                color = StargleanColors.Gold.copy(alpha = .82f), style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}
