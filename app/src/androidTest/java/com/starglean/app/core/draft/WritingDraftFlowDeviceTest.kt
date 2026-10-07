package com.starglean.app.core.draft

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.BuildConfig
import com.starglean.app.QaSignedIn
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit disposable-emulator test only; never touches the user's preview emulator. */
@RunWith(AndroidJUnit4::class)
class WritingDraftFlowDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val body = "这是一段用于草稿恢复验收的虚构文字，不是用户日记。"
    private val title = "草稿恢复验收（测试）"

    @Test fun actualEditorPersistsAndRestoresAcrossTabsAndColdProcess() = runBlocking {
        assertTrue("Draft persistence QA must exercise the phone edition", BuildConfig.LOCAL_DEVICE_EDITION)
        val phase = InstrumentationRegistry.getArguments().getString("draftPhase")
        assumeTrue(phase in setOf("prepare", "verify"))
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_Location_QA", avd)
        val store = WritingDraftStore(QaSignedIn.library(context))
        if (phase == "prepare") {
            // Do not replace an unknown existing draft, even on the disposable AVD.
            assertNull("Prepare requires an empty isolated draft store", store.load().draft)
        } else {
            val restored = store.load().draft
            assertTrue(restored?.title == title && restored.body == body)
        }
        val activity = instrumentation.startActivitySync(QaSignedIn.intent(context, "write"))
        await { editables().size == 2 && editables().all { it.isEnabled } }
        if (phase == "prepare") {
            setText(editables()[0], title)
            setText(editables()[1], "星")
            withTimeout(5_000) { while (store.load().draft?.body != "星") delay(50) }
            setText(editables()[1], body)
            // Leave immediately after the last input. The old composition cannot cancel storage.
            click("Planet")
            withTimeout(5_000) { while (store.load().draft?.body != body) delay(50) }
            click("Write")
            await { editables().any { it.text?.toString() == body } }
            scrollToAndClick("Preview")
            await { nodes().any { it.text?.toString() == "Ready to save" } }
            // The diary paper shows a location status, never a status glued to "Near …".
            assertTrue(nodes().any { it.text?.toString() == "Your fresh location is added when you save" })
            assertTrue(nodes().none { it.text?.toString()?.startsWith("Near ") == true })
            click("Planet")
            click("Write")
            await { nodes().any { it.text?.toString() == "Ready to save" } }
            // Preview must survive a real activity recreation, without fetching new weather.
            instrumentation.runOnMainSync { activity.recreate() }
            await { nodes().any { it.text?.toString() == "Ready to save" } }
        } else {
            assertTrue(editables().any { it.text?.toString() == body })
            val confirmed = store.load()
            assertTrue(confirmed.draft?.title == title && confirmed.draft.body == body)
            assertNull(store.clearIfMatches(confirmed).draft)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(8_000) {
        while (!condition()) delay(100)
    }
    private fun nodes(): List<AccessibilityNodeInfo> {
        val output = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            output += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return output
    }
    private fun editables() = nodes().filter { it.isEditable }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        assertTrue("Editor must accept text through its accessibility action", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
    }
    private suspend fun click(label: String) {
        await { nodes().any { it.text?.toString() == label || it.contentDescription?.toString() == label } }
        var node: AccessibilityNodeInfo? = nodes().first { it.text?.toString() == label || it.contentDescription?.toString() == label }
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("A visible action must be clickable: $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        delay(450)
    }
    private suspend fun scrollToAndClick(label: String) {
        repeat(8) {
            if (nodes().any { it.text?.toString() == label && it.isVisibleToUser }) { click(label); return }
            nodes().filter { it.isScrollable }.forEach { it.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
            delay(300)
        }
        fail("Expected editor action not reachable: $label")
    }
}
