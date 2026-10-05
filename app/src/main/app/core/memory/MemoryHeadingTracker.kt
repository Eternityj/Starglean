package com.starglean.app.core.memory

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * Foreground, magnetic-north readings only. The owner must start on RESUMED and stop on pause
 * or disposal. stop() immediately invalidates queued callbacks and unregisters every listener;
 * no service, background job or location/network lookup is created here.
 *
 * Uses a north-referenced rotation vector only when a real magnetometer is present. Devices
 * without that vector may use accelerometer + magnetic field. GAME_ROTATION_VECTOR is never
 * used because its reference axis is not north. Display remapping follows the visible top edge.
 */
class MemoryHeadingTracker(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong(0L)
    private val manager: SensorManager? = try {
        appContext.getSystemService(SensorManager::class.java)
    } catch (_: RuntimeException) { null }
    private val display: Display? = resolveDisplay(context)
    private var session: Session? = null

    fun start(onReading: (MemoryHeadingReading) -> Unit) {
        val token = generation.incrementAndGet()
        onMain {
            if (generation.get() != token) return@onMain
            session?.stop()
            Session(token, onReading).also { session = it; it.start() }
        }
    }

    fun stop() {
        val token = generation.incrementAndGet()
        onMain {
            if (generation.get() != token) return@onMain
            session?.stop()
            session = null
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
    }

    private inner class Session(
        private val token: Long,
        private val onReading: (MemoryHeadingReading) -> Unit,
    ) : SensorEventListener {
        private val filter = MemoryHeadingFilter()
        private val gravity = FloatArray(3)
        private val magnetic = FloatArray(3)
        private val rotation = FloatArray(9)
        private val screenRotation = FloatArray(9)
        private val orientation = FloatArray(3)
        private val startedAtNanos = SystemClock.elapsedRealtimeNanos()
        private var stopped = false
        private var usingRotationVector = false
        private var hasMagnetometer = false
        private var magneticTimestamp = 0L
        private var accelerometerTimestamp = 0L
        private var rotationTimestamp = 0L
        private var lastOrientationTimestamp = 0L
        private var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        private var orientationAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        private var fieldStrength = Double.NaN
        private var accelerationUsable = false
        private var estimatedHeadingAccuracy: Double? = null
        private var displayRotation: Int? = null

        private val current: Boolean get() = !stopped && generation.get() == token && session === this

        private val watchdog = object : Runnable {
            override fun run() {
                if (!current) return
                val now = SystemClock.elapsedRealtime()
                if (lastOrientationTimestamp == 0L || now - lastOrientationTimestamp > STALE_STREAM_MILLIS) {
                    invalidate(HeadingReliability.Unavailable, now)
                }
                if (current) handler.postDelayed(this, 1_000L)
            }
        }

        fun start() {
            if (!current) return
            try {
                val sensorManager = manager ?: return fail()
                val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return fail()
                hasMagnetometer = true
                if (!sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI, handler)) return fail()
                val rotationVector = try { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) } catch (_: RuntimeException) { null }
                usingRotationVector = if (rotationVector == null) false else try {
                    sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_UI, handler)
                } catch (_: RuntimeException) {
                    // An OEM's fused sensor may fail even though the raw fallback still works.
                    try { sensorManager.unregisterListener(this, rotationVector) } catch (_: RuntimeException) { }
                    false
                }
                if (!usingRotationVector) {
                    val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return fail()
                    if (!sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI, handler)) return fail()
                }
                // No zero-degree placeholder: the first direction requires actual fresh, usable samples.
                invalidate(HeadingReliability.NeedsCalibration, SystemClock.elapsedRealtime())
                if (current) handler.postDelayed(watchdog, STALE_STREAM_MILLIS)
            } catch (_: RuntimeException) {
                fail()
            }
        }

        fun stop() {
            stopped = true
            handler.removeCallbacks(watchdog)
            try { manager?.unregisterListener(this) } catch (_: RuntimeException) { /* Best-effort OEM cleanup. */ }
            filter.reset()
            gravity.fill(0f)
            magnetic.fill(0f)
            rotation.fill(0f)
            screenRotation.fill(0f)
        }

        private fun fail() {
            invalidate(HeadingReliability.Unavailable, SystemClock.elapsedRealtime())
            stop()
        }

        override fun onSensorChanged(event: SensorEvent) {
            if (!current) return
            try {
                val now = SystemClock.elapsedRealtime()
                val timestamp = event.timestamp / 1_000_000L
                if (event.timestamp < startedAtNanos || timestamp <= 0L ||
                    now - timestamp !in -50L..FRESH_SAMPLE_MILLIS
                ) return
                if (event.values.size < 3 || !event.values[0].isFinite() ||
                    !event.values[1].isFinite() || !event.values[2].isFinite()
                ) {
                    invalidate(HeadingReliability.Unavailable, now)
                    return
                }
                when (event.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        if (timestamp < magneticTimestamp) return
                        copySmoothed(event.values, magnetic, magneticTimestamp == 0L || timestamp - magneticTimestamp > FRESH_SAMPLE_MILLIS)
                        magneticTimestamp = timestamp
                        magneticAccuracy = event.accuracy
                        fieldStrength = vectorMagnitude(event.values)
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        if (!usingRotationVector || timestamp < rotationTimestamp) return
                        if (event.values.size >= 4 && !event.values[3].isFinite()) {
                            invalidate(HeadingReliability.Unavailable, now)
                            return
                        }
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        rotationTimestamp = timestamp
                        orientationAccuracy = event.accuracy
                        // Android uses -1 when no heading-error estimate is provided.
                        estimatedHeadingAccuracy = event.values.getOrNull(4)?.toDouble()?.let { if (it == -1.0) null else it }
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        if (usingRotationVector || timestamp < accelerometerTimestamp) return
                        copySmoothed(event.values, gravity, accelerometerTimestamp == 0L || timestamp - accelerometerTimestamp > FRESH_SAMPLE_MILLIS)
                        accelerometerTimestamp = timestamp
                        orientationAccuracy = event.accuracy
                        accelerationUsable = vectorMagnitude(event.values) in 7.0..13.0
                    }
                    else -> return
                }
                publishOrientation(now)
            } catch (_: RuntimeException) {
                invalidate(HeadingReliability.Unavailable, SystemClock.elapsedRealtime())
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            if (!current) return
            when (sensor?.type) {
                Sensor.TYPE_MAGNETIC_FIELD -> magneticAccuracy = accuracy
                Sensor.TYPE_ROTATION_VECTOR -> if (usingRotationVector) orientationAccuracy = accuracy
                Sensor.TYPE_ACCELEROMETER -> if (!usingRotationVector) orientationAccuracy = accuracy
                else -> return
            }
            if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
                invalidate(HeadingReliability.NeedsCalibration, SystemClock.elapsedRealtime())
            }
        }

        private fun publishOrientation(now: Long) {
            val sourceTimestamp = if (usingRotationVector) rotationTimestamp else accelerometerTimestamp
            if (magneticTimestamp == 0L || sourceTimestamp == 0L) return
            if (now - magneticTimestamp !in -50L..FRESH_SAMPLE_MILLIS || now - sourceTimestamp !in -50L..FRESH_SAMPLE_MILLIS) {
                invalidate(HeadingReliability.Unavailable, now)
                return
            }
            lastOrientationTimestamp = minOf(magneticTimestamp, sourceTimestamp)
            if (!usingRotationVector) {
                if (!accelerationUsable) {
                    invalidate(HeadingReliability.NeedsCalibration, now)
                    return
                }
                if (!SensorManager.getRotationMatrix(rotation, null, gravity, magnetic)) {
                    invalidate(HeadingReliability.NeedsCalibration, now)
                    return
                }
            }
            val currentRotation = try { display?.rotation ?: Surface.ROTATION_0 } catch (_: RuntimeException) { Surface.ROTATION_0 }
            if (displayRotation != currentRotation) {
                displayRotation = currentRotation
                // A rotated screen has a different visible top edge: do not ease through an old frame.
                filter.reset()
            }
            val axisX: Int
            val axisY: Int
            when (currentRotation) {
                Surface.ROTATION_90 -> { axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X }
                Surface.ROTATION_180 -> { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y }
                Surface.ROTATION_270 -> { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X }
                else -> { axisX = SensorManager.AXIS_X; axisY = SensorManager.AXIS_Y }
            }
            if (!SensorManager.remapCoordinateSystem(rotation, axisX, axisY, screenRotation) || screenRotation.any { !it.isFinite() }) {
                invalidate(HeadingReliability.Unavailable, now)
                return
            }
            val quality = memoryHeadingReliability(
                hasMagnetometer = hasMagnetometer,
                hasRecentSample = true,
                sensorAccuracy = orientationAccuracy,
                magneticAccuracy = magneticAccuracy,
                magneticFieldMicrotesla = fieldStrength,
                screenNormalZ = screenRotation[8].toDouble(),
                estimatedHeadingAccuracyRadians = if (usingRotationVector) estimatedHeadingAccuracy else null,
            )
            if (quality != HeadingReliability.Reliable) {
                invalidate(quality, lastOrientationTimestamp)
                return
            }
            SensorManager.getOrientation(screenRotation, orientation)
            val heading = filter.update(Math.toDegrees(orientation[0].toDouble()), lastOrientationTimestamp)
            if (heading == null) {
                invalidate(HeadingReliability.Unavailable, now)
                return
            }
            if (current) onReading(MemoryHeadingReading(heading, HeadingReliability.Reliable, lastOrientationTimestamp))
        }

        private fun invalidate(reliability: HeadingReliability, timestamp: Long) {
            filter.reset()
            if (current) onReading(MemoryHeadingReading(null, reliability, timestamp.coerceAtLeast(0L)))
        }
    }

    private companion object {
        const val FRESH_SAMPLE_MILLIS = 750L
        const val STALE_STREAM_MILLIS = 1_500L

        private fun vectorMagnitude(values: FloatArray): Double = sqrt(
            values[0].toDouble() * values[0] + values[1].toDouble() * values[1] + values[2].toDouble() * values[2],
        )

        private fun copySmoothed(source: FloatArray, destination: FloatArray, first: Boolean) {
            for (axis in 0..2) destination[axis] = if (first) source[axis] else destination[axis] * .80f + source[axis] * .20f
        }

        @Suppress("DEPRECATION")
        private fun resolveDisplay(context: Context): Display? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { context.display?.let { return it } } catch (_: RuntimeException) { /* Non-UI contexts have no display. */ }
            }
            return try { context.getSystemService(WindowManager::class.java)?.defaultDisplay } catch (_: RuntimeException) { null }
        }
    }
}
