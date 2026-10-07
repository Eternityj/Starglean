package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.feedback.EncounterFeedback
import com.starglean.app.ui.components.*
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The prompt remains silent; only a stable, readable diary owns encounter feedback. */
@RunWith(AndroidJUnit4::class)
class DiscoveryPromptFeedbackDeviceTest {
    @Test fun emptyAttemptStaysSilentAndFoundDiaryPlaysOnceWithoutResumeReplay() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("discoveryPromptQa") == "true")
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertTrue(property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
            in setOf("Starglean_QA", "Starglean_Compat_API30"))
        val context = instrumentation.targetContext
        val encounter = mutableIntStateOf(1)
        val empty = mutableStateOf(false)
        val readable = mutableStateOf(false)
        val active = mutableStateOf(true)
        val motion = mutableStateOf(true)
        val plays = AtomicInteger()
        val stops = AtomicInteger()
        val progress = AtomicReference(1f)
        val feedback = object : EncounterFeedback {
            override fun play(): Boolean { plays.incrementAndGet(); return true }
            override fun stop() { stops.incrementAndGet() }
            override fun release() { stop() }
        }
        var activity: MainActivity? = null
        suspend fun await(label: String, predicate: () -> Boolean) {
            try { withTimeout(12_000) { while (!predicate()) delay(30) } }
            catch (timeout: TimeoutCancellationException) {
                throw AssertionError("$label: plays=${plays.get()}, stops=${stops.get()}, " +
                    "progress=${progress.get()}", timeout)
            }
        }
        fun visible(text: String): Boolean {
            fun find(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isVisibleToUser && node.text?.toString() == text) return true
                return (0 until node.childCount).any { find(node.getChild(it)) }
            }
            return find(instrumentation.uiAutomation.rootInActiveWindow)
        }
        fun capture(name: String) {
            val directory = checkNotNull(context.getExternalFilesDir("discovery-prompt-qa"))
            assertTrue(directory.isDirectory || directory.mkdirs())
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(directory, "$name.png").outputStream().use {
                assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { image.recycle() }
        }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("activity resumed") {
                instrumentation.runOnMainSync {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                activity != null
            }
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        val arrival = rememberEncounterArrival(encounter.intValue, readable.value,
                            active.value, motion.value, feedback)
                        // Observe animation during composition, not only in SideEffect; otherwise
                        // an undrawn test animation has no reason to recompose on intermediate frames.
                        val arrivalProgress = arrival.value
                        SideEffect { progress.set(arrivalProgress) }
                        Box(Modifier.fillMaxSize().background(StargleanColors.Ink).statusBarsPadding()) {
                            DiscoverStarfield(motionEnabled = false, subdued = false)
                            Column(Modifier.fillMaxSize().padding(bottom = 80.dp)) {
                                Column(Modifier.weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = {}) { Text("Account", color = StargleanColors.MutedText) }
                                        Text("10 stars", color = StargleanColors.Gold,
                                            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
                                    }
                                    Spacer(Modifier.weight(1f))
                                    DiscoveryPrompt(empty.value)
                                    if (readable.value) Text("A moment, found", color = StargleanColors.WarmWhite)
                                    Spacer(Modifier.weight(1f))
                                    Spacer(Modifier.height(12.dp))
                                }
                            }
                        }
                    }
                }
            }
            await("idle prompt") { visible("Shake for a star") }
            delay(400)
            capture("01-idle")
            assertEquals("Merely opening Find is silent", 0, plays.get())
            instrumentation.runOnMainSync { empty.value = true }
            await("empty prompt") { visible("No stars can fall here.") }
            delay(300)
            assertEquals("An empty encounter has no arrival feedback", 0, plays.get())
            capture("02-no-stars")
            instrumentation.runOnMainSync { empty.value = false }
            instrumentation.runOnMainSync { readable.value = true }
            await("arrival starts") { progress.get() == 0f }
            await("found diary feedback") { plays.get() == 1 && progress.get() in .1f.. .8f }
            val beforePause = stops.get()
            instrumentation.runOnMainSync { active.value = false }
            await("pause cancellation") { stops.get() > beforePause }
            instrumentation.runOnMainSync { active.value = true }
            delay(350)
            assertEquals("Returning to Find never replays an arrival", 1, plays.get())
            instrumentation.runOnMainSync { readable.value = false; active.value = false; encounter.intValue = 2 }
            delay(ENCOUNTER_ARRIVAL_STABILITY_MILLIS + 80)
            instrumentation.runOnMainSync { active.value = true; readable.value = true; motion.value = false }
            await("new diary arrival with reduced motion") { plays.get() == 2 }
            delay(100)
            assertEquals("Reduced motion keeps one feedback per readable diary", 2, plays.get())
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }
}
