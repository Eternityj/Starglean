package com.starglean.app.ui.components

import com.starglean.app.core.model.Daylight
import androidx.compose.ui.graphics.Color
import com.starglean.app.core.model.WeatherKind
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tiny CPU-only plates; no Bitmap, emulator, Android runtime or weather source is needed. */
class WeatherAtmosphereTextureTest {
    @Test
    fun identicalInputsProduceTheSamePixelsWithoutSharedMutableArrays() {
        for (kind in WeatherKind.entries) {
            val first = sample(kind)
            val second = sample(kind)
            assertArrayEquals("Deterministic $kind material", first, second)
            assertFalse(first === second)
            first[0] = 123
            assertEquals(0, second[0])
        }
    }

    @Test
    fun clearAirAndUnknownHaveNoCloudPlateForEveryDaylightAndLayer() {
        for (kind in listOf(WeatherKind.Clear, WeatherKind.Unknown)) {
            for (daylight in Daylight.entries) {
                for (near in listOf(false, true)) {
                    val pixels = sample(kind, daylight, near)
                    assertTrue("$kind does not inherit another weather's cloud wall", pixels.all { it == 0 })
                }
            }
        }
    }

    @Test
    fun argbChannelsAreBoundedAndNeitherLayerCanBecomeAnOpaqueCloudWall() {
        for (kind in texturedWeather) {
            for (daylight in Daylight.entries) {
                for (near in listOf(false, true)) {
                    val pixels = sample(kind, daylight, near)
                    assertEquals(24 * 48, pixels.size)
                    assertTrue("Known $kind/$daylight/$near has some material", pixels.any { it != 0 })
                    pixels.forEach { pixel ->
                        val alpha = pixel ushr 24
                        assertTrue("Alpha remains within its 8-bit rounded cap", alpha in 0..(if (near) 92 else 87))
                        if (alpha == 0) {
                            assertEquals("Transparent pixels carry no undefined colour", 0, pixel)
                        } else {
                            val red = (pixel ushr 16) and 255
                            val green = (pixel ushr 8) and 255
                            val blue = pixel and 255
                            assertTrue(red in 14..179)
                            assertTrue(green in 27..192)
                            assertTrue(blue in 33..207)
                            assertTrue((red / 255.0).isFinite() && (green / 255.0).isFinite() && (blue / 255.0).isFinite())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun texturedWeatherKindsHaveDifferentGeneratedMaterial() {
        val samples = texturedWeather.associateWith { sample(it) }
        for ((kind, pixels) in samples) {
            for ((otherKind, otherPixels) in samples) {
                if (kind == otherKind) continue
                assertFalse("$kind must not be only a label on $otherKind", pixels.contentEquals(otherPixels))
            }
        }
    }

    @Test
    fun daylightChangesLightingWithoutInventingADifferentWeatherDensity() {
        for (kind in texturedWeather) {
            val day = sample(kind, Daylight.Day)
            val dusk = sample(kind, Daylight.Dusk)
            val night = sample(kind, Daylight.Night)
            assertFalse(day.contentEquals(dusk))
            assertFalse(day.contentEquals(night))
            assertFalse(dusk.contentEquals(night))
            assertArrayEquals(day.map { it ushr 24 }.toIntArray(), night.map { it ushr 24 }.toIntArray())
        }
    }

    @Test
    fun nearAndFarLayersHaveDifferentDensityDetail() {
        val far = sample(WeatherKind.Rain, near = false)
        val near = sample(WeatherKind.Rain, near = true)
        assertFalse(far.contentEquals(near))
        assertFalse(far.map { it ushr 24 } == near.map { it ushr 24 })
    }

    @Test
    fun outerPixelBorderAlwaysFadesFullyAway() {
        for (kind in WeatherKind.entries) {
            val pixels = sample(kind)
            repeat(24) { x ->
                assertEquals(0, pixels[x])
                assertEquals(0, pixels[47 * 24 + x])
            }
            repeat(48) { y ->
                assertEquals(0, pixels[y * 24])
                assertEquals(0, pixels[y * 24 + 23])
            }
        }
    }

    @Test
    fun regroupingTheWeatherMenuKeepsTheExistingMaterialIdentities() {
        val expectedSeeds = mapOf(
            WeatherKind.Clear to 0,
            WeatherKind.Cloudy to 1,
            WeatherKind.Rain to 2,
            WeatherKind.Unknown to 5,
            WeatherKind.Snow to 7,
            WeatherKind.Fog to 8,
        )
        assertEquals(WeatherKind.entries.toSet(), expectedSeeds.keys)
        expectedSeeds.forEach { (kind, seed) -> assertEquals(seed, weatherMaterialSeed(kind)) }
    }

    @Test
    fun sunnyDayIsChromaticallyDistinctFromNeutralOvercastAtTheExposedMargins() {
        val sunny = weatherScenePalette(WeatherKind.Clear, Daylight.Day)
        val overcast = weatherScenePalette(WeatherKind.Cloudy, Daylight.Day)
        val sunnyBlueBias = sunny.upper.blue - sunny.upper.red
        val overcastBlueBias = overcast.upper.blue - overcast.upper.red
        assertTrue("Clear air has a much stronger blue than the stone-grey cloud ceiling",
            sunnyBlueBias > overcastBlueBias + .15f)
        assertTrue("Different weather is not merely a small brightness adjustment",
            colorDistance(sunny.upper, overcast.upper) > .15f)
        assertTrue("Sunny blue does not become a white wash", sunny.upper.blue < .60f)
        assertTrue("The paper's lower edge retains reading depth", perceivedBrightness(sunny.lower) < .20f)
    }

    @Test
    fun clearNightHasItsOwnDarkPaletteInsteadOfReusingTheDaytimeBlueWash() {
        val day = weatherScenePalette(WeatherKind.Clear, Daylight.Day)
        val night = weatherScenePalette(WeatherKind.Clear, Daylight.Night)
        assertTrue(perceivedBrightness(night.upper) < perceivedBrightness(day.upper) * .45f)
        assertTrue(perceivedBrightness(night.middle) < perceivedBrightness(day.middle) * .45f)
        assertTrue(night.upper.blue > night.upper.red)
        assertTrue(night.lower.blue > night.lower.red)
    }

    @Test
    fun overcastHasAnUpperCeilingButNoRecycledCloudBankBelowThePaper() {
        for (near in listOf(false, true)) {
            val cloud = structureSample(WeatherKind.Cloudy, near)
            assertTrue("Clouds occupy the exposed upper area", meanAlpha(cloud, .18f, .82f, .10f, .22f) > 12.0)
            assertEquals("The cloud ceiling is not mirrored along the bottom",
                0.0, meanAlpha(cloud, .15f, .85f, .65f, .88f), 0.0)
        }
    }

    @Test
    fun rainWaterTracesAreVerticalWhileFogFormsHorizontalLayers() {
        for (near in listOf(false, true)) {
            val rain = structureSample(WeatherKind.Rain, near)
            val fog = structureSample(WeatherKind.Fog, near)
            // A vertical trace changes primarily when crossing it horizontally, and vice versa.
            assertTrue("Rain has long wet traces, not isotropic cloud noise",
                alphaGradient(rain, horizontal = true) > alphaGradient(rain, horizontal = false) * 3)
            assertTrue("Fog is broad horizontal strata, not recoloured rain or billowing clouds",
                alphaGradient(fog, horizontal = false) > alphaGradient(fog, horizontal = true) * 3)
        }
    }

    @Test
    fun snowLeavesCleanAirForSnowflakesInsteadOfAnotherDenseCloudTexture() {
        for (near in listOf(false, true)) {
            val snow = structureSample(WeatherKind.Snow, near)
            val fog = structureSample(WeatherKind.Fog, near)
            val rain = structureSample(WeatherKind.Rain, near)
            assertTrue("Snow haze is deliberately almost transparent", snow.maxOf { it ushr 24 } <= 18)
            val snowDensity = meanAlpha(snow, .15f, .85f, .15f, .85f)
            assertTrue(snowDensity < meanAlpha(fog, .15f, .85f, .15f, .85f) * .25)
            assertTrue(snowDensity < meanAlpha(rain, .15f, .85f, .15f, .85f) * .40)
        }
    }

    @Test
    fun fogRemainsVisibleAroundThePaperWithoutFineCloudEddies() {
        for (near in listOf(false, true)) {
            val fog = structureSample(WeatherKind.Fog, near)
            assertTrue("An upper veil is visible above the diary", meanAlpha(fog, .18f, .82f, .10f, .18f) > 20.0)
            assertTrue("A lower veil is visible below the diary", meanAlpha(fog, .18f, .82f, .80f, .88f) > 20.0)
            assertTrue("Fog has an intentionally smooth lateral surface",
                alphaGradient(fog, horizontal = true) < .15)
        }
    }

    @Test
    fun defaultDimensionsStayCompatibleWithTheExistingBitmapWrapper() {
        val pixels = generateWeatherAtmospherePixels(WeatherKind.Unknown, Daylight.Day, near = false)
        assertEquals(240 * 480, pixels.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun degenerateDimensionsAreRejectedBeforeNormalisation() {
        generateWeatherAtmospherePixels(WeatherKind.Cloudy, Daylight.Day, near = false, width = 1, height = 12)
    }

    private fun sample(
        kind: WeatherKind,
        daylight: Daylight = Daylight.Day,
        near: Boolean = false,
    ): IntArray = generateWeatherAtmospherePixels(kind, daylight, near, width = 24, height = 48)

    private fun perceivedBrightness(color: Color): Float =
        color.red * .2126f + color.green * .7152f + color.blue * .0722f

    private fun colorDistance(a: Color, b: Color): Float =
        abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)

    private val texturedWeather = listOf(WeatherKind.Cloudy, WeatherKind.Rain, WeatherKind.Snow, WeatherKind.Fog)
    private val structureWidth = 96
    private val structureHeight = 192

    private fun structureSample(kind: WeatherKind, near: Boolean): IntArray =
        generateWeatherAtmospherePixels(kind, Daylight.Day, near, structureWidth, structureHeight)

    private fun meanAlpha(pixels: IntArray, x0: Float, x1: Float, y0: Float, y1: Float): Double {
        var total = 0L
        var count = 0
        for (y in (y0 * structureHeight).toInt() until (y1 * structureHeight).toInt()) {
            for (x in (x0 * structureWidth).toInt() until (x1 * structureWidth).toInt()) {
                total += pixels[y * structureWidth + x] ushr 24
                count++
            }
        }
        return total.toDouble() / count
    }

    private fun alphaGradient(pixels: IntArray, horizontal: Boolean): Double {
        var total = 0L
        var count = 0
        val offset = if (horizontal) 1 else structureWidth
        for (y in (structureHeight * .20f).toInt() until (structureHeight * .80f).toInt()) {
            for (x in (structureWidth * .20f).toInt() until (structureWidth * .80f).toInt()) {
                val index = y * structureWidth + x
                total += abs((pixels[index] ushr 24) - (pixels[index + offset] ushr 24))
                count++
            }
        }
        return total.toDouble() / count
    }
}
