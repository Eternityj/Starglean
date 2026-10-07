package com.starglean.app.ui.screens

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.BuildConfig
import com.starglean.app.core.weather.CoarseWeatherLocationClient
import com.starglean.app.core.weather.CurrentWritingWeather
import com.starglean.app.core.weather.OpenMeteoWeatherClient
import com.starglean.app.core.weather.WeatherFetchError
import com.starglean.app.core.weather.WeatherFetchException
import com.starglean.app.core.weather.WeatherLocationException
import com.starglean.app.core.weather.WeatherLocationFailure
import com.starglean.app.core.weather.WeatherQueryLocation
import com.starglean.app.core.weather.WeatherPlaceGranularity
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal enum class WritingWeatherStage { Idle, Locating, Fetching }

/** Unmanaged previews/tests never inherit authorization accidentally. */
internal fun canUseWritingLocation(managed: Boolean, consented: Boolean,
    precisePermissionGranted: Boolean, fixedDebugLocation: Boolean = false): Boolean =
    fixedDebugLocation || (managed && consented && precisePermissionGranted)

internal fun writingWeatherProgressLabel(stage: WritingWeatherStage): String = when (stage) {
    WritingWeatherStage.Locating -> "Finding your location…"
    WritingWeatherStage.Fetching -> "Fetching weather…"
    WritingWeatherStage.Idle -> "Weather unavailable · Quiet background"
}

internal fun canImproveWeatherLocation(failure: WeatherLocationFailure?, preciseGranted: Boolean): Boolean =
    !preciseGranted && failure in setOf(WeatherLocationFailure.Unavailable, WeatherLocationFailure.Timeout,
        WeatherLocationFailure.Unreliable)

internal data class WritingWeatherUiState(
    val current: CurrentWritingWeather? = null,
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val stage: WritingWeatherStage = WritingWeatherStage.Idle,
    val message: String? = null,
    val locationFailure: WeatherLocationFailure? = null,
    val fetchFailure: WeatherFetchError? = null,
    val permissionGranted: Boolean = false,
    val precisePermissionGranted: Boolean = false,
    val foreground: Boolean = true,
    val placeName: String? = null,
    val placeGranularity: WeatherPlaceGranularity = WeatherPlaceGranularity.Unknown,
    val placeLoading: Boolean = false,
    val placeImproving: Boolean = false,
    val placeLookupEnabled: Boolean = false,
    val locationAccuracyMeters: Float? = null,
)

internal data class WritingWeatherController(
    val state: WritingWeatherUiState,
    val refresh: () -> Unit,
    val cancel: () -> Unit,
)

/** Uses only the app-level location decision and performs no work off the active,
 * resumed writing page. Weather and coordinates are never persisted here. */
@Composable
internal fun rememberWritingWeather(
    isActive: Boolean,
    testLocation: Boolean = false,
): WritingWeatherController {
    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val testMode = BuildConfig.DEBUG && testLocation
    val appLocation = LocalAppLocationAccess.current
    // Production location work is authorized only by the app-level disclosure and
    // precise permission. An unmanaged CompositionLocal stays denied in tests/previews.
    val enabled = if (testMode) true else canUseWritingLocation(appLocation.managed,
        appLocation.consented, appLocation.precisePermissionGranted)
    val placeLookupEnabled = enabled && !testMode
    val latestEnabled by rememberUpdatedState(enabled)
    val latestAppLocationAllowed by rememberUpdatedState(appLocation.managed && appLocation.allowed)
    var permissionGranted by remember(testMode, appLocation.managed, appLocation.consented,
        appLocation.precisePermissionGranted) {
        mutableStateOf(testMode || (appLocation.managed && appLocation.allowed))
    }
    val precisePermissionGranted = testMode || appLocation.precisePermissionGranted
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var current by remember(testMode) { mutableStateOf<CurrentWritingWeather?>(null) }
    var attemptedPlaceName by remember(testMode) { mutableStateOf<String?>(null) }
    var attemptedPlaceGranularity by remember(testMode) { mutableStateOf(WeatherPlaceGranularity.Unknown) }
    var attemptedAccuracyMeters by remember(testMode) { mutableStateOf<Float?>(null) }
    var improvingPlace by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf(WritingWeatherStage.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    var locationFailure by remember { mutableStateOf<WeatherLocationFailure?>(null) }
    var fetchFailure by remember { mutableStateOf<WeatherFetchError?>(null) }
    var manualRetryRequired by remember { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var handledRefreshVersion by remember { mutableIntStateOf(0) }
    var resumeVersion by remember { mutableIntStateOf(0) }
    var requestEpoch by remember { mutableLongStateOf(0L) }
    var nextAutomaticAttemptAt by remember(testMode) { mutableLongStateOf(0L) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    val locationClient = remember(context) { CoarseWeatherLocationClient(context) }
    val weatherClient = remember { OpenMeteoWeatherClient() }

    DisposableEffect(lifecycleOwner, context, testMode) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    foreground = true
                    // Restart even if pause/resume state changes coalesce in one frame.
                    resumeVersion += 1
                    if (!latestAppLocationAllowed && !testMode) {
                        current = null
                        attemptedPlaceName = null
                        attemptedPlaceGranularity = WeatherPlaceGranularity.Unknown
                        attemptedAccuracyMeters = null
                        if (latestEnabled) {
                            locationFailure = WeatherLocationFailure.PermissionRequired
                            message = WeatherLocationException(WeatherLocationFailure.PermissionRequired).message
                        }
                    }
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                    requestEpoch += 1
                    foreground = false
                    activeJob?.cancel()
                    improvingPlace = false
                    loading = false
                    stage = WritingWeatherStage.Idle
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            activeJob?.cancel()
        }
    }

    LaunchedEffect(isActive, foreground, enabled, permissionGranted, refreshVersion, resumeVersion, testMode, manualRetryRequired) {
        if (!enabled) {
            manualRetryRequired = false
            // Revoking the unified grant also discards the weather snapshot. If the
            // user later enables it again, perform one fresh lookup immediately;
            // ordinary failures and pause/resume keep their existing retry cooldown.
            nextAutomaticAttemptAt = 0L
            current = null
            attemptedPlaceName = null
            attemptedPlaceGranularity = WeatherPlaceGranularity.Unknown
            attemptedAccuracyMeters = null
            loading = false
            stage = WritingWeatherStage.Idle
            message = null
            locationFailure = null
            fetchFailure = null
            return@LaunchedEffect
        }
        if (!testMode && !permissionGranted) {
            current = null
            attemptedPlaceName = null
            attemptedPlaceGranularity = WeatherPlaceGranularity.Unknown
            attemptedAccuracyMeters = null
            loading = false
            stage = WritingWeatherStage.Idle
            locationFailure = WeatherLocationFailure.PermissionRequired
            message = WeatherLocationException(WeatherLocationFailure.PermissionRequired).message
            fetchFailure = null
            return@LaunchedEffect
        }
        if (!isActive || !foreground) {
            loading = false
            stage = WritingWeatherStage.Idle
            if (current?.isFresh() == false) current = null
            return@LaunchedEffect
        }
        if (manualRetryRequired) {
            loading = false
            stage = WritingWeatherStage.Idle
            return@LaunchedEffect
        }

        val ownJob = currentCoroutineContext()[Job]
        val ownEpoch = requestEpoch
        activeJob = ownJob
        val forced = refreshVersion != handledRefreshVersion
        handledRefreshVersion = refreshVersion
        var mayUseCache = !forced
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!testMode && !latestAppLocationAllowed) {
                    permissionGranted = false
                    current = null
                    attemptedPlaceName = null
                    attemptedPlaceGranularity = WeatherPlaceGranularity.Unknown
                    attemptedAccuracyMeters = null
                    locationFailure = WeatherLocationFailure.PermissionRequired
                    message = WeatherLocationException(WeatherLocationFailure.PermissionRequired).message
                    return@LaunchedEffect
                }
                val now = Instant.now()
                val cached = current
                val cachedAgeMillis = cached?.let { Duration.between(it.fetchedAt, now).toMillis() }
                val useCache = mayUseCache && cached != null && cached.isFresh(now) &&
                    cachedAgeMillis != null && cachedAgeMillis in 0L until WEATHER_REFRESH_MILLIS
                val cooldownRemaining = nextAutomaticAttemptAt - SystemClock.elapsedRealtime()
                val waitAfterFailure = mayUseCache && cached == null && cooldownRemaining > 0L
                var waitMillis = WEATHER_REFRESH_MILLIS
                if (waitAfterFailure) {
                    // Pause/resume of this instance cannot create an automatic retry loop.
                    // Re-entering a disposed writing page performs a new single lookup.
                    waitMillis = cooldownRemaining
                    loading = false
                    stage = WritingWeatherStage.Idle
                } else if (useCache) {
                    waitMillis = WEATHER_REFRESH_MILLIS - checkNotNull(cachedAgeMillis)
                    loading = false
                    stage = WritingWeatherStage.Idle
                    message = null
                    locationFailure = null
                    fetchFailure = null
                } else {
                    if (current?.isFresh(now) == false) current = null
                    attemptedPlaceName = null
                    attemptedPlaceGranularity = WeatherPlaceGranularity.Unknown
                    attemptedAccuracyMeters = null
                    loading = true
                    stage = if (testMode) WritingWeatherStage.Fetching else WritingWeatherStage.Locating
                    message = null
                    locationFailure = null
                    fetchFailure = null
                    try {
                        var weatherQuery: WeatherQueryLocation? = null
                        // The whole location + network operation has its own deadline.
                        // withTimeoutOrNull converts only this deadline; a parent's
                        // cancellation still propagates to both clients unchanged.
                        val weather = withTimeoutOrNull(WEATHER_REQUEST_TIMEOUT_MILLIS) {
                            val queryLocation = if (testMode) {
                                // Public fixture, never device location or stored consent.
                                WeatherQueryLocation.fromCoordinates(-37.8, 145.0)
                            } else {
                                locationClient.locate(resolvePlaceName = placeLookupEnabled)
                            }
                            currentCoroutineContext().ensureActive()
                            attemptedPlaceName = queryLocation.placeName
                            attemptedPlaceGranularity = queryLocation.placeGranularity
                            attemptedAccuracyMeters = queryLocation.accuracyMeters
                            weatherQuery = queryLocation
                            stage = WritingWeatherStage.Fetching
                            weatherClient.fetch(queryLocation)
                        } ?: if (stage == WritingWeatherStage.Locating) {
                            throw WeatherLocationException(WeatherLocationFailure.Timeout)
                        } else throw WeatherFetchException(WeatherFetchError.Timeout)
                        currentCoroutineContext().ensureActive()
                        if (!testMode && !latestAppLocationAllowed) {
                            throw WeatherLocationException(WeatherLocationFailure.PermissionRequired)
                        }
                        if (!weather.isFresh()) {
                            current = null
                            fetchFailure = WeatherFetchError.StaleObservation
                            message = "This weather is out of date. Tap Refresh."
                        } else {
                            current = weather
                            val originalQuery = weatherQuery
                            if (!testMode && placeLookupEnabled && originalQuery != null &&
                                shouldRetryWritingWeatherPlace(originalQuery.placeGranularity)) {
                                // Weather is already usable. Make one finite foreground-only
                                // improvement attempt instead of waiting for the 15-minute cache.
                                loading = false
                                stage = WritingWeatherStage.Idle
                                improvingPlace = true
                                delay(WRITING_PLACE_RETRY_DELAY_MILLIS)
                                currentCoroutineContext().ensureActive()
                                if (latestEnabled && latestAppLocationAllowed && foreground) {
                                    val improved = try {
                                        withTimeoutOrNull(WRITING_PLACE_RETRY_TIMEOUT_MILLIS) {
                                            locationClient.locate(resolvePlaceName = true)
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: WeatherLocationException) {
                                        null
                                    } catch (_: RuntimeException) {
                                        null
                                    }
                                    currentCoroutineContext().ensureActive()
                                    if (improved != null && isWritingWeatherRequestCurrent(ownEpoch, requestEpoch,
                                            latestEnabled && latestAppLocationAllowed, foreground) &&
                                        canApplyWritingWeatherPlaceImprovement(originalQuery, improved)) {
                                        current = weather.copy(placeName = improved.placeName,
                                            placeGranularity = improved.placeGranularity)
                                    }
                                }
                                if (activeJob === ownJob) improvingPlace = false
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: WeatherLocationException) {
                        current = null
                        locationFailure = failure.code
                        fetchFailure = null
                        message = failure.message
                        if (failure.code == WeatherLocationFailure.PermissionRequired) permissionGranted = false
                    } catch (failure: WeatherFetchException) {
                        current = null
                        fetchFailure = failure.code
                        message = failure.message
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        current = null
                        if (stage == WritingWeatherStage.Locating) {
                            locationFailure = WeatherLocationFailure.Unavailable
                            message = "Location is unavailable, so weather has not been queried. You can keep writing."
                        } else {
                            fetchFailure = WeatherFetchError.ServiceUnavailable
                            message = "Your location was found, but the weather service is unavailable. You can keep writing."
                        }
                    } finally {
                        if (activeJob === ownJob) improvingPlace = false
                        if (activeJob === ownJob) { loading = false; stage = WritingWeatherStage.Idle }
                    }
                }
                mayUseCache = false

                // A failed attempt waits the normal interval too; there is no rapid
                // retry. A minute-scale freshness check can neutralize old visuals
                // without issuing another network/location request.
                val refreshAt = SystemClock.elapsedRealtime() + waitMillis
                nextAutomaticAttemptAt = refreshAt
                while (true) {
                    val remaining = refreshAt - SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    delay(minOf(remaining, WEATHER_FRESHNESS_CHECK_MILLIS))
                    if (current?.isFresh() == false) {
                        current = null
                        fetchFailure = WeatherFetchError.StaleObservation
                        message = "This weather is out of date. Tap Refresh."
                    }
                }
            }
        } finally {
            if (activeJob === ownJob) {
                activeJob = null
                loading = false
                stage = WritingWeatherStage.Idle
            }
        }
    }

    return WritingWeatherController(
        state = WritingWeatherUiState(
            current = current,
            enabled = enabled,
            loading = loading,
            stage = stage,
            message = message,
            locationFailure = locationFailure,
            fetchFailure = fetchFailure,
            permissionGranted = permissionGranted,
            precisePermissionGranted = precisePermissionGranted,
            foreground = foreground,
            // Keep any cached weather paired with its own place; never combine
            // yesterday's/another location's weather with a newly resolved name.
            placeName = if (current != null) current?.placeName else attemptedPlaceName,
            placeGranularity = current?.placeGranularity ?: attemptedPlaceGranularity,
            placeLoading = placeLookupEnabled && loading && stage == WritingWeatherStage.Locating,
            placeImproving = placeLookupEnabled && improvingPlace,
            placeLookupEnabled = placeLookupEnabled,
            locationAccuracyMeters = if (current != null) current?.locationAccuracyMeters else attemptedAccuracyMeters,
        ),
        refresh = {
            requestEpoch += 1
            activeJob?.cancel()
            improvingPlace = false
            manualRetryRequired = false
            if (!permissionGranted && !testMode) current = null
            refreshVersion += 1
        },
        cancel = {
            requestEpoch += 1
            activeJob?.cancel()
            improvingPlace = false
            manualRetryRequired = true
            loading = false
            stage = WritingWeatherStage.Idle
            message = "Weather request cancelled. Retry when ready, or keep writing."
        },
    )
}

private const val WEATHER_REFRESH_MILLIS = 15 * 60 * 1_000L
private const val WEATHER_FRESHNESS_CHECK_MILLIS = 60 * 1_000L
private const val WEATHER_REQUEST_TIMEOUT_MILLIS = 55_000L
private const val WRITING_PLACE_RETRY_DELAY_MILLIS = 750L
private const val WRITING_PLACE_RETRY_TIMEOUT_MILLIS = 12_000L

internal fun shouldRetryWritingWeatherPlace(granularity: WeatherPlaceGranularity): Boolean = when (granularity) {
    WeatherPlaceGranularity.Street -> false
    WeatherPlaceGranularity.Area, WeatherPlaceGranularity.City, WeatherPlaceGranularity.Region -> true
    WeatherPlaceGranularity.Unknown -> true
}

internal fun isWritingWeatherRequestCurrent(expectedEpoch: Long, currentEpoch: Long,
    consentEnabled: Boolean, foreground: Boolean): Boolean =
    expectedEpoch == currentEpoch && consentEnabled && foreground

internal fun canApplyWritingWeatherPlaceImprovement(original: WeatherQueryLocation,
    improved: WeatherQueryLocation): Boolean {
    if (improved.placeGranularity != WeatherPlaceGranularity.Street || improved.placeName == null) return false
    if (!isValidWritingWeatherFix(original) || !isValidWritingWeatherFix(improved)) return false
    val distance = writingLocationDistanceMeters(original.latitude, original.longitude,
        improved.latitude, improved.longitude)
    // Both fixes were independently required to be <=100 m. Do not stretch that
    // contract: a 150 m result is never accepted as "within 200 m".
    return distance <= 100.0
}

private fun isValidWritingWeatherFix(location: WeatherQueryLocation): Boolean =
    location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
        location.longitude.isFinite() && location.longitude in -180.0..180.0 &&
        location.accuracyMeters?.let { it.isFinite() && it > 0f && it <= 100f } == true

private fun writingLocationDistanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val x = kotlin.math.sin(dLat / 2).let { it * it } + kotlin.math.cos(Math.toRadians(aLat)) *
        kotlin.math.cos(Math.toRadians(bLat)) * kotlin.math.sin(dLon / 2).let { it * it }
    return radius * 2 * kotlin.math.atan2(kotlin.math.sqrt(x), kotlin.math.sqrt(1 - x))
}
