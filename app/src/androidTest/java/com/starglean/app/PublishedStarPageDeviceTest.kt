package com.starglean.app

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.library.DiaryDeletionStore
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.*
import com.starglean.app.core.stars.ConstellationStore
import com.starglean.app.ui.components.PublishedStarPage
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The page after saving: with no current constellation the writer chooses one, then the star lights. */
@RunWith(AndroidJUnit4::class)
class PublishedStarPageDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val diary = OwnDiaryRecord(
        DiaryDemo("own-${UUID.randomUUID()}", "QA only · Published star",
            "A fictional entry for the published star page test. No real person's diary or location is used.",
            "3 Oct 2026", DiaryWeatherSnapshot(WeatherKind.Unknown, Daylight.Night), "", PaperStyle.WarmWhite, DiaryFont.Serif),
        null, 1_791_000_000_000L,
    )
    private fun fixture(index: Int) = diary.copy(diary = diary.diary.copy(
        id = "own-${UUID.randomUUID()}",
        title = "QA only · Published star $index",
    ))

    @Test fun withoutACurrentConstellationTheWriterChoosesOneAndItsStarLights() = runBlocking {
        requireQa()
        val library = QaSignedIn.library(context)
        val stars = ConstellationStore(library)
        val previousTarget = stars.load().targetId
        stars.setTarget(null)
        OwnDiaryStore(library).save(diary)
        var finished = false
        context.startActivity(QaSignedIn.intent(context, "write"))
        try {
            val activity = awaitActivity()
            instrumentation.runOnMainSync {
                activity.setContent {
                    StargleanTheme {
                        CompositionLocalProvider(LocalContext provides library) {
                            Box(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding()) {
                                PublishedStarPage(diary.diary.id, isActive = true, published = true,
                                    onSeeConstellations = {}, onWriteAnother = { finished = true })
                            }
                        }
                    }
                }
            }
            await { textVisible("Choose where it shines") }
            assertNull("Nothing is placed before the writer chooses", stars.load().placementForDiary(diary.diary.id))
            capture("01-choose-constellation")
            val light = nodes().first { it.isVisibleToUser && it.text?.toString()?.startsWith("Light a star in ") == true }
            val chosenName = light.text.toString().removePrefix("Light a star in ")
            click(light)
            await { nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(" · Star ") == true } }
            val state = stars.load()
            val placement = state.placementForDiary(diary.diary.id)
            assertNotNull(placement)
            assertEquals("The chosen constellation becomes current", placement!!.instanceId, state.targetId)
            val chosen = state.galleryItems().first { it.id == placement.instanceId }
            assertEquals(chosenName, chosen.name + if (chosen.ordinal > 1) " · ${chosen.ordinal}" else "")
            assertTrue(textVisible("See my constellations"))
            delay(2_000) // Let the lighting animation finish before the screenshot.
            capture("02-star-lit")
            assertFalse(finished)
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach { it.finish() }
            }
            // Leave the shared QA library as it was: no test diary, no test star, the old target.
            DiaryDeletionStore(library).deleteDiary(diary.diary.id)
            stars.setTarget(previousTarget?.takeIf { id -> stars.load().galleryItems().any { it.id == id && !it.completed } })
        }
    }

    @Test fun currentConstellationLightsOnceAndReopeningKeepsTheSameStar() = runBlocking {
        requireQa()
        val library = QaSignedIn.library(context)
        val stars = ConstellationStore(library)
        val previousTarget = stars.load().targetId
        val target = stars.load().galleryItems().first { !it.completed }
        val entry = fixture(101)
        val openedCollection = AtomicBoolean(false)
        OwnDiaryStore(library).save(entry)
        stars.setTarget(target.id)
        context.startActivity(QaSignedIn.intent(context, "write"))
        try {
            val activity = awaitActivity()
            showPage(activity, library, entry.diary.id, onSeeConstellations = { openedCollection.set(true) })
            await { nodes().any { it.isVisibleToUser && it.text?.toString()?.contains(" · Star ") == true } }
            val first = checkNotNull(stars.load().placementForDiary(entry.diary.id))
            assertEquals(target.id, first.instanceId)
            showPage(activity, library, entry.diary.id, onSeeConstellations = { openedCollection.set(true) })
            await { textVisible("See my constellations") }
            val after = stars.load()
            assertEquals(first, after.placementForDiary(entry.diary.id))
            assertEquals(1, after.placements.count { it.diaryId == entry.diary.id })
            click(nodes().first { it.isVisibleToUser && it.text?.toString() == "See my constellations" })
            assertTrue("Collection navigation remains available", openedCollection.get())
            capture("03-current-constellation")
        } finally {
            finishActivities()
            DiaryDeletionStore(library).deleteDiary(entry.diary.id)
            restoreTarget(stars, previousTarget)
        }
    }

    @Test fun leavingThePageCancelsQueuedPlacementAndResumingRetriesIt() = runBlocking {
        requireQa()
        val library = QaSignedIn.library(context)
        val stars = ConstellationStore(library)
        val previousTarget = stars.load().targetId
        val target = stars.load().galleryItems().first { !it.completed }
        val entry = fixture(102)
        val active = mutableStateOf(true)
        OwnDiaryStore(library).save(entry)
        stars.setTarget(target.id)
        context.startActivity(QaSignedIn.intent(context, "write"))
        try {
            val activity = awaitActivity()
            LibraryStorageCoordinator.mutex.withLock {
                showPage(activity, library, entry.diary.id, isActive = { active.value })
                await { textVisible("Placing this entry in your sky…") }
                instrumentation.runOnMainSync { active.value = false }
                instrumentation.waitForIdleSync()
            }
            assertNull("An inactive page must not commit its queued star", stars.load().placementForDiary(entry.diary.id))
            instrumentation.runOnMainSync { active.value = true }
            await { textVisible("See my constellations") }
            assertEquals(target.id, stars.load().placementForDiary(entry.diary.id)?.instanceId)
        } finally {
            finishActivities()
            DiaryDeletionStore(library).deleteDiary(entry.diary.id)
            restoreTarget(stars, previousTarget)
        }
    }

    @Test fun fullCurrentConstellationOffersOnlyThoseWithRoom() = runBlocking {
        requireQa()
        val library = QaSignedIn.library(context)
        val stars = ConstellationStore(library)
        val previousTarget = stars.load().targetId
        val available = stars.load().galleryItems().filterNot { it.completed }
        assumeTrue("Need another constellation with room after the target fills", available.size > 1)
        val target = available.minBy { it.totalStars - it.litStars }
        val fillers = (1..(target.totalStars - target.litStars)).map { fixture(200 + it) }
        val entry = fixture(299)
        val diaries = OwnDiaryStore(library)
        (fillers + entry).forEach { diaries.save(it) }
        stars.setTarget(target.id)
        try {
            fillers.forEach { stars.placeDiary(it.diary.id, target.id) }
            assertTrue(stars.load().galleryItems().first { it.id == target.id }.completed)
            context.startActivity(QaSignedIn.intent(context, "write"))
            val activity = awaitActivity()
            showPage(activity, library, entry.diary.id)
            await { textVisible("Choose where it shines") }
            assertNull(stars.load().placementForDiary(entry.diary.id))
            val completedName = target.name + if (target.ordinal > 1) " · ${target.ordinal}" else ""
            assertFalse("A full constellation cannot be offered", textVisible("Light a star in $completedName"))
        } finally {
            finishActivities()
            (fillers + entry).forEach { DiaryDeletionStore(library).deleteDiary(it.diary.id) }
            restoreTarget(stars, previousTarget)
        }
    }

    @Test fun compactFrameWithLargeTextCanScrollToBothActions() = runBlocking {
        requireQa()
        val library = QaSignedIn.library(context)
        val stars = ConstellationStore(library)
        val entry = fixture(103)
        val target = stars.load().galleryItems().first { !it.completed }
        OwnDiaryStore(library).save(entry)
        stars.placeDiary(entry.diary.id, target.id)
        context.startActivity(QaSignedIn.intent(context, "write"))
        try {
            val activity = awaitActivity()
            showCompactPage(activity, library, entry.diary.id)
            // The title also exists on the loading page, whose scroll state is replaced
            // when the saved placement finishes loading. Wait for the actual star content.
            await { nodes().any { it.text?.toString()?.contains(" · Star ") == true } }
            capture("04-compact-before-scroll")
            await {
                if (!textVisible("See my constellations")) {
                    scrollForward()
                    delay(350)
                }
                textVisible("See my constellations")
            }
            assertTrue("The collection action remains reachable at large text", textVisible("See my constellations"))
            await {
                if (!textVisible("Write another")) {
                    scrollForward()
                    delay(350)
                }
                textVisible("Write another")
            }
            assertTrue("The lower action remains reachable at large text", textVisible("Write another"))
            capture("04-compact-large-text")
        } finally {
            finishActivities()
            DiaryDeletionStore(library).deleteDiary(entry.diary.id)
        }
    }

    @Test fun compactChooserShowsTheNewStarFromTheTopAfterScrolling() = runBlocking {
        requireQa()
        val library = QaSignedIn.library(context)
        val stars = ConstellationStore(library)
        val previousTarget = stars.load().targetId
        val entry = fixture(104)
        OwnDiaryStore(library).save(entry)
        stars.setTarget(null)
        context.startActivity(QaSignedIn.intent(context, "write"))
        try {
            val activity = awaitActivity()
            showCompactPage(activity, library, entry.diary.id)
            await { textVisible("Choose where it shines") }
            var scrolled = false
            await {
                if (nodes().none { it.isVisibleToUser && it.text?.toString()?.startsWith("Light a star in ") == true }) {
                    scrollForward()
                    scrolled = true
                    delay(250)
                }
                nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith("Light a star in ") == true }
            }
            assertTrue("This test needs the chooser to start scrolled", scrolled)
            val choose = nodes().first { it.isVisibleToUser && it.text?.toString()?.startsWith("Light a star in ") == true }
            click(choose)
            await { stars.load().placementForDiary(entry.diary.id) != null }
            await { textVisible("A moment, shared") && nodes().any {
                it.isVisibleToUser && it.text?.toString()?.contains(" · Star ") == true
            } }
            capture("05-compact-chooser-arrival")
        } finally {
            finishActivities()
            DiaryDeletionStore(library).deleteDiary(entry.diary.id)
            restoreTarget(stars, previousTarget)
        }
    }

    private fun showPage(
        activity: MainActivity,
        library: android.content.Context,
        diaryId: String,
        isActive: () -> Boolean = { true },
        onSeeConstellations: () -> Unit = {},
    ) = instrumentation.runOnMainSync {
        activity.setContent {
            StargleanTheme {
                CompositionLocalProvider(LocalContext provides library) {
                    Box(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding()) {
                        PublishedStarPage(diaryId, isActive = isActive(), published = true,
                            onSeeConstellations = onSeeConstellations, onWriteAnother = {})
                    }
                }
            }
        }
    }

    private fun showCompactPage(activity: MainActivity, library: android.content.Context, diaryId: String) =
        instrumentation.runOnMainSync {
            activity.setContent {
                val density = LocalDensity.current
                StargleanTheme {
                    CompositionLocalProvider(LocalContext provides library,
                        LocalDensity provides Density(density.density, 1.8f)) {
                        Box(Modifier.fillMaxSize().background(StargleanColors.Ink).safeDrawingPadding()) {
                            Box(Modifier.width(320.dp).height(420.dp)) {
                                PublishedStarPage(diaryId, isActive = true, published = true,
                                    onSeeConstellations = {}, onWriteAnother = {})
                            }
                        }
                    }
                }
            }
        }

    private fun finishActivities() = instrumentation.runOnMainSync {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach { it.finish() }
    }

    private suspend fun restoreTarget(stars: ConstellationStore, previousTarget: String?) {
        stars.setTarget(previousTarget?.takeIf { id -> stars.load().galleryItems().any { it.id == id && !it.completed } })
    }

    private fun requireQa() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("starsQa") == "true")
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_Location_QA", avd)
        assertTrue(BuildConfig.LOCAL_DEVICE_EDITION)
    }

    private suspend fun awaitActivity(): MainActivity {
        var found: MainActivity? = null
        withTimeout(12_000) {
            while (found == null) {
                instrumentation.runOnMainSync {
                    found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().singleOrNull()
                }
                if (found == null) delay(100)
            }
        }
        return checkNotNull(found)
    }

    private fun textVisible(value: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == value }
    private suspend fun scrollForward() {
        var bounds: Rect? = null
        val deadline = SystemClock.uptimeMillis() + 1_200L
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = nodes().filter { node ->
                // HorizontalPager also exposes SCROLL_FORWARD. Only the outer vertical
                // page exposes SCROLL_DOWN; paging constellations cannot reveal the button.
                node.isVisibleToUser && node.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
                }
            }
            for (node in candidates) {
                val rect = Rect().also(node::getBoundsInScreen)
                if (rect.width() >= 100 && rect.height() >= 100) bounds = rect
                if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            }
            delay(100)
        }
        val rect = checkNotNull(bounds) { "The compact page has no reachable scroll region" }
        val x = rect.centerX().toFloat()
        val startY = rect.top + rect.height() * .78f
        val endY = rect.top + rect.height() * .22f
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, y: Float): Boolean {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            return try { instrumentation.uiAutomation.injectInputEvent(event, true) }
            finally { event.recycle() }
        }
        assertTrue("Could not start a touch scroll", send(MotionEvent.ACTION_DOWN, startY))
        repeat(12) { index ->
            Thread.sleep(16)
            val y = startY + (endY - startY) * (index + 1) / 12f
            assertTrue("Could not move the touch scroll", send(MotionEvent.ACTION_MOVE, y))
        }
        assertTrue("Could not finish a touch scroll", send(MotionEvent.ACTION_UP, endY))
    }
    private fun click(start: AccessibilityNodeInfo) {
        var node: AccessibilityNodeInfo? = start
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private suspend fun await(condition: suspend () -> Boolean) = withTimeout(12_000) { while (!condition()) delay(100) }
    private suspend fun capture(name: String) {
        delay(500)
        val directory = checkNotNull(context.getExternalFilesDir("stars-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "published-$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
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
