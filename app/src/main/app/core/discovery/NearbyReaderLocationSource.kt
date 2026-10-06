package com.starglean.app.core.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.starglean.app.BuildConfig
import com.starglean.app.core.location.LocationUpdateSource
import com.starglean.app.core.location.LocationUpdateSubscription

/**
 * Explicitly opted-in, RESUMED-only continuous reader sampling. No permission
 * prompt, background service, last-known lookup, network upload or persistence.
 * Uses Google Play fused location when available (it combines GPS, Wi-Fi and cell
 * into one steady stream); otherwise the platform network, fused and GPS providers.
 * The caller must stop on nearby-consent revocation and discard each old fix when
 * the callback has no fix. Every UI consumer still needs NearbyReadingGate timers.
 */
internal class NearbyReaderLocationSource(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var monitor: NearbyReaderLocationMonitor? = null
    private var startAttempt: Any? = null
    private var observedLifecycle: Lifecycle? = null
    private var lifecycleObserver: LifecycleEventObserver? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            val current = monitor ?: return
            current.tick()
            schedule(current)
        }
    }

    /** Calling start again cancels the old session; returning to RESUMED never auto-restarts it. */
    @MainThread
    fun start(
        consentGranted: Boolean,
        lifecycle: Lifecycle,
        purpose: NearbyLocationPurpose = NearbyLocationPurpose.Reading,
        onUpdate: (NearbyReaderLocationUpdate) -> Unit,
    ) {
        requireMainThread()
        val attempt = Any()
        startAttempt = attempt
        stopCurrent()
        // A callback may explicitly stop or start another session while the old one ends.
        if (startAttempt !== attempt) return
        val manager = try { appContext.getSystemService(LocationManager::class.java) } catch (_: RuntimeException) { null }
        lateinit var current: NearbyReaderLocationMonitor
        current = NearbyReaderLocationMonitor(
            sources = when {
                manager == null -> emptyList()
                hasGooglePlayLocation() -> listOf(googleFusedSource())
                else -> listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER)
                    .map { provider -> systemSource(manager, provider) { if (monitor === current) current.tick() } }
            },
            clockNanos = SystemClock::elapsedRealtimeNanos,
            purpose = purpose,
            blocker = {
                val common = when {
                    !consentGranted -> NearbyReaderLocationStatus.ConsentRequired
                    !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) -> NearbyReaderLocationStatus.NotForeground
                    else -> null
                }
                common ?: when {
                    !hasPrecisePermission() -> NearbyReaderLocationStatus.PrecisePermissionRequired
                    manager == null -> NearbyReaderLocationStatus.NoProvider
                    !LocationManagerCompat.isLocationEnabled(manager) -> NearbyReaderLocationStatus.ServicesDisabled
                    else -> null
                }
            },
            onUpdate = { update ->
                if (monitor === current) {
                    if (!current.isRunning) detach(current)
                    try { onUpdate(update) } catch (failure: Throwable) {
                        detach(current)
                        throw failure
                    }
                }
            },
        )
        monitor = current
        val observer = LifecycleEventObserver { _, event ->
            if (monitor === current && event in STOP_EVENTS) {
                current.stop(NearbyReaderLocationStatus.NotForeground)
            }
        }
        observedLifecycle = lifecycle
        lifecycleObserver = observer
        lifecycle.addObserver(observer)
        current.start()
        schedule(current)
    }

    @MainThread
    fun stop() {
        requireMainThread()
        startAttempt = null
        stopCurrent()
    }

    private fun stopCurrent() {
        val current = monitor ?: return
        current.stop()
        detach(current)
    }

    private fun schedule(current: NearbyReaderLocationMonitor) {
        if (monitor !== current || !current.isRunning) return
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, current.nextPollDelayMillis())
    }

    private fun detach(current: NearbyReaderLocationMonitor) {
        if (monitor !== current) return
        handler.removeCallbacks(heartbeat)
        lifecycleObserver?.let { observedLifecycle?.removeObserver(it) }
        lifecycleObserver = null
        observedLifecycle = null
        monitor = null
    }

    @SuppressLint("MissingPermission") // Checked before every registration, callback and heartbeat.
    private fun systemSource(manager: LocationManager, provider: String, availabilityChanged: () -> Unit) =
        LocationUpdateSource<NearbyReaderLocationSample> { callback ->
            if (!LocationManagerCompat.hasProvider(manager, provider) || !manager.isProviderEnabled(provider)) {
                null
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = callback(location.toSample())
                    override fun onProviderEnabled(provider: String) { availabilityChanged() }
                    override fun onProviderDisabled(provider: String) { availabilityChanged() }
                    @Deprecated("Required for pre-Android R provider compatibility")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                try {
                    manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                    LocationUpdateSubscription { manager.removeUpdates(listener) }
                } catch (failure: RuntimeException) {
                    // Some OEMs partially register before throwing.
                    try { manager.removeUpdates(listener) } catch (_: RuntimeException) { /* Redacted. */ }
                    throw failure
                }
            }
        }

    @SuppressLint("MissingPermission") // Checked before every registration, callback and heartbeat.
    private fun googleFusedSource() = LocationUpdateSource<NearbyReaderLocationSample> { callback ->
        val client = LocationServices.getFusedLocationProviderClient(appContext)
        val listener = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) = result.locations.forEach { callback(it.toSample()) }
        }
        client.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build(),
            listener, Looper.getMainLooper())
        LocationUpdateSubscription { client.removeLocationUpdates(listener) }
    }

    private fun hasGooglePlayLocation(): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
    } catch (_: RuntimeException) { false }

    private fun hasPrecisePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requireMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Nearby reader location requires the main thread" }
    }

    private companion object {
        /** Copy only necessary immutable primitives, not extras/provider/addresses. */
        fun Location.toSample(): NearbyReaderLocationSample {
            @Suppress("DEPRECATION")
            val mock = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider
            return NearbyReaderLocationSample(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
                sampledAtElapsedNanos = elapsedRealtimeNanos,
                isMock = mock,
            )
        }

        val STOP_EVENTS = setOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY)
    }
}
