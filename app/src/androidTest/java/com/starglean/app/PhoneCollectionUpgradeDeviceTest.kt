package com.starglean.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only release-update check; never logs diary contents, coordinates or preferences. */
@RunWith(AndroidJUnit4::class)
class PhoneCollectionUpgradeDeviceTest {
    @Test fun updatePreservesDiaryDraftAndStarFilesByteForByte() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("phoneUpgradeQa") == "true")
        val phase = args.getString("upgradePhase")
        assumeTrue(phase == "prepare" || phase == "verify")
        val avd = instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_Location_QA", avd)
        val context = instrumentation.targetContext
        val files = listOf("own-diaries-v1.bin", "writing-draft-v1.bin", "star-collection-v1.bin")
        assertTrue("Cover a nonempty diary library", File(context.filesDir, files[0]).length() > 0)
        assertTrue("Cover an existing star collection", File(context.filesDir, files[2]).length() > 0)
        val fingerprint = files.joinToString("\n") { name ->
            val file = File(context.filesDir, name)
            val digest = if (file.isFile) MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) } else "absent"
            "$name:$digest"
        }
        val checkpoint = args.getString("upgradeCheckpoint") ?: "phone-v29-upgrade-sha256.txt"
        require(checkpoint.matches(Regex("phone-v[0-9]{1,4}-upgrade-sha256[.]txt")))
        val marker = File(context.cacheDir, checkpoint)
        if (phase == "prepare") {
            if (marker.exists()) assertEquals("Do not overwrite an unrelated checkpoint", marker.readText(), fingerprint)
            else {
                assertTrue(marker.createNewFile())
                marker.writeText(fingerprint)
            }
        }
        assertTrue("Prepare before updating", marker.isFile)
        assertTrue("All local data files must be unchanged by installation", marker.readText() == fingerprint)
    }
}
