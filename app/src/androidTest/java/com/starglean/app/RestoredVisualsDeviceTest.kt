package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.model.GallerySamples
import com.starglean.app.ui.components.GalleryConstellationScene
import com.starglean.app.ui.screens.PrivacyDetailsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

/** Explicit QA-only rendering. Does not sign in, grant location, save diaries or contact the backend. */
@RunWith(AndroidJUnit4::class)
class RestoredVisualsDeviceTest {
    @Test fun privacyAssetAndCygnusMotionRenderOnPhone() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("repairVisualQa") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Only run this fixture on the isolated QA emulator", "Starglean_QA", avd)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity: MainActivity? = null
        val directory = checkNotNull(context.getExternalFilesDir("repair-visual-qa"))
        check(directory.isDirectory || directory.mkdirs())
        fun capture(name: String): Bitmap {
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(directory, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return image
        }
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
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent { StargleanTheme { PrivacyDetailsScreen {} } }
            }
            withTimeout(12_000) {
                while (instrumentation.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("Local writing and cloud saving").isNullOrEmpty()) delay(100)
            }
            capture("privacy-offline.png").recycle()
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        Column(Modifier.fillMaxSize().background(StargleanColors.Ink)
                            .statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            Text("Cygnus · offline visual test", color = StargleanColors.Gold)
                            GalleryConstellationScene(GallerySamples.items.first(), motionEnabled = true,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                            Text("Test fixture only · no saved memories changed", color = StargleanColors.MutedText)
                        }
                    }
                }
            }
            delay(1_200)
            val first = capture("cygnus-motion-1.png")
            try {
                delay(1_800)
                val second = capture("cygnus-motion-2.png")
                try {
                    assertEquals(first.width, second.width)
                    assertEquals(first.height, second.height)
                    var changed = 0
                    // Ignore status bars and labels; only the central figure can satisfy this assertion.
                    for (y in first.height / 8 until first.height / 2 step 3) {
                        for (x in first.width / 8 until first.width * 7 / 8 step 3) {
                            if (first.getPixel(x, y) != second.getPixel(x, y)) changed++
                        }
                    }
                    assertTrue("The shared scene must animate, not remain a static picture", changed > 100)
                } finally { second.recycle() }
            } finally { first.recycle() }
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }
}
