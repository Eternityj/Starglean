package com.starglean.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val WEATHER_LOCATION_REQUEST_TIMEOUT_MILLIS = 30_000L
internal const val MEMORY_LOCATION_REQUEST_TIMEOUT_MILLIS = 45_000L

/**
 * A caller-owned, finite foreground lookup, not a background tracking service.
 * Uses only Android system providers (the "fused" name is not a GMS/HMS API).
 * No last-known cache, logs, persistence or permission prompts.
 */
internal class ForegroundLocationRequest(context: Context) {
    private val appContext = context.applicationContext

    fun hasPermission(): Boolean = hasPrecisePermission()

    fun hasPrecisePermission(): Boolean = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

    suspend fun <R : Any> locate(timeoutMillis: Long, includePlaceName: Boolean = false, qualify: (Location) -> R?): R =
        withContext(Dispatchers.Main.immediate) {
            val manager = appContext.getSystemService(LocationManager::class.java)
                ?: throw ForegroundLocationException(ForegroundLocationFailure.Unavailable)
            val checkAvailability = {
                if (!hasPermission()) throw ForegroundLocationException(ForegroundLocationFailure.PermissionRequired)
                if (!LocationManagerCompat.isLocationEnabled(manager)) {
                    throw ForegroundLocationException(ForegroundLocationFailure.ServicesDisabled)
                }
            }
            awaitQualifiedLocation(
                sources = listOf(LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER)
                    .map { provider -> systemSource(manager, provider) },
                timeoutMillis = timeoutMillis,
                checkAvailability = checkAvailability,
                qualify = qualify,
            )
        }

    @SuppressLint("MissingPermission") // Checked before registration, while waiting and before returning.
    private fun systemSource(manager: LocationManager, provider: String) = LocationUpdateSource<Location> { onLocation ->
        if (!LocationManagerCompat.hasProvider(manager, provider) || !manager.isProviderEnabled(provider)) {
            null
        } else {
            // Each provider owns a different listener: registrations cannot replace
            // one another, and a blocked network source does not delay GPS startup.
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) { onLocation(Location(location)) }
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Required for pre-Android R provider compatibility")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            try {
                // Keep listening for a fresh/accurate fix if the first one fails
                // validation. The shared request's finally always removes updates.
                manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper())
                LocationUpdateSubscription { manager.removeUpdates(listener) }
            } catch (failure: RuntimeException) {
                // Some OEMs may register before throwing. Also clean that partial
                // registration; the coordinator will still try the other sources.
                try { manager.removeUpdates(listener) } catch (_: RuntimeException) { /* No provider details retained. */ }
                throw failure
            }
        }
    }

    private fun hasPermission(permission: String): Boolean = try {
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) { false }
}
