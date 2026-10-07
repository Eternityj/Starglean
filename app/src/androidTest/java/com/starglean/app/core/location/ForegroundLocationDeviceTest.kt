package com.starglean.app.core.location

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.MainActivity
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.memory.MemoryLocationClient
import com.starglean.app.core.memory.MemoryLocationException
import com.starglean.app.core.memory.MemoryLocationFailure
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.weather.CoarseWeatherLocationClient
import com.starglean.app.core.weather.OpenMeteoWeatherClient
import com.starglean.app.core.weather.WeatherLocationException
import com.starglean.app.core.weather.WeatherLocationFailure
import com.starglean.app.ui.screens.WriteScreen
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in tests ONLY for the disposable Starglean_Location_QA emulator. They replace
 * system providers with public fictional fixtures, never read the user's position,
 * never change a real phone's settings, and never write the actual diary library.
 * Run with -e locationQa true; live HTTPS is separately opt-in via -e liveWeather true.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundLocationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var manager: LocationManager
    private val ownedProviders = mutableListOf<String>()
    private var testActivity: android.app.Activity? = null
    private var ownedDirectory: File? = null
    private var locationWasEnabled = true
    private var allowedToModify = false

    @Before
    fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("locationQa") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.startsWith("sdk_"))
        assertEquals("Starglean_Location_QA", shell("getprop ro.boot.qemu.avd_name").trim())
        context = instrumentation.targetContext
        manager = context.getSystemService(LocationManager::class.java)
        assertEquals("The isolated test app needs at least coarse permission for denial testing", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        locationWasEnabled = shell("cmd location is-location-enabled").trim() == "true"
        allowedToModify = true
        shell("cmd location set-location-enabled true")
        testActivity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        for (provider in PROVIDERS) {
            @Suppress("DEPRECATION")
            manager.addTestProvider(provider, false, provider == "gps", false, false,
                true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
            ownedProviders += provider
            manager.setTestProviderEnabled(provider, true)
        }
    }

    @After
    fun restoreOnlyOwnedTestState() {
        if (!allowedToModify) return
        ownedProviders.asReversed().forEach { manager.removeTestProvider(it) }
        ownedProviders.clear()
        if (!locationWasEnabled) shell("cmd location set-location-enabled false")
        testActivity?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        ownedDirectory?.let { root ->
            assertEquals(context.cacheDir.canonicalFile, root.canonicalFile.parentFile)
            assertTrue(root.name.startsWith("location-flow-test-"))
            val children = requireNotNull(root.listFiles())
            children.forEach { child ->
                assertTrue(child.name in setOf("own-diaries-v1.bin", "own-diaries-v1.bin.new", "own-diaries-v1.bin.bak"))
                assertEquals(root.canonicalFile, child.canonicalFile.parentFile)
                assertTrue(child.isFile)
            }
            children.forEach { assertTrue(it.delete()) }
            assertTrue(root.delete())
        }
    }

    @Test
    fun weatherDoesNotWaitForSilentNetworkBeforeUsingGps() = runBlocking {
        withFixture("gps") {
            withTimeout(8_000) {
                val query = CoarseWeatherLocationClient(context).locate()
                // Inspect neither raw coordinates nor HTTP URLs in test output.
                assertFalse(query.toString().isBlank())
            }
        }
    }

    @Test
    fun memoryDoesNotWaitForSilentFusedAndGpsBeforeUsingNetwork() = runBlocking {
        withFixture("network") {
            withTimeout(8_000) {
                val fix = MemoryLocationClient(context).locate()
                assertTrue(fix.accuracyMeters <= 100f)
            }
        }
    }

    @Test
    fun memoryWaitsForGoodFixAfterInaccurateFirstUpdate() = runBlocking {
        onlyGpsEnabled()
        withFixture("gps", initiallyBad = true) {
            withTimeout(8_000) {
                val fix = MemoryLocationClient(context).locate()
                assertTrue(fix.accuracyMeters <= 100f)
            }
        }
    }

    @Test
    fun weatherWaitsForFreshFixAfterStaleFirstUpdate() = runBlocking {
        onlyGpsEnabled()
        withFixture("gps", initiallyStale = true) {
            withTimeout(8_000) { CoarseWeatherLocationClient(context).locate() }
        }
        Unit
    }

    @Test
    fun coarseOnlyPermissionCannotStartPreciseWeatherQuery() = runBlocking {
        assumeTrue(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        try {
            withTimeout(2_000) { CoarseWeatherLocationClient(context).locate() }
            throw AssertionError("Approximate-only permission must not start a precise query")
        } catch (failure: WeatherLocationException) {
            assertEquals(WeatherLocationFailure.PermissionRequired, failure.code)
        }
        Unit
    }

    @Test
    fun explicitUpgradeFixtureSurvivesReplacingOldApk() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        // Before() guards the exact disposable AVD; only this opt-in test touches
        // its real filesDir, proving persistence across an actual APK replacement.
        val store = OwnDiaryStore(context)
        val body = "这是一篇专用于旧版覆盖升级验证的虚构日记，不是用户的数据。"
        val id = "own-00000000-0000-0000-0000-000000000024"
        if (phase == "prepare") {
            assertTrue("Upgrade fixture requires an empty disposable emulator library", store.load().isEmpty())
            store.save(OwnDiaryRecord(
                diary = DiaryDemo(id = id, title = "覆盖升级测试", body = body, date = "测试",
                    weatherSnapshot = DiaryWeatherSnapshot.Unavailable, blurredPlace = "地点未记录",
                    paper = PaperStyle.Midnight, font = DiaryFont.Serif),
                place = null, savedAtMillis = System.currentTimeMillis()))
        }
        val record = store.load().single()
        assertEquals(id, record.diary.id)
        assertEquals(body, record.diary.body)
    }

    @Test
    fun parentCancellationFinishesPromptlyWithoutSuccess() = runBlocking {
        var returned = false
        val request = launch {
            MemoryLocationClient(context).locate()
            returned = true
        }
        delay(700)
        withTimeout(2_000) { request.cancelAndJoin() }
        assertFalse(returned)
    }

    @Test
    fun disabledSystemLocationIsDistinguishedFromTimeout() = runBlocking {
        shell("cmd location set-location-enabled false")
        try {
            MemoryLocationClient(context).locate()
            throw AssertionError("Expected ServicesDisabled")
        } catch (failure: MemoryLocationException) {
            assertEquals(MemoryLocationFailure.ServicesDisabled, failure.code)
        } finally {
            shell("cmd location set-location-enabled true")
        }
    }

    @Test
    fun systemLocationCanBeSavedAndReopenedInIsolatedDiaryStore() = runBlocking {
        withFixture("gps") {
            withTimeout(8_000) {
                val fix = MemoryLocationClient(context).locate()
                val directory = Files.createTempDirectory(context.cacheDir.toPath(), "location-flow-test-").toFile()
                ownedDirectory = directory
                val isolated = object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir(): File = directory
                }
                val record = OwnDiaryRecord(
                    diary = DiaryDemo(id = "own-${UUID.randomUUID()}", title = "定位测试 · 非用户日记",
                        body = "这是隔离模拟器使用公开测试坐标完成的保存与读取验证，不是真实用户的日记。",
                        date = "测试", weatherSnapshot = DiaryWeatherSnapshot.Unavailable,
                        blurredPlace = "公开测试地点", paper = PaperStyle.Midnight, font = DiaryFont.Serif),
                    place = fix, savedAtMillis = System.currentTimeMillis())
                OwnDiaryStore(isolated).save(record)
                val loaded = OwnDiaryStore(isolated).load()
                assertEquals(1, loaded.size)
                assertEquals(record.diary.id, loaded.single().diary.id)
                assertEquals(fix, loaded.single().place)
            }
        }
    }

    @Test
    fun realWeatherRequestUsesSystemDeliveredFixtureNotDebugShortcut() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveWeather") == "true")
        withFixture("gps") {
            withTimeout(40_000) {
                val query = CoarseWeatherLocationClient(context).locate()
                val weather = OpenMeteoWeatherClient().fetch(query)
                assertTrue(weather.isFresh())
                assertTrue(weather.snapshot.kind != WeatherKind.Unknown)
                assertFalse(weather.snapshot.isDemo)
            }
        }
    }

    @Test
    fun writingScreenShowsRealWeatherFromSystemFixture() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureWeather") == "true")
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveWeather") == "true")
        val preferences = context.getSharedPreferences("writing_weather_consent", Context.MODE_PRIVATE)
        val hadConsentValue = preferences.contains("enabled")
        val oldConsent = preferences.getBoolean("enabled", false)
        val hadVersion = preferences.contains("consent_version")
        val oldVersion = preferences.getInt("consent_version", 0)
        preferences.edit().putBoolean("enabled", true).putInt("consent_version", 2).commit()
        try {
            instrumentation.runOnMainSync {
                (checkNotNull(testActivity) as ComponentActivity).setContent {
                    StargleanTheme {
                        Column(Modifier.fillMaxSize().background(StargleanColors.Ink).statusBarsPadding()) {
                            Text("隔离设备测试 · 公开模拟坐标 · 非实际位置",
                                color = StargleanColors.Gold, modifier = Modifier.fillMaxWidth().padding(12.dp))
                            WriteScreen(Modifier.weight(1f))
                        }
                    }
                }
            }
            withFixture("gps") {
                withTimeout(40_000) {
                    while (!containsText(instrumentation.uiAutomation.rootInActiveWindow, "Open-Meteo · Estimate")) delay(200)
                }
                delay(400)
                val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val directory = checkNotNull(context.getExternalFilesDir(null))
                File(directory, "location-v24-weather-system-fixture.png").outputStream().use {
                    assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
                screenshot.recycle()
            }
        } finally {
            val editor = preferences.edit()
            if (hadConsentValue) editor.putBoolean("enabled", oldConsent) else editor.remove("enabled")
            if (hadVersion) editor.putInt("consent_version", oldVersion) else editor.remove("consent_version")
            editor.commit()
        }
    }

    private fun onlyGpsEnabled() {
        manager.setTestProviderEnabled("network", false)
        manager.setTestProviderEnabled("fused", false)
    }

    private suspend fun <T> withFixture(
        provider: String,
        initiallyBad: Boolean = false,
        initiallyStale: Boolean = false,
        action: suspend () -> T,
    ): T = coroutineScope {
        val emitter = launch {
            delay(600)
            if (initiallyBad || initiallyStale) {
                manager.setTestProviderLocation(provider, fixture(provider, initiallyBad, initiallyStale))
                delay(1_600)
            }
            while (true) {
                manager.setTestProviderLocation(provider, fixture(provider))
                delay(300)
            }
        }
        try { action() } finally { emitter.cancelAndJoin() }
    }

    private fun fixture(provider: String, bad: Boolean = false, stale: Boolean = false): Location = Location(provider).apply {
        // Public Melbourne fixture. Never the computer/phone user's actual position.
        latitude = -37.8136
        longitude = 144.9631
        accuracy = if (bad) 100_000f else 12f
        time = System.currentTimeMillis() - if (stale) 300_000L else 0L
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - if (stale) 300_000_000_000L else 0L
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .use { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() } }

    private fun containsText(node: AccessibilityNodeInfo?, value: String): Boolean {
        if (node == null) return false
        if (node.text?.contains(value) == true) return true
        return (0 until node.childCount).any { containsText(node.getChild(it), value) }
    }

    private companion object {
        val PROVIDERS = listOf("network", "fused", "gps")
    }
}
