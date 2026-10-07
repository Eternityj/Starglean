package com.starglean.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.BuildConfig
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.DiaryPhotoRules
import com.starglean.app.core.draft.WritingDraftState as StoredDraftState
import com.starglean.app.core.memory.MemoryLocationClient
import com.starglean.app.core.memory.MemoryLocationException
import com.starglean.app.core.memory.MemoryLocationFailure
import com.starglean.app.core.memory.OwnDiaryDataException
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryRules
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.*
import com.starglean.app.core.weather.*
import com.starglean.app.ui.components.DemoBadge
import com.starglean.app.ui.components.DiaryPaper
import com.starglean.app.ui.components.DiaryPhotos
import com.starglean.app.ui.components.DiaryWeatherBackground
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal enum class WritingTool { Photo, Font, Paper, Visibility }
internal enum class LocalSavePhase { Idle, PreparingDraft, Locating, Writing }

internal fun canCancelWritingLocation(phase: LocalSavePhase): Boolean = phase == LocalSavePhase.Locating

internal fun canImproveDiaryLocation(failure: MemoryLocationFailure?, preciseGranted: Boolean): Boolean =
    !preciseGranted && failure in setOf(MemoryLocationFailure.Unavailable, MemoryLocationFailure.Timeout,
        MemoryLocationFailure.Unreliable)

internal fun writingLocationFailureMessage(failure: MemoryLocationFailure): String = when (failure) {
    MemoryLocationFailure.PermissionRequired -> "Precise location permission is needed. Your entry is not saved. Allow precise access in Settings, or save without a location."
    MemoryLocationFailure.ServicesDisabled -> "Location services are off. Your entry is not saved. Turn them on, or save without a location."
    MemoryLocationFailure.Unavailable -> "No usable location is available yet. Your entry is not saved. Try again, or save without a location."
    MemoryLocationFailure.Timeout -> "Finding your location is taking longer. Your entry is not saved. Try near a window, or save without a location."
    MemoryLocationFailure.Unreliable -> "This location is too old or inaccurate to remember. Your entry is not saved."
}

/** Provenance stays explicit even when a test query returns real, non-demo weather data. */
internal enum class WritingPreviewOrigin { OwnDraft, DemoWeather, FixedTestLocation }

internal fun writingPreviewOrigin(demoWeather: Boolean, fixedTestLocation: Boolean): WritingPreviewOrigin = when {
    fixedTestLocation -> WritingPreviewOrigin.FixedTestLocation
    demoWeather -> WritingPreviewOrigin.DemoWeather
    else -> WritingPreviewOrigin.OwnDraft
}

/** A source flag and a snapshot flag are independent gates; neither can make a demo genuine. */
internal fun writingPreviewCanSave(origin: WritingPreviewOrigin, weatherIsDemo: Boolean): Boolean =
    origin == WritingPreviewOrigin.OwnDraft && !weatherIsDemo

internal fun writingPreviewCanPlaceStar(origin: WritingPreviewOrigin, weatherIsDemo: Boolean, saveConfirmed: Boolean): Boolean =
    saveConfirmed && writingPreviewCanSave(origin, weatherIsDemo)

/** Never freeze a temporary loading placeholder; a definite absence remains saveable. */
internal fun shouldWaitForWritingWeatherBeforePreview(
    weatherEnabled: Boolean,
    weatherLoading: Boolean,
    hasFreshWeather: Boolean,
): Boolean = weatherEnabled && weatherLoading && !hasFreshWeather

internal enum class WritingDraftReconciliation { Uncommitted, ClearCommittedDraft, KeepEditsWithNewId }

/** Compare only the author's editable content, not weather/date/location captured by a preview. */
internal fun reconcileWritingDraft(
    draftId: String, title: String, body: String, paper: PaperStyle, font: DiaryFont, saved: DiaryDemo?,
    photos: List<DiaryPhoto> = emptyList(),
): WritingDraftReconciliation = when {
    saved == null || saved.id != draftId -> WritingDraftReconciliation.Uncommitted
    saved.title == title.ifBlank { null } && saved.body == body && saved.paper == paper && saved.font == font && saved.photos == photos ->
        WritingDraftReconciliation.ClearCommittedDraft
    else -> WritingDraftReconciliation.KeepEditsWithNewId
}

internal data class WritingPreview(
    val diary: DiaryDemo,
    val visibility: DiaryVisibility,
    val source: String,
    val origin: WritingPreviewOrigin,
)

/** Translate only known app metadata from an older saved preview, never diary text. */
private fun writingPreviewMetadataLabel(value: String): String = when (value) {
    "Sample location" -> value
    "Melbourne test location (not your device)" -> value
    "No location saved" -> value
    "Location saved privately" -> value
    "Visual preview · Not live weather" -> value
    "Melbourne test location · Not your device" -> value
    "Weather unavailable · Quiet background" -> value
    "Weather captured for this preview" -> value
    else -> value
}

/** SavedState only: this frozen preview is deliberately absent from the editable draft file. */
internal fun encodeWritingPreview(preview: WritingPreview): List<String> = with(preview) {
    listOf("2", diary.id, diary.title.orEmpty(), diary.body, diary.date,
        diary.weatherSnapshot.kind.name, diary.weatherSnapshot.daylight.name,
        diary.weatherSnapshot.temperatureC?.toString().orEmpty(), diary.weatherSnapshot.capturedAt.orEmpty(),
        diary.weatherSnapshot.isDemo.toString(), diary.blurredPlace, diary.paper.name, diary.font.name,
        visibility.name, source, origin.name, diary.photos.size.toString()) +
        diary.photos.flatMap { listOf(it.ref, it.width.toString(), it.height.toString(), it.mimeType, it.sizeBytes.toString()) }
}

internal fun decodeWritingPreview(fields: List<String>): WritingPreview? = try {
    val version = fields.firstOrNull()
    val photoCount = if (version == "2") fields.getOrNull(16)?.toIntOrNull() ?: -1 else 0
    if ((version != "1" && version != "2") || photoCount !in 0..2 ||
        fields.size != (if (version == "1") 16 else 17 + photoCount * 5)) null else {
        val photos = (0 until photoCount).map { index ->
            val start = 17 + index * 5
            DiaryPhoto(fields[start], fields[start + 1].toInt(), fields[start + 2].toInt(), fields[start + 3], fields[start + 4].toLong())
        }
        DiaryPhotoRules.validateAttachments(photos)
        val weather = DiaryWeatherSnapshot(WeatherKind.valueOf(fields[5]), Daylight.valueOf(fields[6]),
            fields[7].takeIf { it.isNotEmpty() }?.toInt(), fields[8].takeIf { it.isNotEmpty() },
            fields[9].toBooleanStrict())
        val diary = DiaryDemo(fields[1], fields[2].ifBlank { null }, fields[3], fields[4], weather,
            fields[10], PaperStyle.valueOf(fields[11]), DiaryFont.valueOf(fields[12]), photos)
        WritingDraft(diary.id, diary.title.orEmpty(), diary.body, diary.paper, diary.font, photos)
        if (OwnDiaryRules.textError(diary.title, diary.body) != null) null else
            WritingPreview(diary, DiaryVisibility.valueOf(fields[13]), fields[14], WritingPreviewOrigin.valueOf(fields[15]))
    }
} catch (_: Exception) { null }

private val WritingPreviewSaver = listSaver<WritingPreview?, String>(
    save = { it?.let(::encodeWritingPreview).orEmpty() }, restore = ::decodeWritingPreview,
)

@Composable
fun WriteScreen(
    modifier: Modifier = Modifier,
    isActive: Boolean = true,
    demoWeather: String? = null,
    testLiveWeather: Boolean = false,
    demoPreview: Boolean = false,
    onOpenConstellations: () -> Unit = {},
) {
    // Fixed public test coordinates never masquerade as the device location.
    val testLocation = BuildConfig.DEBUG && testLiveWeather
    val sample = remember(demoWeather) { if (BuildConfig.DEBUG) writingWeatherSample(demoWeather) else null }
    val debugExample = sample != null || testLocation
    var preview by rememberSaveable(stateSaver = WritingPreviewSaver) { mutableStateOf<WritingPreview?>(null) }
    // A preview freezes its weather. The separate diary-location permission flow must not
    // awaken the editor's weather lookup when Android resumes after its permission sheet.
    val controller = rememberWritingWeather(isActive = isActive && sample == null && preview == null, testLocation = testLocation)
    val weather = controller.state
    val snapshot = sample ?: weather.current?.snapshot ?: DiaryWeatherSnapshot.Unavailable
    // A preview is an attempt to commit this draft, not a new diary identity on every visit.
    var draftId by rememberSaveable { mutableStateOf("own-${UUID.randomUUID()}") }
    var title by rememberSaveable { mutableStateOf(if (debugExample) "A moment to keep" else "") }
    var body by rememberSaveable {
        mutableStateOf(if (debugExample) "On the way home, I felt like slowing down.\n\nNothing extraordinary happened today. A quiet moment by the window was enough.\n\nI am leaving this small moment here for my future self." else "")
    }
    var paper by rememberSaveable { mutableStateOf(PaperStyle.WarmWhite) }
    var font by rememberSaveable { mutableStateOf(DiaryFont.Clear) }
    var photos by remember { mutableStateOf<List<DiaryPhoto>>(emptyList()) }
    var visibility by rememberSaveable { mutableStateOf(DiaryVisibility.Private) }
    var selectedTool by rememberSaveable { mutableStateOf<WritingTool?>(null) }
    var showWeatherInfo by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val context = LocalContext.current
    val appLocation = LocalAppLocationAccess.current
    val draftSession = remember(context, debugExample) { if (debugExample) null else WritingDraftSessions.get(context) }
    val draftUi = if (draftSession != null) draftSession.state.collectAsState().value else WritingDraftUiState(loaded = true)
    var draftHydrated by remember(draftSession) { mutableStateOf(debugExample) }
    val localStore = remember(context) { OwnDiaryStore(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestIsActive by rememberUpdatedState(isActive)
    var verifiedDraftId by remember { mutableStateOf<String?>(null) }
    var draftCheckVersion by remember { mutableIntStateOf(0) }
    var draftCheckFailure by remember { mutableStateOf<String?>(null) }
    var draftNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var draftRecoveryOpen by remember { mutableStateOf(false) }
    if (draftRecoveryOpen && draftSession != null) DraftRecoveryDialog(draftSession) { draftRecoveryOpen = false }
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val motionEnabled = rememberMotionEnabled()
    val zone = weather.current?.zoneId ?: ZoneId.systemDefault()
    val paperHeight = (LocalConfiguration.current.screenHeightDp * .42f).coerceIn(320f, 450f).dp
    fun applyEditableDraft(draft: WritingDraft) {
        draftId = draft.id
        title = draft.title
        body = draft.body
        paper = draft.paper
        font = draft.font
        photos = draft.photos
    }

    fun updateEditableDraft(updated: WritingDraft) {
        if (!debugExample && (!draftHydrated || !draftUi.loaded || draftUi.conflict)) return
        applyEditableDraft(updated)
        draftSession?.edit(updated)
    }

    fun updateDraftText(newTitle: String = title, newBody: String = body,
        newPaper: PaperStyle = paper, newFont: DiaryFont = font) {
        val candidate = try { WritingDraft(draftId, newTitle, newBody, newPaper, newFont, photos) }
        catch (_: IllegalArgumentException) {
            Toast.makeText(context, "This text could not be saved safely. Your previous input is unchanged.", Toast.LENGTH_LONG).show()
            return
        }
        updateEditableDraft(candidate)
    }

    LaunchedEffect(draftUi.draft, debugExample) {
        if (!debugExample && draftUi.loaded) {
            draftUi.draft?.let { restored ->
                val firstHydration = !draftHydrated
                applyEditableDraft(restored)
                draftHydrated = true
                // Keep a frozen preview only while it still belongs to these exact edits.
                if (firstHydration && preview?.let { !writingDraftMatchesDiary(restored, it.diary) } == true) preview = null
            }
        }
    }

    fun applyCommittedDraft(savedDiary: DiaryDemo, closePreview: Boolean, token: StoredDraftState? = null) {
        val result = reconcileWritingDraft(draftId, title, body, paper, font, savedDiary, photos)
        if (result == WritingDraftReconciliation.Uncommitted) return
        if (result == WritingDraftReconciliation.ClearCommittedDraft) {
            visibility = DiaryVisibility.Private
            selectedTool = null
            draftNotice = "Your entry is saved on this device. A fresh draft is ready."
        } else {
            // An older transaction may have committed while this draft was being restored.
            // New edits are never discarded or used to overwrite that earlier diary.
            draftNotice = "The earlier version is saved. Your latest edits are kept as a new draft, without replacing it."
        }
        if (closePreview && preview?.diary?.id == draftId) preview = null
        draftSession?.committed(savedDiary, token)?.let(::applyEditableDraft)
        verifiedDraftId = null
        draftCheckFailure = null
    }

    fun returnToDraft() {
        preview = null
        verifiedDraftId = null
        draftCheckFailure = null
        draftCheckVersion++
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    verifiedDraftId = null
                    showWeatherInfo = false
                }
                Lifecycle.Event.ON_RESUME -> draftCheckVersion++
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(isActive) {
        if (!isActive) {
            showWeatherInfo = false
        }
    }

    LaunchedEffect(isActive, weather.foreground, draftId, draftCheckVersion, debugExample, draftHydrated) {
        verifiedDraftId = null
        if (!isActive || !weather.foreground || debugExample || !draftHydrated) return@LaunchedEffect
        val checkingId = draftId
        draftCheckFailure = null
        try {
            // Existing private-store locking serializes this read after any in-flight atomic
            // write. Never infer "not saved" from a cancelled acknowledgement or a read error.
            val savedDiary = localStore.load().firstOrNull { it.diary.id == checkingId }?.diary
            currentCoroutineContext().ensureActive()
            if (draftId != checkingId || !latestIsActive ||
                !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
            if (savedDiary == null) verifiedDraftId = checkingId
            else applyCommittedDraft(savedDiary, closePreview = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (draftId == checkingId && latestIsActive) {
                draftCheckFailure = "Could not check the saved entries. Your text is still here. Retry before saving."
            }
        }
    }
    val draftVerified = debugExample || (draftHydrated && draftUi.loaded && !draftUi.conflict && verifiedDraftId == draftId)

    LaunchedEffect(isActive, weather.foreground) {
        if (isActive && weather.foreground) while (true) {
            now = Instant.now()
            delay(60_000)
        }
    }

    fun capturePreview() {
        if (!debugExample && verifiedDraftId != draftId) {
            Toast.makeText(context, draftCheckFailure ?: "Checking saved entries. Please wait.", Toast.LENGTH_LONG).show()
            return
        }
        val textError = OwnDiaryRules.textError(title, body)
        if (textError != null) {
            Toast.makeText(context, textError, Toast.LENGTH_LONG).show()
            return
        }
        val captured = Instant.now()
        val freshWeather = weather.current?.takeIf { it.isFresh(captured) }
        if (sample == null && shouldWaitForWritingWeatherBeforePreview(
                weatherEnabled = weather.enabled,
                weatherLoading = weather.loading,
                hasFreshWeather = freshWeather != null,
            )) {
            Toast.makeText(context,
                "Weather is still being captured. Wait a moment, or cancel weather to continue without it.",
                Toast.LENGTH_LONG).show()
            return
        }
        keyboard?.hide()
        // Freeze weather with the draft; future refreshes cannot change this preview.
        val selectedWeather = sample ?: freshWeather?.snapshot
            ?: DiaryWeatherSnapshot.Unavailable
        preview = WritingPreview(
            diary = DiaryDemo(
                id = draftId, title = title.ifBlank { null }, body = body,
                date = formatWritingDate(captured, zone, true), weatherSnapshot = selectedWeather,
                blurredPlace = when {
                    sample != null -> "Sample location"
                    testLocation -> "Melbourne test location (not your device)"
                    else -> "No location saved"
                },
                paper = paper, font = font, photos = photos.toList(),
            ),
            visibility = if (BuildConfig.LOCAL_DEVICE_EDITION) DiaryVisibility.Private else visibility,
            source = when {
                sample != null -> "Visual preview · Not live weather"
                testLocation -> "Melbourne test location · Not your device"
                selectedWeather.kind == WeatherKind.Unknown -> "Weather unavailable · Quiet background"
                else -> "Weather captured for this preview"
            },
            origin = writingPreviewOrigin(demoWeather = sample != null, fixedTestLocation = testLocation),
        )
    }

    var debugPreviewOpened by remember { mutableStateOf(false) }
    LaunchedEffect(demoPreview, snapshot) {
        if (BuildConfig.DEBUG && demoPreview && debugExample && !debugPreviewOpened && snapshot.kind != WeatherKind.Unknown) {
            debugPreviewOpened = true
            capturePreview()
        }
    }
    BackHandler(enabled = preview != null) { returnToDraft() }

    Box(modifier = modifier.fillMaxSize()) {
        DiaryWeatherBackground(
            snapshot = preview?.diary?.weatherSnapshot ?: snapshot,
            motionEnabled = motionEnabled && isActive && weather.foreground && !keyboardVisible,
        )
        // Only the editor adjusts for IME. The full sky never shrinks with the keyboard.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to StargleanColors.Ink.copy(alpha = .16f), .38f to Color.Transparent,
            1f to StargleanColors.Ink.copy(alpha = .35f),
        )))
        val capturedPreview = preview
        if (capturedPreview != null) {
            if (capturedPreview.diary.weatherSnapshot.isDemo || capturedPreview.origin != WritingPreviewOrigin.OwnDraft) {
                PreviewSheet(capturedPreview, paperHeight, isActive = isActive,
                    canSaveDraft = { false }, reconciliationFailure = null, onRecheck = {},
                    onBack = ::returnToDraft, flushDraft = { error("Demo previews cannot be saved") },
                    onSaved = {}, onSavedReturn = ::returnToDraft)
            } else OnlineSaveSheet(capturedPreview, isActive = isActive,
                canSaveDraft = { capturedPreview.diary.id == draftId && verifiedDraftId == draftId && draftHydrated &&
                    draftSession?.state?.value?.let { it.loaded && !it.conflict && it.draft?.let { draft ->
                        writingDraftMatchesDiary(draft, capturedPreview.diary)
                    } == true } == true },
                reconciliationFailure = draftUi.error ?: draftCheckFailure,
                onRecheck = {
                    if (draftUi.error != null || !draftUi.loaded) draftSession?.retry()
                    verifiedDraftId = null
                    draftCheckVersion++
                },
                onBack = ::returnToDraft,
                flushDraft = {
                    val session = checkNotNull(draftSession)
                    // Diary titles normalize blank text to null; the durable draft token must
                    // still retain the exact original whitespace rather than reconstruct it.
                    val exactDraft = session.state.value.draft?.takeIf {
                        writingDraftMatchesDiary(it, capturedPreview.diary)
                    } ?: throw IllegalStateException("This draft has changed. Go back and preview it again.")
                    session.flush(exactDraft)
                },
                onSaved = { token -> applyCommittedDraft(capturedPreview.diary, closePreview = false, token = token) },
                onSavedReturn = ::returnToDraft,
                onSeeConstellations = { returnToDraft(); onOpenConstellations() })
        } else {
            Column(
                Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatWritingDate(now, zone, false), color = StargleanColors.WarmWhite.copy(alpha = .75f),
                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        DemoBadge(text = when {
                            debugExample -> "Demo draft"
                            !draftHydrated -> "Loading draft"
                            draftUi.error != null -> "Draft needs retry"
                            draftUi.saving -> "Saving draft"
                            else -> "Local draft"
                        })
                    }
                    WritingHeading()
                    draftNotice?.let { Text(it, color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium) }
                    if (!debugExample && draftUi.error != null) {
                        Text(checkNotNull(draftUi.error), color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { draftSession?.retry() }) { Text("Retry draft", color = StargleanColors.Gold) }
                        TextButton(onClick = { draftRecoveryOpen = true }, enabled = !draftUi.photoImporting) {
                            Text("Review recovery options", color = StargleanColors.Gold)
                        }
                    } else if (!debugExample && !draftHydrated) {
                        Text("Loading your draft…", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium)
                    }
                    if (!debugExample && draftHydrated && !draftVerified && !draftUi.conflict) {
                        Text(draftCheckFailure ?: "Checking saved entries…", color = StargleanColors.MutedText,
                            style = MaterialTheme.typography.labelMedium)
                        if (draftCheckFailure != null) TextButton(onClick = { draftCheckVersion++ }) {
                            Text("Check again", color = StargleanColors.Gold)
                        }
                    }
                    WritingWeatherStatus(snapshot, weather, sample != null, testLocation,
                        permissionNotice = null,
                        onDetails = { showWeatherInfo = true },
                        onCancel = controller.cancel,
                        onAction = {
                            if (latestIsActive && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                when {
                                    !weather.enabled -> appLocation.openSettings()
                                    weather.locationFailure == WeatherLocationFailure.ServicesDisabled ->
                                        context.openWritingSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                    else -> controller.refresh()
                                }
                            }
                        },
                    )
                }
                WritingPaper(title, body, paper, font, paperHeight, enabled = debugExample || (draftHydrated && !draftUi.conflict), onTitleChange = {
                    if (OwnDiaryRules.codePointCount(it) <= OwnDiaryRules.MAX_TITLE_CODE_POINTS)
                        updateDraftText(newTitle = it)
                    else Toast.makeText(context, "Titles can have up to ${OwnDiaryRules.MAX_TITLE_CODE_POINTS} characters.", Toast.LENGTH_SHORT).show()
                }, onBodyChange = {
                    if (OwnDiaryRules.codePointCount(it) <= OwnDiaryRules.MAX_BODY_CODE_POINTS)
                        updateDraftText(newBody = it)
                    else Toast.makeText(context, "Entries can have up to ${OwnDiaryRules.MAX_BODY_CODE_POINTS} characters.", Toast.LENGTH_SHORT).show()
                })
                WritingToolRow(photos.size, font, paper, visibility, selectedTool,
                    enabled = debugExample || (draftHydrated && !draftUi.conflict)) { tool ->
                    selectedTool = if (selectedTool == tool) null else tool
                }
                AnimatedVisibility(selectedTool != null && !(BuildConfig.LOCAL_DEVICE_EDITION && selectedTool == WritingTool.Visibility)) {
                    if (selectedTool == WritingTool.Photo) WritingPhotoControls(draftSession, draftId,
                        enabled = draftHydrated && !draftUi.conflict)
                    else ToolOptions(selectedTool, font, paper, visibility,
                        { updateDraftText(newFont = it) },
                        { updateDraftText(newPaper = it) }, { visibility = it })
                }
                DiaryPhotos(photos, enabled = draftHydrated && !draftUi.conflict, onRemove = { photo ->
                    draftSession?.removePhoto(photo.ref)
                })
                val count = OwnDiaryRules.meaningfulCodePointCount(body)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (count >= 10) "$count characters · Ready" else "$count / 10 characters", color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium)
                        writingDraftStatusMessage(debugExample, draftHydrated, draftUi)?.let {
                            Text(it, color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Box(Modifier.background(if (count >= 10 && draftVerified) StargleanColors.Gold else Color.White.copy(alpha = .08f), RoundedCornerShape(50))
                        .clickable(enabled = count >= 10 && draftVerified) { capturePreview() }.padding(horizontal = 28.dp, vertical = 15.dp)) {
                        Text("Preview", color = if (count >= 10 && draftVerified) StargleanColors.Ink else StargleanColors.MutedText, style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (!BuildConfig.LOCAL_DEVICE_EDITION && visibility == DiaryVisibility.Public) Text(
                    "Sharing is not available yet. Even if Public is selected, this entry stays on your device and cannot be discovered by others.",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showWeatherInfo) AlertDialog(
        onDismissRequest = { showWeatherInfo = false },
        containerColor = StargleanColors.InkRaised, titleContentColor = StargleanColors.WarmWhite,
        title = { Text("Weather & location") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!weather.placeLoading && weather.placeLookupEnabled) {
                    writingWeatherPlaceDetail(weather.placeGranularity, !weather.placeName.isNullOrBlank())?.let {
                        Text(it, color = StargleanColors.MutedText)
                    }
                }
                writingWeatherAccuracyLabel(weather)?.let { Text(it, color = StargleanColors.MutedText) }
                Text("Use precise location to fetch local weather while you write. No background tracking.", color = StargleanColors.WarmWhite)
                Text("Your precise coordinates are sent to Open-Meteo. Your writing and saved memory locations are not sent.", color = StargleanColors.MutedText)
                Text(writingPlaceDisclosure(), color = StargleanColors.MutedText)
                Text("Saving a memory location is a separate choice, kept only on this device. Location can have errors; weather is a model estimate. The accuracy shown belongs to this weather lookup, not a live 200 m reading check. A street name is a map label, not proof of distance.", color = StargleanColors.MutedText)
                TextButton(onClick = { context.openWritingLink("https://open-meteo.com/en/terms") }) { Text("Weather service & privacy  ›", color = StargleanColors.Gold) }
                if (!weather.enabled && !testLocation) TextButton(onClick = {
                    appLocation.openSettings()
                    showWeatherInfo = false
                }) { Text("Location settings", color = StargleanColors.Gold) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showWeatherInfo = false
            }) { Text("Got it", color = StargleanColors.Gold) }
        },
        dismissButton = {
            TextButton(onClick = {
                showWeatherInfo = false
            }) { Text("Close", color = StargleanColors.MutedText) }
        },
    )
}

@Composable
internal fun WritingHeading() {
    Text("Let a star take flight", color = StargleanColors.WarmWhite,
        style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = 2.sp))
}

/** Routine autosave stays quiet; pending work and failed saves must remain visible. */
internal fun writingDraftStatusMessage(
    debugExample: Boolean, draftHydrated: Boolean, draftUi: WritingDraftUiState,
): String? = when {
    debugExample -> "Demo content is not saved to your drafts"
    !draftHydrated -> "Loading your draft"
    draftUi.error != null -> "Draft not saved · Retry before closing"
    draftUi.saving -> "Saving your draft…"
    else -> null
}

/** Display only the system's supplied street/area. Never infer a place from timezone or weather. */
internal fun writingWeatherPlaceLabel(
    placeName: String?, placeLoading: Boolean, placeLookupEnabled: Boolean, weatherEnabled: Boolean,
): String = when {
    !weatherEnabled -> "Local weather"
    !placeLookupEnabled -> "Place names are off"
    placeLoading -> "Finding the place name…"
    else -> placeName?.trim()?.takeIf { it.isNotEmpty() } ?: "Place name unavailable"
}

internal fun writingWeatherPlaceDetail(granularity: com.starglean.app.core.weather.WeatherPlaceGranularity,
    hasPlaceName: Boolean): String? = when {
    !hasPlaceName -> null
    granularity == com.starglean.app.core.weather.WeatherPlaceGranularity.Street -> "Near this street · area and city from Android"
    granularity == com.starglean.app.core.weather.WeatherPlaceGranularity.Area -> "Street unavailable · area and city shown"
    granularity == com.starglean.app.core.weather.WeatherPlaceGranularity.City -> "Street unavailable · only city available"
    granularity == com.starglean.app.core.weather.WeatherPlaceGranularity.Region -> "Street and city unavailable · region shown"
    else -> null
}

internal fun writingPlaceDisclosure(): String =
    "Precise coordinates are also sent through Android’s system place-name provider to find the street, district and city for your own weather card. Your writing is not sent. If street details are unavailable, only the returned area is shown. Availability varies by device. This label is not shared with anonymous readers."

internal fun writingWeatherAccuracyLabel(state: WritingWeatherUiState): String? {
    if (!state.enabled || !state.precisePermissionGranted || !state.foreground || state.loading ||
        state.locationFailure != null) return null
    val accuracy = state.locationAccuracyMeters?.takeIf { it.isFinite() && it > 0f && it <= 100f } ?: return null
    // Round upward: avoid implying better precision than the OS reported.
    return "Weather location · estimated accuracy ${kotlin.math.ceil(accuracy.toDouble()).toInt()} m"
}

@Composable
internal fun WritingWeatherStatus(snapshot: DiaryWeatherSnapshot, weather: WritingWeatherUiState,
    sample: Boolean, testLocation: Boolean, permissionNotice: String?, onDetails: () -> Unit,
    onCancel: () -> Unit, onAction: () -> Unit) {
    val context = LocalContext.current
    val appLocation = LocalAppLocationAccess.current
    val showPlace = !sample && !testLocation
    val weatherLabel = when {
        sample -> "${snapshot.label} · Sample"
        weather.loading -> writingWeatherProgressLabel(weather.stage)
        weather.current != null -> snapshot.label
        !weather.enabled -> "Location off · Keep writing"
        weather.locationFailure != null -> "Location unavailable · Keep writing"
        weather.fetchFailure != null -> "Weather unavailable · Keep writing"
        else -> "Weather unavailable · Quiet background"
    }
    Column(Modifier.fillMaxWidth().background(StargleanColors.Ink.copy(alpha = .55f), RoundedCornerShape(18.dp))
        .border(1.dp, Color.White.copy(alpha = .09f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showPlace) Row(Modifier.weight(1f).padding(end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                WritingPlaceMark()
                Text(writingWeatherPlaceLabel(weather.placeName, weather.placeLoading, weather.placeLookupEnabled, weather.enabled),
                    color = StargleanColors.WarmWhite, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Text(weatherLabel, color = StargleanColors.WarmWhite,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (!sample) TextButton(onClick = if (weather.loading) onCancel else onAction, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(when {
                    weather.loading -> "Cancel"
                    !weather.enabled -> "Location settings"
                    weather.locationFailure == WeatherLocationFailure.ServicesDisabled -> "Enable location"
                    weather.current != null -> "Refresh"
                    else -> "Retry"
                }, color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium)
            }
        }
        if (showPlace && !weather.placeLoading && weather.placeLookupEnabled && weather.placeImproving) {
            Text("Checking once for a nearby street…", color = StargleanColors.MutedText,
                style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (showPlace) Text(weatherLabel, color = StargleanColors.WarmWhite.copy(alpha = .84f),
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 2.dp))
        if (sample) Text("Visual preview · Not live weather", color = StargleanColors.Gold, style = MaterialTheme.typography.labelSmall)
        if (testLocation) Text("Test location: central Melbourne · Not your device", color = StargleanColors.Gold, style = MaterialTheme.typography.labelSmall)
        if (weather.message != null && !sample) Text(weather.message, color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
        if (permissionNotice != null && !sample) Text(permissionNotice, color = StargleanColors.Gold,
            style = MaterialTheme.typography.labelSmall)
        if (!sample && !testLocation && !weather.loading && weather.locationFailure != null) {
            TextButton(onClick = {
                appLocation.openSettings()
            }) { Text("Location settings", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium) }
        }
        if (!sample && weather.current != null) {
            val current = weather.current
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${current.observedAt.atZone(current.zoneId).format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))} · Open-Meteo · Estimate", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable { context.openWritingLink(OPEN_METEO_ATTRIBUTION_URL) }.padding(vertical = 8.dp))
                Text("  ·  Details", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable(onClick = onDetails).padding(vertical = 8.dp))
            }
        } else if (!sample) Text("Precise-location weather · Managed in Location settings  ›", color = StargleanColors.MutedText,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable(onClick = onDetails).padding(vertical = 8.dp))
    }
}

@Composable
private fun WritingPlaceMark() {
    Canvas(Modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val outline = Path().apply {
            moveTo(w * .5f, h * .93f)
            cubicTo(w * .36f, h * .75f, w * .14f, h * .51f, w * .14f, h * .35f)
            cubicTo(w * .14f, h * -.04f, w * .86f, h * -.04f, w * .86f, h * .35f)
            cubicTo(w * .86f, h * .51f, w * .64f, h * .75f, w * .5f, h * .93f)
            close()
        }
        drawPath(outline, StargleanColors.Gold.copy(alpha = .84f), style = Stroke(1.05.dp.toPx()))
        drawCircle(StargleanColors.Gold.copy(alpha = .84f), w * .105f,
            Offset(w * .5f, h * .34f), style = Stroke(.95.dp.toPx()))
    }
}

@Composable
internal fun WritingPaper(title: String, body: String, paper: PaperStyle, font: DiaryFont, height: Dp, enabled: Boolean,
    onTitleChange: (String) -> Unit, onBodyChange: (String) -> Unit) {
    val (brush, textColor, hintColor) = when (paper) {
        PaperStyle.WarmWhite -> Triple(Brush.verticalGradient(listOf(Color(0xFFF5EEDF), Color(0xFFE9DEC9))), StargleanColors.PaperText, Color(0xFF8A8177))
        PaperStyle.Kraft -> Triple(Brush.verticalGradient(listOf(Color(0xFFD5BA90), Color(0xFFB99567))), Color(0xFF30261F), Color(0xFF76634E))
        PaperStyle.Midnight -> Triple(Brush.verticalGradient(listOf(Color(0xFF142A42), Color(0xFF0C1B2D))), StargleanColors.WarmWhite, Color(0xFF91A4B9))
    }
    val family = when (font) { DiaryFont.Clear -> FontFamily.SansSerif; DiaryFont.Handwritten -> FontFamily.Cursive; DiaryFont.Serif -> FontFamily.Serif }
    Column(Modifier.fillMaxWidth().height(height).shadow(18.dp, RoundedCornerShape(22.dp))
        .background(brush, RoundedCornerShape(22.dp)).border(1.dp, Color.White.copy(alpha = .14f), RoundedCornerShape(22.dp))
        .padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("THIS MOMENT", color = hintColor, style = MaterialTheme.typography.labelSmall)
        BasicTextField(value = title, onValueChange = onTitleChange, singleLine = true, readOnly = !enabled,
            cursorBrush = Brush.linearGradient(listOf(textColor, textColor)),
            textStyle = MaterialTheme.typography.titleLarge.copy(color = textColor, fontFamily = family), modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner -> Box {
                if (title.isBlank()) Text("A title, if you like", color = hintColor, style = MaterialTheme.typography.titleMedium)
                inner()
            } })
        Box(Modifier.fillMaxWidth().height(1.dp).background(textColor.copy(alpha = .08f)))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (body.isBlank()) Text("What would you like to keep from today?\n\nNo perfect words needed. This is for you.", color = hintColor,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 29.sp))
            BasicTextField(value = body, onValueChange = onBodyChange, readOnly = !enabled,
                cursorBrush = Brush.linearGradient(listOf(textColor, textColor)),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor, fontFamily = family, lineHeight = 29.sp),
                modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun WritingToolRow(
    photoCount: Int, font: DiaryFont, paper: PaperStyle, visibility: DiaryVisibility,
    selectedTool: WritingTool?, enabled: Boolean, onSelect: (WritingTool) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WritingTool.entries.filterNot { BuildConfig.LOCAL_DEVICE_EDITION && it == WritingTool.Visibility }.forEach { tool ->
            ToolButton(when (tool) {
                WritingTool.Photo -> "Photos $photoCount/2"
                WritingTool.Font -> "Font"
                WritingTool.Paper -> "Background"
                WritingTool.Visibility -> visibility.writingLabel()
            }, selectedTool == tool, Modifier.weight(1f), enabled = enabled,
                valueDescription = when (tool) {
                    WritingTool.Font -> font.writingLabel()
                    WritingTool.Paper -> paper.writingLabel()
                    else -> null
                }) { onSelect(tool) }
        }
    }
}

@Composable
private fun ToolButton(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueDescription: String? = null, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 50.dp).background(if (selected) StargleanColors.Gold.copy(alpha = .12f) else StargleanColors.InkRaised.copy(alpha = .87f), RoundedCornerShape(14.dp))
        .border(1.dp, if (selected) StargleanColors.Gold.copy(alpha = .56f) else Color.White.copy(alpha = .08f), RoundedCornerShape(14.dp))
        .semantics { valueDescription?.let { stateDescription = it } }
        .clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) StargleanColors.GoldBright else StargleanColors.WarmWhite,
            style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp))
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ToolOptions(tool: WritingTool?, font: DiaryFont, paper: PaperStyle, visibility: DiaryVisibility,
    onFont: (DiaryFont) -> Unit, onPaper: (PaperStyle) -> Unit, onVisibility: (DiaryVisibility) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().background(StargleanColors.InkRaised, RoundedCornerShape(16.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (tool) {
            WritingTool.Photo -> Unit // Rendered by WritingPhotoControls with stable system launchers.
            WritingTool.Font -> DiaryFont.entries.forEach { option -> OptionPill(option.writingLabel(), font == option) { onFont(option) } }
            WritingTool.Paper -> PaperStyle.entries.forEach { option -> OptionPill(option.writingLabel(), paper == option) { onPaper(option) } }
            WritingTool.Visibility -> DiaryVisibility.entries.forEach { option -> OptionPill(option.writingLabel(), visibility == option) { onVisibility(option) } }
            null -> Unit
        }
    }
}

@Composable
private fun OptionPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(label, color = if (selected) StargleanColors.Ink else StargleanColors.WarmWhite, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(if (selected) StargleanColors.Gold else Color.White.copy(alpha = .06f), RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp))
}

@Composable
private fun PreviewSheet(preview: WritingPreview, minimumPaperHeight: Dp,
    isActive: Boolean, canSaveDraft: () -> Boolean, reconciliationFailure: String?, onRecheck: () -> Unit,
    onBack: () -> Unit, flushDraft: suspend () -> StoredDraftState,
    onSaved: (StoredDraftState) -> Unit, onSavedReturn: () -> Unit) {
    val context = LocalContext.current
    val appLocation = LocalAppLocationAccess.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { OwnDiaryStore(context) }
    val locationClient = remember(context) { MemoryLocationClient(context) }
    val maySaveLocally = writingPreviewCanSave(preview.origin, preview.diary.weatherSnapshot.isDemo)
    val draftVerified = canSaveDraft()
    var rememberLocation by rememberSaveable(preview.diary.id) { mutableStateOf(false) }
    var busy by remember(preview.diary.id) { mutableStateOf(false) }
    var saved by remember(preview.diary.id) { mutableStateOf(false) }
    var pendingRecord by remember(preview.diary.id) { mutableStateOf<OwnDiaryRecord?>(null) }
    var locationFailure by remember(preview.diary.id) { mutableStateOf<String?>(null) }
    var locationFailureCode by remember(preview.diary.id) { mutableStateOf<MemoryLocationFailure?>(null) }
    var preciseLocationGranted by remember(appLocation.precisePermissionGranted) { mutableStateOf(appLocation.precisePermissionGranted) }
    var showPreciseLocationInfo by remember(preview.diary.id) { mutableStateOf(false) }
    var saveFailure by remember(preview.diary.id) { mutableStateOf<String?>(null) }
    var showConfirmation by remember(preview.diary.id) { mutableStateOf(false) }
    var operationJob by remember(preview.diary.id) { mutableStateOf<Job?>(null) }
    var phase by remember(preview.diary.id) { mutableStateOf(LocalSavePhase.Idle) }
    var requestToken by remember(preview.diary.id) { mutableLongStateOf(0L) }
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val latestIsActive by rememberUpdatedState(isActive)
    val latestLocationAllowed by rememberUpdatedState(appLocation.managed && appLocation.allowed)

    fun cancelForegroundRequest() {
        requestToken++
        showConfirmation = false
        showPreciseLocationInfo = false
        val interrupted = phase == LocalSavePhase.Locating || phase == LocalSavePhase.PreparingDraft
        if (phase == LocalSavePhase.Locating || phase == LocalSavePhase.PreparingDraft) {
            operationJob?.cancel()
            operationJob = null
            phase = LocalSavePhase.Idle
        }
        // Once the atomic store transaction starts, it can finish locally. It never locates.
        if (phase != LocalSavePhase.Writing) busy = false
        if (interrupted) {
            locationFailureCode = null
            locationFailure = "Location and saving are paused. When you return, confirm again or save without a location."
        }
    }

    fun cancelLocationWait() {
        // Once the private atomic write has begun it must finish/roll back normally; never
        // label that phase as cancelled. A cancelled location wait cannot create a record.
        if (!canCancelWritingLocation(phase)) return
        cancelForegroundRequest()
        locationFailure = "Location cancelled. Your entry is not saved. Retry, or save without a location."
    }

    DisposableEffect(lifecycleOwner, preview.diary.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    preciseLocationGranted = appLocation.precisePermissionGranted
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    foreground = false
                    cancelForegroundRequest()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            foreground = false
            cancelForegroundRequest()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(isActive) {
        if (!isActive) cancelForegroundRequest()
        onDispose { if (isActive) cancelForegroundRequest() }
    }

    fun isCurrentForegroundRequest(token: Long): Boolean = token == requestToken && latestIsActive &&
        (!rememberLocation || latestLocationAllowed) &&
        foreground && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && canSaveDraft()

    LaunchedEffect(appLocation.managed, appLocation.allowed) {
        if (!(appLocation.managed && appLocation.allowed) && phase == LocalSavePhase.Locating) {
            cancelForegroundRequest()
            pendingRecord = null
            locationFailureCode = MemoryLocationFailure.PermissionRequired
            locationFailure = "Location access was turned off. Your entry is not saved. Open Location settings, or save without a location."
        }
    }

    fun saveLocally(includeLocation: Boolean) {
        // Guard the operation itself, not just the button: demo retries and permission callbacks
        // must never write sample text/test weather into the same private store as real diaries.
        if (!maySaveLocally) {
            saveFailure = "Demos cannot be saved to your entries. Open Write to create your own entry."
            return
        }
        if (!canSaveDraft()) {
            saveFailure = "This draft’s save status is not confirmed. Please check it before saving."
            return
        }
        if (busy || saved || !latestIsActive || !foreground ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (includeLocation && !latestLocationAllowed) {
            locationFailureCode = MemoryLocationFailure.PermissionRequired
            locationFailure = "Location access is off. Your entry is not saved. Open Location settings, or save without a location."
            return
        }
        busy = true // Synchronous guard: a double tap cannot enqueue a second location/save job.
        locationFailure = null
        locationFailureCode = null
        saveFailure = null
        val token = ++requestToken
        phase = LocalSavePhase.PreparingDraft
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ownJob = currentCoroutineContext()[Job]
            var locating = false
            try {
                // The snapshot token is the only authority for retiring this draft later.
                // The writer survives UI cancellation; cancelling this await never cancels
                // accepted draft text, and the diary itself is still not submitted.
                val confirmedDraft = flushDraft()
                currentCoroutineContext().ensureActive()
                if (!isCurrentForegroundRequest(token)) throw CancellationException("Local save request paused")
                phase = LocalSavePhase.Locating
                val record = pendingRecord ?: run {
                    locating = includeLocation
                    val place = if (includeLocation) locationClient.locate() else null
                    currentCoroutineContext().ensureActive()
                    if (!isCurrentForegroundRequest(token)) throw CancellationException("Local save request paused")
                    locating = false
                    OwnDiaryRecord(
                        diary = preview.diary.copy(blurredPlace = if (place != null) "Location saved privately" else "No location saved"),
                        place = place,
                        savedAtMillis = System.currentTimeMillis(),
                    ).also { pendingRecord = it }
                }
                currentCoroutineContext().ensureActive()
                if (!isCurrentForegroundRequest(token)) throw CancellationException("Local save request paused")
                phase = LocalSavePhase.Writing
                store.save(record)
                saved = true
                onSaved(confirmedDraft)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                if (locating) {
                    if (!isCurrentForegroundRequest(token)) return@launch
                    locationFailureCode = if (error is MemoryLocationException) error.code else MemoryLocationFailure.Unavailable
                    locationFailure = writingLocationFailureMessage(checkNotNull(locationFailureCode))
                    preciseLocationGranted = appLocation.precisePermissionGranted
                } else {
                    saveFailure = if (phase == LocalSavePhase.PreparingDraft)
                        "The draft is not safely saved yet, so no entry was submitted. Your text is still here. Go back and retry the draft."
                    else if (error is OwnDiaryDataException) error.message
                        else "Could not save on this device. Your preview is still here. Please retry."
                }
            } finally {
                if (operationJob === ownJob) {
                    busy = false
                    phase = LocalSavePhase.Idle
                    operationJob = null
                }
            }
        }
        operationJob = job
        job.start()
    }

    fun beginSave() {
        if (!maySaveLocally) {
            saveFailure = "Demos cannot be saved to your entries. Open Write to create your own entry."
            return
        }
        if (!canSaveDraft()) {
            saveFailure = "This draft’s save status is not confirmed. Please check it before saving."
            return
        }
        if (busy || saved || !latestIsActive || !foreground ||
            !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (pendingRecord != null || !rememberLocation) {
            saveLocally(includeLocation = rememberLocation)
        } else if (appLocation.managed && appLocation.allowed) {
            saveLocally(includeLocation = true)
        } else {
            locationFailureCode = MemoryLocationFailure.PermissionRequired
            locationFailure = "Location access is off. Your entry is not saved. Open Location settings, or save without a location."
        }
    }

    val previewScroll = rememberScrollState()
    val previewMotion = rememberMotionEnabled()
    LaunchedEffect(saved) {
        if (saved) {
            if (previewMotion && isActive && foreground) previewScroll.animateScrollTo(0)
            else previewScroll.scrollTo(0)
        }
    }
    BackHandler(enabled = busy) { cancelLocationWait() }
    Column(Modifier.fillMaxSize().verticalScroll(previewScroll).padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (saved) "A moment, kept" else "Today, on a page", color = StargleanColors.WarmWhite,
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            DemoBadge(text = when { !maySaveLocally -> "Demo preview"; saved -> "On this device"; else -> "Not saved" })
        }
        Text(writingPreviewMetadataLabel(preview.source), color = StargleanColors.WarmWhite.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
        val displayedDiary = if (saved) pendingRecord?.diary ?: preview.diary else preview.diary
        DiaryPaper(displayedDiary, Modifier.fillMaxWidth(), showDemoBadge = false,
            minimumHeight = minimumPaperHeight, placeLabel = writingPreviewMetadataLabel(displayedDiary.blurredPlace))
        if (!preview.diary.weatherSnapshot.isDemo && preview.diary.weatherSnapshot.kind != WeatherKind.Unknown) {
            Text("Weather · Open-Meteo model estimate · CC BY 4.0\nWeather categories are grouped; temperature is rounded.",
                color = StargleanColors.Gold, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.clickable { context.openWritingLink(OPEN_METEO_LICENCE_URL) }.padding(vertical = 8.dp))
        }
        if (!maySaveLocally) {
            Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised.copy(alpha = .78f), RoundedCornerShape(18.dp))
                .border(1.dp, StargleanColors.Gold.copy(alpha = .18f), RoundedCornerShape(18.dp)).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Preview only", color = StargleanColors.Gold, style = MaterialTheme.typography.titleMedium)
                Text("This is a visual preview. Sample writing, weather and fixed test locations are not saved to your entries. No memory location is requested.\n\nOpen Write to create and save your own entry.",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 52.dp).border(1.dp, StargleanColors.Gold.copy(alpha = .5f), RoundedCornerShape(50))
                    .clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Text("Back to edit", color = StargleanColors.Gold, style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                }
                Box(Modifier.weight(1f).heightIn(min = 52.dp).background(StargleanColors.InkRaised, RoundedCornerShape(50))
                    .clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                    Text("Demo · Not saved", color = StargleanColors.MutedText, style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                }
            }
        } else if (saved) {
            Text("Saved on this device, just for you.", color = StargleanColors.Gold, style = MaterialTheme.typography.bodyLarge)
            Text("Find this page in My constellations → My entries. ${if (pendingRecord?.place != null) "Its location is saved privately. You can turn on the Memory Compass when you choose." else "No location was saved, so this entry has no memory direction."}\nYour entry and its star stay only on this device.",
                color = StargleanColors.WarmWhite, style = MaterialTheme.typography.bodyMedium)
            Text("Entries stay only on this device. Uninstalling or clearing app data removes them. Cloud backup is not available.",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(StargleanColors.Gold, RoundedCornerShape(50))
                .clickable(onClick = onSavedReturn), contentAlignment = Alignment.Center) {
                Text("Write another", color = StargleanColors.Ink, style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            }
        } else {
            Text("Saved only on this device · Not shared", color = StargleanColors.WarmWhite, style = MaterialTheme.typography.bodyMedium)
            if (!draftVerified) {
                Text(reconciliationFailure ?: "Checking save status to prevent a duplicate entry.",
                    color = StargleanColors.Gold, style = MaterialTheme.typography.bodyMedium)
                if (reconciliationFailure != null) TextButton(onClick = onRecheck) {
                    Text("Check again", color = StargleanColors.Gold)
                }
            }
            if (preview.visibility == DiaryVisibility.Public) Text("Public was selected, but sharing is not available. This entry will stay only on your device.",
                color = StargleanColors.Gold, style = MaterialTheme.typography.labelSmall)
            Column(Modifier.fillMaxWidth().background(StargleanColors.InkRaised.copy(alpha = .78f), RoundedCornerShape(18.dp))
                .border(1.dp, StargleanColors.Gold.copy(alpha = .18f), RoundedCornerShape(18.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rememberLocation, onCheckedChange = { rememberLocation = it; locationFailure = null; locationFailureCode = null },
                        enabled = !busy && draftVerified && pendingRecord == null,
                        modifier = Modifier.semantics { contentDescription = "Remember this location, just for me" },
                        colors = CheckboxDefaults.colors(checkedColor = StargleanColors.Gold, checkmarkColor = StargleanColors.Ink,
                            uncheckedColor = StargleanColors.MutedText))
                    Text("Remember this location (private)", color = StargleanColors.WarmWhite,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                Text("When checked, confirming Save requests your location once, in the foreground. Precise access is required; you can also save without a location. It stays with this entry on your device, not with a network service. A weather query is never used as a memory location.",
                    color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
            }
            locationFailure?.let { message ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(message, color = StargleanColors.Gold, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { rememberLocation = false; saveLocally(includeLocation = false) }, enabled = !busy && draftVerified) {
                        Text("Save without location", color = StargleanColors.Gold)
                    }
                    TextButton(onClick = ::beginSave, enabled = !busy && draftVerified) { Text("Try location again", color = StargleanColors.WarmWhite) }
                    if (canImproveDiaryLocation(locationFailureCode, preciseLocationGranted)) {
                        TextButton(onClick = { showPreciseLocationInfo = true }, enabled = !busy && draftVerified) {
                            Text("Improve precision (optional)", color = StargleanColors.Gold)
                        }
                    }
                    TextButton(onClick = {
                        if (locationFailureCode == MemoryLocationFailure.ServicesDisabled) context.openWritingSettings(
                            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        ) else appLocation.openSettings()
                    }, enabled = !busy) {
                        Text(if (locationFailureCode == MemoryLocationFailure.ServicesDisabled) "Turn location on" else "Location settings",
                            color = StargleanColors.MutedText)
                    }
                }
            }
            saveFailure?.let { Text(it, color = StargleanColors.Gold, style = MaterialTheme.typography.bodyMedium) }
            if (busy) {
                Text(when {
                    phase == LocalSavePhase.PreparingDraft -> "Checking your saved draft…"
                    pendingRecord == null && rememberLocation -> "Finding your location (up to 45 seconds)…"
                    else -> "Saving on this device…"
                },
                    color = StargleanColors.Gold, style = MaterialTheme.typography.bodyMedium)
                if (canCancelWritingLocation(phase)) TextButton(onClick = ::cancelLocationWait) {
                    Text("Cancel · Don’t save", color = StargleanColors.Gold)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).heightIn(min = 52.dp).border(1.dp, StargleanColors.Gold.copy(alpha = .5f), RoundedCornerShape(50))
                    .clickable(enabled = !busy, onClick = onBack), contentAlignment = Alignment.Center) {
                    Text("Back to edit", color = StargleanColors.Gold, style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                }
                Box(Modifier.weight(1.3f).heightIn(min = 52.dp).background(if (busy || !draftVerified) StargleanColors.InkRaised else StargleanColors.Gold, RoundedCornerShape(50))
                    .clickable(enabled = !busy && draftVerified) { showConfirmation = true }, contentAlignment = Alignment.Center) {
                    Text(if (saveFailure != null) "Retry save" else "Save on device",
                        color = if (busy || !draftVerified) StargleanColors.MutedText else StargleanColors.Ink, style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                }
            }
            Text("Your entry is not uploaded, and the compass stays off until you choose. Writing is saved as a local draft; confirmation makes it an entry. Uninstalling or clearing app data removes it.",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (showConfirmation && maySaveLocally && draftVerified) AlertDialog(
        onDismissRequest = { showConfirmation = false }, containerColor = StargleanColors.InkRaised,
        title = { Text("Save on this device", color = StargleanColors.WarmWhite) },
        text = { Text(if (rememberLocation) "Confirm to request your location once and save it privately with this entry on your device. Nothing is uploaded or shared. You choose when to turn on the compass."
            else "Save only this entry and its captured weather, without a memory location. Nothing is uploaded, shared or added to the sample constellations.",
            color = StargleanColors.WarmWhite) },
        confirmButton = { TextButton(onClick = { showConfirmation = false; beginSave() }) { Text("Confirm save", color = StargleanColors.Gold) } },
        dismissButton = { TextButton(onClick = { showConfirmation = false }) { Text("Not yet", color = StargleanColors.MutedText) } },
    )
    if (showPreciseLocationInfo && maySaveLocally && draftVerified && !saved) AlertDialog(
        onDismissRequest = { showPreciseLocationInfo = false }, containerColor = StargleanColors.InkRaised,
        title = { Text("Improve location (optional)", color = StargleanColors.WarmWhite) },
        text = { Text("Precise access is required to save a memory location. You can allow it or save without a location.\n\nThe location is saved only with this entry on your device after you confirm again. It is not sent to the weather service or anyone else. Permission alone does not start locating, saving or the compass. Return and retry when ready.",
            color = StargleanColors.MutedText, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = {
            showPreciseLocationInfo = false
            if (!busy && latestIsActive && foreground && canSaveDraft() &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                appLocation.openSettings()
            }
        }) { Text("Location settings", color = StargleanColors.Gold) } },
        dismissButton = { TextButton(onClick = { showPreciseLocationInfo = false }) {
            Text("Keep current access", color = StargleanColors.MutedText)
        } },
    )
}

internal fun formatWritingDate(instant: Instant, zone: ZoneId, withTime: Boolean): String =
    instant.atZone(zone).format(DateTimeFormatter.ofPattern(
        if (withTime) "d MMM · HH:mm"
        else "EEEE, d MMM yyyy", Locale.ENGLISH))

private fun Context.openWritingLink(url: String) = openWritingSettings(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

private fun Context.openWritingSettings(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: RuntimeException) {
        Toast.makeText(this, "Could not open this. Check your browser or system settings.", Toast.LENGTH_LONG).show()
    }
}

private fun DiaryFont.writingLabel() = when (this) { DiaryFont.Clear -> "Clean"; DiaryFont.Handwritten -> "Handwritten"; DiaryFont.Serif -> "Serif" }
private fun PaperStyle.writingLabel() = when (this) { PaperStyle.WarmWhite -> "Ivory"; PaperStyle.Kraft -> "Kraft"; PaperStyle.Midnight -> "Midnight" }
private fun DiaryVisibility.writingLabel() = if (this == DiaryVisibility.Public) "Public" else "Private"

private fun writingWeatherSample(value: String?): DiaryWeatherSnapshot? {
    val kind = when (value) { "clear" -> WeatherKind.Clear; "cloudy" -> WeatherKind.Cloudy; "rain" -> WeatherKind.Rain; "snow" -> WeatherKind.Snow; "fog" -> WeatherKind.Fog; else -> return null }
    return DiaryWeatherSnapshot(kind, Daylight.Day, when (kind) { WeatherKind.Clear -> 23; WeatherKind.Snow -> -2; else -> 12 }, isDemo = true)
}
