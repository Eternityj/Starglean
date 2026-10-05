package com.starglean.app.core.memory

import android.content.Context
import android.os.SystemClock
import com.starglean.app.core.location.ForegroundLocationException
import com.starglean.app.core.location.ForegroundLocationFailure
import com.starglean.app.core.location.ForegroundLocationRequest
import com.starglean.app.core.location.MEMORY_LOCATION_REQUEST_TIMEOUT_MILLIS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One finite foreground lookup. No logging or persistence. */
class MemoryLocationClient(context: Context) {
    private val request = ForegroundLocationRequest(context)

    fun hasPermission(): Boolean = request.hasPermission()

    /** An explicit UI choice may request precision; this client never upgrades permission itself. */
    fun hasPrecisePermission(): Boolean = request.hasPrecisePermission()

    suspend fun locate(): MemoryLocationFix {
        currentCoroutineContext().ensureActive()
        try {
            return request.locate(MEMORY_LOCATION_REQUEST_TIMEOUT_MILLIS) { location ->
                if (isMemoryLocationReliable(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        hasAccuracy = location.hasAccuracy(),
                        accuracyMeters = location.accuracy,
                        capturedAtEpochMillis = location.time,
                        nowEpochMillis = System.currentTimeMillis(),
                        fixElapsedRealtimeNanos = location.elapsedRealtimeNanos,
                        nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                    )
                ) MemoryLocationFix(location.latitude, location.longitude, location.accuracy, location.time)
                else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ForegroundLocationException) {
            throw MemoryLocationException(when (failure.code) {
                ForegroundLocationFailure.PermissionRequired -> MemoryLocationFailure.PermissionRequired
                ForegroundLocationFailure.ServicesDisabled -> MemoryLocationFailure.ServicesDisabled
                ForegroundLocationFailure.Unavailable -> MemoryLocationFailure.Unavailable
                ForegroundLocationFailure.Timeout -> MemoryLocationFailure.Timeout
                ForegroundLocationFailure.Unreliable -> MemoryLocationFailure.Unreliable
            })
        } catch (_: SecurityException) {
            throw MemoryLocationException(if (hasPermission()) MemoryLocationFailure.Unavailable else MemoryLocationFailure.PermissionRequired)
        } catch (_: RuntimeException) {
            throw MemoryLocationException(MemoryLocationFailure.Unavailable)
        }
    }
}
