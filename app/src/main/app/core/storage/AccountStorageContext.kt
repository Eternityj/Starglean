package com.starglean.app.core.storage

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID

private const val ACCOUNT_ROOT = "account-libraries-v1"

class AccountStorageContext private constructor(
    private val base: Context,
    val accountId: String,
) : ContextWrapper(base) {
    private val accountFiles = child(base.filesDir, accountId)
    private val accountNoBackup = child(base.noBackupFilesDir, accountId)
    private val accountCache = child(base.cacheDir, accountId)

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = accountFiles.ensureDirectory()
    override fun getNoBackupFilesDir(): File = accountNoBackup.ensureDirectory()
    override fun getCacheDir(): File = accountCache.ensureDirectory()

    private fun child(parent: File, id: String): File {
        val canonicalParent = parent.canonicalFile
        val rawRoot = File(canonicalParent, ACCOUNT_ROOT).absoluteFile
        require(!java.nio.file.Files.isSymbolicLink(rawRoot.toPath())) { "Account storage root is invalid" }
        val root = rawRoot.canonicalFile
        require(rawRoot == root && root.parentFile == canonicalParent) { "Account storage root is invalid" }
        val rawResult = File(root, id).absoluteFile
        require(!java.nio.file.Files.isSymbolicLink(rawResult.toPath())) { "Account storage identity is invalid" }
        val result = rawResult.canonicalFile
        require(rawResult == result && result.parentFile == root) { "Account storage escaped its root" }
        return rawResult
    }

    private fun File.ensureDirectory(): File {
        if ((!exists() && !mkdirs()) || !isDirectory || canonicalFile != this) {
            throw AccountLibraryException("Private account storage is unavailable.")
        }
        return this
    }

    companion object {
        fun create(context: Context, accountId: String): AccountStorageContext {
            val normalized = normalizeAccountId(accountId)
            return AccountStorageContext(baseStorageContext(context), normalized)
        }
    }
}

/**
 * UI-only bridge: keeps the Activity in the ContextWrapper chain for launchers, dialogs and
 * system UI, while every store that takes applicationContext is redirected to account storage.
 * Never retain this wrapper in a store; use [applicationContext], which is Activity-free.
 */
class AccountLibraryUiContext(
    parentUiContext: Context,
    private val storage: AccountStorageContext,
) : ContextWrapper(parentUiContext) {
    override fun getApplicationContext(): Context = storage
    override fun getFilesDir(): File = storage.filesDir
    override fun getNoBackupFilesDir(): File = storage.noBackupFilesDir
    override fun getCacheDir(): File = storage.cacheDir
}

fun baseStorageContext(context: Context): Context {
    var current = context
    while (true) {
        if (current is AccountStorageContext) {
            current = current.baseContext
            continue
        }
        val application = current.applicationContext
        if (application !== current) {
            current = application
            continue
        }
        return current
    }
}

internal fun normalizeAccountId(value: String): String {
    val parsed = runCatching { UUID.fromString(value) }.getOrElse {
        throw AccountLibraryException("The signed-in account identity is invalid.")
    }
    val normalized = parsed.toString()
    if (!value.equals(normalized, ignoreCase = true)) throw AccountLibraryException("The signed-in account identity is invalid.")
    return normalized
}

class AccountLibraryException(message: String, cause: Throwable? = null) : Exception(message, cause)
