package com.starglean.app.core.weather

import android.content.Context
import android.os.SystemClock
import com.starglean.app.core.location.ForegroundLocationException
import com.starglean.app.core.location.ForegroundLocationFailure
import com.starglean.app.core.location.ForegroundLocationRequest
import com.starglean.app.core.location.WEATHER_LOCATION_REQUEST_TIMEOUT_MILLIS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One finite foreground lookup after explicit precise-weather consent.
 * Fine permission and a <=100m quality result are required. No background permission.
 */
class CoarseWeatherLocationClient(context: Context) {
    private val request = ForegroundLocationRequest(context)
    private val phonePlaceClient = AndroidWeatherPlaceClient(context)

    suspend fun locate(resolvePlaceName: Boolean = false): WeatherQueryLocation {
        currentCoroutineContext().ensureActive()
        try {
            val query = request.locate(WEATHER_LOCATION_REQUEST_TIMEOUT_MILLIS, includePlaceName = resolvePlaceName) { location ->
                if (isWeatherLocationReliable(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        hasAccuracy = location.hasAccuracy(),
                        accuracyMeters = location.accuracy,
                        fixElapsedRealtimeNanos = location.elapsedRealtimeNanos,
                        nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    )
                ) WeatherQueryLocation.fromCoordinates(location.latitude, location.longitude, null,
                    accuracyMeters = location.accuracy)
                else null
            }
            currentCoroutineContext().ensureActive()
            return if (shouldResolveAndroidWeatherPlace(resolvePlaceName)) {
                phonePlaceClient.resolve(query)
            } else query
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ForegroundLocationException) {
            throw WeatherLocationException(when (failure.code) {
                ForegroundLocationFailure.PermissionRequired -> WeatherLocationFailure.PermissionRequired
                ForegroundLocationFailure.ServicesDisabled -> WeatherLocationFailure.ServicesDisabled
                ForegroundLocationFailure.Unavailable -> WeatherLocationFailure.Unavailable
                ForegroundLocationFailure.Timeout -> WeatherLocationFailure.Timeout
                ForegroundLocationFailure.Unreliable -> WeatherLocationFailure.Unreliable
            })
        } catch (_: SecurityException) {
            throw WeatherLocationException(
                if (request.hasPermission()) WeatherLocationFailure.Unavailable else WeatherLocationFailure.PermissionRequired,
            )
        } catch (_: RuntimeException) {
            // Never retain platform exception details, which may contain location data.
            throw WeatherLocationException(WeatherLocationFailure.Unavailable)
        }
    }
}
