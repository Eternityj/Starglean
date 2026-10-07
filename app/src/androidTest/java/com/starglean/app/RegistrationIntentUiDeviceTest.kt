package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.account.EmailOtpIntent
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.ui.screens.AccountUiState
import com.starglean.app.ui.screens.MandatoryLoginScreen
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Render the real login screen without sending email, persisting sessions or altering app data. */
@RunWith(AndroidJUnit4::class)
class RegistrationIntentUiDeviceTest {
    @Test fun registrationAndExistingAccountGuidanceAreVisible() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("registrationQa") == "true")
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
            .ifBlank { instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu.avd_name")
                .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() } }
        assertTrue(avd in setOf("Starglean_QA", "Starglean_Compat_API30"))
        val context = instrumentation.targetContext
        val state = mutableStateOf(AccountUiState(mode = EmailOtpIntent.EXISTING_ACCOUNT, message = "Signed out."))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var activity: MainActivity? = null
        try {
            withTimeout(12_000) {
                while (activity == null) {
                    instrumentation.runOnMainSync {
                        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<MainActivity>().singleOrNull()
                    }
                    if (activity == null) delay(100)
                }
            }
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme { MandatoryLoginScreen(state.value, {}, {}) }
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
            fun verifySimplifiedChrome() {
                val found = mutableListOf<AccessibilityNodeInfo>()
                fun visit(node: AccessibilityNodeInfo?) {
                    if (node == null) return
                    found += node
                    for (index in 0 until node.childCount) visit(node.getChild(index))
                }
                visit(instrumentation.uiAutomation.rootInActiveWindow)
                listOf("Sign in once", "Keep your sky with you", "Your identity stays private",
                    "Your memories belong to you", "Your email is never").forEach { removed ->
                    assertFalse("Removed copy must not be rendered: $removed", found.any { it.text?.contains(removed) == true })
                }
                assertTrue(found.any { it.isVisibleToUser && it.contentDescription?.toString() == "Starglean app icon" })
                assertTrue(visible("Privacy & sharing"))
                assertTrue(visible("Starglean"))
            }
            suspend fun capture(name: String, expected: String) {
                withTimeout(12_000) { while (!visible(expected)) delay(100) }
                delay(1_200)
                val directory = checkNotNull(context.getExternalFilesDir("registration-qa"))
                assertTrue(directory.isDirectory || directory.mkdirs())
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try { File(directory, "$name.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { bitmap.recycle() }
            }
            capture("00-email-sign-in", "Welcome back")
            verifySimplifiedChrome()
            instrumentation.runOnMainSync {
                state.value = AccountUiState(mode = EmailOtpIntent.NEW_ACCOUNT,
                    email = "layout-test@example.invalid", signupConsent = true)
            }
            capture("01-new-registration", "Send registration code")
            verifySimplifiedChrome()
            assertTrue(visible("Create your private account with an email code."))
            assertTrue(visible("I have read the privacy details and want to create an account."))
            assertFalse(visible("Create account & send code"))
            val message = "An account already uses this email. Sign in and request a new code."
            instrumentation.runOnMainSync {
                state.value = AccountUiState(mode = EmailOtpIntent.EXISTING_ACCOUNT,
                    email = "layout-test@example.invalid", message = message)
            }
            capture("02-existing-account-signin", message)
            verifySimplifiedChrome()
            assertTrue(visible("Send sign-in code"))
            assertFalse(visible("8-digit code"))
        } finally { instrumentation.runOnMainSync { activity?.finish() } }
    }
}
