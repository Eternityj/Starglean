package com.starglean.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.starglean.app.core.design.*
import com.starglean.app.core.feedback.AndroidEncounterFeedback
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.network.DiaryContent
import com.starglean.app.core.network.ReactionKind
import com.starglean.app.core.network.ReportCategory
import com.starglean.app.core.online.OnlineRuntime
import com.starglean.app.core.sensor.ShakeEffect
import com.starglean.app.ui.components.*
import com.starglean.app.ui.space.DiscoveryArrival
import kotlinx.coroutines.delay

internal fun DiaryContent.toDisplayDiary() = DiaryDemo(
    "nearby-reading", title.takeIf(String::isNotBlank), body, "A moment left nearby",
    weather?.let { snapshot -> DiaryWeatherSnapshot(
        com.starglean.app.core.model.WeatherKind.valueOf(snapshot.kind.name),
        com.starglean.app.core.model.Daylight.valueOf(snapshot.daylight.name),
        snapshot.temperatureCelsius, snapshot.capturedAt?.toString(), false,
    ) } ?: DiaryWeatherSnapshot.Unavailable,
    "Nearby · Exact place is private",
    when (paperStyle) { "kraft" -> PaperStyle.Kraft; "midnight" -> PaperStyle.Midnight; else -> PaperStyle.WarmWhite },
    when (fontStyle) { "serif" -> DiaryFont.Serif; "handwritten" -> DiaryFont.Handwritten; else -> DiaryFont.Clear },
)

internal enum class NearbyReadingPresentation { Discovery, Readable, Checking }

/** The controller separates a verified opened note from short-lived permission for cloud actions. */
internal fun nearbyReadingPresentation(
    reading: Boolean,
    readableNow: Boolean,
    presentedThisEncounter: Boolean,
): NearbyReadingPresentation = when {
    !reading -> NearbyReadingPresentation.Discovery
    readableNow -> NearbyReadingPresentation.Readable
    presentedThisEncounter -> NearbyReadingPresentation.Checking
    else -> NearbyReadingPresentation.Discovery
}

@Composable
internal fun LiveDiscoverScreen(modifier: Modifier = Modifier, isActive: Boolean = true) {
    val context = LocalContext.current
    val appLocationAccess = LocalAppLocationAccess.current
    val locationAllowed = isManagedLocationAllowed(appLocationAccess.managed, appLocationAccess.allowed)
    val runtime = remember(context) { OnlineRuntime.get(context) }
    val location = rememberForegroundNearbyState(isActive)
    val scope = rememberCoroutineScope()
    val controller = remember(runtime, location, scope) { LiveDiscoveryController(runtime, location, scope) }
    val identity by runtime.auth.accountIdentity.collectAsState()
    var sensorAvailable by remember { mutableStateOf(true) }
    var reportOpen by remember { mutableStateOf(false) }
    var reportDetail by remember { mutableStateOf("") }
    var reportCategory by remember { mutableStateOf(ReportCategory.Privacy) }
    var blockConfirm by remember { mutableStateOf(false) }
    var safetyEncounter by remember { mutableIntStateOf(-1) }
    val motion = rememberMotionEnabled()
    val feedback = remember(context) { AndroidEncounterFeedback(context.applicationContext) }
    val reactionProgress = remember { Animatable(1f) }
    var departureOrigin by remember { mutableStateOf(Offset(.74f, .25f)) }
    var animatedReaction by remember { mutableStateOf<Pair<Int, ReactionKind>?>(null) }
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val canRead = controller.canRead
    // A completely verified note remains readable across ordinary app switches.
    val diary = if (canRead) controller.content?.toDisplayDiary() else null
    val readableNow = canRead && diary != null
    var presentedEncounter by remember { mutableStateOf<Int?>(null) }
    val presentation = nearbyReadingPresentation(controller.reading, readableNow,
        presentedEncounter == controller.encounterNumber)
    if (presentation == NearbyReadingPresentation.Readable &&
        presentedEncounter != controller.encounterNumber) SideEffect {
        // Keep the reading frame stable while the controller owns the opened note.
        presentedEncounter = controller.encounterNumber
    }
    LaunchedEffect(controller.reading, controller.encounterNumber) {
        if (!controller.reading) presentedEncounter = null
        else if (presentedEncounter != null && presentedEncounter != controller.encounterNumber)
            presentedEncounter = null
    }
    // Sound and vibration mark the diary's arrival, once per encounter, not the shake itself.
    val approach = rememberEncounterArrival(controller.encounterNumber, canRead,
        isActive && foreground, motion, feedback)

    LaunchedEffect(identity, isActive) {
        controller.authChanged(identity?.userId)
        if (isActive && identity != null && !controller.accountReady) controller.prepareAccount()
    }
    LaunchedEffect(locationAllowed) {
        if (!locationAllowed) controller.pause()
    }
    LaunchedEffect(canRead, controller.encounterNumber) {
        if (!canRead || safetyEncounter != controller.encounterNumber) { reportOpen = false; blockConfirm = false }
    }
    LaunchedEffect(controller, isActive, foreground) {
        if (!isActive) controller.close()
        else if (!foreground) controller.pause()
        while (isActive && foreground) { controller.tick(); delay(100) }
    }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) foreground = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                foreground = false
                feedback.stop()
                controller.pause()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); controller.dispose(); location.stop() }
    }
    DisposableEffect(feedback) {
        onDispose { feedback.release() }
    }
    LaunchedEffect(controller.selectedReaction, canRead, foreground, isActive) {
        val reaction = controller.selectedReaction ?: return@LaunchedEffect
        val key = controller.encounterNumber to reaction
        if (!foreground || !isActive) {
            animatedReaction = key
            reactionProgress.snapTo(1f)
        } else if (canRead && motion && animatedReaction != key) {
            animatedReaction = key
            reactionProgress.snapTo(0f)
            reactionProgress.animateTo(1f, tween(DiaryReaction.valueOf(reaction.name).durationMillis, easing = LinearEasing))
        } else reactionProgress.snapTo(1f)
    }
    // Listen even before the account and location are ready, so a shake then gets a hint, not silence.
    ShakeEffect(enabled = locationAllowed && isActive && !controller.reading,
        onAvailability = { sensorAvailable = it }, onShake = controller::shake)
    BackHandler(controller.reading || reportOpen || blockConfirm) {
        when { reportOpen -> reportOpen = false; blockConfirm -> blockConfirm = false; else -> controller.close() }
    }

    Box(modifier.fillMaxSize()) {
        val readingShell = presentation != NearbyReadingPresentation.Discovery
        DiscoverStarfield(motionEnabled = motion && isActive && foreground && !readingShell, subdued = readingShell,
            departureKey = controller.encounterNumber, reserveDeparture = readingShell,
            hideDeparture = readingShell, onDepartureOrigin = { departureOrigin = it })
        if (readingShell) {
            if (presentation == NearbyReadingPresentation.Readable && diary != null) {
                DiaryWeatherBackground(diary.weatherSnapshot, motionEnabled = motion && isActive && foreground,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = DiscoveryArrival.sample(approach.value).revealAlpha })
            }
            NearbyReadingShell(presentation = presentation,
                alpha = if (presentation == NearbyReadingPresentation.Readable)
                    DiscoveryArrival.sample(approach.value).revealAlpha else 1f,
                onClose = controller::close) {
                if (diary != null) {
                    DiaryPaper(diary, Modifier.fillMaxWidth(), showDemoBadge = false, readingLayout = true,
                        placeLabel = "Left nearby · Exact place is private",
                        minimumHeight = 420.dp, reactionContent = {
                            controller.selectedReaction?.let { kind ->
                                Box(Modifier.fillMaxWidth().height(132.dp)) {
                                    DiaryPaperReaction(DiaryReaction.valueOf(kind.name), reactionProgress.value,
                                        Modifier.fillMaxSize(), darkPaper = diary.paper == PaperStyle.Midnight)
                                }
                            }
                        }, photoContent = { EphemeralDiaryPhotos(controller.photos) })
                    ReactionKind.entries.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { kind ->
                                val reaction = DiaryReaction.valueOf(kind.name)
                                OutlinedButton(onClick = { controller.react(kind) },
                                    enabled = foreground && controller.canReact && !controller.reacting && !controller.reactionCompleted,
                                    shape = RoundedCornerShape(24.dp), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                    Text(reaction.label, Modifier.weight(1f), color = StargleanColors.WarmWhite)
                                    DiaryReactionIcon(reaction, Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                    controller.message?.let { Text(it, color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { safetyEncounter = controller.encounterNumber; reportOpen = true }, enabled = !controller.reacting) { Text("Report", color = StargleanColors.MutedText) }
                        TextButton(onClick = { safetyEncounter = controller.encounterNumber; blockConfirm = true }, enabled = !controller.reacting) { Text("Don't meet this author again", color = StargleanColors.MutedText) }
                    }
                }
            }
            if (presentation == NearbyReadingPresentation.Readable && approach.value < 1f) {
                DiscoveryFlyingStar({ approach.value }, departureOrigin, Modifier.fillMaxSize())
                DiscoveryArrivalLight({ approach.value }, Modifier.fillMaxSize())
            }
        } else {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.TopEnd) {
                    Text(controller.remaining?.let { "$it left today" } ?: "10 a day",
                        color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
                }
                Spacer(Modifier.weight(1f))
                DiscoveryPrompt(noEligibleContent = controller.noEligibleContent)
                if (controller.reading) TextButton(onClick = { controller.close() }) { Text("End this encounter") }
                else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Why a shake was not accepted, the last search result, then the live location status.
                    listOfNotNull(
                        discoveryShakeHint(controller.shakeRejection, controller.accountReady, location.ready),
                        controller.message.takeUnless { controller.noEligibleContent },
                    ).forEach { Text(it, color = StargleanColors.Gold, style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center) }
                    if (locationAllowed) Text(location.message, color = StargleanColors.MutedText,
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.weight(1f))
                if (!controller.reading) {
                    if (!controller.accountReady) OutlinedButton(onClick = controller::prepareAccount, enabled = !controller.busy) {
                        Text(if (controller.busy) "Connecting your account…" else "Retry account connection", color = StargleanColors.Gold)
                    }
                    if (!locationAllowed) OutlinedButton(onClick = appLocationAccess.openSettings) {
                        Text("Location settings", color = StargleanColors.Gold)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (reportOpen && canRead && safetyEncounter == controller.encounterNumber) AlertDialog(onDismissRequest = { reportOpen = false }, title = { Text("Report this entry") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ReportCategory.entries.forEach { category -> Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(reportCategory == category, onClick = { reportCategory = category }); Text(category.name)
            } }
            OutlinedTextField(reportDetail, { if (it.length <= 1000) reportDetail = it }, label = { Text("Details (optional)") })
            Text("Reporting hides this entry from you. It does not automatically remove it for everyone.", style = MaterialTheme.typography.labelMedium)
        }
    }, confirmButton = { TextButton(enabled = !controller.reacting, onClick = {
        if (canRead && safetyEncounter == controller.encounterNumber) { reportOpen = false; controller.report(reportCategory, reportDetail); reportDetail = "" }
    }) { Text("Send report") } },
        dismissButton = { TextButton(onClick = { reportOpen = false }) { Text("Cancel") } })
    if (blockConfirm && canRead && safetyEncounter == controller.encounterNumber) AlertDialog(onDismissRequest = { blockConfirm = false }, title = { Text("Stop meeting this author?") },
        text = { Text("Their entries will no longer appear for you. Neither person's identity is revealed.") },
        confirmButton = { TextButton(enabled = !controller.reacting, onClick = {
            if (canRead && safetyEncounter == controller.encounterNumber) { blockConfirm = false; controller.block() }
        }) { Text("Block author") } },
        dismissButton = { TextButton(onClick = { blockConfirm = false }) { Text("Cancel") } })
}

/** Production reading frame shared with isolated UI verification. Only the controller
 * can provide either a newly authorized note or a previously verified retained note. */
@Composable
internal fun NearbyReadingShell(
    presentation: NearbyReadingPresentation,
    alpha: Float,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    readableContent: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        .graphicsLayer { this.alpha = if (presentation == NearbyReadingPresentation.Readable) alpha else 1f },
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("A moment, found", Modifier.weight(1f), color = StargleanColors.WarmWhite,
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose) { Text("Close", color = StargleanColors.Gold) }
        }
        when (presentation) {
            NearbyReadingPresentation.Readable -> readableContent()
            NearbyReadingPresentation.Checking -> NearbyReadingCheckingCard()
            NearbyReadingPresentation.Discovery -> Unit
        }
    }
}

@Composable
private fun NearbyReadingCheckingCard() {
    Column(Modifier.fillMaxWidth().heightIn(min = 420.dp)
        .background(StargleanColors.InkRaised.copy(alpha = .92f), RoundedCornerShape(22.dp))
        .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text("Checking location…", color = StargleanColors.Gold,
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Your entry is hidden while we confirm you are still nearby.",
            color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center)
    }
}

/** Previously verified images remain in memory until Close; no URI, disk cache or share action. */
@Composable
private fun EphemeralDiaryPhotos(photos: List<androidx.compose.ui.graphics.ImageBitmap>) {
    var selected by remember(photos) { mutableStateOf<Int?>(null) }
    if (photos.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        photos.forEachIndexed { index, bitmap ->
            Image(bitmap, "Diary photo ${index + 1}", contentScale = ContentScale.Crop,
                modifier = Modifier.weight(1f).aspectRatio(if (photos.size == 1) 1.5f else 1f)
                    .clip(RoundedCornerShape(12.dp)).clickable { selected = index })
        }
    }
    selected?.let { index ->
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding().padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("A moment, found", Modifier.weight(1f), color = StargleanColors.WarmWhite)
                    TextButton(onClick = { selected = null }) { Text("Close", color = StargleanColors.Gold) }
                }
                Image(photos[index], "Diary photo ${index + 1}", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                Text("Kept in memory while this note is open",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
