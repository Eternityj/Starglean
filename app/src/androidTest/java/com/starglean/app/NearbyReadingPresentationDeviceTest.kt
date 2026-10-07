package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.ui.screens.NearbyReadingPresentation
import com.starglean.app.ui.screens.NearbyReadingShell
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production shell with synthetic semantics only: no account, network, GPS or diary reads. */
@RunWith(AndroidJUnit4::class)
class NearbyReadingPresentationDeviceTest {
    @Test fun readableCheckingAndRecoveryUseOneProductionShell() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("readingShellQa") == "true")
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertTrue(property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
            in setOf("Starglean_QA", "Starglean_Compat_API30"))

        val context = instrumentation.targetContext
        val presentation = mutableStateOf(NearbyReadingPresentation.Readable)
        val closes = AtomicInteger()
        var activity: MainActivity? = null
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        suspend fun await(predicate: () -> Boolean) = withTimeout(12_000) {
            while (!predicate()) delay(30)
        }
        fun visibleText(value: String) = nodes(instrumentation).any {
            it.isVisibleToUser && it.text?.toString() == value
        }
        fun visibleDescription(value: String) = nodes(instrumentation).any {
            it.isVisibleToUser && it.contentDescription?.toString() == value
        }
        try {
            await {
                instrumentation.runOnMainSync {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                activity != null
            }
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        Box(Modifier.fillMaxSize().background(StargleanColors.Ink).statusBarsPadding()) {
                            NearbyReadingShell(presentation.value, alpha = 1f,
                                onClose = { closes.incrementAndGet() }) {
                                Text("Synthetic nearby entry body", color = StargleanColors.WarmWhite)
                                Box(Modifier.fillMaxWidth().height(120.dp).semantics {
                                    contentDescription = "Synthetic nearby photo"
                                }.background(StargleanColors.InkRaised))
                                Text("Synthetic reaction control", color = StargleanColors.Gold,
                                    modifier = Modifier.semantics {
                                        contentDescription = "Synthetic nearby reaction"
                                    })
                            }
                        }
                    }
                }
            }
            await { visibleText("Synthetic nearby entry body") && visibleDescription("Synthetic nearby photo") &&
                visibleDescription("Synthetic nearby reaction") && visibleText("Close") }

            instrumentation.runOnMainSync { presentation.value = NearbyReadingPresentation.Checking }
            await { visibleText("Checking location…") &&
                visibleText("Your entry is hidden while we confirm you are still nearby.") &&
                visibleText("A moment, found") && visibleText("Close") }
            assertFalse(visibleText("Synthetic nearby entry body"))
            assertFalse(visibleDescription("Synthetic nearby photo"))
            assertFalse(visibleDescription("Synthetic nearby reaction"))
            val directory = checkNotNull(context.getExternalFilesDir("reading-shell-qa"))
            assertTrue(directory.isDirectory || directory.mkdirs())
            // Accessibility is ready before the system Activity/window transition ends.
            // Capture the settled production shell, not an outgoing QA screen mid-slide.
            delay(800)
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(directory, "reading-shell-qa.png").outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }

            instrumentation.runOnMainSync { presentation.value = NearbyReadingPresentation.Readable }
            await { visibleText("Synthetic nearby entry body") && visibleDescription("Synthetic nearby photo") }
            val close = nodes(instrumentation).first { it.isVisibleToUser && it.text?.toString() == "Close" }
            var clickable: AccessibilityNodeInfo? = close
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertTrue(checkNotNull(clickable).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await { closes.get() == 1 }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    private fun nodes(instrumentation: android.app.Instrumentation): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.packageName?.toString() == instrumentation.targetContext.packageName) result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }
}
