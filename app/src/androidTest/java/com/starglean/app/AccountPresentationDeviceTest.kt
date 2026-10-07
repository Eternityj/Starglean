package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.ui.screens.AccountAction
import com.starglean.app.ui.screens.AccountScreen
import com.starglean.app.ui.screens.AccountUiState
import java.io.File
import java.io.FileInputStream
import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Fictional presentation state only: no auth, email, deletion, storage or network work is run. */
@RunWith(AndroidJUnit4::class)
class AccountPresentationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test fun signedInAccountPresentationAndActionsRemainSafe() = runBlocking {
        requireQaDevice()
        val state = mutableStateOf(AccountUiState(signedInEmail = QA_EMAIL))
        val actions = Collections.synchronizedList(mutableListOf<AccountAction>())
        var activity: MainActivity? = null
        targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            activity = awaitActivity()
            instrumentation.runOnMainSync {
                checkNotNull(activity).setContent {
                    StargleanTheme {
                        AccountScreen(
                            state = state.value,
                            onAction = { action ->
                                actions += action
                                when (action) {
                                    AccountAction.ShowDeleteConfirmation ->
                                        state.value = state.value.copy(destructiveConfirmationVisible = true)
                                    AccountAction.HideDeleteConfirmation ->
                                        state.value = state.value.copy(destructiveConfirmationVisible = false)
                                    else -> Unit
                                }
                            },
                            onLocationSettings = {},
                            modifier = Modifier.systemBarsPadding(),
                        )
                    }
                }
            }

            awaitText(QA_EMAIL)
            assertEquals("The signed-in page must expose only one Account heading", 1, textNodes("Account").size)
            assertFalse(containsText("Signed in"))
            assertFalse(containsText("For your protection"))
            assertFalse(containsText("Your diary remains on this device"))
            capture("00-signed-in-account")

            scrollUntilVisible("Sign out")
            scrollUntilVisible("Delete account")
            assertSameRow(textNode("Sign out"), textNode("Delete account"))
            clickText("Sign out")
            await { actions.contains(AccountAction.SignOut) }
            clickText("Delete account")
            awaitText("Delete this account?")
            assertTrue(actions.contains(AccountAction.ShowDeleteConfirmation))
            delay(350)
            assertTrue("Confirmation must remain until explicitly dismissed", visibleText("Delete this account?"))
            assertFalse(actions.contains(AccountAction.ConfirmDelete))
            clickText("Keep account")
            await { !visibleText("Delete this account?") }
            assertTrue(actions.contains(AccountAction.HideDeleteConfirmation))
            assertFalse(actions.contains(AccountAction.ConfirmDelete))

            scrollUntil { nodes().any {
                it.isVisibleToUser && it.text?.toString()?.startsWith("Starglean · ") == true
            } }
            val footer = nodes().firstOrNull {
                it.isVisibleToUser && it.text?.toString()?.startsWith("Starglean · ") == true
            }
            assertTrue("A visible version footer is required", footer != null)
            assertFalse("The display version must omit the edition suffix", footer!!.text.toString().contains("-en"))

            instrumentation.runOnMainSync {
                state.value = state.value.copy(
                    emailChangeRequested = true,
                    currentEmailCode = "12345678",
                    newEmailCode = "87654321",
                )
            }
            scrollUntilVisible("Code sent to current email")
            scrollUntilVisible("Code sent to new email")

            instrumentation.runOnMainSync {
                state.value = state.value.copy(emailChangeRequested = false, busy = true)
            }
            // State mutation precedes the Compose frame and accessibility-tree refresh.
            awaitText("Contacting the account service…")
            scrollUntilVisible("Sign out")
            assertDisabled("Sign out")
            assertDisabled("Delete account")
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    private fun requireQaDevice() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("accountPresentationQa") == "true")
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

    private suspend fun awaitText(value: String) = await { visibleText(value) }

    private suspend fun await(condition: () -> Boolean) = withTimeout(12_000) {
        while (!condition()) delay(100)
    }

    private suspend fun scrollUntilVisible(value: String) = withTimeout(12_000) {
        while (!visibleText(value)) {
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(120)
        }
    }

    private suspend fun scrollUntil(condition: () -> Boolean) = withTimeout(12_000) {
        while (!condition()) {
            nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            delay(120)
        }
    }

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

    private fun textNodes(value: String) = nodes().filter {
        it.isVisibleToUser && it.text?.toString() == value
    }

    private fun textNode(value: String) = textNodes(value).single()

    private fun visibleText(value: String) = textNodes(value).isNotEmpty()

    private fun containsText(value: String) = nodes().any {
        it.text?.toString()?.contains(value) == true
    }

    private fun clickText(value: String) {
        var node: AccessibilityNodeInfo? = textNode(value)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Action must be clickable: $value", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun assertDisabled(value: String) {
        var node: AccessibilityNodeInfo? = textNode(value)
        while (node?.parent != null && node.isEnabled && !node.isClickable) node = node.parent
        assertTrue("Action must be disabled while busy: $value", node != null && !node.isEnabled)
        assertFalse("Disabled action must not dispatch: $value",
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun assertSameRow(first: AccessibilityNodeInfo, second: AccessibilityNodeInfo) {
        val firstBounds = Rect().also(first::getBoundsInScreen)
        val secondBounds = Rect().also(second::getBoundsInScreen)
        val verticalOverlap = minOf(firstBounds.bottom, secondBounds.bottom) -
            maxOf(firstBounds.top, secondBounds.top)
        assertTrue("Sign out and Delete account must be visible on the same row", verticalOverlap > 0)
        assertTrue("The two account actions must be adjacent rather than overlapping",
            firstBounds.right <= secondBounds.left || secondBounds.right <= firstBounds.left)
    }

    private suspend fun capture(name: String) {
        delay(450)
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = checkNotNull(targetContext.getExternalFilesDir("account-presentation-qa"))
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
        const val QA_EMAIL = "layout-test@example.invalid"
    }
}
