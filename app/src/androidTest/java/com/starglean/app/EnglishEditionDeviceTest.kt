package com.starglean.app

import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in checks for the disposable QA emulator. No location requests or permission grants. */
@RunWith(AndroidJUnit4::class)
class EnglishEditionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun requireQa() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("englishQa") == "true")
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_Location_QA", avd)
    }

    @Test fun existingEntryIsUnchangedByTheEnglishUpdate() = runBlocking {
        requireQa()
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val expected = OwnDiaryRecord(
            DiaryDemo("own-9df09e2a-30d8-4d87-8d67-2196c46fc799", "Upgrade QA · 保留原文",
                "This is a fictional upgrade test. 原始中文和 emoji ✨ must remain unchanged.",
                "16 Sep 2026", DiaryWeatherSnapshot(WeatherKind.Unknown, Daylight.Day),
                "", PaperStyle.WarmWhite, DiaryFont.Clear),
            place = null, savedAtMillis = 1_789_538_400_000L,
        )
        val store = OwnDiaryStore(context)
        val marker = File(context.cacheDir, "english-v27-upgrade-check.txt")
        val diaryFile = File(context.filesDir, "own-diaries-v1.bin")
        fun fingerprint(): String = MessageDigest.getInstance("SHA-256").digest(diaryFile.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        if (phase == "prepare") {
            val existing = store.load()
            // If earlier QA data exists, leave it entirely untouched and verify
            // its byte-for-byte preservation instead of clearing it for a fixture.
            if (existing.isEmpty()) store.save(expected)
            val checkpoint = "${store.load().size}\n${fingerprint()}"
            if (marker.exists()) assertTrue("Never replace an unrelated checkpoint", marker.readText() == checkpoint)
            else {
                assertTrue(marker.createNewFile())
                marker.writeText(checkpoint)
            }
        }
        assertTrue("Prepare a checkpoint on the old APK before updating", marker.isFile)
        val checkpoint = marker.readLines()
        assertEquals(2, checkpoint.size)
        assertTrue("Existing entries must survive without translation or byte changes", fingerprint() == checkpoint[1])
        assertEquals(checkpoint[0].toInt(), store.load().size)
        assertTrue("The upgrade check must cover nonempty storage", store.load().isNotEmpty())
    }

    @Test fun fourPhonePagesAndDisclosureUseEnglish() = runBlocking {
        requireQa()
        assertTrue("English page QA must exercise the phone edition", BuildConfig.LOCAL_DEVICE_EDITION)
        val activity = instrumentation.startActivitySync(QaSignedIn.intent(context))
        try {
            assertEquals("en", activity.resources.configuration.locales[0].language)
            await { nodes().any { it.text?.toString() == "Starglean" } }
            delay(1_400)
            verifyAndCapture("01-planet")

            clickTab("Discover")
            await { nodes().any { it.text?.toString() == "Shake for a star" } }
            verifyAndCapture("02-discover")

            clickTab("Write")
            await { nodes().count { it.isEditable } == 2 }
            assertTrue("Do not inspect a populated user draft", nodes().filter { it.isEditable }.all { it.text.isNullOrEmpty() })
            verifyAndCapture("03-write")
            // Location and weather are now explained once, in the shared Location settings dialog.
            clickText("Location settings")
            await { nodes().any { it.text?.toString()?.contains("Android", ignoreCase = false) == true } }
            verifyAndCapture("05-weather-disclosure")
            // Inspect disclosure only. Never accept location or geocoding consent in this test.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            delay(400)

            clickTab("My constellations")
            await { nodes().any { it.text?.toString() == "Cygnus" } }
            delay(1_000)
            verifyAndCapture("04-constellations")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private suspend fun verifyAndCapture(name: String) {
        delay(350)
        val appNodes = nodes().filter { it.packageName?.toString() == context.packageName && it.isVisibleToUser }
        val texts = appNodes.flatMap { listOf(it.text?.toString().orEmpty(), it.contentDescription?.toString().orEmpty()) }
        assertFalse("Application-owned text must be English on $name", texts.any { Regex("[\\p{IsHan}]").containsMatchIn(it) })
        val directory = checkNotNull(context.getExternalFilesDir("english-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
    }

    private suspend fun clickTab(label: String) {
        await { nodes().any { it.contentDescription?.toString() == label } }
        clickNode(nodes().first { it.contentDescription?.toString() == label })
        delay(500)
    }

    private suspend fun clickText(label: String) {
        await { nodes().any { it.text?.toString() == label && it.isVisibleToUser } }
        clickNode(nodes().first { it.text?.toString() == label && it.isVisibleToUser })
        delay(350)
    }

    private fun clickNode(start: AccessibilityNodeInfo) {
        var node: AccessibilityNodeInfo? = start
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("The action must be clickable", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(100)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
}
