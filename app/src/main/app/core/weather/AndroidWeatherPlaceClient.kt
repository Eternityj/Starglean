package com.starglean.app.core.weather

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.util.Locale
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal const val ANDROID_WEATHER_PLACE_TIMEOUT_MILLIS = 3_000L

/**
 * Optional, explicitly authorized phone-only reverse geocoding of the original
 * qualified fix. The system provider may send these coordinates over its network.
 * No address, coordinate or backend exception is logged or persisted here.
 *
 * Android exposes no cancellation API for either Geocoder overload. Cancellation
 * drops our receiver immediately; an outstanding system call keeps the shared
 * single-flight slot until it actually returns. A stuck provider therefore skips
 * later optional names, rather than accumulating calls or delaying weather.
 */
internal class AndroidWeatherPlaceClient(context: Context) {
    private val appContext = context.applicationContext

    suspend fun resolve(query: WeatherQueryLocation): WeatherQueryLocation {
        currentCoroutineContext().ensureActive()
        return withOptionalAndroidWeatherPlace(query, requests) { latitude, longitude, receiver ->
            // Also keep constructor, isPresent and async registration off the UI
            // thread: an OEM system service can block during any binder operation.
            executor.execute {
                if (!receiver.isActive) {
                    receiver.finish(null)
                    return@execute
                }
                try {
                    if (!Geocoder.isPresent()) {
                        receiver.finish(null)
                        return@execute
                    }
                    val geocoder = Geocoder(appContext, Locale.ENGLISH)
                    if (!receiver.isActive) {
                        receiver.finish(null)
                        return@execute
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        Api33.lookup(geocoder, latitude, longitude, query.accuracyMeters, receiver)
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocation(latitude, longitude, ANDROID_WEATHER_PLACE_MAX_RESULTS)
                        receiver.finish(selectAndroidWeatherPlace(latitude, longitude, query.accuracyMeters,
                            addresses.orEmpty().map { it.weatherPlaceCandidate() }))
                    }
                } catch (_: IOException) {
                    receiver.finish(null)
                } catch (_: RuntimeException) {
                    // Never expose provider messages: they may contain the query.
                    receiver.finish(null)
                }
            }
        }
    }

    private companion object {
        val requests = WeatherPlaceRequestGate()
        // One worker, no pending queue. In API 26–32 a blocked platform call must
        // not hold a coroutine dispatcher or generate additional blocked workers.
        val executor = ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, SynchronousQueue(),
            { runnable -> Thread(runnable, "weather-place").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    @RequiresApi(33)
    private object Api33 {
        fun lookup(geocoder: Geocoder, latitude: Double, longitude: Double, accuracyMeters: Float?, receiver: WeatherPlaceReceiver) {
            geocoder.getFromLocation(latitude, longitude, ANDROID_WEATHER_PLACE_MAX_RESULTS, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    val place = if (receiver.isActive) {
                        try {
                            selectAndroidWeatherPlace(latitude, longitude, accuracyMeters,
                                addresses.map { it.weatherPlaceCandidate() })
                        } catch (_: RuntimeException) {
                            null
                        }
                    } else null
                    receiver.finish(place)
                }

                override fun onError(errorMessage: String?) {
                    receiver.finish(null)
                }
            })
        }
    }
}

/** Structured street/area fields only, with conservative door filtering; providers vary. */
private fun Address.weatherPlaceCandidate(): AndroidWeatherPlaceCandidate = AndroidWeatherPlaceCandidate(
    place = androidWeatherPlace(
    locality = locality,
    subLocality = subLocality,
    adminArea = adminArea,
    countryName = countryName,
    thoroughfare = thoroughfare,
    subThoroughfare = subThoroughfare,
    ),
    areaPlace = androidWeatherPlace(locality, subLocality, adminArea, countryName),
    latitude = if (hasLatitude()) latitude else null,
    longitude = if (hasLongitude()) longitude else null,
)

internal data class WeatherPlace(val name: String, val granularity: WeatherPlaceGranularity)

internal data class AndroidWeatherPlaceCandidate(
    val place: WeatherPlace?,
    val areaPlace: WeatherPlace? = place?.takeUnless { it.granularity == WeatherPlaceGranularity.Street },
    val latitude: Double? = null,
    val longitude: Double? = null,
)

private const val ANDROID_WEATHER_PLACE_MAX_RESULTS = 5
private const val ANDROID_WEATHER_STREET_MAX_DISTANCE_METERS = 100.0

internal fun selectAndroidWeatherPlace(latitude: Double, longitude: Double, accuracyMeters: Float?,
    candidates: List<AndroidWeatherPlaceCandidate>): WeatherPlace? {
    if (candidates.isEmpty()) return null
    val evaluated = candidates.mapIndexedNotNull { index, candidate ->
        val place = candidate.place ?: return@mapIndexedNotNull null
        val hasNeitherCoordinate = candidate.latitude == null && candidate.longitude == null
        val hasValidCoordinatePair = candidate.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            candidate.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true
        // Missing coordinates are a documented provider possibility. Partial,
        // non-finite or out-of-range metadata is malformed and fails closed.
        if (!hasNeitherCoordinate && !hasValidCoordinatePair) return@mapIndexedNotNull null
        val distance = if (hasValidCoordinatePair)
            haversineMeters(latitude, longitude, checkNotNull(candidate.latitude), checkNotNull(candidate.longitude)) else null
        Triple(index, place, distance)
    }
    // Candidate coordinates describe the provider's returned feature, not a new
    // device fix. Respect the fix accuracy but never expand street matching past 100 m.
    val streetDistanceLimit = accuracyMeters?.takeIf { it.isFinite() && it > 0f }
        ?.toDouble()?.coerceIn(50.0, ANDROID_WEATHER_STREET_MAX_DISTANCE_METERS)
        ?: ANDROID_WEATHER_STREET_MAX_DISTANCE_METERS
    val street = evaluated.filter { (index, place, distance) ->
        place.granularity == WeatherPlaceGranularity.Street &&
            (distance?.let { it <= streetDistanceLimit } ?: (index == 0))
    }.minWithOrNull(compareBy<Triple<Int, WeatherPlace, Double?>> { it.third ?: Double.MAX_VALUE }
        .thenBy { it.first })
    return street?.second ?: evaluated.asSequence().map { it.first }
        .mapNotNull { candidates[it].areaPlace }.firstOrNull()
}

private fun haversineMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val radius = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val x = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(aLat)) * kotlin.math.cos(Math.toRadians(bLat)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return radius * 2 * kotlin.math.atan2(kotlin.math.sqrt(x), kotlin.math.sqrt(1 - x))
}

internal fun androidWeatherPlace(
    locality: String?, subLocality: String?, adminArea: String?, countryName: String?,
    thoroughfare: String? = null, subThoroughfare: String? = null,
): WeatherPlace? {
    val street = weatherStreetWithoutDoor(thoroughfare, subThoroughfare)
    val name = androidWeatherPlaceName(locality, subLocality, adminArea, countryName, thoroughfare, subThoroughfare)
        ?: return null
    val granularity = when {
        street != null -> WeatherPlaceGranularity.Street
        normalizeWeatherPlaceName(subLocality) != null -> WeatherPlaceGranularity.Area
        normalizeWeatherPlaceName(locality) != null -> WeatherPlaceGranularity.City
        else -> WeatherPlaceGranularity.Region
    }
    return WeatherPlace(name, granularity)
}

internal fun androidWeatherPlaceName(
    locality: String?,
    subLocality: String?,
    adminArea: String?,
    countryName: String?,
    thoroughfare: String? = null,
    subThoroughfare: String? = null,
): String? {
    val primary = listOfNotNull(weatherStreetWithoutDoor(thoroughfare, subThoroughfare),
        normalizeWeatherPlaceName(subLocality), normalizeWeatherPlaceName(locality))
    val parts = primary.ifEmpty {
        listOfNotNull(normalizeWeatherPlaceName(adminArea), normalizeWeatherPlaceName(countryName))
    }.distinctBy { it.lowercase(Locale.ROOT) }
    // Preserve whole names by priority. Never turn a real long street into a made-up prefix.
    val accepted = mutableListOf<String>()
    parts.forEach { part ->
        if (normalizeWeatherPlaceName((accepted + part).joinToString(" · ")) != null) accepted += part
    }
    return normalizeWeatherPlaceName(accepted.joinToString(" · "))
}

/** Recognisably ambiguous provider output falls back to an area rather than guessing a street. */
internal fun weatherStreetWithoutDoor(thoroughfare: String?, subThoroughfare: String?): String? {
    var street = normalizeWeatherPlaceName(thoroughfare) ?: return null
    val house = subThoroughfare?.trim()?.takeIf { it.isNotEmpty() }
    if (house != null) {
        // A malformed house field must not erase a separately supplied, clean street.
        // It is only used when it exactly occupies a boundary of the thoroughfare.
        if (house.length <= 32 && house.matches(Regex("[\\p{L}\\p{N} .–—/-]+")) &&
            street.contains(house, ignoreCase = true)) {
            val escaped = Regex.escape(house)
            street = Regex("^$escaped\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(street)?.groupValues?.get(1)
                ?: Regex("^(.+)\\s+$escaped$", RegexOption.IGNORE_CASE).matchEntire(street)?.groupValues?.get(1)
                ?: return null
            street = normalizeWeatherPlaceName(street) ?: return null
            if (street.contains(house, ignoreCase = true)) return null
        }
    }
    // Do not accept unit/address separators or a remaining standalone house-number pattern.
    // Numbered streets such as "5th Avenue" are allowed; ambiguous "Route 12" falls back.
    val door = "[\\p{N}]+[\\p{L}]?(?:[-–—][\\p{N}]+[\\p{L}]?)?"
    if (street.contains(Regex("[#,;/]")) ||
        Regex("^$door(?:\\s|$)").containsMatchIn(street) ||
        Regex("(?:^|\\s)$door$").containsMatchIn(street)) return null
    if (street.lowercase(Locale.ROOT) in setOf("street", "st", "road", "rd", "avenue", "ave", "boulevard", "lane", "drive", "highway", "route")) return null
    return street
}

internal fun shouldResolveAndroidWeatherPlace(resolvePlaceName: Boolean): Boolean =
    resolvePlaceName

/** Pure coordinator, with an injectable callback launcher for cancellation tests. */
internal suspend fun withOptionalAndroidWeatherPlace(
    query: WeatherQueryLocation,
    gate: WeatherPlaceRequestGate,
    timeoutMillis: Long = ANDROID_WEATHER_PLACE_TIMEOUT_MILLIS,
    start: (Double, Double, WeatherPlaceReceiver) -> Unit,
): WeatherQueryLocation {
    currentCoroutineContext().ensureActive()
    val place = withTimeoutOrNull(timeoutMillis) {
        suspendCancellableCoroutine<WeatherPlace?> { continuation ->
            val receiver = gate.acquire { continuation.resume(it) }
            if (receiver == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation { receiver.detach() }
            if (!receiver.isActive) {
                receiver.finish(null)
                return@suspendCancellableCoroutine
            }
            try {
                start(query.latitude, query.longitude, receiver)
            } catch (cancelled: CancellationException) {
                receiver.detach()
                receiver.finish(null)
                throw cancelled
            } catch (_: Exception) {
                // Includes a rejected executor submission; keep the valid fix.
                receiver.finish(null)
            }
        }
    }
    currentCoroutineContext().ensureActive()
    return if (place == null) query else WeatherQueryLocation.fromCoordinates(query.latitude, query.longitude,
        place.name, query.accuracyMeters, place.granularity)
}

/** Process-wide in production. A timed-out caller never unlocks an active OS call. */
internal class WeatherPlaceRequestGate {
    private val occupied = AtomicBoolean(false)

    fun acquire(onComplete: (WeatherPlace?) -> Unit): WeatherPlaceReceiver? {
        if (!occupied.compareAndSet(false, true)) return null
        return WeatherPlaceReceiver(onComplete) { occupied.set(false) }
    }
}

internal class WeatherPlaceReceiver(onComplete: (WeatherPlace?) -> Unit, private val release: () -> Unit) {
    private val callback = AtomicReference<((WeatherPlace?) -> Unit)?>(onComplete)
    private val completed = AtomicBoolean(false)
    val isActive: Boolean get() = !completed.get() && callback.get() != null

    fun detach() {
        callback.set(null)
    }

    fun finish(value: Any?) {
        if (!completed.compareAndSet(false, true)) return
        val deliver = callback.getAndSet(null)
        release()
        val place = when (value) {
            is WeatherPlace -> value
            is String -> WeatherPlace(value, WeatherPlaceGranularity.Unknown)
            else -> null
        }
        deliver?.invoke(place?.let { normalized ->
            normalizeWeatherPlaceName(normalized.name)?.let { WeatherPlace(it, normalized.granularity) }
        })
    }
}
