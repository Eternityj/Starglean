package com.starglean.app.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.WeatherKind
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min

/** Small, immutable atmosphere plates. Create off the UI thread and retain for saved weather. */
internal data class WeatherAtmosphereTexture(val far: ImageBitmap, val near: ImageBitmap)

/**
 * Deterministic material, not a photograph or a live weather source. Both plates use straight
 * ARGB input (Bitmap handles premultiplication). There is no opaque sky in either plate.
 * The outer 12% fades away so a caller may overscan by 1.24 and gently translate the images.
 */
internal fun createWeatherAtmosphereTexture(
    kind: WeatherKind,
    daylight: Daylight,
): WeatherAtmosphereTexture {
    val width = 240
    val height = 480
    fun plate(near: Boolean): ImageBitmap {
        val pixels = generateWeatherAtmospherePixels(kind, daylight, near, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }.asImageBitmap()
    }
    return WeatherAtmosphereTexture(far = plate(near = false), near = plate(near = true))
}

/** Pure straight-ARGB material generation: no Android calls, clock, global state or IO. */
internal fun generateWeatherAtmospherePixels(
    kind: WeatherKind,
    daylight: Daylight,
    near: Boolean,
    width: Int = 240,
    height: Int = 480,
): IntArray {
    require(width >= 2 && height >= 2) { "Cloud plate dimensions must be at least 2 × 2" }
    require(width.toLong() * height <= Int.MAX_VALUE) { "Cloud plate dimensions are too large" }
    val pixels = IntArray(width * height)
    if (kind != WeatherKind.Unknown && kind != WeatherKind.Clear) {
        val seed = 0x2c5f19 + weatherMaterialSeed(kind) * 1907 + if (near) 8713 else 0
        val palette = atmospherePalette(kind, daylight)
        val cap = if (near) .36f else .34f
        for (iy in 0 until height) {
            val v = iy.toFloat() / (height - 1)
            for (ix in 0 until width) {
                val u = ix.toFloat() / (width - 1)
                val border = cloudSmooth(0f, .12f, min(min(u, 1f - u), min(v, 1f - v)))
                if (border <= .0001f) continue
                // Coordinates after centered 1.24 overscan. Each weather has its own
                // exposed-margin composition; these are not recoloured copies of a cloud bank.
                val x = (u - .5f) * 1.24f + .5f
                val y = (v - .5f) * 1.24f + .5f
                val material = when (kind) {
                    WeatherKind.Cloudy -> overcastCanopy(x, y, near, seed)
                    WeatherKind.Rain -> rainWaterVeil(x, y, near, seed)
                    WeatherKind.Snow -> snowDistanceHaze(x, y, near, seed)
                    WeatherKind.Fog -> horizontalFog(x, y, near, seed)
                    WeatherKind.Clear, WeatherKind.Unknown -> AtmosphereSample(0f, 0f)
                }
                val alpha = cap * border * material.density.coerceIn(0f, 1f)
                if (alpha < .002f) continue
                val shade = material.shade.coerceIn(0f, 1f)
                val r = cloudChannel(palette.lowR, palette.highR, shade)
                val g = cloudChannel(palette.lowG, palette.highG, shade)
                val b = cloudChannel(palette.lowB, palette.highB, shade)
                pixels[iy * width + ix] = ((alpha * 255f + .5f).toInt() shl 24) or
                    (r shl 16) or (g shl 8) or b
            }
        }
    }
    return pixels
}

private data class AtmosphereSample(val density: Float, val shade: Float)

/** A heavy, uneven ceiling. Crucially, no second cloud bank is mirrored below the paper. */
private fun overcastCanopy(x: Float, y: Float, near: Boolean, seed: Int): AtmosphereSample {
    val ceiling = (if (near) .045f else .075f) +
        cloudValueNoise(x * 3.4f + 1.7f, .4f, seed + 13) * .095f
    val hangingEdge = 1f - cloudSmooth(ceiling - .05f, ceiling + .13f, y)
    if (hangingEdge <= 0f) return AtmosphereSample(0f, 0f)
    val nx = x * (if (near) 5.6f else 4.2f)
    val ny = y * (if (near) 11.5f else 8.6f)
    val warp = cloudFbm(nx * .63f + 2.1f, ny * .52f, seed + 41, 3) - .5f
    val px = nx + warp * 1.3f
    val py = ny + warp * .9f
    val body = cloudFbm(px, py, seed, 5)
    val volume = cloudSmooth(.20f, .76f, body)
    val neighbor = cloudFbm(px - .08f, py - .10f, seed, 4)
    // Neutral lower surfaces and restrained silver rims are specific to overcast weather.
    val rim = ((body - neighbor) * 4.8f).coerceIn(-.18f, .20f)
    val shade = .26f + volume * .35f + rim
    return AtmosphereSample(hangingEdge * (.42f + .58f * volume), shade)
}

/** Long, softly blurred water traces: a wet view, not a field of billowing smoke. */
private fun rainWaterVeil(x: Float, y: Float, near: Boolean, seed: Int): AtmosphereSample {
    val broad = cloudValueNoise(x * (if (near) 10.5f else 7.2f), y * .48f + .7f, seed)
    val fine = cloudValueNoise(x * (if (near) 24f else 17f) + 3.2f, y * .72f, seed + 67)
    val water = cloudSmooth(.20f, .81f, broad * .72f + fine * .28f)
    val wetDistance = .62f + .25f * gaussianBand(y, .03f, .21f) +
        .13f * gaussianBand(y, .94f, .26f)
    return AtmosphereSample((.13f + water * .48f) * wetDistance, .31f + water * .30f)
}

/** Snowflakes provide the detail elsewhere; this material only supplies thin, clean air. */
private fun snowDistanceHaze(x: Float, y: Float, near: Boolean, seed: Int): AtmosphereSample {
    val distance = gaussianBand(y, if (near) 1.04f else .94f, if (near) .16f else .23f)
    val upperAir = gaussianBand(y, -.01f, .14f)
    val softness = cloudValueNoise(x * 1.3f + 2f, y * 1.1f, seed)
    val density = (.025f + distance * .12f + upperAir * .045f) * (.8f + softness * .2f)
    return AtmosphereSample(density, .73f + softness * .08f)
}

/** Broad horizontal strata with almost no horizontal variation or high-frequency detail. */
private fun horizontalFog(x: Float, y: Float, near: Boolean, seed: Int): AtmosphereSample {
    val bend = (cloudValueNoise(x * 1.2f + .7f, .3f, seed) - .5f) * .022f
    val shift = if (near) .055f else -.025f
    val upper = gaussianBand(y + bend, .045f + shift, if (near) .10f else .15f)
    val middle = gaussianBand(y + bend, .46f - shift, .18f)
    val lower = gaussianBand(y + bend, .91f + shift, if (near) .13f else .19f)
    val layers = (upper * .84f + middle * .23f + lower * .91f).coerceIn(0f, 1f)
    return AtmosphereSample(.15f + layers * .69f, .61f + layers * .13f)
}

private fun gaussianBand(y: Float, center: Float, spread: Float): Float {
    val distance = (y - center) / spread
    return exp(-.5f * distance * distance)
}

private data class AtmospherePalette(
    val lowR: Int, val lowG: Int, val lowB: Int,
    val highR: Int, val highG: Int, val highB: Int,
)

private fun atmospherePalette(kind: WeatherKind, daylight: Daylight): AtmospherePalette =
    when (kind) {
        WeatherKind.Cloudy -> when (daylight) {
            Daylight.Day -> AtmospherePalette(43, 47, 51, 155, 160, 164)
            Daylight.Dusk -> AtmospherePalette(45, 42, 46, 148, 135, 136)
            Daylight.Night -> AtmospherePalette(23, 27, 33, 93, 103, 118)
        }
        WeatherKind.Rain -> when (daylight) {
            Daylight.Day -> AtmospherePalette(20, 46, 57, 113, 148, 157)
            Daylight.Dusk -> AtmospherePalette(29, 41, 48, 118, 132, 144)
            Daylight.Night -> AtmospherePalette(14, 30, 44, 78, 112, 131)
        }
        WeatherKind.Snow -> when (daylight) {
            Daylight.Day -> AtmospherePalette(91, 119, 138, 167, 192, 207)
            Daylight.Dusk -> AtmospherePalette(100, 110, 133, 179, 181, 203)
            Daylight.Night -> AtmospherePalette(41, 63, 88, 122, 151, 179)
        }
        WeatherKind.Fog -> when (daylight) {
            Daylight.Day -> AtmospherePalette(76, 86, 90, 155, 165, 166)
            Daylight.Dusk -> AtmospherePalette(78, 78, 88, 150, 144, 157)
            Daylight.Night -> AtmospherePalette(34, 43, 53, 106, 120, 130)
        }
        WeatherKind.Clear, WeatherKind.Unknown -> AtmospherePalette(0, 0, 0, 0, 0, 0)
    }

/** Stable material identities, independent of enum ordering or weather menu grouping. */
internal fun weatherMaterialSeed(kind: WeatherKind): Int = when (kind) {
    WeatherKind.Clear -> 0
    WeatherKind.Cloudy -> 1
    WeatherKind.Rain -> 2
    WeatherKind.Unknown -> 5
    WeatherKind.Snow -> 7
    WeatherKind.Fog -> 8
}

private fun cloudChannel(a: Int, b: Int, amount: Float): Int =
    (a + (b - a) * amount + .5f).toInt().coerceIn(0, 255)

private fun cloudSmooth(low: Float, high: Float, value: Float): Float {
    val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun cloudFbm(x: Float, y: Float, seed: Int, octaves: Int): Float {
    var px = x
    var py = y
    var weight = .54f
    var total = 0f
    var normal = 0f
    repeat(octaves) { octave ->
        total += cloudValueNoise(px, py, seed + octave * 461) * weight
        normal += weight
        // Slight rotation avoids a visible axis-aligned lattice in fine details.
        val nextX = px * 1.928f + py * .424f + 3.73f
        py = py * 1.928f - px * .424f + 1.91f
        px = nextX
        weight *= .51f
    }
    return total / normal
}

private fun cloudValueNoise(x: Float, y: Float, seed: Int): Float {
    val ix = floor(x).toInt()
    val iy = floor(y).toInt()
    val fx = x - ix
    val fy = y - iy
    val sx = fx * fx * fx * (fx * (fx * 6f - 15f) + 10f)
    val sy = fy * fy * fy * (fy * (fy * 6f - 15f) + 10f)
    val top = cloudHash(ix, iy, seed) * (1f - sx) + cloudHash(ix + 1, iy, seed) * sx
    val bottom = cloudHash(ix, iy + 1, seed) * (1f - sx) + cloudHash(ix + 1, iy + 1, seed) * sx
    return top * (1f - sy) + bottom * sy
}

private fun cloudHash(x: Int, y: Int, seed: Int): Float {
    var value = x * 374761393 + y * 668265263 + seed * 144269
    value = (value xor (value ushr 13)) * 1274126177
    value = value xor (value ushr 16)
    return (value ushr 8) / 16777215f
}
