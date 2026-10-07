package com.starglean.app.ui.screens

import android.net.Uri
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftRules
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.media.LeasedDiaryPhoto
import com.starglean.app.core.media.WritingPhotoImports
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class WritingDraftSessionTest {
    private val firstId = "own-00000000-0000-0000-0000-000000000001"
    private val nextId = "own-00000000-0000-0000-0000-000000000002"
    private fun draft(body: String = "") = WritingDraft(firstId, "", body, PaperStyle.WarmWhite, DiaryFont.Clear)
    private fun diary(draft: WritingDraft) = DiaryDemo(draft.id, draft.title.ifBlank { null }, draft.body,
        "9月16日 · 10:00", DiaryWeatherSnapshot.Unavailable, "地点未记录", draft.paper, draft.font)

    private class MemoryStore : WritingDraftPersistence {
        var stored = WritingDraftState(0, null)
        var loadFailure = false
        var failWriteAcknowledgement = false
        var failWriteBeforeCommit = false
        var replaceWithUnknownWrite = false
        var failClearAcknowledgement = false
        var writeGate: CompletableDeferred<Unit>? = null
        var writes = 0
        override suspend fun load(): WritingDraftState {
            if (loadFailure) error("read failed")
            return stored
        }
        override suspend fun save(expectedRevision: Long, draft: WritingDraft): WritingDraftState {
            writeGate?.also { writeGate = null }?.await()
            if (failWriteBeforeCommit) { failWriteBeforeCommit = false; error("write failed") }
            if (replaceWithUnknownWrite) {
                replaceWithUnknownWrite = false
                stored = WritingDraftRules.save(stored, expectedRevision,
                    draft.copy(body = "external revision", photos = emptyList()))
                error("unknown write won")
            }
            stored = WritingDraftRules.save(stored, expectedRevision, draft)
            writes++
            if (failWriteAcknowledgement) { failWriteAcknowledgement = false; error("ack failed") }
            return stored
        }
        override suspend fun clearIfMatches(expected: WritingDraftState): WritingDraftState {
            stored = WritingDraftRules.clearIfMatches(stored, expected)
            if (failClearAcknowledgement) { failClearAcknowledgement = false; error("clear ack failed") }
            return stored
        }
    }

    private class FakePhotoImports(private val photo: DiaryPhoto) : WritingPhotoImports {
        var attached = 0
        var abandoned = 0
        var attachedFailure = false
        val cleanup = mutableListOf<Set<String>>()
        override suspend fun recoverPreviousProcess() = Unit
        override suspend fun importSelected(uri: Uri): LeasedDiaryPhoto =
            error("JVM tests use the Android-free selection operation")
        fun leased() = LeasedDiaryPhoto(photo, photo.ref)
        override suspend fun attached(ref: String) {
            assertEquals(photo.ref, ref)
            attached++
            if (attachedFailure) error("lease release failed")
        }
        override suspend fun abandon(ref: String): Boolean {
            assertEquals(photo.ref, ref)
            abandoned++
            return true
        }
        override suspend fun cleanupCandidates(refs: Set<String>): Boolean {
            cleanup += refs
            return true
        }
    }

    private fun session(store: MemoryStore, scope: CoroutineScope): WritingDraftSession {
        var created = 0
        return WritingDraftSession(store, scope) { draft().copy(id = if (created++ == 0) firstId else nextId) }
    }

    @Test fun unreadFileCannotBeOverwrittenByAnEmptyEditor() = runBlocking {
        val store = MemoryStore().apply { loadFailure = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            assertFalse(session.state.value.loaded)
            session.edit(draft("一"))
            assertEquals(0, store.writes)
            assertNull(session.state.value.draft)
            assertNotNull(session.state.value.error)
            store.loadFailure = false
            session.retry()
            assertTrue(session.state.value.loaded)
        } finally { scope.cancel() }
    }

    @Test fun shortDraftsPersistWithoutTheDiaryTenCharacterMinimum() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            session.edit(draft("一"))
            assertEquals("一", store.stored.draft?.body)
            assertFalse(session.state.value.saving)
            assertNull(session.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun acceptedLastEditSurvivesLossOfTheUiObserver() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            val gate = CompletableDeferred<Unit>()
            store.writeGate = gate
            session.edit(draft("第一版"))
            session.edit(draft("切页之前输入的最后几个字"))
            // No UI/composition scope owns either accepted command.
            gate.complete(Unit)
            withTimeout(1_000) { session.state.first { !it.saving } }
            assertEquals("切页之前输入的最后几个字", store.stored.draft?.body)
        } finally { scope.cancel() }
    }

    @Test fun flushConfirmsTheExactSnapshotAndCommitOnlyClearsThatVersion() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            val submitted = draft("这是超过十个字的真实草稿正文")
            session.edit(submitted)
            val token = session.flush(submitted)
            assertEquals(submitted, token.draft)
            session.committed(diary(submitted), token)
            assertNull(store.stored.draft)
            assertEquals(token.revision + 1, store.stored.revision)
            assertEquals(nextId, session.state.value.draft?.id)
            assertEquals("", session.state.value.draft?.body)
        } finally { scope.cancel() }
    }

    @Test fun submittedOlderVersionDoesNotClearNewEditsWithSameId() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            val submitted = draft("这一版已经提交为正式日记了")
            session.edit(submitted)
            val token = session.flush(submitted)
            val newer = submitted.copy(body = "保存期间新增的文字不能被清掉")
            session.edit(newer)
            session.committed(diary(submitted), token)
            assertEquals(newer.body, store.stored.draft?.body)
            assertEquals(nextId, store.stored.draft?.id)
        } finally { scope.cancel() }
    }

    @Test fun blankTitleNormalizationDoesNotChangeTheExactFlushToken() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            val original = draft("没有标题也应当能够保存的真实正文").copy(title = "  ")
            session.edit(original)
            val preview = diary(original)
            assertNull(preview.title)
            val exact = checkNotNull(session.state.value.draft?.takeIf { writingDraftMatchesDiary(it, preview) })
            val token = session.flush(exact)
            assertEquals("  ", token.draft?.title)
            session.committed(preview, token)
            assertNull(store.stored.draft)
        } finally { scope.cancel() }
    }

    @Test fun lostWriteAcknowledgementCanRecoverThenSaveNewerEdits() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            val gate = CompletableDeferred<Unit>()
            store.writeGate = gate
            store.failWriteAcknowledgement = true
            session.edit(draft("已写入但未收到回执的第一版"))
            session.edit(draft("等待第一版写入时又输入的新内容"))
            gate.complete(Unit)
            assertNotNull(session.state.value.error)
            session.retry()
            assertEquals("等待第一版写入时又输入的新内容", store.stored.draft?.body)
            assertFalse(session.state.value.conflict)
            assertNull(session.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun lostPhotoAttachAcknowledgementAdoptsTheCommittedRevision() = runBlocking {
        val store = MemoryStore().apply { failWriteAcknowledgement = true }
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000001", 100, 80, sizeBytes = 500)
        val importer = FakePhotoImports(photo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.importPhoto(firstId) { importer.leased() }
            withTimeout(1_000) { session.state.first { !it.photoImporting } }
            assertEquals(listOf(photo), store.stored.draft?.photos)
            assertEquals(listOf(photo), session.state.value.draft?.photos)
            assertEquals(1, importer.attached)
            assertEquals(0, importer.abandoned)
            assertNull(session.state.value.error)
            assertFalse(session.state.value.conflict)
            assertNull(session.state.value.photoMessage)

            session.edit(checkNotNull(session.state.value.draft).copy(body = "text after recovered acknowledgement"))
            assertEquals("text after recovered acknowledgement", store.stored.draft?.body)
            assertEquals(listOf(photo), store.stored.draft?.photos)
        } finally { scope.cancel() }
    }

    @Test fun rejectedPhotoAttachRollsBackOnlyAfterDiskConfirmsTheOldRevision() = runBlocking {
        val store = MemoryStore().apply { failWriteBeforeCommit = true }
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000002", 100, 80, sizeBytes = 500)
        val importer = FakePhotoImports(photo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.importPhoto(firstId) { importer.leased() }
            withTimeout(1_000) { session.state.first { !it.photoImporting } }
            assertTrue(session.state.value.draft?.photos.orEmpty().isEmpty())
            assertNull(store.stored.draft)
            assertEquals(0, importer.attached)
            assertEquals(1, importer.abandoned)
            assertNotNull(session.state.value.photoMessage)
            assertFalse(session.state.value.conflict)
        } finally { scope.cancel() }
    }

    @Test fun leaseReleaseFailureCannotRollbackAConfirmedAttachment() = runBlocking {
        val store = MemoryStore()
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000007", 100, 80, sizeBytes = 500)
        val importer = FakePhotoImports(photo).apply { attachedFailure = true }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.importPhoto(firstId) { importer.leased() }
            withTimeout(1_000) { session.state.first { !it.photoImporting } }
            assertEquals(listOf(photo), store.stored.draft?.photos)
            assertEquals(listOf(photo), session.state.value.draft?.photos)
            assertEquals(1, importer.attached)
            assertEquals(0, importer.abandoned)
            assertFalse(session.state.value.conflict)
            assertNull(session.state.value.error)
            assertNotNull(session.state.value.photoMessage)
        } finally { scope.cancel() }
    }

    @Test fun rejectedAttachRollsBackBeforeQueuedTextCanPersistThePhoto() = runBlocking {
        val store = MemoryStore().apply { failWriteBeforeCommit = true }
        val gate = CompletableDeferred<Unit>()
        store.writeGate = gate
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000008", 100, 80, sizeBytes = 500)
        val importer = FakePhotoImports(photo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.importPhoto(firstId) { importer.leased() }
            session.edit(checkNotNull(session.state.value.draft).copy(body = "queued while photo save is blocked"))
            gate.complete(Unit)
            withTimeout(1_000) { session.state.first { !it.photoImporting && !it.saving } }
            assertEquals("queued while photo save is blocked", store.stored.draft?.body)
            assertTrue(store.stored.draft?.photos.orEmpty().isEmpty())
            assertEquals(store.stored.draft, session.state.value.draft)
            assertEquals(1, importer.abandoned)
            assertFalse(session.state.value.conflict)
        } finally { scope.cancel() }
    }

    @Test fun unknownPhotoAttachRevisionLocksEditingAndRetainsTheLease() = runBlocking {
        val store = MemoryStore().apply { replaceWithUnknownWrite = true }
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000003", 100, 80, sizeBytes = 500)
        val importer = FakePhotoImports(photo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.importPhoto(firstId) { importer.leased() }
            withTimeout(1_000) { session.state.first { !it.photoImporting } }
            assertTrue(session.state.value.conflict)
            assertNotNull(session.state.value.error)
            assertEquals(listOf(photo), session.state.value.draft?.photos)
            assertEquals(0, importer.attached)
            assertEquals(0, importer.abandoned)
            val before = session.state.value.draft
            session.edit(checkNotNull(before).copy(body = "must not overwrite"))
            assertEquals(before, session.state.value.draft)
            assertEquals("external revision", store.stored.draft?.body)
        } finally { scope.cancel() }
    }

    @Test fun lostPhotoRemovalAcknowledgementAdoptsRemovalAndQueuesCleanup() = runBlocking {
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000004", 100, 80, sizeBytes = 500)
        val initial = draft("with photo").copy(photos = listOf(photo))
        val store = MemoryStore().apply {
            stored = WritingDraftState(1L, initial)
            failWriteAcknowledgement = true
        }
        val importer = FakePhotoImports(photo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.removePhoto(photo.ref)
            assertTrue(store.stored.draft?.photos.orEmpty().isEmpty())
            assertTrue(session.state.value.draft?.photos.orEmpty().isEmpty())
            assertEquals(listOf(setOf(photo.ref)), importer.cleanup)
            assertNull(session.state.value.error)
            assertFalse(session.state.value.conflict)
        } finally { scope.cancel() }
    }

    @Test fun rejectedPhotoRemovalRestoresTheConfirmedMembership() = runBlocking {
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000005", 100, 80, sizeBytes = 500)
        val initial = draft("with photo").copy(photos = listOf(photo))
        val store = MemoryStore().apply {
            stored = WritingDraftState(1L, initial)
            failWriteBeforeCommit = true
        }
        val importer = FakePhotoImports(photo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.removePhoto(photo.ref)
            assertEquals(listOf(photo), store.stored.draft?.photos)
            assertEquals(listOf(photo), session.state.value.draft?.photos)
            assertTrue(importer.cleanup.isEmpty())
            assertFalse(session.state.value.conflict)
        } finally { scope.cancel() }
    }

    @Test fun unknownPhotoRemovalRevisionLocksEditingAndPersistsCleanupEvidence() = runBlocking {
        val photo = DiaryPhoto("photo-00000000-0000-0000-0000-000000000006", 100, 80, sizeBytes = 500)
        val initial = draft("with photo").copy(photos = listOf(photo))
        val store = MemoryStore().apply {
            stored = WritingDraftState(1L, initial)
            replaceWithUnknownWrite = true
        }
        val importer = FakePhotoImports(photo)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = WritingDraftSession(store, scope, importer, createDraft = { draft() })
            session.removePhoto(photo.ref)
            assertTrue(session.state.value.conflict)
            assertNotNull(session.state.value.error)
            assertEquals(listOf(setOf(photo.ref)), importer.cleanup)
            val before = session.state.value.draft
            session.edit(checkNotNull(before).copy(body = "must remain locked"))
            assertEquals(before, session.state.value.draft)
            assertEquals("external revision", store.stored.draft?.body)
        } finally { scope.cancel() }
    }

    @Test fun lostClearAcknowledgementRecognizesOnlyItsOwnTombstone() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            val submitted = draft("正式提交之后需要清理的草稿")
            session.edit(submitted)
            val token = session.flush(submitted)
            store.failClearAcknowledgement = true
            session.committed(diary(submitted), token)
            assertNotNull(session.state.value.error)
            session.retry()
            assertFalse(session.state.value.conflict)
            assertNull(session.state.value.error)
            assertNotEquals(submitted, store.stored.draft)
        } finally { scope.cancel() }
    }

    @Test fun unknownExternalRevisionNeverBecomesPermissionToOverwrite() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            session.edit(draft("原来的草稿"))
            val external = draft("另一来源更新过的草稿不能覆盖")
            store.stored = WritingDraftRules.save(store.stored, store.stored.revision, external)
            val local = draft("用户在当前页面保留的新输入")
            session.edit(local)
            session.retry()
            assertTrue(session.state.value.conflict)
            assertEquals(local, session.state.value.draft)
            assertEquals(external, store.stored.draft)
        } finally { scope.cancel() }
    }

    @Test fun stalePreviewCannotPassTheSubmissionFlushBarrier() = runBlocking {
        val store = MemoryStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val session = session(store, scope)
            val old = draft("这是进入预览之前的原始内容")
            val current = old.copy(body = "这是预览之后又修改过的文字")
            session.edit(current)
            try { session.flush(old); fail("Stale preview must not submit") }
            catch (_: IllegalStateException) { /* Expected: no diary transaction can start. */ }
            assertEquals(current, store.stored.draft)
        } finally { scope.cancel() }
    }
}
