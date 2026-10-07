package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.ui.screens.PublishingLocationCard
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Guarded visual QA with fictional state only; it never requests location or accesses sensors. */
@RunWith(AndroidJUnit4::class)
class PublishingLocationCardUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test fun coarseCollectingAndReadyStatesRemainClear() = runBlocking {
        requireQaDevice()
        val state = mutableStateOf(CardState(
            "Its reported accuracy is about 85 m. Starglean needs a reported accuracy of 35 m or better.",
            ready = false, optedIn = true,
        ))
        var activity: MainActivity? = null
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            activity = awaitActivity()
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        Box(Modifier.fillMaxSize().background(StargleanColors.Ink).padding(24.dp)) {
                            val value = state.value
                            PublishingLocationCard(value.message, value.ready, value.optedIn, busy = false) {}
                        }
                    }
                }
            }
            awaitText("Its reported accuracy is about 85 m. Starglean needs a reported accuracy of 35 m or better.")
            assertTrue(visibleText("Retry location"))
            capture("publishing-location-01-coarse-85m")

            instrumentation.runOnMainSync {
                state.value = CardState("Collecting a fresh location fix…", ready = false, optedIn = true)
            }
            awaitText("Collecting a fresh location fix…")
            assertTrue(visibleText("CHECKING LOCATION"))
            capture("publishing-location-02-collecting")

            instrumentation.runOnMainSync {
                state.value = CardState("Fresh location is ready for publishing.", ready = true, optedIn = true)
            }
            awaitText("Fresh location is ready for publishing.")
            assertTrue(visibleText("LOCATION READY"))
            assertTrue(!visibleText("Retry location"))
            capture("publishing-location-03-ready")
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    private fun requireQaDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("publishingLocationQa") == "true")
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText().trim() } }
        val avd = property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
        assertTrue("Only isolated QA emulators may run this test",
            avd in setOf("Starglean_QA", "Starglean_Compat_API30"))
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

    private fun visibleText(value: String) = nodes().any { it.isVisibleToUser && it.text?.toString() == value }

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
        // Accessibility can become ready before Android's activity/window fade finishes.
        delay(450)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = checkNotNull(targetContext.getExternalFilesDir("publishing-location-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        image.recycle()
    }

    private data class CardState(val message: String, val ready: Boolean, val optedIn: Boolean)
}
