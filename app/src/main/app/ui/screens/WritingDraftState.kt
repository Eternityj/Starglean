package com.starglean.app.ui.screens

import android.content.Context
import android.net.Uri
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftConflictException
import com.starglean.app.core.draft.WritingDraftState as StoredDraftState
import com.starglean.app.core.draft.WritingDraftStore
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.media.DiaryCameraCache
import com.starglean.app.core.media.DiaryPhotoRules
import com.starglean.app.core.media.LeasedDiaryPhoto
import com.starglean.app.core.media.PhotoImportCoordinator
import com.starglean.app.core.media.WritingPhotoImports
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal interface WritingDraftPersistence {
    suspend fun load(): StoredDraftState
    suspend fun save(expectedRevision: Long, draft: WritingDraft): StoredDraftState
    suspend fun clearIfMatches(expected: StoredDraftState): StoredDraftState
}

internal data class WritingDraftUiState(
    val draft: WritingDraft? = null,
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val conflict: Boolean = false,
    val photoImporting: Boolean = false,
    val photoMessage: String? = null,
)

internal fun newWritingDraft(): WritingDraft = WritingDraft(
    "own-${UUID.randomUUID()}", "", "", PaperStyle.WarmWhite, DiaryFont.Clear,
)

internal fun writingDraftMatchesDiary(draft: WritingDraft, diary: DiaryDemo): Boolean =
    draft.id == diary.id && draft.title.ifBlank { null } == diary.title && draft.body == diary.body &&
        draft.paper == diary.paper && draft.font == diary.font && draft.photos == diary.photos

internal fun draftAfterCommitted(current: WritingDraft, diary: DiaryDemo, nextId: String): WritingDraft = when {
    current.id != diary.id -> current
    writingDraftMatchesDiary(current, diary) -> current.copy(id = nextId, title = "", body = "", photos = emptyList())
    else -> current.copy(id = nextId) // Preserve new edits rather than overwrite the submitted diary.
}

/**
 * One process-owned writer, not a composable's coroutine. Only immutable text enters the queue.
 * UI disposal never cancels accepted edits. Commands run serially; queued edit signals coalesce
 * to the latest visible text, while flush/conditional-clear form explicit transaction barriers.
 */
internal class WritingDraftSession(
    private val persistence: WritingDraftPersistence,
    private val scope: CoroutineScope,
    private val photoImports: WritingPhotoImports? = null,
    private val cameraCache: DiaryCameraCache? = null,
    private val createDraft: () -> WritingDraft = ::newWritingDraft,
) {
    private val mutableState = MutableStateFlow(WritingDraftUiState())
    val state: StateFlow<WritingDraftUiState> = mutableState.asStateFlow()
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var persisted: StoredDraftState? = null // Access only from the single consumer.
    private var unacknowledgedWrite: Pair<Long, WritingDraft>? = null
    private var unacknowledgedClear: StoredDraftState? = null
    private val uncertainPhotoLeases = linkedSetOf<String>()

    init {
        scope.launch {
            for (command in commands) {
                try { command() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { reportFailure(error) }
            }
        }
        if (photoImports != null) enqueue { photoImports.recoverPreviousProcess() }
        retry()
    }

    /** Picker callbacks hand off here immediately; this work is not owned by a composition. */
    fun importPhoto(uri: Uri, expectedDraftId: String, cameraToken: String? = null) {
        importPhoto(expectedDraftId, cameraToken) { importSelected(uri) }
    }

    /** Keeps transaction tests independent of the host JVM's non-functional Android Uri stub. */
    internal fun importPhoto(expectedDraftId: String, cameraToken: String? = null,
        select: suspend WritingPhotoImports.() -> LeasedDiaryPhoto) {
        val importer = photoImports ?: return
        val current = state.value
        if (!current.loaded || current.conflict || current.photoImporting ||
            current.draft?.id != expectedDraftId ||
            current.draft.photos.size >= DiaryPhotoRules.MAX_ATTACHMENTS) {
            cameraToken?.let { scope.launch(Dispatchers.IO) { runCatching { cameraCache?.delete(it) } } }
            mutableState.update { it.copy(photoMessage = "The draft changed. Please select the photo again.") }
            return
        }
        mutableState.update { it.copy(photoImporting = true, photoMessage = null) }
        scope.launch {
            var importedRef: String? = null
            try {
                val leased = importer.select()
                importedRef = leased.ref
                val persistenceResult = request(reportErrors = false) {
                    val latest = state.value
                    val draft = latest.draft
                    if (!latest.loaded || latest.conflict || draft?.id != expectedDraftId ||
                        draft.photos.size >= DiaryPhotoRules.MAX_ATTACHMENTS) {
                        throw IllegalStateException("The draft changed while the photo was being prepared.")
                    }
                    // Merge into the latest queued text/style rather than the picker-launch snapshot.
                    val updated = draft.copy(photos = draft.photos + leased.photo)
                    mutableState.update { it.copy(draft = updated, saving = true) }
                    try {
                        val confirmed = persistLatestWithLostAckReconciliation()
                        if (confirmed.draft != updated) throw IllegalStateException("The photo attachment was not confirmed.")
                        PhotoAttachPersistenceResult.CONFIRMED
                    } catch (_: PhotoWriteRejectedException) {
                        // This state transition must precede the next queued text command.
                        rollbackImportedPhoto(leased.ref)
                        PhotoAttachPersistenceResult.REJECTED
                    } catch (_: PhotoWriteUncertainException) {
                        reportFailure(WritingDraftConflictException())
                        PhotoAttachPersistenceResult.UNCERTAIN
                    }
                }
                when (persistenceResult) {
                    PhotoAttachPersistenceResult.CONFIRMED -> {
                        importedRef = null // Durable membership is no longer rollback-eligible.
                        try {
                            importer.attached(leased.ref)
                            mutableState.update { it.copy(photoMessage = null) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Membership is already durable. Lease release is cleanup, never
                            // authority to remove the photo from the UI or persisted draft.
                            uncertainPhotoLeases += leased.ref
                            mutableState.update {
                                it.copy(photoMessage = "The photo is attached. Private import cleanup will retry safely.")
                            }
                        }
                    }
                    PhotoAttachPersistenceResult.REJECTED -> {
                        runCatching { importer.abandon(leased.ref) }
                        importedRef = null
                        mutableState.update { it.copy(photoMessage = "The photo could not be attached. Your writing is unchanged.") }
                    }
                    PhotoAttachPersistenceResult.UNCERTAIN -> {
                        uncertainPhotoLeases += leased.ref
                        importedRef = null // Keep the lease; membership is intentionally not rolled back.
                        mutableState.update {
                            it.copy(photoMessage = "The photo save could not be confirmed. It is protected while you reload the draft.")
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                importedRef?.let { ref ->
                    runCatching { request(reportErrors = false) { rollbackImportedPhoto(ref) } }
                    runCatching { importer.abandon(ref) }
                }
                mutableState.update { it.copy(photoMessage = "The photo could not be attached. Your writing is unchanged.") }
            } finally {
                cameraToken?.let { runCatching { cameraCache?.delete(it) } }
                mutableState.update { it.copy(photoImporting = false) }
            }
        }
    }

    /** Persists removal first; only then can the candidate enter reference-checked cleanup. */
    fun removePhoto(ref: String) {
        val importer = photoImports ?: return
        enqueue {
            val current = state.value
            val draft = current.draft ?: return@enqueue
            if (!current.loaded || current.conflict || draft.photos.none { it.ref == ref }) return@enqueue
            val updated = draft.copy(photos = draft.photos.filterNot { it.ref == ref })
            mutableState.update { it.copy(draft = updated, saving = true, photoMessage = null) }
            try {
                val confirmed = persistLatestWithLostAckReconciliation()
                if (confirmed.draft != updated) throw IllegalStateException("The photo removal was not confirmed.")
                try { importer.cleanupCandidates(setOf(ref)) }
                catch (_: Exception) {
                    mutableState.update { it.copy(photoMessage = "The photo was removed from this draft; private-file cleanup will retry safely.") }
                }
            } catch (_: PhotoWriteRejectedException) {
                restoreRemovedPhotos(draft)
                mutableState.update { it.copy(photoMessage = "The photo could not be removed. It remains attached to this draft.") }
            } catch (_: PhotoWriteUncertainException) {
                // A durable candidate is safe whether the disk contains the old or new revision:
                // reference aggregation retains it in the former case and deletes it in the latter.
                runCatching { importer.cleanupCandidates(setOf(ref)) }
                reportFailure(WritingDraftConflictException())
                mutableState.update {
                    it.copy(photoMessage = "The photo removal could not be confirmed. Reload the draft before editing.")
                }
            }
        }
    }

    fun cleanupStaleCameraFiles(excludedToken: String?) {
        val cache = cameraCache ?: return
        scope.launch(Dispatchers.IO) { runCatching { cache.cleanupStale(excludedToken) } }
    }

    /** Called on the UI thread. Loading/conflict states cannot replace an unread file. */
    fun edit(draft: WritingDraft) {
        val previous = state.value
        if (!previous.loaded || previous.conflict) return
        // Attachment membership has dedicated import/remove transactions. A late text callback
        // from any older Compose frame is never authority to add back or drop photo references.
        val merged = if (previous.draft?.id == draft.id) draft.copy(photos = previous.draft.photos) else draft
        mutableState.update { it.copy(draft = merged, saving = it.error == null) }
        enqueue {
            // An IO failure needs an explicit retry; typing does not hammer a broken file.
            if (state.value.error == null) persistLatest()
        }
    }

    fun retry() {
        enqueue {
            val actual = persistence.load()
            val known = persisted
            val current = state.value
            if (known == null) {
                persisted = actual
                mutableState.value = WritingDraftUiState(draft = actual.draft ?: createDraft(), loaded = true)
            } else {
                // A lost acknowledgement may already have written this exact candidate.
                // Any other unexpected revision is a conflict, never authority to overwrite.
                if (actual != known && actual.draft != current.draft && !isKnownUnacknowledgedState(actual)) {
                    throw WritingDraftConflictException()
                }
                persisted = actual
                unacknowledgedWrite = null
                unacknowledgedClear = null
                mutableState.update { it.copy(error = null, conflict = false) }
                persistLatest()
            }
            reconcileUncertainPhotoLeases()
        }
    }

    /** Runs behind every previously accepted edit. Recovery never races the process-owned writer. */
    suspend fun recover(action: suspend () -> StoredDraftState) = request {
        check(state.value.error != null && !state.value.photoImporting) { "The draft is no longer awaiting recovery." }
        val recovered = action()
        persisted = recovered
        unacknowledgedWrite = null
        unacknowledgedClear = null
        mutableState.value = WritingDraftUiState(draft = recovered.draft ?: createDraft(), loaded = true)
    }

    /** Before own-diary submission, prove this exact preview text is durable. */
    suspend fun flush(expected: WritingDraft): StoredDraftState = request {
        if (!state.value.loaded || state.value.conflict) throw WritingDraftConflictException()
        if (state.value.draft != expected) throw IllegalStateException("This draft has changed. Go back and preview it again.")
        if (state.value.error != null) {
            val actual = persistence.load()
            if (actual != persisted && actual.draft != expected && !isKnownUnacknowledgedState(actual)) {
                throw WritingDraftConflictException()
            }
            persisted = actual
            unacknowledgedWrite = null
            unacknowledgedClear = null
            mutableState.update { it.copy(error = null, conflict = false) }
        }
        val confirmed = persistLatest()
        if (confirmed.draft != expected || state.value.draft != expected) {
            throw IllegalStateException("This draft has changed. Go back and preview it again.")
        }
        confirmed
    }

    /**
     * Retire only this submitted version. A newer edit is assigned a fresh diary ID and kept.
     * The optional token is from flush; after process restoration the known on-disk snapshot
     * can also be reconciled against an already committed own diary.
     */
    fun committed(diary: DiaryDemo, token: StoredDraftState? = null): WritingDraft? {
        val before = state.value.draft ?: return null
        if (before.id != diary.id) return before
        val next = draftAfterCommitted(before, diary, createDraft().id)
        mutableState.update { it.copy(draft = next, saving = true) }
        enqueue {
            val known = persisted ?: throw IllegalStateException("Could not check the draft. Your current text is still here.")
            val expected = token ?: known.takeIf { it.draft?.let { draft -> writingDraftMatchesDiary(draft, diary) } == true }
            if (expected != null) {
                unacknowledgedClear = expected
                val actual = persistence.clearIfMatches(expected)
                val cleared = actual.draft == null && actual.revision == expected.revision + 1L
                // If earlier queued local edits already advanced the file, they are known and
                // retained. An external update cannot be silently replaced by the new draft.
                if (!cleared && actual != known) throw WritingDraftConflictException()
                persisted = actual
                unacknowledgedClear = null
            }
            mutableState.update { it.copy(error = null, conflict = false) }
            val latest = state.value.draft
            if (latest == next && next.title.isEmpty() && next.body.isEmpty() && next.photos.isEmpty() && persisted?.draft == null) {
                mutableState.update { it.copy(saving = false) } // Keep the clear tombstone, not an empty rewrite.
            } else persistLatest()
        }
        return next
    }

    private suspend fun persistLatest(): StoredDraftState {
        val current = state.value
        val base = persisted ?: throw IllegalStateException("The original draft has not loaded. Nothing will be overwritten.")
        val candidate = current.draft ?: throw IllegalStateException("The draft is not ready yet.")
        if (current.conflict) throw WritingDraftConflictException()
        if (base.draft == candidate) {
            mutableState.update { it.copy(saving = false, error = null) }
            return base
        }
        mutableState.update { it.copy(saving = true) }
        unacknowledgedWrite = base.revision to candidate
        val confirmed = persistence.save(base.revision, candidate)
        persisted = confirmed
        unacknowledgedWrite = null
        mutableState.update { it.copy(saving = it.draft != confirmed.draft, error = null) }
        return confirmed
    }

    /** Distinguishes a committed write with a lost acknowledgement from a write that never landed. */
    private suspend fun persistLatestWithLostAckReconciliation(): StoredDraftState {
        val base = persisted ?: throw PhotoWriteUncertainException()
        val candidate = state.value.draft ?: throw PhotoWriteUncertainException()
        return try {
            persistLatest()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val actual = try { persistence.load() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw PhotoWriteUncertainException() }
            when {
                actual.draft == candidate && isKnownUnacknowledgedState(actual) -> {
                    persisted = actual
                    unacknowledgedWrite = null
                    mutableState.update {
                        it.copy(saving = it.draft != actual.draft, error = null, conflict = false)
                    }
                    actual
                }
                actual == base -> {
                    unacknowledgedWrite = null
                    throw PhotoWriteRejectedException()
                }
                else -> throw PhotoWriteUncertainException()
            }
        }
    }

    private fun rollbackImportedPhoto(ref: String) {
        val latest = state.value.draft ?: return
        if (latest.photos.none { it.ref == ref }) return
        val rolledBack = latest.copy(photos = latest.photos.filterNot { it.ref == ref })
        mutableState.update {
            it.copy(draft = rolledBack, saving = rolledBack != persisted?.draft)
        }
    }

    private fun restoreRemovedPhotos(before: WritingDraft) {
        val latest = state.value.draft ?: return
        if (latest.id != before.id) return
        val restored = latest.copy(photos = before.photos)
        mutableState.update { it.copy(draft = restored, saving = restored != persisted?.draft) }
    }

    private suspend fun reconcileUncertainPhotoLeases() {
        val importer = photoImports ?: return
        val referenced = persisted?.draft?.photos.orEmpty().mapTo(hashSetOf()) { it.ref }
        uncertainPhotoLeases.toList().forEach { ref ->
            if (ref in referenced) importer.attached(ref) else importer.abandon(ref)
            uncertainPhotoLeases.remove(ref)
        }
    }

    private fun isKnownUnacknowledgedState(actual: StoredDraftState): Boolean =
        unacknowledgedWrite?.let { (revision, draft) ->
            revision != Long.MAX_VALUE && actual.revision == revision + 1L && actual.draft == draft
        } == true || unacknowledgedClear?.let {
            it.revision != Long.MAX_VALUE && actual.revision == it.revision + 1L && actual.draft == null
        } == true

    private fun enqueue(command: suspend () -> Unit) {
        if (!commands.trySend(command).isSuccess) reportFailure(IllegalStateException("Draft saving is unavailable. Your text is still here."))
    }

    private suspend fun <T> request(reportErrors: Boolean = true, block: suspend () -> T): T {
        val reply = CompletableDeferred<T>()
        enqueue {
            try { reply.complete(block()) }
            catch (cancelled: CancellationException) { reply.cancel(cancelled); throw cancelled }
            catch (error: Exception) {
                if (reportErrors) reportFailure(error)
                reply.completeExceptionally(error)
            }
        }
        return reply.await()
    }

    private fun reportFailure(error: Exception) {
        val conflict = error is WritingDraftConflictException
        mutableState.update {
            it.copy(saving = false, conflict = it.conflict || conflict,
                error = if (conflict) "Another version of this draft exists. Your input has not overwritten it. Copy your text before checking again, and keep the app open."
                else if (!it.loaded) "Could not load your draft. The original file is unchanged. Retry to continue writing."
                else "Your draft is not saved yet. The text is still here. Retry before closing the app.")
        }
    }
}

private class PhotoWriteRejectedException : IllegalStateException()
private class PhotoWriteUncertainException : IllegalStateException()
private enum class PhotoAttachPersistenceResult { CONFIRMED, REJECTED, UNCERTAIN }

/** Only application context escapes the editor. No location, weather or Activity is retained. */
internal object WritingDraftSessions {
    private val sessions = mutableMapOf<String, WritingDraftSession>()

    @Synchronized fun get(context: Context): WritingDraftSession {
        val scopedContext = context.applicationContext
        val storageKey = scopedContext.filesDir.canonicalPath
        sessions[storageKey]?.let { return it }
        val store = WritingDraftStore(scopedContext)
        val photoImports = PhotoImportCoordinator(scopedContext)
        val cameraCache = DiaryCameraCache(scopedContext)
        val persistence = object : WritingDraftPersistence {
            override suspend fun load() = store.load()
            override suspend fun save(expectedRevision: Long, draft: WritingDraft) = store.save(expectedRevision, draft)
            override suspend fun clearIfMatches(expected: StoredDraftState) = store.clearIfMatches(expected)
        }
        return WritingDraftSession(
            persistence,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            photoImports = photoImports,
            cameraCache = cameraCache,
        ).also { sessions[storageKey] = it }
    }
}
