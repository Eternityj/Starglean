package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.weather.CurrentWritingWeather
import com.starglean.app.core.weather.WeatherPlaceGranularity
import com.starglean.app.ui.screens.WritingWeatherStatus
import com.starglean.app.ui.screens.WritingWeatherUiState
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Renders the actual card with explicitly fictional data; no location, network or store writes. */
@RunWith(AndroidJUnit4::class)
class WeatherPlaceCardDeviceTest {
    @Test fun detailedPlaceAndAccuracyFitThePhoneCard() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("placeQa") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
            .ifBlank { instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu.avd_name")
                .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() } }
        assertTrue("Unexpected QA AVD: $avd", avd in setOf(
            "Starglean_Location_QA", "Starglean_QA", "Starglean_Compat_API30"))
        assertTrue("Weather card QA must exercise the phone edition", BuildConfig.LOCAL_DEVICE_EDITION)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity: MainActivity? = null
        try {
            withTimeout(12_000) {
                while (activity == null) {
                    instrumentation.runOnMainSync {
                        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull()
                    }
                    delay(100)
                }
            }
            val snapshot = DiaryWeatherSnapshot(WeatherKind.Cloudy, Daylight.Day, 12)
            val now = Instant.parse("2026-09-16T06:10:00Z")
            val current = CurrentWritingWeather(snapshot, now, now, ZoneId.of("Australia/Melbourne"),
                placeName = "Example Street · Sample District · Sample City",
                placeGranularity = WeatherPlaceGranularity.Street, locationAccuracyMeters = 18.2f)
            fun render(marker: String, state: WritingWeatherUiState) {
                instrumentation.runOnMainSync {
                    checkNotNull(activity).setContent {
                        StargleanTheme {
                            Column(Modifier.fillMaxSize().background(StargleanColors.Ink).statusBarsPadding()
                                .padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                Text(marker, color = StargleanColors.Gold,
                                    style = MaterialTheme.typography.labelMedium)
                                Text("Write this moment", color = StargleanColors.WarmWhite,
                                    style = MaterialTheme.typography.headlineLarge)
                                WritingWeatherStatus(snapshot, state,
                                    sample = false, testLocation = false, permissionNotice = null,
                                    onDetails = {}, onCancel = {}, onAction = {})
                                Text("This is test data, not your location or live weather.",
                                    color = StargleanColors.MutedText)
                            }
                        }
                    }
                }
            }
            fun visible(label: String): Boolean {
                fun find(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isVisibleToUser && node.text?.toString() == label) return true
                    return (0 until node.childCount).any { find(node.getChild(it)) }
                }
                return find(instrumentation.uiAutomation.rootInActiveWindow)
            }
            fun visibleContaining(fragment: String): Boolean {
                fun find(node: AccessibilityNodeInfo?): Boolean {
                    if (node == null) return false
                    if (node.isVisibleToUser && node.text?.toString()?.contains(fragment) == true) return true
                    return (0 until node.childCount).any { find(node.getChild(it)) }
                }
                return find(instrumentation.uiAutomation.rootInActiveWindow)
            }
            val directory = checkNotNull(context.getExternalFilesDir("place-qa"))
            assertTrue(directory.isDirectory || directory.mkdirs())
            suspend fun awaitAndCapture(marker: String, expected: String, fileName: String) {
                withTimeout(12_000) { while (!visible(marker) || !visible(expected)) delay(100) }
                // Wait for two frames plus accessibility tree settlement; the former
                // fixed 500 ms delay could capture a stale composition on slower AVDs.
                delay(1_000)
                assertTrue("Expected UI text is visible", visible(expected))
                val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(directory, fileName).outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { image.recycle() }
            }

            render("Layout test · Street", WritingWeatherUiState(current = current, enabled = true,
                permissionGranted = true, precisePermissionGranted = true, placeLookupEnabled = true,
                placeName = current.placeName, placeGranularity = current.placeGranularity,
                locationAccuracyMeters = current.locationAccuracyMeters))
            awaitAndCapture("Layout test · Street", checkNotNull(current.placeName),
                "detailed-weather-card.png")
            assertTrue("Detailed location is visible", visible(checkNotNull(current.placeName)))
            assertFalse("Street provenance belongs in Details, not the main card", visibleContaining("Near this street"))
            assertFalse("Location accuracy belongs in Details, not the main card", visibleContaining("Weather location"))
            assertTrue("Refresh action is visible", visible("Refresh"))

            val city = current.copy(placeName = "Melbourne", placeGranularity = WeatherPlaceGranularity.City)
            render("Layout test · City only", WritingWeatherUiState(current = city, enabled = true,
                permissionGranted = true, precisePermissionGranted = true, placeLookupEnabled = true,
                placeName = city.placeName, placeGranularity = city.placeGranularity,
                locationAccuracyMeters = city.locationAccuracyMeters))
            awaitAndCapture("Layout test · City only", "Melbourne",
                "city-only-weather-card.png")
            assertTrue("City remains the primary place", visible("Melbourne"))
            assertFalse("Static city granularity detail belongs in Details",
                visibleContaining("Street unavailable"))

            render("Layout test · Improving", WritingWeatherUiState(current = city, enabled = true,
                permissionGranted = true, precisePermissionGranted = true, placeLookupEnabled = true,
                placeName = city.placeName, placeGranularity = city.placeGranularity, placeImproving = true,
                locationAccuracyMeters = city.locationAccuracyMeters))
            awaitAndCapture("Layout test · Improving", "Checking once for a nearby street…",
                "improving-place-weather-card.png")
            assertTrue("City remains visible while improving", visible("Melbourne"))
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }
}
