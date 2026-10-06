package com.starglean.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.BuildConfig
import com.starglean.app.core.discovery.*

internal fun isManagedLocationAllowed(managed: Boolean, allowed: Boolean): Boolean = managed && allowed

internal fun shouldStartManagedNearby(
    foreground: Boolean,
    accessAllowed: Boolean,
    precisePermission: Boolean,
    sessionStopped: Boolean,
    alreadyStarted: Boolean,
): Boolean = foreground && accessAllowed && precisePermission && !sessionStopped && !alreadyStarted

/** Evidence consumed by discovery; implementations never imply permission to keep location running in the background. */
internal interface DiscoveryLocationState {
    val ready: Boolean
    val foreground: Boolean
    val optedIn: Boolean
    val precisePermission: Boolean
    val networkAvailable: Boolean
    val fix: NearbyReaderLocationFix?
    val movement: NearbyMovementStatus
}

/** Shared foreground-only evidence for publishing and discovery. Never persists a route. */
internal class ForegroundNearbyState(private val context: Context, private val lifecycle: Lifecycle,
    private val purpose: NearbyLocationPurpose = NearbyLocationPurpose.Reading) : DiscoveryLocationState {
    private val source = NearbyReaderLocationSource(context)
    private var movementGate = NearbyMovementGate()
    private var managedAccessAllowed = false
    private var sessionStopped = false
    override var optedIn by mutableStateOf(false); private set
    // The page's explicit isActive + lifecycle effect is the only foreground authority.
    // Starting from false prevents a provider from briefly starting during composition.
    override var foreground by mutableStateOf(false); private set
    override var networkAvailable by mutableStateOf(false); private set
    var status by mutableStateOf(NearbyReaderLocationStatus.Stopped); private set
    var issue by mutableStateOf<NearbyLocationIssue?>(null); private set
    override var fix by mutableStateOf<NearbyReaderLocationFix?>(null); private set
    override var movement by mutableStateOf(NearbyMovementStatus.Collecting); private set
    var epoch by mutableLongStateOf(0); private set
    override val ready: Boolean get() = optedIn && foreground && networkAvailable &&
        movement.allowsNearbyUse && fix?.quality?.isQualifiedAt(SystemClock.elapsedRealtime()) == true
    override val precisePermission: Boolean get() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start() {
        sessionStopped = false
        if (!shouldStartManagedNearby(foreground, managedAccessAllowed, precisePermission, sessionStopped, optedIn)) return
        startSource()
    }

    private fun startSource() {
        if (optedIn) return
        epoch++
        optedIn = true
        movementGate = NearbyMovementGate()
        source.start(true, lifecycle, purpose) { update ->
            fix = update.fix
            status = update.status
            issue = update.issue
            movement = update.fix?.let { movementGate.update(it, SystemClock.elapsedRealtimeNanos()) }
                ?: movementGate.unavailable(SystemClock.elapsedRealtimeNanos())
        }
    }
    fun stop() {
        sessionStopped = true
        stopSource()
    }
    private fun stopSource() {
        epoch++
        optedIn = false
        fix = null
        issue = null
        movement = movementGate.reset()
        source.stop()
        status = NearbyReaderLocationStatus.Stopped
    }
    fun updateForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (!value) {
            sessionStopped = false
            stopSource()
        } else if (shouldStartManagedNearby(foreground, managedAccessAllowed, precisePermission, sessionStopped, optedIn)) {
            startSource()
        }
    }
    fun updateManagedAccess(allowed: Boolean) {
        if (managedAccessAllowed == allowed) return
        managedAccessAllowed = allowed
        if (!allowed) {
            sessionStopped = false
            stopSource()
        } else if (shouldStartManagedNearby(foreground, managedAccessAllowed, precisePermission, sessionStopped, optedIn)) {
            startSource()
        }
    }
    fun setNetwork(value: Boolean) { networkAvailable = value }
    fun tick() { movement = movementGate.statusAt(SystemClock.elapsedRealtimeNanos()) }

    val message: String get() = when {
        !foreground -> "Paused while this page is not active."
        !managedAccessAllowed || !precisePermission -> "Location access is off. You can manage it in Location settings."
        !optedIn -> "Location is paused for this session."
        !networkAvailable -> "No internet connection. Your draft and saved memories are safe."
        status == NearbyReaderLocationStatus.ServicesDisabled -> "Turn on location services, then try again."
        status == NearbyReaderLocationStatus.UnreliableFix -> unreliableLocationMessage(issue)
        status == NearbyReaderLocationStatus.FixExpired -> "Waiting for a fresh location."
        status == NearbyReaderLocationStatus.NoProvider -> "No location provider is available."
        status == NearbyReaderLocationStatus.ClockInvalid -> "The device location clock changed. Please retry location."
        movement == NearbyMovementStatus.FastMovement -> "Please stop safely before continuing."
        fix == null -> "Finding your location… It is faster outdoors or near a window."
        !movement.allowsNearbyUse -> "Checking your location again…"
        else -> "Location ready · about ±${accuracyMeters()} m"
    }
}

private fun ForegroundNearbyState.accuracyMeters(): Int =
    kotlin.math.ceil(fix?.quality?.horizontalAccuracyMeters ?: 0.0).toInt()

@Composable
internal fun rememberForegroundNearbyState(isActive: Boolean,
    purpose: NearbyLocationPurpose = NearbyLocationPurpose.Reading): ForegroundNearbyState {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val appLocationAccess = LocalAppLocationAccess.current
    val state = remember(context, owner, purpose) { ForegroundNearbyState(context, owner.lifecycle, purpose) }
    SideEffect { state.updateManagedAccess(isManagedLocationAllowed(appLocationAccess.managed, appLocationAccess.allowed)) }
    DisposableEffect(owner, isActive) {
        state.updateForeground(isActive && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        val observer = LifecycleEventObserver { _, _ ->
            state.updateForeground(isActive && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); state.updateForeground(false) }
    }
    DisposableEffect(context) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var alive = true
        fun refresh() {
            handler.post {
                if (alive) {
                    val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
                    state.setNetwork(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                }
            }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        }
        var registered = false
        try { manager?.registerDefaultNetworkCallback(callback); registered = manager != null; refresh() }
        catch (_: RuntimeException) { state.setNetwork(false) }
        onDispose {
            alive = false
            if (registered) runCatching { manager?.unregisterNetworkCallback(callback) }
        }
    }
    LaunchedEffect(state, isActive, state.foreground) {
        while (isActive && state.foreground) { state.tick(); kotlinx.coroutines.delay(200) }
    }
    return state
}
