package com.starglean.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.starglean.app.core.account.AuthHttpResponse
import com.starglean.app.core.account.AuthSession
import com.starglean.app.core.account.AuthTransport
import com.starglean.app.core.account.EpochClock
import com.starglean.app.core.account.SessionStore
import com.starglean.app.core.account.SupabaseAuthClient
import com.starglean.app.core.design.StargleanColors
import com.starglean.app.core.design.StargleanTheme
import com.starglean.app.core.discovery.NearbyMovementStatus
import com.starglean.app.core.discovery.NearbyReaderFixQuality
import com.starglean.app.core.discovery.NearbyReaderLocationFix
import com.starglean.app.core.network.AccessTokenProvider
import com.starglean.app.core.network.HttpsConnectionFactory
import com.starglean.app.core.network.NearbyRepository
import com.starglean.app.core.network.SupabaseNearbyClient
import com.starglean.app.core.online.DailyReadsMemory
import com.starglean.app.ui.screens.DiscoveryLocationState
import com.starglean.app.ui.screens.LiveDiscoveryController
import com.starglean.app.ui.screens.NearbyReadingPresentation
import com.starglean.app.ui.screens.NearbyReadingShell
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.security.MessageDigest
import java.security.Principal
import java.security.cert.Certificate
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real controller + reading shell with isolated auth, HTTPS and location; no real account or GPS. */
@RunWith(AndroidJUnit4::class)
class RetainedDiscoveryDeviceTest {
    @Test fun verifiedPhotoAndTextRemainAfterAppSwitchAndLeaseExpiryUntilClose() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("retainedDiscoveryQa") == "true")
        fun property(name: String) = instrumentation.uiAutomation.executeShellCommand("getprop $name")
            .use { FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        assertEquals("Starglean_Location_QA", property("ro.boot.qemu.avd_name")
            .ifBlank { property("ro.kernel.qemu.avd_name") })

        val context = instrumentation.targetContext
        val imageBytes = ByteArrayOutputStream().use { stream ->
            Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.eraseColor(Color.rgb(175, 108, 74))
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
                bitmap.recycle()
            }
            stream.toByteArray()
        }
        val backend = FakeNearbyBackend(imageBytes)
        val auth = SupabaseAuthClient(object : AuthTransport {
            override suspend fun request(method: String, path: String, body: String?, accessToken: String?) =
                AuthHttpResponse(500, "Unexpected auth request")
        }, object : SessionStore {
            private val session = AuthSession(ACCOUNT, "reader@example.com", false, "test-access", "test-refresh", 100_000)
            override suspend fun read() = session
            override suspend fun write(session: AuthSession) = error("No auth write expected")
            override suspend fun clear() = error("No sign-out expected")
        }, EpochClock { 1_000 })
        val nearby = NearbyRepository(SupabaseNearbyClient(URL("https://example.supabase.co"),
            "sb_publishable_test_key_123", AccessTokenProvider { "test-access" },
            HttpsConnectionFactory(backend::open)))
        val location = FakeLocation()
        val timeOffset = AtomicLong(0L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val controller = LiveDiscoveryController(auth, nearby, DailyReadsMemory(), scope, location, scope) {
            SystemClock.elapsedRealtime() + timeOffset.get()
        }
        var activity: MainActivity? = null
        fun visibleText(value: String) = nodes(instrumentation).any { it.isVisibleToUser && it.text?.toString() == value }
        fun visiblePhoto() = nodes(instrumentation).any {
            it.isVisibleToUser && it.contentDescription?.toString() == "Retained diary photo"
        }
        suspend fun await(label: String, condition: () -> Boolean) = withTimeout(15_000) {
            while (!condition()) delay(40)
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
                    val owner = LocalLifecycleOwner.current
                    DisposableEffect(owner) {
                        var wasBackgrounded = false
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                                wasBackgrounded = true
                                location.pauseForBackground()
                                controller.pause()
                            }
                            if (event == Lifecycle.Event.ON_RESUME && wasBackgrounded) {
                                location.resumeWithoutFix()
                                controller.tick()
                            }
                        }
                        owner.lifecycle.addObserver(observer)
                        onDispose { owner.lifecycle.removeObserver(observer) }
                    }
                    StargleanTheme {
                        Box(Modifier.fillMaxSize().background(StargleanColors.Ink).statusBarsPadding()) {
                            val content = controller.content
                            if (controller.canRead && content != null && controller.photos.size == 1) {
                                NearbyReadingShell(NearbyReadingPresentation.Readable, 1f, controller::close) {
                                    Text(content.body, color = StargleanColors.WarmWhite)
                                    Image(controller.photos.single(), "Retained diary photo",
                                        modifier = Modifier.fillMaxWidth().height(180.dp))
                                }
                            } else Text("No open note", color = StargleanColors.WarmWhite)
                        }
                    }
                }
            }
            instrumentation.runOnMainSync { controller.prepareAccount() }
            await("account ready") { controller.accountReady }
            instrumentation.runOnMainSync { location.refreshFix(); controller.shake() }
            await("verified reading visible") {
                controller.canRead && controller.photos.size == 1 && visibleText(BODY) && visiblePhoto()
            }
            assertEquals(1, backend.count("prepare_open"))
            assertEquals(1, backend.count("open_commit"))
            assertEquals(1, backend.count("read_media"))
            assertEquals(9, controller.remaining)

            // The actual Android Activity pauses when Home moves another app to the foreground.
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
            await("controller paused") { !location.foreground }
            assertEquals(0, backend.count("end_session"))
            assertEquals(1, backend.count("open_commit"))
            timeOffset.set(40_000L) // Beyond the server's 35-second recovery deadline.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            await("same Activity resumed") {
                var resumed: MainActivity? = null
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().firstOrNull()
                }
                resumed === activity && visibleText(BODY) && visiblePhoto()
            }
            instrumentation.runOnMainSync { controller.tick() }
            await("expired live session ended") { backend.count("end_session") == 1 }
            assertTrue(controller.canRead)
            assertFalse(controller.canReact)
            assertEquals(1, backend.count("prepare_open"))
            assertEquals(1, backend.count("open_commit"))
            assertEquals(1, backend.count("read_media"))
            assertEquals(9, controller.remaining)
            val screenshotDir = checkNotNull(context.getExternalFilesDir("retained-discovery-qa"))
            assertTrue(screenshotDir.isDirectory || screenshotDir.mkdirs())
            val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(screenshotDir, "returned-open-note.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { screenshot.recycle() }

            val close = nodes(instrumentation).first { it.isVisibleToUser && it.text?.toString() == "Close" }
            var clickable: AccessibilityNodeInfo? = close
            while (clickable != null && !clickable.isClickable) clickable = clickable.parent
            assertTrue(checkNotNull(clickable).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            await("manual close removed content") { !controller.reading && visibleText("No open note") }
            assertFalse(visibleText(BODY))
            assertFalse(visiblePhoto())
            assertEquals(1, backend.count("open_commit"))
            assertEquals(1, backend.count("end_session"))
        } finally {
            instrumentation.runOnMainSync { controller.dispose(); activity?.finish() }
            scope.cancel()
        }
    }

    private fun nodes(instrumentation: android.app.Instrumentation): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.packageName?.toString() == instrumentation.targetContext.packageName) result += node
            for (index in 0 until node.childCount) visit(node.getChild(index))
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
        return result
    }

    private class FakeLocation : DiscoveryLocationState {
        override var foreground by mutableStateOf(true)
        override var optedIn by mutableStateOf(true)
        override var networkAvailable by mutableStateOf(true)
        override var fix by mutableStateOf<NearbyReaderLocationFix?>(null)
        override var movement by mutableStateOf(NearbyMovementStatus.Settled)
        override val precisePermission = true
        override val ready: Boolean get() = foreground && optedIn && networkAvailable &&
            fix?.quality?.isQualifiedAt(SystemClock.elapsedRealtime()) == true
        fun refreshFix() {
            val sampledAt = SystemClock.elapsedRealtimeNanos()
            fix = NearbyReaderLocationFix(-37.8, 144.9, sampledAt,
                NearbyReaderFixQuality(sampledAt / 1_000_000, 8.0, false))
        }
        fun pauseForBackground() { foreground = false; fix = null; networkAvailable = false }
        fun resumeWithoutFix() { foreground = true; networkAvailable = false; fix = null }
    }

    private class FakeNearbyBackend(private val photo: ByteArray) {
        private val session = UUID.randomUUID()
        private val media = UUID.randomUUID()
        private val hash = MessageDigest.getInstance("SHA-256").digest(photo)
        private val calls = Collections.synchronizedList(mutableListOf<String>())
        fun count(action: String): Int = synchronized(calls) { calls.count { it == action } }
        fun open(url: URL): HttpsURLConnection = FakeConnection(url) { connection ->
            if (url.path.endsWith("/nearby-private-media")) {
                calls.add("read_media")
                photo
            } else {
                val request = JSONObject(connection.output.toString(Charsets.UTF_8.name()))
                val action = request.getString("action")
                calls.add(action)
                val payload = request.getJSONObject("payload")
                val response = when (action) {
                    "register_account" -> JSONObject().put("status", "ACCOUNT_READY")
                        .put("account_timezone", "Australia/Melbourne").put("daily_limit", 10).put("reads_used_today", 0)
                    "prepare_open" -> JSONObject().put("status", "CONTENT_STAGED")
                        .put("reader_session_id", session.toString()).put("generation", payload.getLong("generation"))
                        .put("staged_until", Instant.now().plusSeconds(8).toString())
                    "open_commit" -> JSONObject().put("status", "OPEN_COMMITTED")
                        .put("reader_session_id", session.toString()).put("generation", payload.getLong("generation"))
                        .put("sequence", payload.getLong("sequence"))
                        .put("server_expires_at", Instant.now().plusSeconds(5).toString())
                        .put("account_date", "2026-10-03").put("used", 1).put("daily_limit", 10)
                        .put("title", "A found note").put("body", BODY)
                        .put("paper_style", "warm_white").put("font_style", "clear")
                        .put("media", JSONArray().put(JSONObject().put("id", media.toString())
                            .put("ordinal", 0).put("content_type", "image/jpeg").put("bytes", photo.size)
                            .put("sha256", hash.joinToString("") { "%02x".format(it) })
                            .put("width", 24).put("height", 24)))
                    "end_session" -> JSONObject().put("status", "ENDED")
                    else -> error("Unexpected nearby action: $action")
                }
                response.toString().toByteArray(Charsets.UTF_8)
            }
        }.apply {
            if (url.path.endsWith("/nearby-private-media")) {
                responseHeaders["Content-Type"] = "image/jpeg"
                responseHeaders["Digest"] = "sha-256=" + Base64.getEncoder().encodeToString(hash)
            }
        }
    }

    private class FakeConnection(url: URL, private val response: (FakeConnection) -> ByteArray) : HttpsURLConnection(url) {
        val output = ByteArrayOutputStream()
        val responseHeaders = mutableMapOf<String, String>()
        private var bytes: ByteArray? = null
        override fun getOutputStream() = output
        override fun getResponseCode(): Int { if (bytes == null) bytes = response(this); return 200 }
        override fun getInputStream() = ByteArrayInputStream(checkNotNull(bytes))
        override fun getErrorStream() = ByteArrayInputStream(checkNotNull(bytes))
        override fun getHeaderField(name: String?): String? = responseHeaders[name]
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun connect() = Unit
        override fun getCipherSuite(): String = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        @Throws(SSLPeerUnverifiedException::class) override fun getServerCertificates(): Array<Certificate>? = null
        @Throws(SSLPeerUnverifiedException::class) override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }

    private companion object {
        const val ACCOUNT = "00000000-0000-0000-0000-000000000099"
        const val BODY = "The same found note stays here after returning from another app."
    }
}
