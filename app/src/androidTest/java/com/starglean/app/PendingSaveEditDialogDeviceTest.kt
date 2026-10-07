package com.starglean.app

import android.content.Intent
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.model.*
import com.starglean.app.core.network.*
import com.starglean.app.core.online.AndroidPendingPublicationStore
import com.starglean.app.core.online.FrozenPublication
import com.starglean.app.core.online.PendingPublication
import com.starglean.app.ui.screens.OnlineSaveSheet
import com.starglean.app.ui.screens.PendingSaveEditDialog
import com.starglean.app.ui.screens.WritingPreview
import com.starglean.app.ui.screens.WritingPreviewOrigin
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Dialog-only QA: fictional state, with no account, location, or network access. */
@RunWith(AndroidJUnit4::class)
class PendingSaveEditDialogDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test fun explainsRemoteCancellationAndKeepsLocalDraft() = runBlocking {
        requireQaDevice()
        val open = mutableStateOf(true)
        var confirmed = 0
        var dismissed = 0
        var activity: MainActivity? = null
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            activity = awaitActivity()
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        Box(Modifier.fillMaxSize().background(StargleanColors.Ink)) {
                            if (open.value) PendingSaveEditDialog(
                                onConfirm = { confirmed++; open.value = false },
                                onDismiss = { dismissed++; open.value = false },
                            )
                        }
                    }
                }
            }
            awaitText("Cancel save and edit")
            assertTrue(visibleText("Keep pending save"))
            assertTrue(nodes().any { it.text?.toString()?.contains("local draft and photos stay on this device") == true })
            capture("pending-save-edit-confirmation")

            click("Keep pending save")
            awaitDialogClosed()
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)

            instrumentation.runOnMainSync { open.value = true }
            awaitText("Cancel save and edit")
            click("Cancel save and edit")
            awaitDialogClosed()
            assertEquals(1, confirmed)
            assertEquals(1, dismissed)
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    @Test fun pendingSheetEditAndSystemBackBothOpenConfirmation() = runBlocking {
        requireQaDevice()
        val root = File(targetContext.cacheDir, "pending-save-edit-sheet-${UUID.randomUUID()}")
        assertTrue(root.mkdir())
        var activity: MainActivity? = null
        var backCount = 0
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            activity = awaitActivity()
            val storageContext = object : ContextWrapper(checkNotNull(activity)) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
                override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
                override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
            }
            val isolatedStore = AndroidPendingPublicationStore(storageContext)
            val pending = pendingFixture()
            isolatedStore.write(pending)
            val diary = pending.frozen.localRecord.diary
            val preview = WritingPreview(diary, DiaryVisibility.Public, "Test draft", WritingPreviewOrigin.OwnDraft)
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        CompositionLocalProvider(LocalContext provides storageContext) {
                            OnlineSaveSheet(preview, isActive = true, canSaveDraft = { false },
                                reconciliationFailure = "Draft fixture is read only", onRecheck = {},
                                onBack = { backCount++ }, flushDraft = { error("Test must not publish") },
                                onSaved = { error("Test must not publish") }, onSavedReturn = {},
                                onSeeConstellations = {})
                        }
                    }
                }
            }
            awaitText("Reconcile pending save")
            assertTrue(nodes().any { it.isVisibleToUser && it.text?.toString() == "‹  Edit" && it.isEnabled })
            click("‹  Edit")
            awaitText("Cancel save and edit")
            capture("pending-sheet-edit-confirmation")
            click("Keep pending save")
            awaitDialogClosed()
            assertEquals(0, backCount)

            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            awaitText("Cancel save and edit")
            click("Keep pending save")
            awaitDialogClosed()
            assertEquals(0, backCount)
            assertEquals(pending, isolatedStore.read())
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            assertTrue(root.canonicalFile.parentFile == targetContext.cacheDir.canonicalFile &&
                root.name.startsWith("pending-save-edit-sheet-"))
            assertTrue(root.deleteRecursively())
        }
    }

    private fun pendingFixture(): PendingPublication {
        val draftId = UUID.randomUUID()
        val localId = "own-$draftId"
        val draft = WritingDraft(localId, "Pending draft", "A local entry ready to edit after cancellation.",
            PaperStyle.WarmWhite, DiaryFont.Clear)
        val capturedAt = Instant.parse("2026-09-27T00:00:00Z")
        val diary = DiaryDemo(localId, draft.title, draft.body, "27 Sep",
            DiaryWeatherSnapshot(com.starglean.app.core.model.WeatherKind.Rain,
                com.starglean.app.core.model.Daylight.Night, 12, capturedAt.toString(), false),
            "Location shared with Starglean", draft.paper, draft.font)
        val request = PublishEntryRequest(UUID.randomUUID(), draft.title, draft.body, "warm_white", "clear",
            WeatherSnapshot(com.starglean.app.core.network.WeatherKind.Rain,
                com.starglean.app.core.network.Daylight.Night, 12, capturedAt),
            visibility = EntryVisibility.Public,
            location = ReaderLocation(-37.8, 144.9, 5.0, capturedAt, false), shareConfirmed = true)
        return PendingPublication(FrozenPublication(UUID.randomUUID(), draftId, WritingDraftState(9, draft), request,
            OwnDiaryRecord(diary, null, 1234)))
    }

    private fun requireQaDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("pendingSaveEditQa") == "true")
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val avd = property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
        assertTrue("Only isolated QA emulators may run this test",
            avd in setOf("Starglean_Location_QA", "Starglean_QA", "Starglean_Compat_API30"))
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

    private suspend fun awaitText(value: String) = withTimeout(12_000) {
        while (!visibleText(value)) delay(100)
    }

    private suspend fun awaitDialogClosed() = withTimeout(12_000) {
        while (visibleText("Cancel save and edit")) delay(100)
    }

    private fun visibleText(value: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == value }

    private fun click(value: String) {
        var node: AccessibilityNodeInfo? = nodes().first { it.isVisibleToUser && it.text?.toString() == value }
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

    private suspend fun capture(name: String) {
        delay(450)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = checkNotNull(targetContext.getExternalFilesDir("pending-save-edit-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
