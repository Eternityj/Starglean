package com.starglean.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.*
import com.starglean.app.core.stars.ConstellationStore
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly opt-in and restricted to the separate QA emulator, never the user's preview. */
@RunWith(AndroidJUnit4::class)
class LocalCollectionFlowDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun requireQa() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("starsQa") == "true")
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_Location_QA", avd)
        assertTrue(BuildConfig.LOCAL_DEVICE_EDITION)
    }
    private fun fixture(index: Int) = OwnDiaryRecord(
        DiaryDemo("own-58435428-a110-40ce-b000-${index.toString().padStart(12, '0')}", "QA only · Memory $index",
            "A fictional memory for the local constellation test. No real person's diary or location is used.",
            "16 Sep 2026", DiaryWeatherSnapshot(WeatherKind.Unknown, Daylight.Day), "", PaperStyle.WarmWhite, DiaryFont.Serif),
        null, 1_789_538_400_000L + index,
    )

    @Test fun realSavedEntriesBecomeStarsAndAppearAtHome() = runBlocking {
        requireQa()
        val store = ConstellationStore(QaSignedIn.library(context))
        val diaryStore = OwnDiaryStore(QaSignedIn.library(context))
        (1..6).forEach { diaryStore.save(fixture(it)) }
        var state = store.load()
        val item = state.galleryItems().first { it.id == "cygnus-1" }
        if (!item.completed) store.setTarget(item.id)
        // Existing QA data is preserved. Only this test's explicitly named entries are placed.
        (1..2).forEach { store.placeDiary(fixture(it).diary.id, item.id) }
        launchGallery()
        try {
            await { textVisible("Cygnus") }
            capture("01-simple-gallery")
            for (index in 3..6) {
                if (store.load().placementForDiary(fixture(index).diary.id) != null) continue
                clickText("Place a waiting star")
                clickText("QA only · Memory $index")
                await { textVisible("Cygnus") && !textVisible("For Cygnus · Next available star") }
                delay(500)
                assertNotNull(store.load().placementForDiary(fixture(index).diary.id))
            }
            state = store.load()
            assertEquals(6, state.galleryItems().first { it.id == "cygnus-1" }.litStars)
            assertEquals(6, state.placements.filter { it.instanceId == "cygnus-1" }.map { it.diaryId }.toSet().size)
            val retry = store.placeDiary(fixture(1).diary.id, "orion-1")
            assertFalse(retry.newlyPlaced)
            assertEquals("cygnus-1", retry.placement!!.instanceId)
            await { textVisible("Read these memories") }
            capture("02-completed-gallery")
            clickDescription("Collection options")
            clickText("Arrange home")
            await { textVisible("Arrange your home sky") }
            if ("cygnus-1" !in store.load().homeIds) clickText("Cygnus")
            clickText("Confirm · 1 / 8")
            await { !textVisible("Arrange your home sky") }
            assertEquals(setOf("cygnus-1"), store.load().homeIds)
            clickDescription("Planet")
            await { nodes().any { node -> node.actionList.any { it.label?.toString() == "Explore Cygnus · 1" } } }
            delay(800)
            capture("03-real-home-sky")
            performActionLabel("Explore Cygnus · 1")
            await { nodes().any { node -> node.actionList.any { it.label?.toString()?.contains("star 1") == true } } }
            val sky = nodes().first { node -> node.actionList.any { it.label?.toString()?.contains("star 1") == true } }
            val action = sky.actionList.first { it.label?.toString()?.contains("star 1") == true }
            assertTrue(sky.performAction(action.id))
            await { textVisible("Cygnus 1 · Star 1") }
            assertTrue(textVisible("QA only · Memory 1"))
            capture("04-real-star-entry")
        } finally { finishActivities() }
    }

    @Test fun simplifiedGalleryAndDeviceDisclosureFitSmallScreen() = runBlocking {
        requireQa()
        launchGallery()
        try {
            await { textVisible("My constellations") }
            // Since 0.5.1 Settings opens from the gear on the home screen, not the collection menu.
            clickDescription("Planet")
            clickDescription("Settings")
            clickText("Device & activity")
            await { nodes().any { it.text?.toString()?.startsWith("Only short, foreground checks") == true } }
            capture("05-device-disclosure")
            // Never grant permission, start location checks, or inject fake sensor samples here.
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        } finally { finishActivities() }
    }

    @Test fun skyAndEntriesTabsKeepTheMenuShort() = runBlocking {
        requireQa()
        launchGallery()
        try {
            awaitCollection("Sky tab on the constellation page") { textVisible("My constellations") && textVisible("Sky") }
            capture("07-sky-before-tabs")
            clickText("Entries")
            capture("08-entries-tab")
            awaitCollection("Entries shortcuts") { textVisible("Kindness") && textVisible("Unplaced") && textVisible("Cloud") }
            clickText("Kindness")
            awaitCollection("Reaction inbox from Entries") { textVisible("Cloud library") }
            clickText("Back")
            awaitCollection("Entries after closing inbox") { textVisible("Kindness") && textVisible("Sky") }
            clickText("Cloud")
            awaitCollection("Cloud library from Entries") { textVisible("Cloud library") }
            clickText("Back")
            awaitCollection("Entries after closing cloud library") { textVisible("Cloud") && textVisible("Sky") }
            clickText("Sky")
            awaitCollection("Sky after returning from Entries") { textVisible("My constellations") && textVisible("Sky") }
            clickDescription("Collection options")
            awaitCollection("Short constellation menu") { textVisible("Arrange home") }
            // Entries, cloud copies and settings moved out of this menu in 0.5.1.
            listOf("Settings", "My entries", "Cloud library", "Kindness received").forEach {
                assertFalse("$it should not be in the sky menu", textVisible(it))
            }
            capture("09-short-sky-menu")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitCollection("Back closes constellation menu") { textVisible("My constellations") && !textVisible("Arrange home") }
        } finally { finishActivities() }
    }

    @Test fun writingPreviewKeepsActionsFixedAndReturnsToTheSameDraft() = runBlocking {
        requireQa()
        val stars = ConstellationStore(QaSignedIn.library(context))
        val diaries = OwnDiaryStore(QaSignedIn.library(context))
        val before = diaries.load().map { it.diary.id }.toSet()
        val previousStars = stars.load()
        context.startActivity(QaSignedIn.intent(context, "write"))
        try {
            await { nodes().count { it.isEditable } == 2 }
            val title = "QA writing flow"
            val body = "This fictional entry verifies the single-page preview. No real location is requested and nothing is published."
            val fields = nodes().filter { it.isEditable }
            assertTrue("Do not overwrite an unrelated draft", fields[0].text.isNullOrEmpty() || fields[0].text.toString() == title)
            assertTrue("Do not overwrite an unrelated draft", fields[1].text.isNullOrEmpty() || fields[1].text.toString() == body)
            assertTrue(fields[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, title)
            }))
            assertTrue(fields[1].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, body)
            }))
            delay(800)
            scrollTo("Preview")
            clickText("Preview")
            await { textVisible("Ready to save") && textVisible("Tick the box above to continue") }
            fun actionBounds(): android.graphics.Rect = android.graphics.Rect().also { bounds ->
                nodes().first { it.isVisibleToUser && it.text?.toString() == "Tick the box above to continue" }
                    .getBoundsInScreen(bounds)
            }
            val initialAction = actionBounds()
            assertFalse(initialAction.isEmpty)
            capture("06-preview-top")
            scrollTo("Who can find it?")
            clickText("Private")
            scrollTo("Privacy and deletion details")
            assertEquals("The action stays in place as the entry scrolls", initialAction, actionBounds())
            assertTrue(nodes().any { it.isCheckable && !it.isChecked && it.isVisibleToUser })
            capture("06-preview-options")
            clickText("Privacy and deletion details")
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await { textVisible("Ready to save") }
            clickText("‹  Edit")
            await { nodes().count { it.isEditable } == 2 }
            assertEquals(title, nodes().filter { it.isEditable }[0].text.toString())
            assertEquals(body, nodes().filter { it.isEditable }[1].text.toString())
            assertEquals("Preview and back never save or publish", before, diaries.load().map { it.diary.id }.toSet())
            assertEquals(previousStars, stars.load())
        } finally { finishActivities() }
    }

    private suspend fun scrollTo(label: String) {
        repeat(12) {
            if (textVisible(label)) return
            val scroller = nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }
            assertNotNull("A scrollable page is required to reach $label", scroller)
            scroller!!.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(350)
        }
        if (!textVisible(label)) capture("failure-scroll")
        assertTrue("Could not reach $label; visible: " + nodes().filter { it.isVisibleToUser }
            .mapNotNull { it.text?.toString() }.joinToString(" | ") + "; scrollables: " +
            nodes().filter { it.isScrollable }.map { node ->
                val bounds = android.graphics.Rect(); node.getBoundsInScreen(bounds)
                "$bounds ${node.actionList.map { it.id }}"
            }, textVisible(label))
    }

    private suspend fun launchGallery() {
        // The starfield intentionally animates continuously; do not wait for UI idleness.
        // start_destination already opens this tab; its selected tab item is not clickable.
        context.startActivity(QaSignedIn.intent(context, "constellation"))
    }
    private fun finishActivities() = instrumentation.runOnMainSync {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach { it.finish() }
    }

    private fun textVisible(value: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == value }
    private suspend fun clickText(value: String) {
        // A returning Compose page can expose text before its clickable ancestor refreshes.
        // Fetch a new node on each attempt; a rejected accessibility action did not click it.
        await {
            var target = nodes().firstOrNull { it.isVisibleToUser && it.text?.toString() == value }
            while (target != null && !target.isClickable) target = target.parent
            target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(350)
    }
    private suspend fun clickDescription(value: String) {
        await { nodes().any { it.contentDescription?.toString() == value && it.isVisibleToUser } }
        click(nodes().first { it.contentDescription?.toString() == value && it.isVisibleToUser })
        delay(400)
    }
    private fun click(start: AccessibilityNodeInfo) {
        var node: AccessibilityNodeInfo? = start
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun performActionLabel(label: String) {
        val node = nodes().first { n -> n.actionList.any { it.label?.toString() == label } }
        assertTrue(node.performAction(node.actionList.first { it.label?.toString() == label }.id))
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(12_000) { while (!condition()) delay(100) }
    private suspend fun awaitCollection(stage: String, condition: () -> Boolean) {
        try { await(condition) }
        catch (timeout: TimeoutCancellationException) {
            val visible = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.distinct().take(40)
            throw AssertionError("$stage did not appear. Visible text: $visible", timeout)
        }
    }
    private suspend fun capture(name: String) {
        delay(500)
        val texts = nodes().filter { it.isVisibleToUser && it.packageName?.toString() == context.packageName }
            .flatMap { listOf(it.text?.toString().orEmpty(), it.contentDescription?.toString().orEmpty()) }
        assertFalse(texts.any { Regex("[\\p{IsHan}]").containsMatchIn(it) })
        val directory = checkNotNull(context.getExternalFilesDir("stars-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
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
