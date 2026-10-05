package com.starglean.app.core.network

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.BuildConfig
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only real HTTP regression for the disposable QA emulator only. */
@RunWith(AndroidJUnit4::class)
class SupabaseNearbyClientThreadDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun mainDispatcherCallUsesRealHttpStackWithoutMainThreadNetworking(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nearbyNetworkQa") == "true")
        val avd = shell("getprop ro.boot.qemu.avd_name").ifBlank {
            shell("getprop ro.kernel.qemu.avd_name")
        }
        assertTrue("Only isolated QA emulators may run live network checks",
            avd in setOf("Starglean_QA", "Starglean_Compat_API30"))

        val client = SupabaseNearbyClient(
            URL(BuildConfig.SUPABASE_URL),
            BuildConfig.SUPABASE_PUBLISHABLE_KEY,
            AccessTokenProvider { "qa.invalid.unsigned.token" },
        )
        val result = withTimeout(25_000L) {
            withContext(Dispatchers.Main) {
                assertTrue(Looper.myLooper() === Looper.getMainLooper())
                client.command("account_get", emptyMap())
            }
        }

        // An invalid fixed token may be rejected by either the gateway or RPC policy. It must
        // never become NetworkOnMainThreadException wrapped as a transport failure.
        assertFalse(result is NetworkResult.TransportFailure)
        assertTrue(result === NetworkResult.AuthenticationRequired || result is NetworkResult.HttpFailure ||
            result is NetworkResult.ServiceFailure)
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }
}
