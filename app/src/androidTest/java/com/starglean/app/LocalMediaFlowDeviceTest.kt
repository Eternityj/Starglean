package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftStore
import com.starglean.app.core.library.DiaryDeletionStore
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.LibraryPhotoCleanupStore
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.model.*
import com.starglean.app.core.stars.ConstellationStore
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Intentionally writes synthetic fixtures, only on the named disposable media QA AVD. */
@RunWith(AndroidJUnit4::class)
class LocalMediaFlowDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    @Test fun attachmentViewingDeletionAndDraftRemoval(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("completionMediaQa") == "true")
        val avd = instrument.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_Location_QA", avd)
        assertTrue(BuildConfig.LOCAL_DEVICE_EDITION)
        val library = QaSignedIn.library(context)
        val diaries = OwnDiaryStore(library)
        val drafts = WritingDraftStore(library)
        val stars = ConstellationStore(library)
        val photoStore = AndroidPhotoStore(library)
        val cleanup = LibraryPhotoCleanupStore(library)
        val knownQaIds = (2..6).mapTo(linkedSetOf()) { index ->
            "own-34343434-1111-4111-8111-${index.toString().padStart(12, '0')}"
        }
        val originalDiaries = diaries.load()
        val originalDiaryIds = originalDiaries.mapTo(linkedSetOf()) { it.diary.id }
        assertTrue("Only an empty library or the five known legacy QA records may exist",
            originalDiaryIds.isEmpty() || originalDiaryIds == knownQaIds)
        val initialDraft = drafts.load()
        assertNull("Never replace an existing draft", initialDraft.draft)
        val originalStars = stars.load()
        assertEquals("Every existing star must belong to the accepted QA library", originalDiaryIds,
            originalStars.placements.mapTo(linkedSetOf()) { it.diaryId })
        assertTrue("Accepted QA stars must remain in the isolated Cygnus fixture",
            originalStars.placements.all { it.instanceId == "cygnus-1" })

        val diaryId = "own-${UUID.randomUUID()}"
        val draftId = "own-${UUID.randomUUID()}"
        val uniqueTitle = "QA light ${diaryId.takeLast(8)}"
        val fixtureDiaryIds = linkedSetOf(diaryId)
        var diaryPhoto: DiaryPhoto? = null
        var draftPhoto: DiaryPhoto? = null
        try {
            if (originalDiaryIds.isEmpty()) {
                repeat(5) { index ->
                    val companionId = "own-${UUID.randomUUID()}"
                    val companion = OwnDiaryRecord(DiaryDemo(companionId, "QA companion ${index + 1}",
                        "A synthetic companion used only to complete the isolated QA constellation.",
                        "27 Sep 2026", DiaryWeatherSnapshot.Unavailable, "", PaperStyle.WarmWhite,
                        DiaryFont.Clear), null, 1_790_467_100_000 + index)
                    diaries.save(companion)
                    fixtureDiaryIds += companionId
                    stars.placeDiary(companionId, "cygnus-1")
                }
            }
            val diaryAttachment = importSyntheticPhoto(photoStore, Color.rgb(19, 39, 62))
            diaryPhoto = diaryAttachment
            val record = OwnDiaryRecord(DiaryDemo(diaryId, uniqueTitle,
                "This is a fictional moment for an isolated visual test. No personal writing or location is used.",
                "27 Sep 2026", DiaryWeatherSnapshot.Unavailable, "", PaperStyle.WarmWhite, DiaryFont.Serif,
                listOf(diaryAttachment)), null, 1_790_467_200_000)
            diaries.save(record)
            stars.placeDiary(diaryId, "cygnus-1")
            stars.setHome(setOf("cygnus-1"))

            launch("constellation")
            await("constellation-read-memories") { visible("Read these memories") }
            click("Read these memories")
            await("constellation-entry-list") { visible("Selected entries") && nodes().any { it.isScrollable } }
            scrollTo(uniqueTitle)
            await("constellation-fixture-title") { visible(uniqueTitle) }
            click(uniqueTitle)
            await("diary-photo-thumbnail") { nodes().any { it.contentDescription == "Photo 1 of 1" } }
            capture("01-diary-photo")
            clickDescription("Photo 1 of 1")
            await("diary-photo-dialog") { visible("A moment in your diary") }
            capture("02-full-photo")
            click("Close")
            scrollTo("Delete entry")
            click("Delete entry")
            await("diary-delete-confirmation") { visible("Delete this entry?") }
            capture("03-delete-confirmation")
            click("Delete permanently")
            await("diary-record-deleted") { diaries.load().none { it.diary.id == diaryId } }
            assertNull(stars.load().placementForDiary(diaryId))
            assertEquals(originalStars.placements.size + fixtureDiaryIds.size - 1,
                stars.load().placements.size)
            assertFalse("An incomplete constellation must leave home", "cygnus-1" in stars.load().homeIds)
            await("diary-photo-file-deleted") { !photoStore.exists(diaryAttachment.ref) }
            capture("04-after-delete")
            finishMainActivity()

            val draftAttachment = importSyntheticPhoto(photoStore, Color.rgb(48, 29, 57))
            draftPhoto = draftAttachment
            val persisted = drafts.save(initialDraft.revision, WritingDraft(draftId, "QA draft",
                "A synthetic local draft used only to verify attachment restoration.",
                PaperStyle.WarmWhite, DiaryFont.Clear, listOf(draftAttachment)))
            assertEquals(draftAttachment.ref, persisted.draft?.photos?.single()?.ref)
            launch("write")
            scrollToDescription("Photo 1 of 1")
            assertEquals(draftAttachment.ref, drafts.load().draft?.photos?.single()?.ref)
            assertTrue(photoStore.exists(draftAttachment.ref))

            recreateMainActivity()
            scrollToDescription("Photo 1 of 1")
            assertEquals("Activity reconstruction must retain the persisted reference", draftAttachment.ref,
                drafts.load().draft?.photos?.single()?.ref)
            assertTrue("Reconstruction must not collect the referenced file", photoStore.exists(draftAttachment.ref))
            scrollTo("Remove photo 1")
            click("Remove photo 1")
            await("draft-photo-reference-removed") { drafts.load().draft?.photos?.isEmpty() == true }
            await("draft-photo-file-deleted") { !photoStore.exists(draftAttachment.ref) }
            capture("05-draft-photo-removed")
        } finally {
            finishMainActivity()
            val currentDraft = drafts.load()
            if (currentDraft.draft?.id == draftId) drafts.clearIfMatches(currentDraft)
            draftPhoto?.let { cleanup.cleanupCandidates(setOf(it.ref)) }
            val deletion = DiaryDeletionStore(library)
            fixtureDiaryIds.forEach { fixtureId ->
                if (diaries.load().any { it.diary.id == fixtureId }) deletion.deleteDiary(fixtureId)
            }
            diaryPhoto?.let { cleanup.cleanupCandidates(setOf(it.ref)) }
        }
    }

    private suspend fun importSyntheticPhoto(store: AndroidPhotoStore, background: Int): DiaryPhoto {
        val source = File.createTempFile("synthetic-media-qa-", ".png", context.cacheDir)
        val pixels = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
        return try {
            Canvas(pixels).apply {
                drawColor(background)
                drawCircle(600f, 200f, 100f, Paint().apply { color = Color.rgb(221, 192, 134) })
            }
            source.outputStream().use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
            store.importSelected(Uri.fromFile(source))
        } finally {
            pixels.recycle()
            source.delete()
        }
    }

    private fun launch(destination: String) {
        context.startActivity(QaSignedIn.intent(context, destination).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
        instrument.waitForIdleSync()
    }

    private fun recreateMainActivity() {
        instrument.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
            activity.recreate()
        }
        instrument.waitForIdleSync()
    }

    private fun finishMainActivity() {
        instrument.runOnMainSync {
            Stage.values().flatMap { stage ->
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
            }.filterIsInstance<MainActivity>().distinct().forEach { it.finish() }
        }
        instrument.waitForIdleSync()
    }

    private suspend fun await(label: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(15_000) { while (!condition()) delay(120) }
        } catch (_: TimeoutCancellationException) {
            try { capture("failure-$label") } catch (_: Exception) { /* Preserve the stage failure. */ }
            try { writeAccessibilityFailure(label) } catch (_: Exception) { /* Evidence is best effort. */ }
            throw AssertionError("Timed out at QA stage: $label")
        }
    }

    private fun writeAccessibilityFailure(label: String) {
        val directory = File(context.getExternalFilesDir(null), "completion-media-qa").apply { mkdirs() }
        val tree = buildString {
            appendLine("stage=$label")
            nodes().forEachIndexed { index, node ->
                append(index).append('|').append(node.className ?: "")
                    .append("|visible=").append(node.isVisibleToUser)
                    .append("|clickable=").append(node.isClickable)
                    .append("|text=").append(node.text ?: "")
                    .append("|description=").append(node.contentDescription ?: "")
                    .appendLine()
            }
        }
        File(directory, "failure-$label-accessibility.txt").writeText(tree)
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrument.uiAutomation.rootInActiveWindow)
    }
    private fun visible(text: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == text }
    private fun click(text: String) = clickNode(nodes().first { it.isVisibleToUser && it.text?.toString() == text })
    private fun clickDescription(text: String) = clickNode(nodes().first { it.isVisibleToUser && it.contentDescription?.toString() == text })
    private fun clickNode(start: AccessibilityNodeInfo) {
        var node: AccessibilityNodeInfo? = start
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Action must be accessible", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private suspend fun scrollTo(text: String) {
        repeat(12) {
            if (visible(text)) return
            scrollContainerOf(nodes().firstOrNull { it.text?.toString() == text })
            delay(120)
        }
        assertTrue("Action must not be clipped", visible(text))
    }
    /** Scrolls the list that holds [target]; the first scrollable on screen may be a different list. */
    private fun scrollContainerOf(target: AccessibilityNodeInfo?) {
        var container = target?.parent
        while (container != null && !container.isScrollable) container = container.parent
        (container ?: nodes().firstOrNull { it.isScrollable })?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }
    private suspend fun scrollToDescription(text: String) {
        repeat(12) {
            if (nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == text }) return
            scrollContainerOf(nodes().firstOrNull { it.contentDescription?.toString() == text })
            delay(120)
        }
        assertTrue("Photo must not be clipped", nodes().any {
            it.isVisibleToUser && it.contentDescription?.toString() == text
        })
    }
    private suspend fun capture(name: String) {
        delay(800) // Screenshots are evidence; always wait for window and Compose animations to settle.
        val directory = File(context.getExternalFilesDir(null), "completion-media-qa").apply { mkdirs() }
        instrument.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
