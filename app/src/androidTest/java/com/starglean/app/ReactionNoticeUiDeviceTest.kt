package com.starglean.app

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.AppDestination
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.network.ReactionKind
import com.starglean.app.core.online.ReactionNotice
import com.starglean.app.core.storage.AccountStorageContext
import com.starglean.app.ui.components.StargleanBottomBar
import com.starglean.app.ui.screens.AccountLibraryContent
import com.starglean.app.ui.screens.OwnDiariesSheet
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Visual QA with fictional local data only. It never opens auth, network, sensors, or real app storage. */
@RunWith(AndroidJUnit4::class)
class ReactionNoticeUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test fun unreadReactionOpensOnItsDiaryAnimatesAndClearsFromNavigation() = runBlocking {
        requireQaDevice()
        val cacheRoot = targetContext.cacheDir.canonicalFile
        val root = Files.createTempDirectory(cacheRoot.toPath(), PREFIX).toFile().canonicalFile
        val ownerToken = UUID.randomUUID().toString()
        val marker = File(root, OWNER_MARKER).apply { writeText(ownerToken) }
        val isolated = IsolatedFilesContext(targetContext, root)
        val scoped = AccountStorageContext.create(isolated, QA_ACCOUNT_ID)
        val record = fixture()
        OwnDiaryStore(scoped).save(record)
        val notice = ReactionNotice(
            cloudEntryId = UUID.fromString("ca0aa000-0000-4000-8000-000000000001"),
            localDiaryId = record.diary.id,
            leadingKind = ReactionKind.Hug,
            unreadCount = 2,
            seenThrough = Instant.parse("2026-09-27T04:00:00Z"),
            arrivalVersion = "qa-reaction-v1",
        )
        val notices = mutableStateOf(mapOf(record.diary.id to notice))
        val consumed = AtomicBoolean(false)
        var activity: MainActivity? = null
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            activity = awaitActivity()
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    AccountLibraryContent(scoped) {
                        StargleanTheme {
                            Scaffold(bottomBar = {
                                StargleanBottomBar(
                                    selected = AppDestination.Constellations,
                                    showConstellationUnread = notices.value.isNotEmpty(),
                                    onSelect = {},
                                )
                            }) { padding ->
                                Box(Modifier.padding(padding)) {
                                    OwnDiariesSheet(
                                        isActive = true,
                                        reactionNotices = notices.value,
                                        consumeReactionAnimation = { id ->
                                            notice.takeIf { id == record.diary.id && consumed.compareAndSet(false, true) }
                                        },
                                        onBack = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }

            await { visibleDescription("New reactions to your memories") && visibleText("2 new reactions  ·  Open to see") }
            delay(350) // Let the Activity's window transition finish before visual evidence.
            capture("reaction-notice-01-bottom-unread").recycle()
            clickDescription("Open your entry, ${record.diary.title}")
            await { consumed.get() }
            scrollUntilVisible("2 new reactions")
            assertTrue("The reaction must be consumed exactly once", consumed.get())

            delay(550)
            val early = capture("reaction-notice-02-paper-early")
            delay(1_250)
            val middle = capture("reaction-notice-03-paper-middle")
            assertReactionRegionChanged(early, middle)

            // Model the acknowledged inbox only after capturing real animation frames. Clearing
            // it earlier would legitimately restart OwnDiariesSheet's keyed playback effect.
            instrumentation.runOnMainSync { notices.value = emptyMap() }
            await { !visibleDescription("New reactions to your memories") }
            capture("reaction-notice-04-unread-cleared").recycle()
            assertFalse(visibleDescription("New reactions to your memories"))
        } catch (failure: Throwable) {
            try { capture("reaction-notice-failure").recycle() } catch (_: Throwable) { /* keep the original failure */ }
            throw failure
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            removeOwnedDirectory(cacheRoot, root, marker, ownerToken)
        }
    }

    private fun fixture() = OwnDiaryRecord(
        diary = DiaryDemo(
            id = "own-ca0aa000-0000-4000-8000-000000000001",
            title = "QA only · A kind reply",
            body = "This fictional entry verifies that a new response reaches the matching paper.",
            date = "27 Sep 2026 · QA",
            weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Unknown, Daylight.Day, null, null, false),
            blurredPlace = "",
            paper = PaperStyle.WarmWhite,
            font = DiaryFont.Serif,
        ),
        place = null,
        savedAtMillis = 1_790_481_600_000L,
    )

    private fun requireQaDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("reactionNoticeQa") == "true")
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_QA", avd)
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

    private fun assertReactionRegionChanged(first: Bitmap, second: Bitmap) {
        val caption = nodes().first { it.isVisibleToUser && it.text?.toString() == "2 new reactions" }
        val bounds = Rect().also(caption::getBoundsInScreen)
        val density = targetContext.resources.displayMetrics.density
        val left = (bounds.left - 100 * density).toInt().coerceAtLeast(0)
        val right = (bounds.right + 100 * density).toInt().coerceAtMost(first.width)
        val top = (bounds.top - 125 * density).toInt().coerceAtLeast(0)
        val bottom = bounds.top.coerceAtMost(first.height)
        var changed = 0
        for (y in top until bottom step 3) for (x in left until right step 3) {
            if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
        }
        first.recycle()
        second.recycle()
        assertTrue("Two real animation keyframes must differ inside the reaction artwork", changed > 40)
    }

    private suspend fun capture(name: String): Bitmap {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = checkNotNull(targetContext.getExternalFilesDir("reaction-notice-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        return image
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(12_000) {
        while (!condition()) delay(100)
    }

    private suspend fun scrollUntilVisible(value: String) = withTimeout(12_000) {
        while (!visibleText(value)) {
            val scrollable = nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
            // Accessibility may briefly expose the previous list while Compose publishes
            // the opened diary. Wait for the new tree instead of failing on that old frame.
            scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(120)
        }
    }

    private fun visibleText(value: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == value }
    private fun visibleDescription(value: String) = nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == value }
    private fun clickDescription(value: String) {
        var node: AccessibilityNodeInfo? = nodes().first { it.isVisibleToUser && it.contentDescription?.toString() == value }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            found += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return found
    }

    private fun removeOwnedDirectory(cacheRoot: File, root: File, marker: File, ownerToken: String) {
        assertEquals(cacheRoot, root.parentFile)
        assertEquals(root, root.canonicalFile)
        assertTrue(root.name.startsWith(PREFIX))
        assertTrue(marker.isFile)
        assertEquals(ownerToken, marker.readText())
        fun removeOwned(file: File) {
            assertFalse("Refusing to follow a symbolic link", Files.isSymbolicLink(file.toPath()))
            val canonical = file.canonicalFile
            assertTrue("Owned test path escaped its root", canonical == root || canonical.path.startsWith(root.path + File.separator))
            if (file.isDirectory) requireNotNull(file.listFiles()).forEach(::removeOwned)
            assertTrue("Could not remove owned test path ${file.name}", file.delete())
        }
        removeOwned(root)
    }

    private class IsolatedFilesContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = child("files")
        override fun getNoBackupFilesDir(): File = child("no-backup")
        override fun getCacheDir(): File = child("cache")
        override fun getApplicationContext(): Context = this
        private fun child(name: String) = File(directory, name).apply { assertTrue(isDirectory || mkdirs()) }
    }

    private companion object {
        const val PREFIX = "reaction-notice-ui-"
        const val OWNER_MARKER = ".device-test-owner"
        const val QA_ACCOUNT_ID = "ca0aa000-0000-4000-8000-000000000099"
    }
}
