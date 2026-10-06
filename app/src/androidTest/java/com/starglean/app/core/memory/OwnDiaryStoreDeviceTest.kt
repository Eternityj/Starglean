package com.starglean.app.core.memory

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.starglean.app.BuildConfig
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import java.io.File
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises Android's real AtomicFile, but never the application's real diary directory.
 * Every test gets a uniquely created cache child. Both filesDir AND applicationContext are
 * redirected, so OwnDiaryStore cannot accidentally escape to targetContext.filesDir.
 */
@RunWith(AndroidJUnit4::class)
class OwnDiaryStoreDeviceTest {
    private lateinit var targetContext: Context
    private lateinit var isolatedContext: Context
    private lateinit var cacheRoot: File
    private lateinit var ownerToken: String
    private var ownedRoot: File? = null

    @Before
    fun createIsolatedTestDirectory() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        cacheRoot = targetContext.cacheDir.canonicalFile
        val created = Files.createTempDirectory(cacheRoot.toPath(), DIRECTORY_PREFIX).toFile().canonicalFile
        assertEquals(cacheRoot, created.parentFile)
        assertTrue(created.name.startsWith(DIRECTORY_PREFIX))
        ownerToken = UUID.randomUUID().toString()
        File(created, OWNER_MARKER).writeText(ownerToken, Charsets.UTF_8)
        ownedRoot = created
        isolatedContext = IsolatedFilesContext(targetContext, created)
        assertSame(isolatedContext, isolatedContext.applicationContext)
        assertEquals(created, isolatedContext.filesDir)
    }

    @After
    fun removeOnlyThisTestsOwnedFiles() {
        val root = ownedRoot ?: return
        // Refuse cleanup if the path, marker, or direct children no longer match our sandbox.
        // Deliberately avoid deleteRecursively: never follow an unexpected directory or link.
        assertEquals(cacheRoot, root.parentFile)
        assertEquals(root, root.canonicalFile)
        assertTrue(root.name.startsWith(DIRECTORY_PREFIX))
        assertEquals(ownerToken, File(root, OWNER_MARKER).readText(Charsets.UTF_8))
        val children = requireNotNull(root.listFiles())
        val allowedNames = setOf(STORE_NAME, "$STORE_NAME.new", "$STORE_NAME.bak", OWNER_MARKER)
        children.forEach { child ->
            assertTrue("Refusing to remove an unexpected test file", child.name in allowedNames)
            assertEquals(root, child.canonicalFile.parentFile)
            assertTrue("Refusing to traverse a test subdirectory", child.isFile)
        }
        children.filter { it.name != OWNER_MARKER }.forEach { assertTrue("Could not remove an owned test file", it.delete()) }
        assertTrue(File(root, OWNER_MARKER).delete())
        assertTrue("Could not remove the empty owned test directory", root.delete())
        ownedRoot = null
    }

    @Test
    fun releaseInstallationHasItsOwnLocalPackageAndNonDebugIdentity() {
        assumeTrue("Release identity is checked only with -PdeviceTests=true", BuildConfig.BUILD_TYPE == "release")
        assertEquals("com.starglean.app.local", targetContext.packageName)
        assertEquals(targetContext.packageName, BuildConfig.APPLICATION_ID)
        assertFalse(BuildConfig.DEBUG)
        assertTrue(BuildConfig.LOCAL_DEVICE_EDITION)
        assertEquals(0, targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val certificates = installedSigningCertificates()
        assertTrue("The installed release must have a signing certificate", certificates.isNotEmpty())
        certificates.forEach { certificate ->
            assertFalse("A local release must not use the standard Android debug certificate",
                certificate.subjectX500Principal.name.contains("CN=Android Debug", ignoreCase = true))
        }
    }

    @Test
    fun realAtomicSaveSurvivesNewStoreInstancesAndPreservesOptionalLocation() = runBlocking {
        val store = newStore()
        assertTrue(store.load().isEmpty())
        val withLocation = record(1, rememberLocation = true)
        val withoutLocation = record(2, rememberLocation = false)
        store.save(withLocation)
        store.save(withoutLocation)
        assertTrue(storeFile().isFile)
        assertTrue(storeFile().length() > 0L)
        assertEquals(listOf(withoutLocation, withLocation), newStore().load())
        assertFalse(File(requireRoot(), "$STORE_NAME.new").exists())
        assertFalse(File(requireRoot(), "$STORE_NAME.bak").exists())
    }

    @Test
    fun identicalRetriesDoNotDuplicateOrRewriteTheStoredCollection() = runBlocking {
        val original = record(1, rememberLocation = true)
        newStore().save(original)
        val bytes = storeFile().readBytes()
        repeat(3) { newStore().save(original) }
        assertEquals(listOf(original), newStore().load())
        assertArrayEquals(bytes, storeFile().readBytes())
    }

    @Test
    fun reusingAnIdWithChangedTextCannotOverwriteTheOriginal() = runBlocking {
        val original = record(1)
        newStore().save(original)
        val bytes = storeFile().readBytes()
        val changed = original.copy(diary = original.diary.copy(body = "同一个编号不应覆盖之前已经保存的这一篇日记。"))
        expectDataFailure { newStore().save(changed) }
        assertEquals(listOf(original), newStore().load())
        assertArrayEquals(bytes, storeFile().readBytes())
    }

    @Test
    fun simultaneousSavesFromSeparateStoreInstancesDoNotLoseAnyRecord() = runBlocking {
        val records = (1..24).map { record(it, rememberLocation = it % 2 == 0) }
        withTimeout(20_000L) {
            val startTogether = CompletableDeferred<Unit>()
            val writes = records.map { item ->
                async(Dispatchers.IO) {
                    startTogether.await()
                    newStore().save(item)
                }
            }
            startTogether.complete(Unit)
            writes.awaitAll()
        }
        val loaded = newStore().load()
        assertEquals(records.size, loaded.size)
        assertEquals(records.size, loaded.map { it.diary.id }.toSet().size)
        assertEquals(records.sortedByDescending { it.savedAtMillis }, loaded)
    }

    @Test
    fun corruptedLibraryIsPreservedAndCannotBeMistakenForAnEmptyCollection() = runBlocking {
        newStore().save(record(1))
        val corrupted = storeFile().readBytes().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        // This exact file is inside this test's private, verified cache child only.
        storeFile().writeBytes(corrupted)
        expectDataFailure { newStore().load() }
        assertArrayEquals(corrupted, storeFile().readBytes())
        expectDataFailure { newStore().save(record(2)) }
        assertArrayEquals(corrupted, storeFile().readBytes())
        assertFalse(File(requireRoot(), "$STORE_NAME.new").exists())
    }

    @Test
    fun abortedRealAtomicWriteKeepsTheLastCommittedDiary() = runBlocking {
        val original = record(1, rememberLocation = true)
        newStore().save(original)
        val committed = storeFile().readBytes()
        val atomic = AtomicFile(storeFile())
        val unfinished = atomic.startWrite()
        try {
            unfinished.write(byteArrayOf(0x53, 0x54, 0x00))
            unfinished.flush()
        } finally {
            atomic.failWrite(unfinished)
        }
        assertArrayEquals(committed, storeFile().readBytes())
        assertEquals(listOf(original), newStore().load())
    }

    @Test
    fun orphanedPendingFileIsNotSilentlyReplacedByANewLibrary() = runBlocking {
        val pending = File(requireRoot(), "$STORE_NAME.new")
        val pendingBytes = byteArrayOf(0x53, 0x54, 0x47, 0x4c, 0x00)
        pending.writeBytes(pendingBytes)
        assertFalse(storeFile().exists())
        expectDataFailure { newStore().load() }
        expectDataFailure { newStore().save(record(1)) }
        assertFalse(storeFile().exists())
        assertArrayEquals(pendingBytes, pending.readBytes())
    }

    private fun requireRoot(): File {
        val root = checkNotNull(ownedRoot)
        check(root.canonicalFile == root && root.parentFile == cacheRoot)
        check(root.name.startsWith(DIRECTORY_PREFIX))
        return root
    }

    private fun storeFile(): File = File(requireRoot(), STORE_NAME)

    private fun newStore(): OwnDiaryStore = OwnDiaryStore(IsolatedFilesContext(targetContext, requireRoot()))

    private suspend fun expectDataFailure(action: suspend () -> Unit) {
        try {
            action()
        } catch (_: OwnDiaryDataException) {
            return
        }
        throw AssertionError("Expected a safe storage failure, not a replacement or partial result")
    }

    @Suppress("DEPRECATION")
    private fun installedSigningCertificates(): List<X509Certificate> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            targetContext.packageManager.getPackageInfo(targetContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            targetContext.packageManager.getPackageInfo(targetContext.packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty()
        }
        val factory = CertificateFactory.getInstance("X.509")
        return signatures.map { signature ->
            signature.toByteArray().inputStream().use { factory.generateCertificate(it) as X509Certificate }
        }
    }

    private fun record(number: Int, rememberLocation: Boolean = false): OwnDiaryRecord {
        val time = 1_800_000_000_000L + number
        return OwnDiaryRecord(
            diary = DiaryDemo(
                id = "own-${UUID(0L, number.toLong())}",
                title = "独立设备测试 $number 🌙",
                body = "这是存储冒烟测试专用的虚构文字，不来自任何用户的真实日记。\n第 $number 页，保留中文与星星 🪐。",
                date = "测试时间 · $number",
                weatherSnapshot = DiaryWeatherSnapshot(WeatherKind.Rain, Daylight.Night, 12,
                    "2026-09-18T20:30:00+10:00", false),
                blurredPlace = if (rememberLocation) "测试坐标 · 非设备位置" else "地点未记录",
                paper = PaperStyle.Midnight,
                font = DiaryFont.Serif,
            ),
            place = if (rememberLocation) MemoryLocationFix(12.1234567, -45.7654321, 18.5f, time) else null,
            savedAtMillis = time,
        )
    }

    private class IsolatedFilesContext(base: Context, private val directory: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = directory
        override fun getApplicationContext(): Context = this
    }

    private companion object {
        const val DIRECTORY_PREFIX = "own-diary-store-device-test-"
        const val OWNER_MARKER = ".device-test-owner"
        const val STORE_NAME = "own-diaries-v1.bin"
    }
}
