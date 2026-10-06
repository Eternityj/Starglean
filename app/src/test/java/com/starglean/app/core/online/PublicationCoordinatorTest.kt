package com.starglean.app.core.online

import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.draft.WritingDraft
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.media.DiaryPhoto
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.network.*
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationCoordinatorTest {
    @Test fun `fresh first send freezes persists and publishes without a leading status call`() {
        val fresh = frozen(); val store = MemoryPendingStore(); val gateway = FakeGateway()
        var freezeCalls = 0
        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
                .submitFresh(fresh.accountId, fresh.draftId, false) { freezeCalls++; fresh }
        }
        assertTrue(result is PublicationStep.Complete)
        assertEquals(1, freezeCalls)
        assertEquals(listOf("publish", "status"), gateway.events)
        assertEquals(fresh.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `replacement waits for confirmed cancellation before freezing fresh location`() {
        val old = frozen(); val fresh = frozenFor(old.accountId, old.draftId)
        val store = MemoryPendingStore(PendingPublication(old)); val gateway = FakeGateway()
        var cancelObservedAtFreeze = false
        runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
                .submitFresh(old.accountId, old.draftId, true) {
                    cancelObservedAtFreeze = gateway.events == listOf("cancel")
                    fresh
                }
        }
        assertTrue(cancelObservedAtFreeze)
        assertEquals(listOf("cancel", "publish", "status"), gateway.events)
    }

    @Test fun `unknown cancellation preserves old fence and never freezes or publishes`() {
        val old = frozen(); val store = MemoryPendingStore(PendingPublication(old))
        val gateway = FakeGateway().apply { cancelResult = NetworkResult.TransportFailure(java.io.IOException("offline")) }
        var freezeCalls = 0
        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
                .submitFresh(old.accountId, old.draftId, true) { freezeCalls++; frozenFor(old.accountId, old.draftId) }
        }
        assertTrue(result is PublicationStep.Failed && result.outcomeUnknown)
        assertEquals(0, freezeCalls)
        assertEquals(old.request.requestId, store.value?.frozen?.request?.requestId)
        assertEquals(0, gateway.publishCalls)
    }

    @Test fun `mismatched cancellation receipt preserves old fence and never freezes`() {
        val old = frozen(); val store = MemoryPendingStore(PendingPublication(old))
        val gateway = FakeGateway().apply {
            cancelResult = NetworkResult.Success(PublicationStatus(
                "CANCELLED", UUID.randomUUID(), null, null, null, null))
        }
        var freezeCalls = 0
        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
                .submitFresh(old.accountId, old.draftId, true) { freezeCalls++; frozenFor(old.accountId, old.draftId) }
        }
        assertTrue(result is PublicationStep.Failed && result.outcomeUnknown)
        assertEquals(0, freezeCalls)
        assertEquals(old.request.requestId, store.value?.frozen?.request?.requestId)
        assertEquals(0, gateway.publishCalls)
    }

    @Test fun `found cancellation result resumes old request without freezing replacement`() {
        val old = frozen(); val entryId = UUID.randomUUID(); val store = MemoryPendingStore(PendingPublication(old))
        val found = PublicationStatus("FOUND", old.request.requestId, entryId, "public", "published", true)
        val gateway = FakeGateway(NetworkResult.Success(found)).apply { cancelResult = NetworkResult.Success(found) }
        var freezeCalls = 0
        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
                .submitFresh(old.accountId, old.draftId, true) { freezeCalls++; frozenFor(old.accountId, old.draftId) }
        }
        assertTrue(result is PublicationStep.Complete)
        assertEquals(0, freezeCalls)
        assertEquals(0, gateway.publishCalls)
        assertEquals(old.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `fresh store write failure never dispatches publish`() {
        val fresh = frozen()
        val store = object : PendingPublicationStore {
            override suspend fun read(): PendingPublication? = null
            override suspend fun write(value: PendingPublication) { throw PublicationStorageException("write failed") }
            override suspend fun clear(expectedRequestId: UUID) = Unit
        }
        val gateway = FakeGateway()
        val failure = runCatching { runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
                .submitFresh(fresh.accountId, fresh.draftId, false) { fresh }
        } }.exceptionOrNull()
        assertTrue(failure is PublicationStorageException)
        assertEquals(0, gateway.publishCalls)
    }

    @Test fun `fresh interrupted publish preserves the newly frozen request for recovery`() {
        val fresh = frozen(); val store = MemoryPendingStore(); val gateway = FakeGateway().apply {
            publishResult = NetworkResult.TransportFailure(java.io.IOException("lost response"))
        }
        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
                .submitFresh(fresh.accountId, fresh.draftId, false) { fresh }
        }
        assertTrue(result is PublicationStep.Failed && result.outcomeUnknown)
        assertEquals(fresh.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `unknown status never sends publish`() {
        val frozen = frozen()
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway(statusResult = NetworkResult.TransportFailure(java.io.IOException("offline")))
        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).submit(frozen.accountId) }
        assertTrue(result is PublicationStep.Failed && result.outcomeUnknown)
        assertEquals(0, gateway.publishCalls)
    }

    @Test fun `account switch cannot continue pending publication`() {
        val frozen = frozen()
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway()
        assertTrue(runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).recover(UUID.randomUUID()) } is PublicationStep.NeedsAccount)
        assertEquals(0, gateway.publishCalls)
    }

    @Test fun `restart reconciles found receipt without duplicate publish`() {
        val frozen = frozen()
        val entryId = UUID.randomUUID()
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway(statusResult = NetworkResult.Success(PublicationStatus("FOUND", frozen.request.requestId, entryId, "public", "published", true)))
        val restarted = PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") })
        val recovered = runNow { restarted.recover(frozen.accountId) }
        assertTrue(recovered is PublicationStep.NeedsExplicitSubmit)
        val completed = runNow { restarted.submit(frozen.accountId) }
        assertTrue(completed is PublicationStep.Complete)
        assertEquals(0, gateway.publishCalls)
    }

    @Test fun `account lease change after await rejects response and preserves fence`() {
        val frozen = frozen(); val store = MemoryPendingStore(PendingPublication(frozen)); val gateway = FakeGateway()
        var checks = 0
        val coordinator = PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") },
            isIdentityCurrent = { ++checks < 4 })
        assertTrue(runCatching { runNow { coordinator.submit(frozen.accountId) } }.exceptionOrNull() is PublicationIdentityChangedException)
        assertEquals(null, store.value?.entryId)
    }

    @Test fun `partial media progress is durable and retry skips finalized media`() {
        val frozen = frozen(mediaCount = 2); val store = MemoryPendingStore(PendingPublication(frozen, UUID.randomUUID()))
        val first = FakeGateway().apply { failTargetFor = frozen.request.mediaManifest[1].id }
        val source = PublicationPhotoSource { byteArrayOf(1) }
        assertTrue(runNow { PublicationCoordinator(store, first, source).submit(frozen.accountId) } is PublicationStep.Failed)
        assertEquals(setOf(frozen.request.mediaManifest[0].id), store.value?.finalizedMediaIds)
        val second = FakeGateway(statusResult = NetworkResult.Success(PublicationStatus("FOUND", frozen.request.requestId,
            store.value!!.entryId, "public", "published", true)))
        assertTrue(runNow { PublicationCoordinator(store, second, source).submit(frozen.accountId) } is PublicationStep.Complete)
        assertEquals(listOf(frozen.request.mediaManifest[1].id), second.finalizeCalls.distinct())
    }

    @Test fun `lost local receipt write keeps original durable fence and reports unknown`() {
        val frozen = frozen()
        val store = object : PendingPublicationStore {
            var value = PendingPublication(frozen)
            override suspend fun read() = value
            override suspend fun write(value: PendingPublication) { throw PublicationStorageException("simulated crash boundary") }
            override suspend fun clear(expectedRequestId: UUID) = error("must not clear")
        }
        val error = runCatching {
            runNow { PublicationCoordinator(store, FakeGateway(), PublicationPhotoSource { error("none") }).submit(frozen.accountId) }
        }.exceptionOrNull()
        assertTrue(error is PublicationStorageException)
        assertEquals(null, store.value.entryId)
        assertEquals(frozen.request.requestId, store.value.frozen.request.requestId)
    }

    @Test fun `reconciliation accepts a photo finalized while other media is still pending`() {
        val frozen = frozen(mediaCount = 2)
        val entryId = UUID.randomUUID()
        val store = MemoryPendingStore(PendingPublication(frozen, entryId))
        val gateway = FakeGateway(NetworkResult.Success(PublicationStatus(
            "FOUND", frozen.request.requestId, entryId, "public", "published", true,
        ))).apply {
            finalizeResults.add(NetworkResult.Success("MEDIA_PARTIAL"))
            finalizeResults.add(NetworkResult.Success("MEDIA_ALREADY_VALIDATED"))
        }

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { byteArrayOf(1) }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.Complete)
        assertEquals(0, gateway.uploadCalls)
        assertEquals(0, gateway.publishCalls)
        assertEquals(frozen.request.mediaManifest.map { it.id }.toSet(), store.value?.finalizedMediaIds)
    }

    @Test fun `concurrent finalizer success after upload completes without uploading again`() {
        val frozen = frozen(mediaCount = 1)
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply {
            finalizeResults.add(NetworkResult.ServiceFailure("OBJECT_NOT_FOUND"))
            finalizeResults.add(NetworkResult.Success("MEDIA_ALREADY_VALIDATED"))
        }

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { byteArrayOf(1) }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.Complete)
        assertEquals(1, gateway.uploadCalls)
    }

    @Test fun `photo rejection preserves pending entry and never reuploads a rejected object`() {
        listOf<NetworkResult<String>>(
            NetworkResult.ServiceFailure("VALIDATION_REJECTED"),
            NetworkResult.Success("VALIDATION_REJECTED"),
        ).forEach { rejection ->
            val frozen = frozen(mediaCount = 1)
            val store = MemoryPendingStore(PendingPublication(frozen))
            val gateway = FakeGateway().apply { finalizeResults.add(rejection) }

            val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { byteArrayOf(1) }).submit(frozen.accountId) }

            assertTrue(result is PublicationStep.Failed && result.outcomeUnknown)
            assertTrue((result as PublicationStep.Failed).message.contains("Photo 1 was rejected"))
            assertTrue(result.message.contains("draft and photos are kept"))
            assertEquals(0, gateway.uploadCalls)
            assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
            assertTrue(store.value?.entryId != null)
            assertTrue(store.value!!.finalizedMediaIds.isEmpty())
        }
    }

    @Test fun `first upload rejection reports its cause and preserves frozen photos`() {
        val frozen = frozen(mediaCount = 1)
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply {
            finalizeResults.add(NetworkResult.ServiceFailure("OBJECT_NOT_FOUND"))
            finalizeResults.add(NetworkResult.ServiceFailure("VALIDATION_REJECTED"))
        }

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { byteArrayOf(1) }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.Failed)
        assertTrue((result as PublicationStep.Failed).message.contains("VALIDATION_REJECTED"))
        assertEquals(1, gateway.uploadCalls)
        assertEquals(frozen.localRecord.diary.photos, store.value?.frozen?.localRecord?.diary?.photos)
        assertTrue(store.value!!.finalizedMediaIds.isEmpty())
    }

    @Test fun `cloud entry capacity is definite terminal rejection and clears only publication fence`() {
        val frozen = frozen(mediaCount = 1)
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply {
            publishResult = NetworkResult.ServiceFailure("CLOUD_ENTRY_LIMIT")
        }

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("must not read photos") }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.Rejected)
        result as PublicationStep.Rejected
        assertEquals("CLOUD_ENTRY_LIMIT", result.code)
        assertTrue(result.message.contains("1,000") && result.message.contains("Delete an existing cloud entry"))
        assertTrue(!result.message.contains("lifetime") && !result.message.contains("including deleted"))
        assertEquals(null, store.value)
        assertEquals(1, gateway.publishCalls)
        assertEquals("title", frozen.draftToken.draft?.title)
        assertEquals(1, frozen.localRecord.diary.photos.size)
    }

    @Test fun `authentication rejection preserves fence and requests account`() {
        val frozen = frozen(); val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply { publishResult = NetworkResult.AuthenticationRequired }

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.NeedsAccount)
        assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `authentication rejection during status check does not become unknown`() {
        val frozen = frozen(); val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway(statusResult = NetworkResult.AuthenticationRequired)

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.NeedsAccount)
        assertEquals(0, gateway.publishCalls)
        assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `definite client rejection is not called unknown and preserves frozen evidence`() {
        val frozen = frozen(); val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply { publishResult = NetworkResult.HttpFailure(400) }

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.Failed && !result.outcomeUnknown)
        assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `unfixable frozen validation rejection releases only publication fence`() {
        listOf("INVALID_REQUEST", "REQUEST_CONFLICT", "LOCATION_UNRELIABLE").forEach { status ->
            val frozen = frozen(mediaCount = 1)
            val store = MemoryPendingStore(PendingPublication(frozen))
            val gateway = FakeGateway().apply { publishResult = NetworkResult.ServiceFailure(status) }

            val result = runNow {
                PublicationCoordinator(store, gateway, PublicationPhotoSource { error("must not read photos") })
                    .submit(frozen.accountId)
            }

            assertTrue("$status should be terminal", result is PublicationStep.Rejected)
            assertEquals(status, (result as PublicationStep.Rejected).code)
            assertEquals(null, store.value)
            assertEquals("title", frozen.draftToken.draft?.title)
            assertEquals(1, frozen.localRecord.diary.photos.size)
        }
    }

    @Test fun `disabled service is definite but retryable with the same frozen identity`() {
        val frozen = frozen(); val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply { publishResult = NetworkResult.ServiceFailure("SERVICE_DISABLED") }

        val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).submit(frozen.accountId) }

        assertTrue(result is PublicationStep.Failed && !result.outcomeUnknown)
        assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `server tombstone releases fence during recovery without publishing`() {
        listOf("DELETED", "CANCELLED").forEach { status ->
            val frozen = frozen(); val store = MemoryPendingStore(PendingPublication(frozen))
            val gateway = FakeGateway(NetworkResult.Success(PublicationStatus(
                status, frozen.request.requestId, null, null, null, null,
            )))

            val result = runNow {
                PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).recover(frozen.accountId)
            }

            assertTrue(result is PublicationStep.Rejected)
            assertEquals(status, (result as PublicationStep.Rejected).code)
            assertEquals(null, store.value)
            assertEquals(0, gateway.publishCalls)
        }
    }

    @Test fun `malformed or interrupted publish remains unknown and keeps idempotency fence`() {
        listOf<NetworkResult<EntryReceipt>>(
            NetworkResult.InvalidResponse("bad envelope"),
            NetworkResult.TransportFailure(java.io.IOException("lost response")),
            NetworkResult.HttpFailure(408),
            NetworkResult.HttpFailure(429),
            NetworkResult.HttpFailure(503),
        ).forEach { failure ->
            val frozen = frozen(); val store = MemoryPendingStore(PendingPublication(frozen))
            val gateway = FakeGateway().apply { publishResult = failure }
            val result = runNow { PublicationCoordinator(store, gateway, PublicationPhotoSource { error("none") }).submit(frozen.accountId) }
            assertTrue(result is PublicationStep.Failed && result.outcomeUnknown)
            assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
        }
    }

    @Test fun `mismatched found receipt is unknown and never uploads to another entry`() {
        val frozen = frozen(mediaCount = 1); val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway(NetworkResult.Success(PublicationStatus(
            "FOUND", UUID.randomUUID(), UUID.randomUUID(), "public", "published", true,
        )))

        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("must not read photos") }).submit(frozen.accountId)
        }

        assertTrue(result is PublicationStep.Failed && result.outcomeUnknown)
        assertEquals(null, store.value?.entryId)
        assertEquals(0, gateway.publishCalls)
        assertTrue(gateway.finalizeCalls.isEmpty())
    }

    @Test fun `edit without a pending save is ready without contacting the service`() {
        val gateway = FakeGateway()
        val result = runNow {
            PublicationCoordinator(MemoryPendingStore(), gateway, PublicationPhotoSource { error("unused") })
                .cancelForEditing(UUID.randomUUID())
        }
        assertEquals(PublicationEditResult.Ready, result)
        assertTrue(gateway.events.isEmpty())
    }

    @Test fun `confirmed cancellation before creation releases only the pending journal`() {
        val frozen = frozen(mediaCount = 1)
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway()

        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })
                .cancelForEditing(frozen.accountId)
        }

        assertEquals(PublicationEditResult.Ready, result)
        assertEquals(null, store.value)
        assertEquals(listOf("cancel"), gateway.events)
        assertEquals("title", frozen.draftToken.draft?.title)
        assertEquals(1, frozen.localRecord.diary.photos.size)
    }

    @Test fun `found cloud entry is deleted and tombstone checked before editing`() {
        val frozen = frozen(mediaCount = 1)
        val entryId = UUID.randomUUID()
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply {
            cancelResult = NetworkResult.Success(PublicationStatus(
                "FOUND", frozen.request.requestId, entryId, "public", "published", false))
            statusResult = NetworkResult.Success(PublicationStatus(
                "DELETED", frozen.request.requestId, entryId, null, null, null))
        }

        val result = runNow {
            PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })
                .cancelForEditing(frozen.accountId)
        }

        assertEquals(PublicationEditResult.Ready, result)
        assertEquals(listOf("cancel", "delete", "status"), gateway.events)
        assertEquals(listOf(entryId), gateway.deleteCalls)
        assertEquals(null, store.value)
        assertEquals("title", frozen.draftToken.draft?.title)
        assertEquals(1, frozen.localRecord.diary.photos.size)
    }

    @Test fun `interrupted delete preserves entry identity and retry can finish withdrawn entry`() {
        val frozen = frozen(mediaCount = 1)
        val entryId = UUID.randomUUID()
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply {
            cancelResults += NetworkResult.Success(PublicationStatus(
                "FOUND", frozen.request.requestId, entryId, "public", "published", false))
            cancelResults += NetworkResult.Success(PublicationStatus(
                "FOUND", frozen.request.requestId, entryId, "withdrawn", "published", false))
            deleteResults += NetworkResult.TransportFailure(java.io.IOException("lost deletion response"))
            deleteResults += NetworkResult.Success("ENTRY_DELETED")
            statusResult = NetworkResult.Success(PublicationStatus(
                "DELETED", frozen.request.requestId, entryId, null, null, null))
        }
        val coordinator = PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })

        assertTrue(runNow { coordinator.cancelForEditing(frozen.accountId) } is PublicationEditResult.Failed)
        assertEquals(entryId, store.value?.entryId)
        assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
        assertEquals(PublicationEditResult.Ready, runNow { coordinator.cancelForEditing(frozen.accountId) })
        assertEquals(listOf("cancel", "delete", "cancel", "delete", "status"), gateway.events)
        assertEquals(listOf(entryId, entryId), gateway.deleteCalls)
        assertEquals(null, store.value)
    }

    @Test fun `unknown or mismatched cancellation never deletes or clears the journal`() {
        val frozen = frozen(mediaCount = 1)
        val failures = listOf<NetworkResult<PublicationStatus>>(
            NetworkResult.TransportFailure(java.io.IOException("offline")),
            NetworkResult.Success(PublicationStatus("CANCELLED", UUID.randomUUID(), null, null, null, null)),
            NetworkResult.Success(PublicationStatus("NOT_FOUND", frozen.request.requestId, null, null, null, null)),
        )
        failures.forEach { cancellation ->
            val store = MemoryPendingStore(PendingPublication(frozen))
            val gateway = FakeGateway().apply { cancelResult = cancellation }
            val result = runNow {
                PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })
                    .cancelForEditing(frozen.accountId)
            }
            assertTrue(result is PublicationEditResult.Failed)
            assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
            assertTrue(gateway.deleteCalls.isEmpty())
            assertEquals(listOf("cancel"), gateway.events)
        }
    }

    @Test fun `found receipt with a different entry or visibility cannot delete`() {
        val frozen = frozen(mediaCount = 1)
        val existingId = UUID.randomUUID()
        val cases = listOf(
            PublicationStatus("FOUND", frozen.request.requestId, UUID.randomUUID(), "public", "published", false),
            PublicationStatus("FOUND", frozen.request.requestId, existingId, "private", "published", false),
            PublicationStatus("FOUND", frozen.request.requestId, null, "public", "published", false),
        )
        cases.forEach { receipt ->
            val store = MemoryPendingStore(PendingPublication(frozen, existingId))
            val gateway = FakeGateway().apply { cancelResult = NetworkResult.Success(receipt) }
            val result = runNow {
                PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })
                    .cancelForEditing(frozen.accountId)
            }
            assertTrue(result is PublicationEditResult.Failed)
            assertEquals(existingId, store.value?.entryId)
            assertTrue(gateway.deleteCalls.isEmpty())
        }
    }

    @Test fun `unconfirmed delete receipt retains journal without treating upload as editable`() {
        val frozen = frozen(mediaCount = 1)
        val entryId = UUID.randomUUID()
        listOf<NetworkResult<String>>(
            NetworkResult.Success("DELETE_RETRY_REQUIRED"),
            NetworkResult.ServiceFailure("SERVICE_UNAVAILABLE"),
            NetworkResult.AuthenticationRequired,
        ).forEach { deleteResult ->
            val store = MemoryPendingStore(PendingPublication(frozen))
            val gateway = FakeGateway().apply {
                cancelResult = NetworkResult.Success(PublicationStatus(
                    "FOUND", frozen.request.requestId, entryId, "public", "published", false))
                this.deleteResult = deleteResult
            }
            val result = runNow {
                PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })
                    .cancelForEditing(frozen.accountId)
            }
            assertTrue(result is PublicationEditResult.Failed || result is PublicationEditResult.NeedsAccount)
            assertEquals(entryId, store.value?.entryId)
            assertEquals(listOf("cancel", "delete"), gateway.events)
        }
    }

    @Test fun `delete success with missing or mismatched tombstone retains journal`() {
        val frozen = frozen(mediaCount = 1)
        val entryId = UUID.randomUUID()
        listOf<NetworkResult<PublicationStatus>>(
            NetworkResult.Success(PublicationStatus("FOUND", frozen.request.requestId, entryId, "withdrawn", "published", false)),
            NetworkResult.Success(PublicationStatus("DELETED", UUID.randomUUID(), entryId, null, null, null)),
            NetworkResult.TransportFailure(java.io.IOException("status unavailable")),
        ).forEach { statusResult ->
            val store = MemoryPendingStore(PendingPublication(frozen))
            val gateway = FakeGateway(statusResult).apply {
                cancelResult = NetworkResult.Success(PublicationStatus(
                    "FOUND", frozen.request.requestId, entryId, "public", "published", false))
            }
            val result = runNow {
                PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })
                    .cancelForEditing(frozen.accountId)
            }
            assertTrue(result is PublicationEditResult.Failed)
            assertEquals(entryId, store.value?.entryId)
            assertEquals(listOf("cancel", "delete", "status"), gateway.events)
        }
    }

    @Test fun `wrong account and authentication failure never release journal`() {
        val frozen = frozen(mediaCount = 1)
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway()
        val coordinator = PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") })

        assertTrue(runNow { coordinator.cancelForEditing(UUID.randomUUID()) } is PublicationEditResult.NeedsAccount)
        assertTrue(gateway.events.isEmpty())
        gateway.cancelResult = NetworkResult.AuthenticationRequired
        assertTrue(runNow { coordinator.cancelForEditing(frozen.accountId) } is PublicationEditResult.NeedsAccount)
        assertEquals(listOf("cancel"), gateway.events)
        assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
    }

    @Test fun `identity change after deletion prevents clearing old account journal`() {
        val frozen = frozen(mediaCount = 1)
        val entryId = UUID.randomUUID()
        val store = MemoryPendingStore(PendingPublication(frozen))
        val gateway = FakeGateway().apply {
            cancelResult = NetworkResult.Success(PublicationStatus(
                "FOUND", frozen.request.requestId, entryId, "public", "published", false))
        }
        var checks = 0
        val coordinator = PublicationCoordinator(store, gateway, PublicationPhotoSource { error("unused") },
            isIdentityCurrent = { ++checks < 4 })

        assertTrue(runCatching { runNow { coordinator.cancelForEditing(frozen.accountId) } }
            .exceptionOrNull() is PublicationIdentityChangedException)
        assertEquals(listOf("cancel", "delete"), gateway.events)
        assertEquals(entryId, store.value?.entryId)
        assertEquals(frozen.request.requestId, store.value?.frozen?.request?.requestId)
    }

    private fun frozen(mediaCount: Int = 0): FrozenPublication {
        val id = UUID.randomUUID()
        val photos = (0 until mediaCount).map { DiaryPhoto("photo-${UUID.randomUUID()}", 1, 1, sizeBytes = 1) }
        val manifests = photos.map { MediaManifestItem(UUID.randomUUID(), "image/jpeg", 1,
            java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)).joinToString("") { "%02x".format(it) }) }
        val diary = DiaryDemo("own-$id", "title", "0123456789 body", "27 Sep", DiaryWeatherSnapshot.Unavailable,
            "Location shared with service", PaperStyle.WarmWhite, DiaryFont.Clear, photos)
        return FrozenPublication(UUID.randomUUID(), id, WritingDraftState(4, WritingDraft("own-$id", "title", "0123456789 body", PaperStyle.WarmWhite, DiaryFont.Clear, photos)),
            PublishEntryRequest(id, "title", "0123456789 body", "warm_white", "clear",
                WeatherSnapshot(WeatherKind.Unknown, Daylight.Day), manifests, visibility = EntryVisibility.Public,
                location = ReaderLocation(-37.8, 144.9, 5.0, Instant.parse("2026-09-27T00:00:00Z"), false), shareConfirmed = true),
            OwnDiaryRecord(diary, null, 1))
    }

    private fun frozenFor(accountId: UUID, draftId: UUID): FrozenPublication {
        val value = frozen()
        val diaryId = "own-$draftId"
        return value.copy(
            accountId = accountId,
            draftId = draftId,
            draftToken = value.draftToken.copy(draft = value.draftToken.draft!!.copy(id = diaryId)),
            request = value.request.copy(requestId = UUID.randomUUID()),
            localRecord = value.localRecord.copy(diary = value.localRecord.diary.copy(id = diaryId)),
        )
    }
}

private class MemoryPendingStore(var value: PendingPublication? = null) : PendingPublicationStore {
    override suspend fun read() = value
    override suspend fun write(value: PendingPublication) { this.value = value }
    override suspend fun clear(expectedRequestId: UUID) { if (value?.frozen?.request?.requestId == expectedRequestId) value = null }
}
private class FakeGateway(var statusResult: NetworkResult<PublicationStatus> = NetworkResult.Success(PublicationStatus("NOT_FOUND", null, null, null, null, null))) : PublicationGateway {
    var publishCalls = 0
    var publishResult: NetworkResult<EntryReceipt>? = null
    var cancelResult: NetworkResult<PublicationStatus>? = null
    val cancelResults = java.util.ArrayDeque<NetworkResult<PublicationStatus>>()
    var deleteResult: NetworkResult<String> = NetworkResult.Success("ENTRY_DELETED")
    val deleteResults = java.util.ArrayDeque<NetworkResult<String>>()
    val deleteCalls = mutableListOf<UUID>()
    var failTargetFor: UUID? = null
    var uploadCalls = 0
    val finalizeResults = java.util.ArrayDeque<NetworkResult<String>>()
    val finalizeCalls = mutableListOf<UUID>()
    val events = mutableListOf<String>()
    private var publishedRequest: PublishEntryRequest? = null
    private var publishedReceipt: EntryReceipt? = null
    override suspend fun status(requestId: UUID): NetworkResult<PublicationStatus> {
        events += "status"
        val request = publishedRequest
        val receipt = publishedReceipt
        return if (request?.requestId == requestId && receipt != null)
            NetworkResult.Success(PublicationStatus("FOUND", requestId, receipt.entryId, receipt.visibility, receipt.publicationState, true))
        else statusResult
    }
    override suspend fun publish(request: PublishEntryRequest): NetworkResult<EntryReceipt> {
        events += "publish"
        publishCalls++
        val result = publishResult ?: NetworkResult.Success(EntryReceipt("PUBLICATION_PREPARED", UUID.randomUUID(), "public", "published"))
        if (result is NetworkResult.Success) { publishedRequest = request; publishedReceipt = result.value }
        return result
    }
    override suspend fun cancel(requestId: UUID): NetworkResult<PublicationStatus> {
        events += "cancel"
        if (cancelResults.isNotEmpty()) return cancelResults.removeFirst()
        return cancelResult ?: NetworkResult.Success(PublicationStatus("CANCELLED", requestId, null, null, null, null))
    }
    override suspend fun deleteEntry(entryId: UUID): NetworkResult<String> {
        events += "delete"
        deleteCalls += entryId
        return if (deleteResults.isNotEmpty()) deleteResults.removeFirst() else deleteResult
    }
    override suspend fun uploadTarget(entryId: UUID, mediaId: UUID): NetworkResult<MediaUploadTarget> =
        if (mediaId == failTargetFor) NetworkResult.TransportFailure(java.io.IOException("offline"))
        else NetworkResult.Success(MediaUploadTarget("UPLOAD_READY", "nearby-private-photos", "00000000-0000-0000-0000-000000000001/$entryId/$mediaId", "image/jpeg", 1,
            java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1)).joinToString("") { "%02x".format(it) }))
    override suspend fun upload(target: MediaUploadTarget, bytes: ByteArray): NetworkResult<Unit> {
        uploadCalls++
        return NetworkResult.Success(Unit)
    }
    override suspend fun finalize(entryId: UUID, mediaId: UUID): NetworkResult<String> {
        finalizeCalls += mediaId
        if (finalizeResults.isNotEmpty()) return finalizeResults.removeFirst()
        return if (finalizeCalls.count { it == mediaId } == 1) NetworkResult.ServiceFailure("OBJECT_NOT_FOUND") else NetworkResult.Success("MEDIA_READY")
    }
}
private fun <T> runNow(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(object : Continuation<T> { override val context = EmptyCoroutineContext; override fun resumeWith(result: Result<T>) { outcome = result } })
    return outcome!!.getOrThrow()
}
