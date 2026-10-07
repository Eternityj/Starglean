package com.starglean.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.WeatherKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Five distinct scenes derived only from the author's saved weather. Their materials and
 * motion differ in structure: open light, a cloud ceiling, wet glass, snowfall, layered fog.
 * Weather stays behind the paper. Nothing here reads the device clock or fetches conditions.
 */
@Composable
fun DiaryWeatherBackground(
    snapshot: DiaryWeatherSnapshot,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val elapsed = remember(snapshot) { mutableStateOf(0.0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val grains = remember { atmosphereGrains() }
    val textures = produceState<WeatherAtmosphereTexture?>(null, snapshot.kind, snapshot.daylight) {
        value = null
        if (snapshot.kind != WeatherKind.Unknown && snapshot.kind != WeatherKind.Clear) {
            value = withContext(Dispatchers.Default) {
                createWeatherAtmosphereTexture(snapshot.kind, snapshot.daylight)
            }
        }
    }

    LaunchedEffect(snapshot, lifecycleOwner, motionEnabled) {
        if (!motionEnabled || snapshot.kind == WeatherKind.Unknown) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (isActive) {
                withFrameNanos { now ->
                    if (previous != 0L) {
                        elapsed.value += ((now - previous) / 1_000_000_000.0).coerceIn(0.0, .05)
                    }
                    previous = now
                }
            }
        }
    }

    Box(modifier.fillMaxSize().clipToBounds().drawWithCache {
        val kind = snapshot.kind
        val known = kind != WeatherKind.Unknown
        val night = known && snapshot.daylight == Daylight.Night
        val dusk = known && snapshot.daylight == Daylight.Dusk
        val clear = kind == WeatherKind.Clear
        val overcast = kind == WeatherKind.Cloudy
        val snowy = kind == WeatherKind.Snow
        val foggy = kind == WeatherKind.Fog
        val wet = kind == WeatherKind.Rain
        val sunlight = clear && !night
        val palette = weatherScenePalette(kind, snapshot.daylight)
        val backdrop = Brush.verticalGradient(
            0f to palette.upper,
            .44f to palette.middle,
            1f to palette.lower,
        )
        val edgeShade = Brush.verticalGradient(
            0f to Color(0x0202070E).copy(alpha = if (foggy || overcast) .26f else .09f),
            .16f to Color.Transparent,
            .78f to Color.Transparent,
            1f to Color(0x7302080F),
        )
        val lightColor = when {
            night -> Color(0xFF93B2CC)
            clear && dusk -> Color(0xFFE0AD80)
            clear -> Color(0xFFF1D6A6)
            snowy -> Color(0xFFBBD2DF)
            foggy -> Color(0xFFB8C3CA)
            dusk -> Color(0xFFB59FAD)
            else -> Color(0xFF9CAFBF)
        }
        val ambientRadius = (size.width * .73f).coerceAtLeast(1f)
        val ambientLight = Brush.radialGradient(
            0f to lightColor.copy(alpha = if (sunlight) .29f else .115f),
            .31f to lightColor.copy(alpha = if (sunlight) .14f else .058f),
            .72f to lightColor.copy(alpha = .008f),
            1f to Color.Transparent,
            center = Offset.Zero, radius = ambientRadius,
        )
        val shaftRadius = (size.width * .46f).coerceAtLeast(1f)
        val lightShaft = Brush.radialGradient(
            0f to lightColor.copy(alpha = if (sunlight) .205f else .105f),
            .18f to lightColor.copy(alpha = if (sunlight) .135f else .068f),
            .46f to lightColor.copy(alpha = if (sunlight) .048f else .025f),
            .78f to lightColor.copy(alpha = .004f),
            1f to Color.Transparent,
            center = Offset.Zero, radius = shaftRadius,
        )
        // Each shaft is feathered across AND along its length. No hard triangle/sun disc.
        val shafts = listOf(
            AtmosphereShaft(.085f, .090f, -31f, .16f, 2.0f, .90f),
            AtmosphereShaft(.245f, .055f, -28f, .075f, 1.68f, .72f),
            AtmosphereShaft(-.035f, .220f, -35f, .23f, 1.71f, .58f),
        )
        val rainColor = if (night) Color(0xFFABC7DB) else Color(0xFFAFC9D7)
        val threads = grains.map { grain ->
            val near = grain.depth > .79f
            val middle = grain.depth > .44f
            val length = when {
                near -> 26f + grain.depth * 19f
                middle -> 11f + grain.depth * 14f
                else -> 5f + grain.depth * 13f
            }.dp.toPx()
            val end = Offset(-length * (.14f + grain.depth * .10f), length)
            RainThread(
                end = end,
                stroke = (if (near) .82f else if (middle) .49f else .30f).dp.toPx(),
                opacity = if (near) .34f else if (middle) .23f else .11f,
                speed = .083 + grain.depth * grain.depth * .27,
                paint = Brush.linearGradient(
                    0f to Color.Transparent,
                    .18f to rainColor.copy(alpha = .25f),
                    .71f to rainColor.copy(alpha = .90f),
                    1f to rainColor.copy(alpha = .08f),
                    start = Offset.Zero, end = end,
                ),
            )
        }
        val rivulets = List(12) { index ->
            val grain = grains[index * 11]
            val length = (36f + grain.depth * 94f).dp.toPx()
            val bend = (1f + grain.depth * 1.4f).dp.toPx()
            RainRivulet(
                length = length,
                x = if (index % 2 == 0) .008f + grain.x * .042f else .95f + grain.x * .042f,
                phase = grain.y,
                speed = (5f + grain.depth * 13f).dp.toPx(),
                stroke = (.38f + grain.depth * .44f).dp.toPx(),
                path = Path().apply {
                    moveTo(0f, 0f)
                    cubicTo(bend, length * .19f, -bend, length * .44f, 0f, length * .62f)
                    cubicTo(bend * .6f, length * .79f, -bend * .5f, length * .93f, 0f, length)
                },
                paint = Brush.verticalGradient(
                    0f to Color.Transparent,
                    .34f to rainColor.copy(alpha = .07f),
                    .88f to rainColor.copy(alpha = .36f),
                    1f to rainColor.copy(alpha = .04f),
                    startY = 0f, endY = length,
                ),
            )
        }
        // Fog has a distant landscape to obscure; these are filled, soft tonal silhouettes,
        // not outlines. They live behind the mist and below the opaque reading surface.
        val fogHorizons = List(3) { index ->
            val level = .855f + index * .055f
            Path().apply {
                moveTo(-.12f * size.width, (level + .10f) * size.height)
                cubicTo(.08f * size.width, (level - .055f) * size.height,
                    .22f * size.width, (level + .005f) * size.height, .38f * size.width, (level + .035f) * size.height)
                cubicTo(.60f * size.width, (level + .09f) * size.height,
                    .64f * size.width, (level - .06f) * size.height, .82f * size.width, level * size.height)
                cubicTo(.97f * size.width, (level + .065f) * size.height,
                    1.04f * size.width, (level + .055f) * size.height, 1.12f * size.width, (level + .10f) * size.height)
                lineTo(1.12f * size.width, size.height * 1.2f)
                lineTo(-.12f * size.width, size.height * 1.2f)
                close()
            }
        }
        val fogVeilRadius = (size.width * .88f).coerceAtLeast(1f)
        val fogVeil = Brush.radialGradient(
            0f to Color(0xFFCAD4C9).copy(alpha = if (night) .12f else .20f),
            .45f to Color(0xFFB9C8BF).copy(alpha = if (night) .06f else .10f),
            1f to Color.Transparent,
            center = Offset.Zero, radius = fogVeilRadius,
        )
        val moteColor = if (night) Color(0xFFD0DFEA) else if (clear) Color(0xFFE2C99C) else Color(0xFFC1D0D0)
        val motes = grains.map { grain ->
            val radius = (.19f + grain.depth * .50f).dp.toPx()
            MotePaint(
                radius,
                Brush.radialGradient(
                    0f to moteColor.copy(alpha = .11f),
                    .30f to moteColor.copy(alpha = .036f),
                    1f to Color.Transparent,
                    center = Offset.Zero, radius = radius * 6f,
                ),
            )
        }
        val snowColor = if (night) Color(0xFFCDDDE9) else Color(0xFFE1E8EB)
        val snowflakes = grains.take(154).map { grain ->
            // Distance gives a fine crisp far layer and a few soft, larger foreground flakes.
            // No repeated snowflake glyphs, discs with borders, or star-shaped sparkles.
            val near = grain.depth > .86f
            val radius = (if (near) 2.50f + grain.depth * 2f else .62f + grain.depth * 1.42f).dp.toPx()
            SnowPaint(
                radius = radius,
                speed = .012 + grain.depth * grain.depth * .050,
                paint = Brush.radialGradient(
                    0f to snowColor.copy(alpha = if (near) .50f else .84f),
                    .27f to snowColor.copy(alpha = if (near) .37f else .65f),
                    .62f to snowColor.copy(alpha = if (near) .14f else .23f),
                    1f to Color.Transparent,
                    center = Offset.Zero, radius = radius,
                ),
            )
        }
        val textureSize = IntSize((size.width * 1.22f).toInt().coerceAtLeast(1), (size.height * 1.20f).toInt().coerceAtLeast(1))

        onDrawBehind {
            // State is sampled only while drawing, so the frame clock never recomposes the UI.
            val seconds = elapsed.value
            drawRect(backdrop)
            if (known) {
                if (clear) {
                    translate(size.width * if (night) .89f else .10f, size.height * if (dusk) .20f else -.025f) {
                        scale(1f, 1.24f, pivot = Offset.Zero) {
                            drawCircle(ambientLight, ambientRadius, Offset.Zero)
                        }
                    }
                }
                if (foggy) {
                    fogHorizons.forEachIndexed { index, horizon ->
                        translate(sin(seconds * .018 + index).toFloat() * size.width * .012f, 0f) {
                            drawPath(horizon, Color(0xFF192F33), alpha = .12f + index * .045f)
                        }
                    }
                }

                val material = textures.value
                if (material != null) {
                    val horizontalDrift = when (kind) {
                        WeatherKind.Cloudy -> sin(seconds * .027 + .7).toFloat() * .025f
                        WeatherKind.Fog -> sin(seconds * .060 + .7).toFloat() * .047f
                        else -> 0f
                    }
                    drawCloudMaterial(
                        material.far, textureSize,
                        x = -.11f + horizontalDrift,
                        y = -.10f + if (wet) sin(seconds * .031).toFloat() * .008f else 0f,
                        alpha = if (overcast) 1f else .96f,
                    )
                }

                if (sunlight) {
                    shafts.forEachIndexed { index, shaft ->
                        // Light is still; only minute atmospheric refraction moves it.
                        val breath = sin(seconds * .057 + index).toFloat() * .012f
                        translate((shaft.x + breath) * size.width, shaft.y * size.height) {
                            rotate(shaft.angle, pivot = Offset.Zero) {
                                scale(shaft.width, shaft.length, pivot = Offset.Zero) {
                                    drawCircle(lightShaft, shaftRadius, Offset.Zero, alpha = shaft.alpha)
                                }
                            }
                        }
                    }
                }

                if (material != null) {
                    drawCloudMaterial(
                        material.near, textureSize,
                        x = -.105f + when (kind) {
                            WeatherKind.Cloudy -> sin(seconds * .041 + 2.1).toFloat() * .033f
                            WeatherKind.Fog -> -sin(seconds * .081 + 2.1).toFloat() * .054f
                            else -> 0f
                        },
                        y = -.095f + if (wet) sin(seconds * .047 + 1.3).toFloat() * .012f else 0f,
                        alpha = 1f,
                    )
                }
                if (foggy) {
                    for (layer in 0..2) {
                        val drift = sin(seconds * (.037 + layer * .013) + layer * 2.2).toFloat()
                        translate(size.width * (.28f + layer * .22f + drift * .10f),
                            size.height * (if (layer == 0) .06f else .84f + layer * .045f)) {
                            scale(1.5f, .25f + layer * .05f, pivot = Offset.Zero) {
                                drawCircle(fogVeil, fogVeilRadius, Offset.Zero)
                            }
                        }
                    }
                }

                if (wet) {
                    // One rain scene: a single natural density with near/far depth variation.
                    repeat(206.coerceAtMost(grains.size)) { index ->
                        val grain = grains[index]
                        val thread = threads[index]
                        val speed = thread.speed
                        val y = wrapAtmosphere(grain.y + (seconds * speed).toFloat())
                        val x = wrapAtmosphere(grain.x - (seconds * speed * (.12 + grain.depth * .055)).toFloat())
                        val alpha = thread.opacity * quietReadingArea(x, y)
                        translate(x * size.width, y * size.height) {
                            drawLine(thread.paint, Offset.Zero, thread.end, thread.stroke, StrokeCap.Round, alpha = alpha)
                        }
                    }
                    // Slow curved glass trails separate the foreground from the faster rain.
                    // The wrap starts/ends outside the viewport, so drops do not pop into view.
                    rivulets.forEach { drop ->
                        val cycle = size.height + drop.length * 2f
                        val y = ((drop.phase * cycle + seconds * drop.speed).toFloat() % cycle) - drop.length
                        translate(drop.x * size.width, y) {
                            drawPath(drop.path, Color(0x22030E15), style = Stroke(drop.stroke * 2f, cap = StrokeCap.Round))
                            drawPath(drop.path, drop.paint, style = Stroke(drop.stroke, cap = StrokeCap.Round))
                        }
                    }
                } else if (snowy) {
                    snowflakes.forEachIndexed { index, flake ->
                        val grain = grains[index]
                        val fall = (seconds * flake.speed).toFloat()
                        val drift = sin(seconds * (.15 + grain.depth * .10) + grain.phase).toFloat() * (.017f + grain.depth * .026f)
                        // Most of the canvas is covered by the paper. Keep the same particle
                        // budget, but place 40% in loose edge currents and 20% in each exposed
                        // upper/lower band. The remainder keeps an uninterrupted full sky.
                        // All flakes are still behind the paper and the separate navigation.
                        val region = index % 5
                        val x = when (region) {
                            0, 1 -> {
                                val edge = -.008f + grain.x * .088f
                                (if ((index / 5) % 2 == 0) edge else 1f - edge) + drift * .72f
                            }
                            else -> wrapAtmosphere(grain.x + fall * .13f + drift)
                        }
                        val y = when (region) {
                            2 -> wrapSnowBand(grain.y * .235f + fall, -.065f, .170f)
                            3 -> wrapSnowBand(grain.y * .35f + fall, .78f, 1.13f)
                            else -> wrapAtmosphere(grain.y + fall)
                        }
                        val bandAlpha = when (region) {
                            2 -> ((y + .065f) / .035f).coerceIn(0f, 1f) * ((.170f - y) / .035f).coerceIn(0f, 1f)
                            3 -> ((y - .78f) / .035f).coerceIn(0f, 1f) * ((1.13f - y) / .035f).coerceIn(0f, 1f)
                            else -> 1f
                        }
                        val point = Offset(x * size.width, y * size.height)
                        val opacity = (.66f + grain.depth * .32f) * quietReadingArea(x, y) * bandAlpha
                        translate(point.x, point.y) {
                            // A small aspect variation removes the uniform confetti appearance.
                            rotate(sin(grain.phase).toFloat() * 24f, pivot = Offset.Zero) {
                                scale(.65f + grain.depth * .26f, 1.18f, pivot = Offset.Zero) {
                                    drawCircle(flake.paint, flake.radius, Offset.Zero, alpha = opacity)
                                }
                            }
                        }
                    }
                } else if (clear) {
                    // Only a clear night has stars. Daytime has a few dust specks in sunlight;
                    // overcast, rain, snow and fog must not inherit a generic starfield.
                    repeat(if (night) 82 else 18) { index ->
                        val grain = grains[index]
                        val paint = motes[index]
                        val speed = .0009 + grain.depth * .0018
                        val horizontal = grain.x + (seconds * speed).toFloat()
                        val x = wrapAtmosphere(horizontal)
                        val curl = sin(seconds * .045 + grain.phase).toFloat() * .008f
                        val y = wrapAtmosphere(grain.y + curl)
                        val alpha = (.13f + grain.depth * .28f) * quietReadingArea(x, y) * if (night) .82f else .71f
                        val point = Offset(x * size.width, y * size.height)
                        if (grain.depth > .56f) {
                            translate(point.x, point.y) { drawCircle(paint.glow, paint.radius * 6f, Offset.Zero, alpha = alpha) }
                        }
                        drawCircle(moteColor.copy(alpha = alpha), paint.radius, point)
                    }
                }
            }
            // Unknown remains a neutral, still scene.
            drawRect(edgeShade)
        }
    })
}

private fun DrawScope.drawCloudMaterial(image: ImageBitmap, target: IntSize, x: Float, y: Float, alpha: Float) {
    drawImage(
        image,
        dstOffset = IntOffset((size.width * x).toInt(), (size.height * y).toInt()),
        dstSize = target,
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

internal data class AtmosphereScenePalette(val upper: Color, val middle: Color, val lower: Color)

internal fun weatherScenePalette(kind: WeatherKind, daylight: Daylight): AtmosphereScenePalette {
    return when (kind) {
        // The exposed top and paper margins must read as blue sky even when most of the
        // canvas is covered. Night is independent: no daytime blue wash or golden sun.
        WeatherKind.Clear -> when (daylight) {
            Daylight.Day -> AtmosphereScenePalette(Color(0xFF335F7E), Color(0xFF224F6C), Color(0xFF172F44))
            Daylight.Dusk -> AtmosphereScenePalette(Color(0xFF655265), Color(0xFF35374D), Color(0xFF192637))
            Daylight.Night -> AtmosphereScenePalette(Color(0xFF11253F), Color(0xFF0C1C31), Color(0xFF091525))
        }
        WeatherKind.Cloudy -> when (daylight) {
            Daylight.Day -> AtmosphereScenePalette(Color(0xFF5B606B), Color(0xFF333F4B), Color(0xFF26323F))
            Daylight.Dusk -> AtmosphereScenePalette(Color(0xFF635A65), Color(0xFF413E4B), Color(0xFF292D3B))
            Daylight.Night -> AtmosphereScenePalette(Color(0xFF30323F), Color(0xFF232A38), Color(0xFF171F2E))
        }
        WeatherKind.Rain -> when (daylight) {
            Daylight.Day -> AtmosphereScenePalette(Color(0xFF34484D), Color(0xFF1C343D), Color(0xFF10242D))
            Daylight.Dusk -> AtmosphereScenePalette(Color(0xFF3D3943), Color(0xFF243039), Color(0xFF15222D))
            Daylight.Night -> AtmosphereScenePalette(Color(0xFF172B31), Color(0xFF11242C), Color(0xFF0B171F))
        }
        WeatherKind.Snow -> when (daylight) {
            Daylight.Day -> AtmosphereScenePalette(Color(0xFF566776), Color(0xFF364F60), Color(0xFF223846))
            Daylight.Dusk -> AtmosphereScenePalette(Color(0xFF695E77), Color(0xFF45415E), Color(0xFF28364B))
            Daylight.Night -> AtmosphereScenePalette(Color(0xFF344961), Color(0xFF21384B), Color(0xFF15293A))
        }
        WeatherKind.Fog -> when (daylight) {
            Daylight.Day -> AtmosphereScenePalette(Color(0xFF657A78), Color(0xFF536B68), Color(0xFF3D5454))
            Daylight.Dusk -> AtmosphereScenePalette(Color(0xFF70696E), Color(0xFF5B595E), Color(0xFF363C45))
            Daylight.Night -> AtmosphereScenePalette(Color(0xFF3C4E50), Color(0xFF2D4447), Color(0xFF1D343B))
        }
        WeatherKind.Unknown -> AtmosphereScenePalette(Color(0xFF101923), Color(0xFF0B1420), Color(0xFF080F19))
    }
}

private data class AtmosphereGrain(val x: Float, val y: Float, val depth: Float, val phase: Float)
private data class AtmosphereShaft(val x: Float, val y: Float, val angle: Float, val width: Float, val length: Float, val alpha: Float)
private data class RainThread(val end: Offset, val stroke: Float, val opacity: Float, val speed: Double, val paint: Brush)
private data class RainRivulet(val length: Float, val x: Float, val phase: Float, val speed: Float,
    val stroke: Float, val path: Path, val paint: Brush)
private data class MotePaint(val radius: Float, val glow: Brush)
private data class SnowPaint(val radius: Float, val speed: Double, val paint: Brush)

private fun atmosphereGrains(): List<AtmosphereGrain> {
    val random = Random(20260919)
    return List(256) { AtmosphereGrain(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat() * 6.28f) }
}

private fun wrapAtmosphere(value: Float): Float = ((value + .12f) % 1.24f + 1.24f) % 1.24f - .12f

private fun wrapSnowBand(value: Float, start: Float, end: Float): Float {
    val span = end - start
    return ((value % span) + span) % span + start
}

/** Paper occupies the central two thirds; motion lives mainly beyond its perimeter. */
private fun quietReadingArea(x: Float, y: Float): Float {
    val side = ((abs(x - .5f) - .30f) / .15f).coerceIn(0f, 1f)
    val top = ((.20f - y) / .11f).coerceIn(0f, 1f)
    val bottom = ((y - .78f) / .13f).coerceIn(0f, 1f)
    return .11f + .89f * maxOf(side, top, bottom)
}
