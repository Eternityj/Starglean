package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.draft.*
import com.starglean.app.core.memory.*
import com.starglean.app.core.model.*
import com.starglean.app.core.storage.AccountStorageContext
import com.starglean.app.ui.screens.*
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated on-device UI verification; fictional local data only, no auth, network writes or GPS. */
@RunWith(AndroidJUnit4::class)
class MemoryImprovementsUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun settingsSearchAndDraftRecoveryAreUsableOnPhone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("improvementsQa") == "true")
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() } }
        val avd = property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
        assertTrue(avd in setOf("Starglean_QA", "Starglean_Compat_API30"))
        assertTrue(BuildConfig.LOCAL_DEVICE_EDITION)
        val accountId = UUID.randomUUID().toString()
        val storage = AccountStorageContext.create(target, accountId)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var activity: MainActivity? = null
        val page = mutableStateOf("settings")
        val dialogOpen = mutableStateOf(true)
        val draftStore = WritingDraftStore(storage)
        val first = draftStore.save(0, WritingDraft("own-${UUID.randomUUID()}", "Earlier words",
            "A safely preserved earlier draft.", PaperStyle.WarmWhite, DiaryFont.Clear))
        val pending = first.copy(revision = first.revision + 1, draft = first.draft!!.copy(title = "After the rain",
            body = "A newer thought, kept safe through an interrupted save."))
        File(storage.filesDir, "writing-draft-v1.bin.new").writeBytes(WritingDraftCodec.encode(pending))
        val session = WritingDraftSession(object : WritingDraftPersistence {
            override suspend fun load() = draftStore.load()
            override suspend fun save(expectedRevision: Long, draft: WritingDraft) = draftStore.save(expectedRevision, draft)
            override suspend fun clearIfMatches(expected: WritingDraftState) = draftStore.clearIfMatches(expected)
        }, scope)
        try {
            fun record(title: String, body: String, time: Long) = OwnDiaryRecord(
                DiaryDemo("own-${UUID.randomUUID()}", title, body, "28 Sep", DiaryWeatherSnapshot.Unavailable,
                    "No location saved", PaperStyle.WarmWhite, DiaryFont.Clear), null, time)
            // Use a separate local store for the list; an interrupted draft intentionally blocks shared-store recovery.
            val listStorage = AccountStorageContext.create(target, UUID.randomUUID().toString())
            try {
                OwnDiaryStore(listStorage).save(record("A walk by the sea", "The quiet ocean made room for a new beginning.", 1_790_553_600_000L))
                OwnDiaryStore(listStorage).save(record("A little everyday joy", "Coffee, warm sunlight, and a kind conversation.", 1_787_875_200_000L))
                target.startActivity(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                withTimeout(12_000) {
                    while (activity == null) {
                        instrumentation.runOnMainSync { activity = ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull() }
                        if (activity == null) delay(100)
                    }
                }
                instrumentation.runOnMainSync {
                    activity!!.setContent {
                        StargleanTheme {
                            AccountLibraryContent(if (page.value == "entries") listStorage else storage) {
                                Box(Modifier.fillMaxSize().background(StargleanColors.Ink)) {
                                    when (page.value) {
                                        "settings" -> SettingsScreen { }
                                        "entries" -> OwnDiariesSheet(isActive = true, onBack = {})
                                        "recovery" -> if (dialogOpen.value) DraftRecoveryDialog(session) { dialogOpen.value = false }
                                    }
                                }
                            }
                        }
                    }
                }
                awaitText("Private backup"); capture("01-settings")
                clickText("Private backup")
                awaitText("Keep your memories safe"); capture("02-backup")
                assertTrue(nodes().any { it.isPassword })
                var export: AccessibilityNodeInfo? = nodes().first { it.text?.toString() == "Create encrypted backup" }
                var disabled = false
                while (export != null) { disabled = disabled || !export.isEnabled; export = export.parent }
                assertTrue("Export must be disabled until a valid matching password is entered", disabled)
                val fields = nodes().filter { it.isEditable }
                assertEquals(2, fields.size)
                fields.forEach { field -> assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "a private test password")
                })) }
                // A normal trip to the background must clear secrets; no real file is created.
                instrumentation.uiAutomation.executeShellCommand("input keyevent 3").use {
                    FileInputStream(it.fileDescriptor).use { output -> output.readBytes() }
                }
                withTimeout(12_000) {
                    var stopped = false
                    while (!stopped) {
                        instrumentation.runOnMainSync { stopped = !activity!!.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                        if (!stopped) delay(100)
                    }
                }
                target.startActivity(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                awaitText("Backup paused.")
                assertTrue(nodes().filter { it.isEditable }.all { it.text.isNullOrEmpty() || !it.text.toString().contains("•") })
                clickText("‹  Settings")
                awaitText("Sound & touch"); clickText("Sound & touch")
                awaitText("Encounter sound"); awaitText("Play preview"); capture("03-feedback")
                instrumentation.runOnMainSync { page.value = "entries" }
                awaitText("2 entries"); capture("04-library")
                val search = nodes().first { it.isEditable }
                assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "ocean")
                }))
                awaitText("1 entry")
                assertFalse(nodes().any { it.isVisibleToUser && it.text?.toString()?.contains("A little everyday joy") == true })
                capture("05-search")
                clickText("Clear"); awaitText("2 entries")
                clickText("All dates"); awaitText("2026-09"); clickText("2026-09"); awaitText("1 entry")
                instrumentation.runOnMainSync { page.value = "recovery" }
                awaitText("Interrupted write"); capture("06-recovery")
                clickText("Interrupted write"); awaitText("✓ Interrupted write"); clickText("Confirm recovery")
                withTimeout(12_000) { while (dialogOpen.value) delay(100) }
                assertEquals("After the rain", draftStore.load().draft!!.title)
                assertNotEquals(first.draft!!.id, draftStore.load().draft!!.id)
            } finally {
                instrumentation.runOnMainSync { activity?.finish() }
                scope.cancel()
                clean(listStorage)
            }
        } finally { scope.cancel(); clean(storage) }
    }

    private fun clean(storage: AccountStorageContext) {
        listOf(storage.filesDir, storage.cacheDir, storage.noBackupFilesDir).forEach {
            assertEquals(storage.accountId, it.name)
            assertEquals("account-libraries-v1", it.parentFile?.name)
            assertTrue(it.deleteRecursively())
        }
    }
    private suspend fun awaitText(text: String) {
        try {
            withTimeout(12_000) {
                while (nodes().none { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }) delay(100)
            }
        } catch (timeout: TimeoutCancellationException) {
            capture("99-ui-failure")
            throw AssertionError("Did not find '$text'. Visible UI: ${nodes().filter { it.isVisibleToUser }.mapNotNull { it.text }.joinToString()}", timeout)
        }
    }
    private fun clickText(text: String) {
        var node: AccessibilityNodeInfo? = nodes().first { it.isVisibleToUser && it.text?.toString()?.contains(text) == true }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Clickable text not found: $text", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            result += node
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
    private suspend fun capture(name: String) {
        delay(500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = checkNotNull(target.getExternalFilesDir("improvements-qa"))
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
