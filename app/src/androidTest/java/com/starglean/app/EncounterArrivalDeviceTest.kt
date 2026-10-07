package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioTrack
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.feedback.EncounterFeedback
import com.starglean.app.core.feedback.AndroidEncounterFeedback
import com.starglean.app.core.feedback.FeedbackPreferenceState
import com.starglean.app.core.feedback.EncounterFeedbackSignal
import com.starglean.app.core.network.*
import com.starglean.app.ui.components.*
import com.starglean.app.ui.screens.toDisplayDiary
import com.starglean.app.ui.space.DiscoveryArrival
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual arrival composable and diary rendering; synthetic content, no GPS/account/backend writes. */
@RunWith(AndroidJUnit4::class)
class EncounterArrivalDeviceTest {
    @Test fun androidAudioEnginePlaysFiniteSignalAndStopsWithoutAReplay() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("arrivalQa") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertTrue(property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
            in setOf("Starglean_QA", "Starglean_Compat_API30"))
        val context = instrumentation.targetContext
        // This isolated hardware smoke test has no signed-in account context.
        val engine = AndroidEncounterFeedback(context, FeedbackPreferenceState())
        // Inspect the actual engine, not a second fake implementation. No device audio settings change.
        val activeField = engine.javaClass.getDeclaredField("active").apply { isAccessible = true }
        val trackField = engine.javaClass.getDeclaredField("audioTrack").apply { isAccessible = true }
        val lockField = engine.javaClass.getDeclaredField("lock").apply { isAccessible = true }
        val monitor = checkNotNull(lockField.get(engine))
        fun isActive() = synchronized(monitor) { activeField.getBoolean(engine) }
        fun track() = synchronized(monitor) { trackField.get(engine) as? AudioTrack }
        var activity: MainActivity? = null
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            withTimeout(12_000) {
                while (activity == null) {
                    instrumentation.runOnMainSync {
                        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().firstOrNull()
                    }
                    delay(50)
                }
            }
            assertTrue("QA device should allow a foreground feedback run", engine.play())
            withTimeout(3_000) { while (track() == null && isActive()) delay(20) }
            val first = checkNotNull(track()) { "QA device audio is unavailable; engine playback was not verified" }
            val openingHead = first.playbackHeadPosition
            delay(180)
            assertEquals(AudioTrack.PLAYSTATE_PLAYING, first.playState)
            val middleHead = first.playbackHeadPosition
            delay(180)
            val laterHead = first.playbackHeadPosition
            assertTrue("PCM reached the Android mixer", middleHead > openingHead)
            assertTrue("Owned playback must continue while AudioManager reports music active", laterHead > middleHead)
            assertFalse("Active run cannot overlap itself", engine.play())
            withTimeout(3_500) { while (isActive()) delay(20) }
            assertNull(track())
            // The system mixer can report a just-released track briefly. A new run must
            // honor that music policy, so wait for the test's audible-start precondition.
            val audio = context.getSystemService(android.media.AudioManager::class.java)
            withTimeout(3_000) { while (audio.isMusicActive) delay(20) }
            assertTrue(engine.play())
            withTimeout(2_000) { while (track() == null && isActive()) delay(20) }
            engine.stop()
            assertFalse(isActive())
            assertNull(track())
            engine.release()
            assertFalse("Released engine never restarts", engine.play())

            val samples = EncounterFeedbackSignal.synthesizePcm16()
            val bytes = java.nio.ByteBuffer.allocate(44 + samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + samples.size * 2)
                .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
                .putInt(EncounterFeedbackSignal.SAMPLE_RATE_HZ).putInt(EncounterFeedbackSignal.SAMPLE_RATE_HZ * 2)
                .putShort(2).putShort(16).put("data".toByteArray(Charsets.US_ASCII)).putInt(samples.size * 2)
            samples.forEach(bytes::putShort)
            val directory = checkNotNull(context.getExternalFilesDir("arrival-qa"))
            assertTrue(directory.isDirectory || directory.mkdirs())
            File(directory, "star-flight.wav").outputStream().use { it.write(bytes.array()) }
        } finally {
            engine.release()
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    @Test fun oneArrivalPerEncounterAndWeatherSurvivesReadingRecovery() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("arrivalQa") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertTrue(property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
            in setOf("Starglean_QA", "Starglean_Compat_API30"))
        val played = AtomicInteger()
        val stopped = AtomicInteger()
        val feedback = object : EncounterFeedback {
            override fun play(): Boolean { played.incrementAndGet(); return true }
            override fun stop() { stopped.incrementAndGet() }
            override fun release() { stop() }
        }
        val encounter = mutableIntStateOf(1)
        val readable = mutableStateOf(true)
        val active = mutableStateOf(true)
        val motion = mutableStateOf(true)
        val extraRecomposition = mutableIntStateOf(0)
        val progress = AtomicReference(1f)
        val firstDrawn = AtomicReference<Float?>(null)
        val diary = DiaryContent("Rain on the window", "A quiet fictional memory, with its original rainy night saved.",
            "midnight", "serif", WeatherSnapshot(WeatherKind.Rain, Daylight.Night, 12,
                Instant.parse("2026-09-27T10:00:00Z")), emptyList()).toDisplayDiary()
        var activity: MainActivity? = null
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        suspend fun await(predicate: () -> Boolean) = withTimeout(12_000) {
            while (!predicate()) delay(30)
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
                        val arrival = rememberEncounterArrival(encounter.intValue, readable.value,
                            active.value, motion.value, feedback)
                        SideEffect { progress.set(arrival.value) }
                        Box(Modifier.fillMaxSize().background(StargleanColors.Ink).statusBarsPadding()
                            .drawBehind { firstDrawn.compareAndSet(null, arrival.value) }) {
                            if (readable.value) {
                                DiaryWeatherBackground(diary.weatherSnapshot, motionEnabled = true,
                                    modifier = Modifier.fillMaxSize().graphicsLayer {
                                        alpha = DiscoveryArrival.sample(arrival.value).revealAlpha
                                    })
                                Column(Modifier.fillMaxSize().padding(22.dp).graphicsLayer {
                                    alpha = DiscoveryArrival.sample(arrival.value).revealAlpha
                                }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Arrival QA · synthetic entry ${extraRecomposition.intValue}", color = StargleanColors.Gold)
                                    Text("A moment, found", color = StargleanColors.WarmWhite,
                                        style = MaterialTheme.typography.titleLarge)
                                    DiaryPaper(diary, Modifier.fillMaxWidth(), showDemoBadge = false,
                                        readingLayout = true, minimumHeight = 420.dp,
                                        placeLabel = "Left nearby · Exact place is private")
                                }
                                if (arrival.value < 1f) {
                                    DiscoveryFlyingStar({ arrival.value }, Offset(.74f, .25f), Modifier.fillMaxSize())
                                    DiscoveryArrivalLight({ arrival.value }, Modifier.fillMaxSize())
                                }
                            } else Text("Content hidden while permission is unavailable", color = StargleanColors.WarmWhite)
                        }
                    }
                }
            }
            // The very first drawn frame must already hide a new encounter. A fixed 80 ms check
            // passed even while the completed diary was drawn for a frame or two before hiding.
            await { firstDrawn.get() != null }
            assertEquals("A new encounter stays hidden from its first frame", 0f, firstDrawn.get())
            // A short eligibility pulse must cancel before consuming this encounter.
            assertEquals("Stability wait must precede feedback", 0, played.get())
            instrumentation.runOnMainSync { readable.value = false }
            delay(ENCOUNTER_ARRIVAL_STABILITY_MILLIS + 80)
            assertEquals("A transient readable pulse must not play feedback", 0, played.get())
            instrumentation.runOnMainSync { readable.value = true }
            await { played.get() == 1 && progress.get() > .15f && progress.get() < .9f }
            val directory = checkNotNull(context.getExternalFilesDir("arrival-qa"))
            assertTrue(directory.isDirectory || directory.mkdirs())
            fun capture(name: String) {
                val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(directory, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { image.recycle() }
            }
            capture("01-star-approach.png")
            await { progress.get() == 1f && stopped.get() >= 1 }
            delay(700)
            capture("02-saved-weather-reading.png")
            repeat(5) {
                instrumentation.runOnMainSync { extraRecomposition.intValue++ }
                delay(80)
            }
            assertEquals("Recomposition is not another arrival", 1, played.get())
            val beforeHide = stopped.get()
            instrumentation.runOnMainSync { readable.value = false }
            await { stopped.get() > beforeHide }
            instrumentation.runOnMainSync { readable.value = true }
            delay(400)
            assertEquals("Reauthorization must not replay", 1, played.get())
            assertEquals(1f, progress.get())
            instrumentation.runOnMainSync { encounter.intValue = 2 }
            await { played.get() == 2 && progress.get() < .8f }
            val beforePause = stopped.get()
            instrumentation.runOnMainSync { active.value = false }
            await { stopped.get() > beforePause && progress.get() == 1f }
            instrumentation.runOnMainSync { active.value = true }
            delay(200)
            assertEquals("Returning to an interrupted encounter stays quiet", 2, played.get())
            instrumentation.runOnMainSync { motion.value = false; encounter.intValue = 3 }
            await { played.get() == 3 }
            assertEquals("Reduced motion presents the complete diary", 1f, progress.get())
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }
}
