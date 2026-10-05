package com.starglean.app.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Phase-1 shake detector. Thresholds are engineering defaults and must be tuned with real-device traces.
 * It prefers linear acceleration, otherwise separates gravity from accelerometer samples with a low-pass filter.
 */
private class ShakeSensorListener(
    private val linearAcceleration: Boolean,
    private val onShake: () -> Unit,
) : SensorEventListener {
    private val gravity = FloatArray(3)
    private val detector = IntentionalShake()
    private var previousNanos = 0L

    override fun onSensorChanged(event: SensorEvent) {
        val x: Float
        val y: Float
        val z: Float
        if (linearAcceleration) {
            x = event.values[0]
            y = event.values[1]
            z = event.values[2]
        } else {
            if (previousNanos == 0L) {
                for (axis in 0..2) gravity[axis] = event.values[axis]
                previousNanos = event.timestamp
                return
            }
            val seconds = ((event.timestamp - previousNanos) / 1_000_000_000.0).coerceIn(0.0, .5)
            val alpha = exp(-seconds / .22).toFloat()
            for (axis in 0..2) {
                gravity[axis] = alpha * gravity[axis] + (1f - alpha) * event.values[axis]
            }
            x = event.values[0] - gravity[0]
            y = event.values[1] - gravity[1]
            z = event.values[2] - gravity[2]
        }

        previousNanos = event.timestamp
        if (detector.add(event.timestamp / 1_000_000L, x, y, z)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
@Composable
fun ShakeEffect(
    enabled: Boolean,
    onAvailability: (Boolean) -> Unit,
    onShake: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnShake by rememberUpdatedState(onShake)
    val currentAvailability by rememberUpdatedState(onAvailability)

    DisposableEffect(context, owner, enabled) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val sensor = linear ?: accelerometer
        currentAvailability(sensor != null)

        var listener: ShakeSensorListener? = null
        fun stop() { listener?.let(manager::unregisterListener); listener = null }
        fun start() {
            if (!enabled || sensor == null || listener != null) return
            val next = ShakeSensorListener(sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
                if (currentEnabled && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) currentOnShake()
            }
            if (manager.registerListener(next, sensor, SensorManager.SENSOR_DELAY_GAME)) listener = next
            else currentAvailability(false)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> start()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            stop()
        }
    }
}
