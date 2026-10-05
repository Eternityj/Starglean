package com.starglean.app.core.media

import android.net.Uri
import java.io.File
import java.io.InputStream

interface PhotoStore {
    /** Call only for a URI returned by an explicit picker selection or successful camera capture. */
    suspend fun importSelected(uri: Uri): DiaryPhoto

    /** The returned stream reads an already-normalized, app-private JPEG. */
    fun open(ref: String): InputStream

    /** Safe for local image loaders; never resolves a caller-provided path. */
    fun fileForDisplay(ref: String): File

    fun exists(ref: String): Boolean

    /**
     * Deletes only this store's file and only when the caller's transactionally collected set of
     * all draft and saved-diary references does not contain [ref]. Call only while holding the
     * app's LibraryStorageCoordinator.mutex across reference collection and this deletion. There
     * is deliberately no automatic garbage collection because a draft may still own the photo.
     */
    suspend fun deleteIfUnreferenced(ref: String, referencedRefs: Set<String>): Boolean
}
