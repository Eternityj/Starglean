package com.starglean.app.ui.components

import android.hardware.GeomagneticField
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.core.memory.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay

internal data class MemoryCompassState(
    val enabled: Boolean,
    val foreground: Boolean,
    val locating: Boolean,
    val location: MemoryLocationFix?,
    val heading: MemoryHeadingReading?,
    val trueHeading: Double?,
    val failure: String?,
)

internal data class MemoryCompassController(
    val state: MemoryCompassState,
    val start: () -> Unit,
    val stop: () -> Unit,
    val retry: () -> Unit,
)

/** Opt-in, foreground-only state. Neither coordinates nor consent are persisted here. */
@Composable
internal fun rememberMemoryCompass(place: MemoryLocationFix?, isActive: Boolean): MemoryCompassController {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val client = remember(context) { MemoryLocationClient(context) }
    val tracker = remember(context) { MemoryHeadingTracker(context) }
    var enabled by remember(place) { mutableStateOf(false) }
    var foreground by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var resumeVersion by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    var location by remember(place) { mutableStateOf<MemoryLocationFix?>(null) }
    var heading by remember(place) { mutableStateOf<MemoryHeadingReading?>(null) }
    var failure by remember(place) { mutableStateOf<String?>(null) }
    var validUntil by remember { mutableLongStateOf(0L) }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var tick by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    DisposableEffect(owner, tracker) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { foreground = true; resumeVersion++ }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    foreground = false
                    tracker.stop()
                    requestJob?.cancel()
                    location = null
                    heading = null
                    locating = false
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            tracker.stop()
            requestJob?.cancel()
        }
    }
    val running = enabled && isActive && foreground && place != null
    DisposableEffect(running, resumeVersion, tracker) {
        if (running) tracker.start { heading = it }
        else { tracker.stop(); heading = null }
        onDispose { tracker.stop() }
    }
    LaunchedEffect(running, resumeVersion, refresh, place) {
        if (!running) { location = null; locating = false; return@LaunchedEffect }
        val ownJob = currentCoroutineContext()[Job]
        requestJob = ownJob
        failure = null
        try {
            while (true) {
                location = null
                locating = true
                val fix = client.locate()
                if (!client.hasPermission()) throw MemoryLocationException(MemoryLocationFailure.PermissionRequired)
                // Account for the source fix's age; do not turn a cached fix into a fresh one.
                val sourceAge = (System.currentTimeMillis() - fix.capturedAtEpochMillis).coerceAtLeast(0L)
                validUntil = SystemClock.elapsedRealtime() + (45_000L - sourceAge).coerceAtLeast(0L)
                location = fix
                locating = false
                delay(25_000)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: MemoryLocationException) {
            failure = error.message
            location = null
        } catch (_: Exception) {
            failure = "Your location is unavailable. Please try again later."
            location = null
        } finally {
            if (requestJob === ownJob) { requestJob = null; locating = false }
        }
    }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            tick = SystemClock.elapsedRealtime()
            if (!client.hasPermission()) {
                enabled = false
                location = null
                heading = null
                failure = "Location access was revoked. The compass has stopped."
            }
            delay(500)
        }
    }
    val freshLocation = location?.takeIf { running && tick < validUntil }
    val freshHeading = heading?.takeIf { running && tick - it.sampledAtElapsedMillis in -500L..3_000L }
    val declination = remember(freshLocation) {
        freshLocation?.let {
            GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), 0f, System.currentTimeMillis())
                .declination.toDouble()
        }
    }
    val trueHeading = if (freshHeading?.reliability == HeadingReliability.Reliable && declination != null) {
        freshHeading.magneticDegrees?.let { MemoryCompassMath.normalizeDegrees(it + declination) }
    } else null
    return MemoryCompassController(
        MemoryCompassState(enabled, foreground, locating, freshLocation, freshHeading, trueHeading, failure),
        start = { enabled = true; failure = null; refresh++ },
        stop = { enabled = false; tracker.stop(); requestJob?.cancel(); location = null; heading = null; failure = null },
        retry = { if (enabled) refresh++ },
    )
}
