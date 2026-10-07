package com.starglean.app.ui.components

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.starglean.app.BuildConfig
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.rememberMotionEnabled
import com.starglean.app.core.memory.*
import com.starglean.app.ui.screens.LocalAppLocationAccess
import kotlinx.coroutines.isActive
import kotlin.math.*

/** Only mounted by own-diary readers; anonymous DiaryPaper never receives location data. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun MemoryCompassCard(
    place: MemoryLocationFix?,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    demoInitially: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val appLocationAccess = LocalAppLocationAccess.current
    val locationAllowed = appLocationAccess.managed && appLocationAccess.allowed
    val currentlyActive by rememberUpdatedState(isActive)
    var demo by remember(place) { mutableStateOf(BuildConfig.DEBUG && demoInitially) }
    val controller = rememberMemoryCompass(place, isActive && !demo && locationAllowed)
    val state = controller.state
    val motion = rememberMotionEnabled()
    LaunchedEffect(locationAllowed) {
        if (!locationAllowed) controller.stop()
    }
    var seconds by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(isActive, state.foreground, demo, state.enabled, motion) {
        if (!isActive || !state.foreground || (!demo && !state.enabled) || !motion) return@LaunchedEffect
        var previous = 0L
        while (isActive) withFrameNanos { now ->
            if (previous != 0L) seconds += ((now - previous) / 1e9).coerceIn(0.0, .05)
            previous = now
        }
    }
    // Public illustrative fixture, never persisted or combined with the device location.
    val sampleOrigin = remember { MemoryLocationFix(-37.8136, 144.9631, 10f, 1_789_517_000_000L) }
    val samplePlace = remember { MemoryLocationFix(-37.8678, 144.8947, 10f, 1_789_517_000_000L) }
    val target = if (demo) samplePlace else place
    val current = if (demo) sampleOrigin else state.location
    val bearing = remember(current, target) {
        if (current != null && target != null) MemoryCompassMath.bearingDegrees(current, target) else null
    }
    val approximate = current != null && target != null &&
        (current.accuracyMeters > 100f || target.accuracyMeters > 100f)
    val demoOffset = if (motion) {
        val cycle = seconds % 13.0
        when {
            cycle < 5.0 -> 65.0 * (1.0 + cos(PI * cycle / 5.0)) / 2.0
            cycle < 8.0 -> 0.0
            else -> -40.0 * sin(PI * (cycle - 8.0) / 5.0)
        }
    } else 0.0
    val heading = if (demo && bearing != null) MemoryCompassMath.normalizeDegrees(bearing - demoOffset) else state.trueHeading
    val delta = if (bearing != null && heading != null) MemoryCompassMath.signedDeltaDegrees(bearing, heading) else null
    val aligned = delta != null && abs(delta) < 10.0
    val strength by animateFloatAsState(
        if (delta != null) MemoryCompassMath.alignmentStrength(delta) else 0f,
        tween(if (motion) 550 else 0), label = "memory stars gather",
    )
    val title = when {
        place == null && !demo -> "Some memories\nhave no location."
        !demo && !state.enabled -> "Turn toward\nthat day."
        bearing != null -> "This memory lies\n${if (approximate) "roughly " else ""}to your ${MemoryCompassMath.directionName(bearing)}."
        current != null && target != null -> "No direction\nfor this memory."
        else -> "Let a star\nlead you back."
    }
    val detail = when {
        demo -> if (aligned) "Demo · Starlight aligned" else "Demo · Simulated turning"
        state.failure != null -> state.failure
        place == null -> "No location was saved for this entry. We will not guess a direction."
        !locationAllowed -> "Enable precise location in Location settings to use the compass."
        !state.enabled -> "Just for you · Uses your current location and heading"
        !state.foreground || !isActive -> "Location and heading are paused"
        state.locating -> "Finding your location…"
        current == null -> "This location is out of date. Refresh to try again."
        bearing == null -> "No direction is shown when you are too close, accuracy is low, or heading is uncertain."
        state.heading?.reliability == HeadingReliability.Unavailable -> "This device has no usable compass sensor."
        state.heading?.reliability == HeadingReliability.TooSteep -> "Hold your phone flatter, with its top edge pointing ahead."
        state.heading?.reliability == HeadingReliability.NeedsCalibration -> "Move away from magnets and slowly trace a figure eight with your phone."
        heading == null -> "Waiting for a reliable heading. Hold your phone a little flatter."
        aligned -> if (approximate) "Approximately this way. Location accuracy is limited." else "This way. A little light remembers."
        else -> "Turn slowly, pointing the top of your phone toward the star."
    }

    Column(modifier.fillMaxWidth()
        .background(Brush.linearGradient(listOf(Color(0xF00B1626), Color(0xF0050B16))), RoundedCornerShape(24.dp))
        .border(.7.dp, StargleanColors.Gold.copy(alpha = .18f), RoundedCornerShape(24.dp))
        .padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("MEMORY COMPASS", color = StargleanColors.Gold, fontSize = 11.sp, letterSpacing = 1.sp,
                modifier = Modifier.weight(1f))
            Text(if (demo) "Demo · Not real" else "Just for you", color = StargleanColors.MutedText, fontSize = 10.sp)
        }
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            MemoryCompassFace(heading, delta, strength, seconds, Modifier.size(124.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, color = StargleanColors.WarmWhite, fontSize = 17.sp, lineHeight = 26.sp,
                    fontFamily = FontFamily.Serif)
                Text(detail, color = if (aligned) StargleanColors.GoldBright else StargleanColors.MutedText,
                    fontSize = 11.sp, lineHeight = 17.sp)
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (BuildConfig.DEBUG && place == null) TextButton(onClick = {
                controller.stop(); demo = !demo; seconds = 0.0
            }) { Text(if (demo) "Exit demo" else "Preview motion", color = StargleanColors.Gold, fontSize = 12.sp) }
            if (place != null && !demo) {
                if (state.enabled && !state.locating) TextButton(onClick = {
                    if (locationAllowed) controller.retry()
                    else {
                        controller.stop()
                        appLocationAccess.openSettings()
                    }
                }) {
                    Text("Refresh location", color = StargleanColors.MutedText, fontSize = 12.sp)
                }
                TextButton(onClick = {
                    if (state.enabled) controller.stop()
                    else if (!locationAllowed) {
                        controller.stop()
                        appLocationAccess.openSettings()
                    } else if (currentlyActive && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        controller.start()
                    }
                }) { Text(if (state.enabled) "Stop compass" else "Start compass", color = StargleanColors.Gold, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun MemoryCompassFace(heading: Double?, delta: Double?, strength: Float, seconds: Double, modifier: Modifier) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER } }
    Canvas(modifier.semantics { contentDescription = "Starlight compass. The top of your phone points forward." }) {
        val radius = size.minDimension * .365f
        val gold = StargleanColors.GoldBright
        val blue = StargleanColors.ColdBlue
        fun polar(degrees: Double, distance: Float) = Offset(
            center.x + sin(Math.toRadians(degrees)).toFloat() * distance,
            center.y - cos(Math.toRadians(degrees)).toFloat() * distance,
        )
        drawCircle(Brush.radialGradient(listOf(blue.copy(alpha = .08f), Color.Transparent), center, radius * 1.3f), radius * 1.3f)
        drawCircle(gold.copy(alpha = .12f), radius, style = Stroke(.6.dp.toPx()))
        drawArc(gold.copy(alpha = .24f), -110f, 40f, false,
            Offset(center.x - radius * 1.12f, center.y - radius * 1.12f),
            androidx.compose.ui.geometry.Size(radius * 2.24f, radius * 2.24f), style = Stroke(.8.dp.toPx(), cap = StrokeCap.Round))
        repeat(48) { index ->
            val direction = index * 7.5 - (heading ?: 0.0)
            val major = index % 12 == 0
            drawLine(gold.copy(alpha = if (major) .46f else .15f), polar(direction, radius - if (major) 5.dp.toPx() else 2.dp.toPx()),
                polar(direction, radius), .6.dp.toPx(), StrokeCap.Round)
        }
        if (heading != null) {
            paint.textSize = 8.dp.toPx()
            paint.color = android.graphics.Color.argb(130, 181, 190, 202)
            listOf("N", "E", "S", "W").forEachIndexed { index, label ->
                val p = polar(index * 90.0 - heading, radius * 1.30f)
                drawContext.canvas.nativeCanvas.drawText(label, p.x, p.y - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        // A quiet marker for the top edge of the phone, not a glaring cross flare.
        val pointer = Path().apply {
            moveTo(center.x, center.y - 7.dp.toPx())
            lineTo(center.x - 3.dp.toPx(), center.y + 3.dp.toPx())
            quadraticTo(center.x, center.y + 1.dp.toPx(), center.x + 3.dp.toPx(), center.y + 3.dp.toPx())
            close()
        }
        drawPath(pointer, gold.copy(alpha = .65f))
        if (delta == null) {
            // Unknown direction has no target star: never imply a fabricated 35° bearing.
            repeat(16) { index ->
                drawCircle(blue, .5.dp.toPx(), polar(index * 22.5, radius * .70f), alpha = .16f)
            }
            return@Canvas
        }
        val star = polar(delta, radius * .76f)
        repeat(32) { index ->
            val angle = index * 137.507 + sin(seconds * .37 + index) * 7.0
            val spread = (7f + (index % 7) * 3f) * (1f - strength * .82f) * density
            val mote = Offset(star.x + cos(Math.toRadians(angle)).toFloat() * spread,
                star.y + sin(Math.toRadians(angle)).toFloat() * spread)
            drawCircle(if (index % 3 == 0) blue else gold, (.35f + index % 3 * .18f).dp.toPx(), mote,
                alpha = .18f + strength * .42f)
        }
        val glowRadius = (8f + strength * 6f).dp.toPx()
        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .12f + .24f * strength),
            Color.Transparent), star, glowRadius), glowRadius, star)
        drawCircle(gold, (1.25f + strength * .85f).dp.toPx(), star, alpha = .65f + .35f * strength)
        drawCircle(Color(0xFFFFF3D7), .65.dp.toPx(), star, alpha = .65f + .35f * strength)
    }
}
