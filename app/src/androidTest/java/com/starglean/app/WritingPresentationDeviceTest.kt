package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryVisibility
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.weather.CurrentWritingWeather
import com.starglean.app.core.weather.WeatherPlaceGranularity
import com.starglean.app.ui.screens.ToolOptions
import com.starglean.app.ui.screens.WritingDraftPersistence
import com.starglean.app.ui.screens.WritingDraftSession
import com.starglean.app.ui.screens.WritingDraftUiState
import com.starglean.app.ui.screens.WritingHeading
import com.starglean.app.ui.screens.WritingPaper
import com.starglean.app.ui.screens.WritingPhotoControls
import com.starglean.app.ui.screens.WritingTool
import com.starglean.app.ui.screens.WritingToolRow
import com.starglean.app.ui.screens.WritingWeatherStatus
import com.starglean.app.ui.screens.WritingWeatherUiState
import com.starglean.app.ui.screens.writingDraftStatusMessage
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real write-page components with fictional memory-only state; pickers and save actions are never invoked. */
@RunWith(AndroidJUnit4::class)
class WritingPresentationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test fun refreshedWritingPresentationKeepsToolsAndSafetyCopyClear() = runBlocking {
        requireQaDevice()
        val draft = WritingDraft(
            id = "own-${UUID.randomUUID()}",
            title = "A fictional morning",
            body = "Soft clouds crossed a sky used only for this presentation test.",
            paper = PaperStyle.WarmWhite,
            font = DiaryFont.Clear,
        )
        var stored = WritingDraftState(1L, draft)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val session = WritingDraftSession(object : WritingDraftPersistence {
            override suspend fun load(): WritingDraftState = stored
            override suspend fun save(expectedRevision: Long, draft: WritingDraft): WritingDraftState {
                check(expectedRevision == stored.revision)
                return WritingDraftState(stored.revision + 1L, draft).also { stored = it }
            }
            override suspend fun clearIfMatches(expected: WritingDraftState): WritingDraftState {
                if (stored == expected) stored = WritingDraftState(stored.revision + 1L, null)
                return stored
            }
        }, scope)
        val font = mutableStateOf(DiaryFont.Clear)
        val paper = mutableStateOf(PaperStyle.WarmWhite)
        val selectedTool = mutableStateOf<WritingTool?>(null)
        val draftStatus = mutableStateOf(WritingDraftUiState(draft = draft, loaded = true))
        val snapshot = DiaryWeatherSnapshot(WeatherKind.Cloudy, Daylight.Day, 12)
        val observed = Instant.parse("2026-09-28T05:30:00Z")
        val current = CurrentWritingWeather(
            snapshot,
            observed,
            observed,
            ZoneId.of("Australia/Melbourne"),
            placeName = QA_PLACE,
            placeGranularity = WeatherPlaceGranularity.Street,
            locationAccuracyMeters = 14.2f,
        )
        val weather = WritingWeatherUiState(
            current = current,
            enabled = true,
            permissionGranted = true,
            precisePermissionGranted = true,
            placeLookupEnabled = true,
            placeName = QA_PLACE,
            placeGranularity = WeatherPlaceGranularity.Street,
            locationAccuracyMeters = 14.2f,
        )
        var activity: MainActivity? = null
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            activity = awaitActivity()
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        Column(
                            Modifier.fillMaxSize().background(StargleanColors.Ink).systemBarsPadding()
                                .verticalScroll(rememberScrollState()).padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            WritingHeading()
                            WritingWeatherStatus(
                                snapshot = snapshot,
                                weather = weather,
                                sample = false,
                                testLocation = false,
                                permissionNotice = null,
                                onDetails = {},
                                onCancel = {},
                                onAction = {},
                            )
                            WritingPaper(
                                title = draft.title,
                                body = draft.body,
                                paper = paper.value,
                                font = font.value,
                                height = 340.dp,
                                enabled = true,
                                onTitleChange = {},
                                onBodyChange = {},
                            )
                            WritingToolRow(
                                photoCount = 0,
                                font = font.value,
                                paper = paper.value,
                                visibility = DiaryVisibility.Private,
                                selectedTool = selectedTool.value,
                                enabled = true,
                                onSelect = { selectedTool.value = it },
                            )
                            when (selectedTool.value) {
                                WritingTool.Photo -> WritingPhotoControls(session, draft.id, enabled = true)
                                null -> Unit
                                else -> ToolOptions(
                                    tool = selectedTool.value,
                                    font = font.value,
                                    paper = paper.value,
                                    visibility = DiaryVisibility.Private,
                                    onFont = { font.value = it },
                                    onPaper = { paper.value = it },
                                    onVisibility = {},
                                )
                            }
                            writingDraftStatusMessage(
                                debugExample = false,
                                draftHydrated = true,
                                draftUi = draftStatus.value,
                            )?.let { Text(it, color = StargleanColors.MutedText, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }

            awaitText("Let a star take flight", "new writing heading")
            awaitText(QA_PLACE, "actual weather place")
            assertAbsent("Write a moment")
            assertAbsent("A quiet place for whatever today brings.")
            assertAbsent("Take your time.")
            assertAbsent("Near this street")
            assertAbsent("Weather location")
            assertAbsent("Draft saved locally")
            capture("00-default-writing-page")

            scrollAndClick("Font")
            scrollUntilVisible("Serif")
            await("all font options composed") {
                listOf("Clean", "Handwritten", "Serif").all(::renderedText)
            }
            assertTrue("The toolbar label stays Font while its options are expanded", visibleText("Font"))
            clickText("Serif")
            await("Serif font callback") { font.value == DiaryFont.Serif }
            assertTrue(visibleText("Font"))
            capture("01-font-panel")

            scrollBackAndClick("Background")
            scrollUntilVisible("Midnight")
            await("all background options composed") {
                listOf("Ivory", "Kraft", "Midnight").all(::renderedText)
            }
            assertTrue("The toolbar label stays Background while its options are expanded", visibleText("Background"))
            clickText("Midnight")
            await("Midnight background callback") { paper.value == PaperStyle.Midnight }
            assertTrue(visibleText("Background"))

            scrollBackAndClick("Photos 0/2")
            scrollUntilVisible("Timeless moments")
            scrollUntilVisible("Choose photo")
            assertTrue(visibleText("Take photo"))
            assertAbsent("Up to two photos")
            assertAbsent("Photo attachments are available in your own drafts")
            capture("02-photo-panel")

            instrumentation.runOnMainSync {
                draftStatus.value = draftStatus.value.copy(saving = true, error = null)
            }
            scrollUntilVisible("Saving your draft…")
            instrumentation.runOnMainSync {
                draftStatus.value = draftStatus.value.copy(saving = false, error = "Fictional failure")
            }
            awaitText("Draft not saved · Retry before closing", "failure status")
            instrumentation.runOnMainSync {
                draftStatus.value = draftStatus.value.copy(error = null)
            }
            await("normal draft status hidden again") {
                !visibleText("Saving your draft…") && !visibleText("Draft not saved · Retry before closing")
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            scope.cancel()
        }
    }

    private fun requireQaDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("writingPresentationQa") == "true")
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() } }
        val avd = property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
        assertTrue("Only a named isolated QA emulator may run this test",
            avd in setOf("Starglean_QA", "Starglean_Compat_API30"))
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

    private suspend fun awaitText(value: String, label: String) = await(label) { visibleText(value) }

    private suspend fun await(label: String, condition: () -> Boolean) {
        try {
            withTimeout(12_000) { while (!condition()) delay(100) }
        } catch (failure: Throwable) {
            throw AssertionError("Timed out waiting for $label. Visible text: ${visibleTexts().joinToString()}", failure)
        }
    }

    private suspend fun scrollAndClick(value: String) {
        scrollUntilVisible(value)
        clickText(value)
    }

    private suspend fun scrollBackAndClick(value: String) {
        scrollBackUntilVisible(value)
        clickText(value)
    }

    private suspend fun scrollUntilVisible(value: String) = withTimeout(12_000) {
        while (!visibleText(value)) {
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(120)
        }
    }

    private suspend fun scrollBackUntilVisible(value: String) = withTimeout(12_000) {
        while (!visibleText(value)) {
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(120)
        }
    }

    private fun clickText(value: String) {
        var node: AccessibilityNodeInfo? = nodes().firstOrNull {
            it.isVisibleToUser && it.text?.toString() == value
        }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Clickable text not found: $value", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun assertAbsent(fragment: String) {
        assertFalse("Removed copy must not be rendered: $fragment",
            nodes().any { it.text?.toString()?.contains(fragment) == true })
    }

    private fun visibleText(value: String) = nodes().any {
        it.isVisibleToUser && it.text?.toString() == value
    }

    private fun renderedText(value: String) = nodes().any { it.text?.toString() == value }

    private fun visibleTexts() = nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }

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
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = checkNotNull(targetContext.getExternalFilesDir("writing-presentation-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        try {
            File(directory, "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
    }

    private companion object {
        const val QA_PLACE = "Example Street · Sample District · Sample City"
    }
}
