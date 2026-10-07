package com.starglean.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.ui.screens.parsePrivacyNotice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline checks: no accounts, location grants or changes to existing user data. */
@RunWith(AndroidJUnit4::class)
class PhonePackagingDeviceTest {
    @Test fun launcherAndBusinessCodeAreIncludedInTheInstalledPackage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("The packaged app must have a launcher", launcher)
        assertEquals(MainActivity::class.java.name, launcher!!.component!!.className)
        assertTrue("All normal build types must use the phone experience", BuildConfig.LOCAL_DEVICE_EDITION)
        assertEquals("Starglean", context.getString(R.string.app_name))
    }

    @Test fun completePrivacyNoticeIsAvailableWithoutNetwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notice = context.assets.open("privacy-notice.json").bufferedReader().use {
            parsePrivacyNotice(it.readText())
        }
        assertTrue("The disclosure must include every current topic", notice.getJSONArray("sections").length() >= 9)
        assertTrue(notice.getString("contact").isNotBlank())
    }
}
