package com.starglean.app.core.weather

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Current-only HTTPS client, with no location lookup, disk cache, analytics, or automatic retry.
 * Open-Meteo's free endpoint is for this non-commercial prototype; commercial distribution
 * needs a suitable service plan. Sources: https://open-meteo.com/en/docs and /en/terms.
 */
class OpenMeteoWeatherClient(private val clock: Clock = Clock.systemUTC()) {
    suspend fun fetch(location: WeatherQueryLocation): CurrentWritingWeather = withContext(Dispatchers.IO) {
        coroutineScope {
            currentCoroutineContext().ensureActive()
            val connection = openMeteoCurrentUrl(location).openConnection() as HttpURLConnection
            // A child suspended on cancellation disconnects from another IO worker, even while
            // this worker is blocked in connect/read. Normal completion also cancels the watcher.
            val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    connection.disconnect()
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "Starglean-Weather/0.1")
                if (connection.responseCode !in 200..299) {
                    throw WeatherFetchException(WeatherFetchError.ServiceUnavailable)
                }
                if (connection.contentLengthLong > WEATHER_RESPONSE_MAX_BYTES) invalidWeatherResponse()
                val context = currentCoroutineContext()
                val response = connection.inputStream.use { stream ->
                    readBoundedWeatherResponse(stream) { context.ensureActive() }
                }
                context.ensureActive()
                mapOpenMeteoCurrent(parseOpenMeteoCurrent(response), clock.instant())
                    .copy(placeName = location.placeName, placeGranularity = location.placeGranularity,
                        locationAccuracyMeters = location.accuracyMeters)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: WeatherFetchException) {
                currentCoroutineContext().ensureActive()
                throw failure
            } catch (_: SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                throw WeatherFetchException(WeatherFetchError.Timeout)
            } catch (_: IOException) {
                currentCoroutineContext().ensureActive()
                throw WeatherFetchException(WeatherFetchError.Network)
            } finally {
                cancellationWatcher.cancel()
                connection.disconnect()
            }
        }
    }
}

internal const val WEATHER_RESPONSE_MAX_BYTES = 64 * 1024

internal fun readBoundedWeatherResponse(stream: InputStream, ensureActive: () -> Unit = {}): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        ensureActive()
        val read = stream.read(buffer)
        if (read < 0) break
        if (output.size() + read > WEATHER_RESPONSE_MAX_BYTES) invalidWeatherResponse()
        output.write(buffer, 0, read)
    }
    if (output.size() == 0) invalidWeatherResponse()
    return output.toString(Charsets.UTF_8.name())
}

/** Android's bundled JSON reader; field coercion is intentionally avoided. */
internal fun parseOpenMeteoCurrent(response: String): OpenMeteoCurrentPayload {
    try {
        val root = JSONObject(response)
        if (root.opt("error") == true) throw WeatherFetchException(WeatherFetchError.ServiceUnavailable)
        val current = root.optJSONObject("current") ?: invalidWeatherResponse()
        val units = root.optJSONObject("current_units") ?: invalidWeatherResponse()
        return OpenMeteoCurrentPayload(
            weatherCode = current.strictNumber("weather_code"),
            isDay = current.strictNumber("is_day"),
            temperatureC = current.strictNumber("temperature_2m"),
            observedEpochSeconds = current.strictNumber("time"),
            timezone = root.opt("timezone") as? String,
            temperatureUnit = units.opt("temperature_2m") as? String,
            timeUnit = units.opt("time") as? String,
        )
    } catch (_: JSONException) {
        invalidWeatherResponse()
    }
}

private fun JSONObject.strictNumber(name: String): Double? = (opt(name) as? Number)?.toDouble()
