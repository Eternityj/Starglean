package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.storage.AccountStorageContext
import com.starglean.app.ui.screens.*
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No GPS fixture or permission grants: isolated local preference choice and real Compose screens. */
@RunWith(AndroidJUnit4::class)
class UnifiedLocationEntryDeviceTest {
    @Test fun firstEntryDisclosureDeclinePersistsAndHomeHasNoRemovedShortcuts() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("locationEntryQa") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertTrue(property("ro.boot.qemu.avd_name").ifBlank { property("ro.kernel.qemu.avd_name") }
            in setOf("Starglean_QA", "Starglean_Compat_API30"))
        val account = UUID.randomUUID().toString()
        val scoped = AccountStorageContext.create(context, account)
        val prefName = "foreground_location_$account"
        val preferences = context.getSharedPreferences(prefName, 0)
        assertTrue(preferences.all.isEmpty())
        var activity: MainActivity? = null
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun find(label: String, node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isVisibleToUser && node.text?.toString() == label) return node
            for (i in 0 until node.childCount) find(label, node.getChild(i))?.let { return it }
            return null
        }
        suspend fun await(label: String) { withTimeout(12_000) { while (find(label) == null) delay(60) } }
        suspend fun tap(label: String) {
            await(label)
            val node = checkNotNull(find(label))
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            assertNotNull("Clickable action $label", target)
            assertTrue(checkNotNull(target).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val directory = checkNotNull(context.getExternalFilesDir("location-entry-qa"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        suspend fun capture(name: String) {
            delay(800)
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(directory, name).outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { image.recycle() }
        }
        fun renderGate() {
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    AccountLibraryContent(scoped) {
                        StargleanTheme {
                            AppLocationGate {
                                val access = LocalAppLocationAccess.current
                                Column {
                                    Text(if (access.allowed) "QA access enabled" else "QA access disabled")
                                    TextButton(onClick = access.openSettings) { Text("Open location settings") }
                                }
                            }
                        }
                    }
                }
            }
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
            renderGate()
            await("Allow precise location")
            assertNotNull("Deny is visible without scrolling", find("Not now · Keep writing locally"))
            assertNull("Content is not composed before the choice", find("QA access disabled"))
            await("Only while you are here")
            capture("01-unified-location-welcome.png")
            tap("Not now · Keep writing locally")
            await("QA access disabled")
            assertEquals("Declined", preferences.getString("decision", null))
            // A new composition reads persisted choice, not remember state.
            instrumentation.runOnMainSync { checkNotNull(activity).setContent { Text("Recreating entry") } }
            delay(100)
            renderGate()
            await("QA access disabled")
            assertNull("Decline never triggers an automatic re-prompt", find("Allow precise location"))
            tap("Open location settings")
            await("Location settings")
            await("Enable location")
            capture("02-unified-location-settings.png")
            tap("Not now")
            await("QA access disabled")

            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    AccountLibraryContent(scoped) { StargleanTheme { PlanetScreen(isActive = true) } }
                }
            }
            delay(2_000)
            assertNull("Removed home shortcut", find("My entries"))
            assertNull("Removed home shortcut", find("My constellations"))
            capture("03-home-without-shortcuts.png")
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            preferences.edit().clear().commit()
            context.deleteSharedPreferences(prefName)
            // Only this test's random empty account library; never an authenticated user.
            listOf(scoped.filesDir, scoped.noBackupFilesDir, scoped.cacheDir).forEach { file ->
                check(file.name == account && file.parentFile.name == "account-libraries-v1")
                file.deleteRecursively()
            }
        }
    }
}
