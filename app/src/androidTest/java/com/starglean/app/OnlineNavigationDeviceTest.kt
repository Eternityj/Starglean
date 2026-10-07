package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.activity.compose.setContent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.starglean.app.core.account.*
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.ui.screens.AppEntrySurface
import com.starglean.app.ui.screens.AccountLibraryGatePreview
import com.starglean.app.ui.screens.AccountLibraryGatePreviewState
import com.starglean.app.ui.screens.AccountAction
import com.starglean.app.ui.screens.AccountUiState
import com.starglean.app.ui.screens.MandatoryLoginScreen
import com.starglean.app.ui.screens.parsePrivacyNotice
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only navigation on the named QA emulator. No sign-up, upload or location grant. */
@RunWith(AndroidJUnit4::class)
class OnlineNavigationDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext

    @Test fun mandatoryLoginBlocksMainNavigationAndKeepsPrivacyReachable(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("onlineNavigationQa") == "true")
        val avd = shell("getprop ro.boot.qemu.avd_name").ifBlank {
            // Android 11 exposes the same read-only emulator identity under ro.kernel.
            shell("getprop ro.kernel.qemu.avd_name")
        }
        assertTrue("Only isolated QA emulators may run navigation checks", avd in setOf("Starglean_QA", "Starglean_Compat_API30"))
        assertTrue("Test the real release routes, never demo fixtures", BuildConfig.LOCAL_DEVICE_EDITION)
        val privacyTitle = context.assets.open("privacy-notice.json").bufferedReader().use {
            parsePrivacyNotice(it.readText()).getString("title")
        }
        assumeTrue("Do not inspect an existing account", !File(context.noBackupFilesDir, "auth/session-v1.enc").exists())
        assumeTrue(!File(context.noBackupFilesDir, "auth/session-v1.enc.bak").exists())
        // The real starfield continuously redraws. Wait for RESUMED/visible content rather
        // than Instrumentation's queue-idle condition, which an animation need not satisfy.
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var launched: MainActivity? = null
        waitFor("MainActivity resumed") {
            instrument.runOnMainSync {
                launched = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().singleOrNull()
            }
            launched != null
        }
        var activity = requireNotNull(launched)
        var previousImeSetting: String? = null
        try {
            waitFor("login brand") { visible("Starglean") }
            waitFor("sign-in form") { visible("Welcome back") }
            assertPrimaryNavigationHidden()
            assertFalse(visible("Continue with a temporary account"))
            capture("01-email-sign-in")
            click("Create account")
            waitFor("create-account form") { visible("Begin your collection") }
            assertPrimaryNavigationHidden()
            val signupEmail = "qa-signup@example.invalid"
            setText(nodes().first { it.isEditable && it.isVisibleToUser }, signupEmail)
            val consent = nodes().first { it.isCheckable && it.isVisibleToUser }
            clickNode(consent)
            waitFor("signup consent checked") { nodes().any { it.isCheckable && it.isChecked && it.isVisibleToUser } }
            capture("02-create-account")
            scrollTo("Privacy & sharing")
            click("Privacy & sharing")
            waitFor("pre-login privacy notice") { visible(privacyTitle) }
            assertPrimaryNavigationHidden()
            capture("03-before-login-privacy")
            instrument.runOnMainSync { activity.recreate() }
            waitFor("MainActivity recreated with privacy open") {
                instrument.runOnMainSync {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().singleOrNull() ?: activity
                }
                visible(privacyTitle)
            }
            click("Back")
            waitFor("create-account form after privacy and recreation") { visible("Begin your collection") }
            assertTrue(nodes().any { it.isEditable && it.text?.toString() == signupEmail && it.isVisibleToUser })
            assertTrue(nodes().any { it.isCheckable && it.isChecked && it.isVisibleToUser })
            // Render only the code-entry state with an invalid example address. Never
            // request an email or persist a fabricated authenticated session for UI QA.
            val form = mutableStateOf(AccountUiState(email = "qa@example.invalid", otpSent = true))
            instrument.runOnMainSync { activity.setContent {
                StargleanTheme { MandatoryLoginScreen(form.value, onAction = { action ->
                    if (action is AccountAction.EditOtp) form.value = form.value.copy(otp = action.value)
                }, onPrivacy = {}) }
            } }
            waitFor("OTP entry form") { visible("Check your inbox") }
            scrollTo("Verify & enter")
            capture("04-code-entry")
            previousImeSetting = shell("settings get secure show_ime_with_hard_keyboard")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            val codeField = nodes().first { it.isEditable && it.isPassword && it.isEnabled }
            clickNode(codeField)
            waitFor("OTP field focus") { nodes().any { it.isEditable && it.isPassword && it.isFocused } }
            instrument.runOnMainSync {
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.ime())
            }
            waitFor("software keyboard visible") {
                var imeVisible = false
                instrument.runOnMainSync {
                    imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                imeVisible
            }
            scrollTo("Verify & enter")
            waitFor("code field and submit button above the keyboard") {
                var keyboardTop = 0
                instrument.runOnMainSync {
                    val view = activity.window.decorView
                    keyboardTop = view.height - (ViewCompat.getRootWindowInsets(view)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                }
                val visibleNodes = nodes()
                val targets = listOf(
                    visibleNodes.firstOrNull { it.isEditable && it.isPassword && it.isEnabled },
                    visibleNodes.firstOrNull { it.text?.toString() == "Verify & enter" },
                )
                targets.all { node ->
                    val bounds = android.graphics.Rect()
                    node?.getBoundsInScreen(bounds)
                    node != null && !bounds.isEmpty && bounds.top >= 0 && bounds.bottom <= keyboardTop
                }
            }
            waitForStable("software keyboard and OTP focus before evidence", stableMillis = 750) {
                var imeVisible = false
                instrument.runOnMainSync {
                    imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                imeVisible && nodes().any { it.isEditable && it.isPassword && it.isFocused && it.isVisibleToUser }
            }
            capture("05-code-keyboard", settleMillis = 0)
            instrument.runOnMainSync {
                activity.currentFocus?.clearFocus()
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            // rootInActiveWindow can continue to expose the IME accessibility window for a
            // short time after it is hidden. Do not replace the composition until the app window is
            // active again, otherwise subsequent assertions can inspect the stale IME tree.
            waitFor("software keyboard hidden before gate harness") {
                var imeVisible = true
                instrument.runOnMainSync {
                    imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                !imeVisible
            }
            // Rendering-only transition harness, with no real account, disk token or network.
            // Authentication/session behavior is independently covered by isolated auth tests.
            val gate = mutableStateOf<AppAccessState>(AppAccessState.Loading)
            instrument.runOnMainSync { activity.setContent {
                StargleanTheme { AppEntrySurface(gate.value, onRetry = {},
                    login = { Text("QA locked surface") }, content = { Text("QA protected surface") }) }
            } }
            waitFor("loading gate") { visible("Gathering your stars…") }
            assertFalse(visible("QA protected surface"))
            val identity = AccountIdentity("b9c63e86-c8b5-4bd4-a637-c0e650080901", "qa@example.invalid", false)
            instrument.runOnMainSync { gate.value = AppAccessState.Granted(identity, AccessConnectivity.OFFLINE_VERIFIED_SESSION) }
            waitFor("granted protected surface") { visible("QA protected surface") }
            assertTrue(visible("Offline · Local memories and drafts are available"))
            instrument.runOnMainSync { gate.value = AppAccessState.Locked(AppAccessLockReason.SESSION_REVOKED, "Session revoked") }
            waitFor("revoked-session locked surface") { visible("QA locked surface") }
            assertFalse(visible("QA protected surface"))
            instrument.runOnMainSync { gate.value = AppAccessState.Locked(AppAccessLockReason.SECURE_STORAGE_UNAVAILABLE, "Storage locked") }
            waitFor("secure-storage recovery surface") { visible("Retry secure sign-in") }
            assertFalse(visible("QA protected surface"))
            assertFalse(visible("QA locked surface"))
            instrument.runOnMainSync { activity.setContent {
                StargleanTheme { AccountLibraryGatePreview(AccountLibraryGatePreviewState.Choice) }
            } }
            waitFor("account library choice preview") { visible("Existing memories were found on this device") }
            capture("06-library-choice")
            instrument.runOnMainSync { activity.setContent {
                StargleanTheme { AccountLibraryGatePreview(AccountLibraryGatePreviewState.Error) }
            } }
            waitFor("account library error preview") { visible("Your private library is locked") }
            assertTrue(visible("Sign out and use another account"))
            capture("07-library-error")
        } finally {
            instrument.runOnMainSync { activity.finish() }
            previousImeSetting?.let { previous ->
                check(previous in setOf("0", "1", "null"))
                shell(if (previous == "null") "settings delete secure show_ime_with_hard_keyboard"
                    else "settings put secure show_ime_with_hard_keyboard $previous")
            }
        }
    }

    private fun shell(command: String): String = instrument.uiAutomation.executeShellCommand(command)
        .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    private fun visible(text: String) = nodes().any { it.text?.toString() == text && it.isVisibleToUser }
    private fun assertPrimaryNavigationHidden() {
        assertFalse(nodes().any { it.contentDescription?.toString() in setOf("Discover", "My constellations", "Write") })
    }
    private suspend fun click(text: String) { waitFor("click target '$text'") { visible(text) }; clickNode(nodes().first { it.text?.toString() == text && it.isVisibleToUser }); delay(250) }
    private suspend fun clickDescription(text: String) {
        waitFor("click target description '$text'") { nodes().any { it.contentDescription?.toString() == text && it.isVisibleToUser } }
        clickNode(nodes().first { it.contentDescription?.toString() == text && it.isVisibleToUser }); delay(250)
    }
    private fun clickNode(start: AccessibilityNodeInfo) {
        var node: AccessibilityNodeInfo? = start
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun setText(node: AccessibilityNodeInfo, value: String) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }
    private suspend fun scrollTo(text: String) {
        repeat(12) {
            if (visible(text)) return
            nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(200)
        }
        assertTrue("Missing navigation item: $text", visible(text))
    }
    private suspend fun waitFor(label: String = "visible content", predicate: () -> Boolean) {
        val completed = withTimeoutOrNull(20_000) {
            while (!predicate()) delay(100)
            true
        } == true
        if (!completed) {
            val visibleEvidence = nodes().asSequence()
                .filter { it.isVisibleToUser }
                .mapNotNull { it.text?.toString() ?: it.contentDescription?.toString() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(20)
                .joinToString(" | ")
                .ifBlank { "<no visible accessibility text>" }
            val evidenceName = "failure-${label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)}"
            val screenshot = runCatching { capture(evidenceName) }.getOrNull()
            fail("Timed out waiting for $label; visible nodes: $visibleEvidence; screenshot: ${screenshot?.absolutePath ?: "capture failed"}")
        }
    }
    private suspend fun waitForStable(label: String, stableMillis: Long, predicate: () -> Boolean) {
        var stableSince = 0L
        waitFor(label) {
            if (predicate()) {
                if (stableSince == 0L) stableSince = android.os.SystemClock.elapsedRealtime()
                android.os.SystemClock.elapsedRealtime() - stableSince >= stableMillis
            } else {
                stableSince = 0L
                false
            }
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) { if (node == null) return; add(node); repeat(node.childCount) { visit(node.getChild(it)) } }
        visit(instrument.uiAutomation.rootInActiveWindow)
    }
    private suspend fun capture(name: String, settleMillis: Long = 450): File {
        if (settleMillis > 0) delay(settleMillis)
        val screenshot = requireNotNull(instrument.uiAutomation.takeScreenshot())
        val directory = requireNotNull(context.getExternalFilesDir("online-navigation-qa")).apply { mkdirs() }
        val destination = File(directory, "$name.png")
        try { destination.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
        return destination
    }
}
