package com.starglean.app.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.memory.MemoryLocationClient
import com.starglean.app.core.sensor.MovementAssessment
import com.starglean.app.core.sensor.MovementSample
import com.starglean.app.core.sensor.MovementWindow
import com.starglean.app.core.sensor.ShakeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** User-started, short, foreground-only checks. No tracking history or fabricated activity. */
@Composable
internal fun DeviceActivityPanel(isActive: Boolean) {
    val context = LocalContext.current
    val appLocationAccess = LocalAppLocationAccess.current
    val locationAllowed = isManagedLocationAllowed(appLocationAccess.managed, appLocationAccess.allowed)
    val currentLocationAllowed by rememberUpdatedState(locationAllowed)
    val owner = LocalLifecycleOwner.current
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val location = remember(context) { MemoryLocationClient(context) }
    var checking by remember { mutableStateOf(false) }
    var movement by remember { mutableStateOf("Not checked") }
    var shakeTesting by remember { mutableStateOf(false) }
    var shakeAvailable by remember { mutableStateOf(false) }
    var shakes by remember { mutableIntStateOf(0) }
    LaunchedEffect(checking, isActive, locationAllowed, owner) {
        if (!checking || !isActive || !locationAllowed) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                withTimeout(35_000) {
                    val window = MovementWindow()
                    var assessment = MovementAssessment.Collecting
                    movement = "Checking fresh locations…"
                    while (assessment == MovementAssessment.Collecting || assessment == MovementAssessment.Uncertain) {
                        val fix = location.locate()
                        // locate() suspends. Never expose a fix after consent, foreground,
                        // or this panel's active state changed while awaiting it.
                        if (!currentLocationAllowed || !isActive ||
                            !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            throw CancellationException("Location check is no longer authorized")
                        }
                        assessment = window.add(MovementSample(fix.latitude, fix.longitude, fix.accuracyMeters, fix.capturedAtEpochMillis), System.currentTimeMillis())
                        movement = when (assessment) {
                            MovementAssessment.Collecting -> "Collecting fresh samples…"
                            MovementAssessment.Uncertain -> "Not enough reliable movement evidence yet"
                            MovementAssessment.FastMoving -> "Fast movement detected · Pause and settle before sharing"
                            MovementAssessment.SlowOrStill -> "Slow or still during this short check"
                        }
                        if (assessment == MovementAssessment.Collecting || assessment == MovementAssessment.Uncertain) delay(3_000)
                    }
                }
            } catch (_: TimeoutCancellationException) { movement = "Movement is uncertain. No activity was assumed." }
            catch (cancelled: CancellationException) { movement = "Check stopped. Nothing is being tracked."; throw cancelled }
            catch (_: Exception) { movement = "Fresh precise location is unavailable. Please retry later." }
            finally { checking = false }
        }
    }
    LaunchedEffect(shakeTesting, isActive) {
        if (shakeTesting && isActive) { delay(15_000); shakeTesting = false }
    }
    ShakeEffect(enabled = shakeTesting && isActive, onAvailability = { shakeAvailable = it }, onShake = { shakes++ })
    Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Only short, foreground checks run here. Motion sensors, location estimates and an activity-recognition service are different capabilities.",
            color = StargleanColors.MutedText, style = MaterialTheme.typography.bodyMedium)
        Text("Movement check", style = MaterialTheme.typography.titleMedium)
        Text("Uses consecutive precise locations on this device for up to 35 seconds. No routes or activity history are saved or sent to a server. Closing this panel or leaving the app stops the check.",
            color = StargleanColors.MutedText, style = MaterialTheme.typography.bodySmall)
        Text(movement, color = StargleanColors.Gold, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            if (checking) checking = false
            else if (locationAllowed && location.hasPrecisePermission()) checking = true
            else appLocationAccess.openSettings()
        }, enabled = isActive) { Text(when {
            checking -> "Stop check"
            !locationAllowed -> "Location settings"
            else -> "Check movement"
        }, color = StargleanColors.Gold) }
        Text("This is a movement estimate, not a walking or driving classification. Local journaling remains available; public posting and discovery are not connected.",
            color = StargleanColors.MutedText, style = MaterialTheme.typography.bodySmall)
        HorizontalDividerQuiet()
        Text("Sensor availability", style = MaterialTheme.typography.titleMedium)
        val types = listOf("Acceleration" to Sensor.TYPE_ACCELEROMETER, "Rotation" to Sensor.TYPE_GYROSCOPE, "Magnetic field" to Sensor.TYPE_MAGNETIC_FIELD)
        types.forEach { (name, type) ->
            val available = manager.getDefaultSensor(type) != null
            Text("$name · ${if (!available) "Not available" else "Available"}",
                color = StargleanColors.MutedText, style = MaterialTheme.typography.bodySmall)
        }
        Text("Shake check · $shakes detected", color = StargleanColors.Gold, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { shakeTesting = !shakeTesting; if (shakeTesting) shakes = 0 }, enabled = isActive && shakeAvailable) {
            Text(if (shakeTesting) "Stop sensor check" else "Test sensor for 15 seconds", color = StargleanColors.Gold)
        }
        Text("This checks the detector only; it never opens a diary or pretends that virtual sensor input is real activity.",
            color = StargleanColors.MutedText, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun HorizontalDividerQuiet() {
    androidx.compose.material3.HorizontalDivider(color = StargleanColors.Gold.copy(alpha = .12f))
}
