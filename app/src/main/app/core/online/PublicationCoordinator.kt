package com.starglean.app.core.online

import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.draft.WritingDraftState
import com.starglean.app.core.network.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FrozenPublication(
    val accountId: UUID,
    val draftId: UUID,
    val draftToken: WritingDraftState,
    val request: PublishEntryRequest,
    val localRecord: OwnDiaryRecord,
) {
    init {
        require(localRecord.diary.id == "own-$draftId")
        require(draftToken.draft?.id == localRecord.diary.id)
    }
    val fingerprint: String = publicationFingerprint(request)
    override fun toString() = "FrozenPublication(accountId=$accountId, draftId=$draftId, content=redacted)"
}

data class PendingPublication(
    val frozen: FrozenPublication,
    val entryId: UUID? = null,
    val finalizedMediaIds: Set<UUID> = emptySet(),
) { override fun toString() = "PendingPublication(accountId=${frozen.accountId}, requestId=${frozen.request.requestId}, content=redacted)" }

interface PendingPublicationStore {
    suspend fun read(): PendingPublication?
    suspend fun write(value: PendingPublication)
    suspend fun clear(expectedRequestId: UUID)
}

interface PublicationGateway {
    suspend fun status(requestId: UUID): NetworkResult<PublicationStatus>
    suspend fun publish(request: PublishEntryRequest): NetworkResult<EntryReceipt>
    suspend fun cancel(requestId: UUID): NetworkResult<PublicationStatus>
    suspend fun deleteEntry(entryId: UUID): NetworkResult<String>
    suspend fun uploadTarget(entryId: UUID, mediaId: UUID): NetworkResult<MediaUploadTarget>
    suspend fun upload(target: MediaUploadTarget, bytes: ByteArray): NetworkResult<Unit>
    suspend fun finalize(entryId: UUID, mediaId: UUID): NetworkResult<String>
}

class NearbyPublicationGateway(private val repository: NearbyRepository) : PublicationGateway {
    override suspend fun status(requestId: UUID) = repository.publicationStatus(requestId)
    override suspend fun publish(request: PublishEntryRequest) = repository.publish(request)
    override suspend fun cancel(requestId: UUID) = repository.cancelPublicationRequest(requestId)
    override suspend fun deleteEntry(entryId: UUID) = repository.deleteEntryWithMedia(entryId)
    override suspend fun uploadTarget(entryId: UUID, mediaId: UUID) = repository.mediaUploadTarget(entryId, mediaId)
    override suspend fun upload(target: MediaUploadTarget, bytes: ByteArray) = repository.uploadMedia(target, bytes)
    override suspend fun finalize(entryId: UUID, mediaId: UUID) = repository.finalizeMedia(entryId, mediaId)
}

fun interface PublicationPhotoSource { suspend fun bytes(localRef: String): ByteArray }

sealed interface PublicationStep {
    data class NeedsExplicitSubmit(val pending: PendingPublication) : PublicationStep
    data class NeedsAccount(val reason: String) : PublicationStep
    data class Failed(val message: String, val outcomeUnknown: Boolean) : PublicationStep
    data class Rejected(val message: String, val code: String) : PublicationStep
    data class Complete(val pending: PendingPublication) : PublicationStep
}

sealed interface PublicationEditResult {
    data object Ready : PublicationEditResult
    data class NeedsAccount(val reason: String) : PublicationEditResult
    data class Failed(val message: String) : PublicationEditResult
}

internal const val CLOUD_CAPACITY_MESSAGE =
    "This account already has the current maximum of 1,000 cloud entries. " +
        "This draft and its photos remain on this device. Delete an existing cloud entry and wait for deletion to finish before submitting it again."

private const val LEGACY_LIFETIME_CAPACITY_MESSAGE =
    "The server rejected this publication under its legacy lifetime submission limit. " +
        "This draft and its photos remain on this device."

/** Serial, explicit publication transaction. Network failures never imply success or trigger replay. */
class PublicationCoordinator(
    private val store: PendingPublicationStore,
    private val gateway: PublicationGateway,
    private val photos: PublicationPhotoSource,
    private val isIdentityCurrent: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()

    /** Explicitly cancel this attempt's cloud copy, retaining the editable draft and local photos. */
    suspend fun cancelForEditing(accountId: UUID): PublicationEditResult = exclusive {
        var pending = store.read() ?: return@exclusive PublicationEditResult.Ready
        if (pending.frozen.accountId != accountId)
            return@exclusive PublicationEditResult.NeedsAccount("Sign in with the account that started this save before returning to edit.")
        val requestId = pending.frozen.request.requestId
        val failure = PublicationEditResult.Failed(
            "Cancellation could not be confirmed. Your draft and photos are safe. Try Cancel save and edit again when connected.")
        val auth = PublicationEditResult.NeedsAccount("Sign in again to cancel this pending save. Your draft and photos are safe.")
        // This creates a permanent cancellation tombstone if create has not committed,
        // so an in-flight create cannot appear after the editor has been unlocked.
        val receipt = when (val result = dispatch { gateway.cancel(requestId) }) {
            is NetworkResult.Success -> result.value
            NetworkResult.AuthenticationRequired -> return@exclusive auth
            else -> return@exclusive failure
        }
        if (receipt.requestId != requestId) return@exclusive failure
        when (receipt.status) {
            "CANCELLED" -> {
                if (pending.entryId != null) return@exclusive failure
                store.clear(requestId)
                return@exclusive PublicationEditResult.Ready
            }
            "DELETED" -> {
                store.clear(requestId)
                return@exclusive PublicationEditResult.Ready
            }
            "FOUND" -> {
                val entryId = receipt.entryId ?: return@exclusive failure
                if (pending.entryId != null && pending.entryId != entryId) return@exclusive failure
                // A prior interrupted deletion has already withdrawn the entry.
                if (receipt.visibility !in setOf(pending.frozen.request.visibility.wire, "withdrawn"))
                    return@exclusive failure
                pending = pending.copy(entryId = entryId)
                store.write(pending)
                when (val deleted = dispatch { gateway.deleteEntry(entryId) }) {
                    is NetworkResult.Success -> if (deleted.value != "ENTRY_DELETED") return@exclusive failure
                    NetworkResult.AuthenticationRequired -> return@exclusive auth
                    else -> return@exclusive failure
                }
                val confirmed = when (val result = dispatch { gateway.status(requestId) }) {
                    is NetworkResult.Success -> result.value
                    NetworkResult.AuthenticationRequired -> return@exclusive auth
                    else -> return@exclusive failure
                }
                if (confirmed.status != "DELETED" || confirmed.requestId != requestId) return@exclusive failure
                store.clear(requestId)
                PublicationEditResult.Ready
            }
            else -> failure
        }
    }

    suspend fun stage(frozen: FrozenPublication): PublicationStep = exclusive {
        val current = store.read()
        if (current != null) {
            if (current.frozen.accountId != frozen.accountId) return@exclusive PublicationStep.NeedsAccount("Finish or discard the pending publication under its original account.")
            if (current.frozen.request.requestId != frozen.request.requestId)
                return@exclusive PublicationStep.Failed("A different frozen draft is already awaiting confirmation.", false)
            if (current.frozen.fingerprint != frozen.fingerprint)
                return@exclusive PublicationStep.Failed("The frozen publication cannot be replaced after it may have been dispatched.", true)
            return@exclusive reconcile(current)
        }
        store.write(PendingPublication(frozen))
        PublicationStep.NeedsExplicitSubmit(PendingPublication(frozen))
    }

    suspend fun recover(accountId: UUID): PublicationStep = exclusive {
        val pending = store.read() ?: return@exclusive PublicationStep.Failed("No publication is awaiting confirmation.", false)
        if (pending.frozen.accountId != accountId) return@exclusive PublicationStep.NeedsAccount("Sign in to the account that started this publication.")
        reconcile(pending)
    }

    /** Explicit stale-attempt abandonment. Replacement is persisted only after server CANCELLED. */
    suspend fun cancelAndReplace(fresh: FrozenPublication): PublicationStep = exclusive {
        val pending = store.read() ?: return@exclusive PublicationStep.Failed("No earlier publication exists.", false)
        if (pending.frozen.accountId != fresh.accountId || pending.frozen.draftId != fresh.draftId ||
            pending.frozen.request.requestId == fresh.request.requestId) return@exclusive PublicationStep.Failed("The fresh retry identity is invalid.", false)
        when (val result = dispatch { gateway.cancel(pending.frozen.request.requestId) }) {
            is NetworkResult.Success -> when (result.value.status) {
                "CANCELLED" -> PendingPublication(fresh).also { store.write(it) }.let(PublicationStep::NeedsExplicitSubmit)
                "FOUND" -> {
                    if (!result.value.matches(pending.frozen.request))
                        return@exclusive PublicationStep.Failed("The committed receipt did not match the frozen request.", true)
                    val id = checkNotNull(result.value.entryId)
                    val restored = pending.copy(entryId = id); store.write(restored); PublicationStep.NeedsExplicitSubmit(restored)
                }
                else -> PublicationStep.Failed("The earlier publication was not cancelled (${result.value.status}).", true)
            }
            NetworkResult.AuthenticationRequired -> PublicationStep.NeedsAccount("Sign in again to reconcile the frozen publication before replacing it.")
            else -> PublicationStep.Failed("Cancellation was not confirmed. The old request remains frozen.", true)
        }
    }

    /** Freeze the final payload only after any replacement preflight, then send it without a redundant status lookup. */
    suspend fun submitFresh(
        accountId: UUID,
        draftId: UUID,
        replaceCancelledAttempt: Boolean,
        freeze: () -> FrozenPublication,
    ): PublicationStep = exclusive {
        val previous = store.read()
        if (previous != null) {
            if (previous.frozen.accountId != accountId)
                return@exclusive PublicationStep.NeedsAccount("Finish or discard the pending publication under its original account.")
            if (!replaceCancelledAttempt)
                return@exclusive PublicationStep.Failed("A different frozen draft is already awaiting confirmation.", false)
            if (previous.frozen.draftId != draftId)
                return@exclusive PublicationStep.Failed("The fresh retry identity is invalid.", false)
            when (val result = dispatch { gateway.cancel(previous.frozen.request.requestId) }) {
                is NetworkResult.Success -> when (result.value.status) {
                    "CANCELLED" -> {
                        if (result.value.requestId != previous.frozen.request.requestId)
                            return@exclusive PublicationStep.Failed("The cancellation receipt did not match the frozen request.", true)
                        store.clear(previous.frozen.request.requestId)
                    }
                    "FOUND" -> {
                        if (!result.value.matches(previous.frozen.request))
                            return@exclusive PublicationStep.Failed("The committed receipt did not match the frozen request.", true)
                        val restored = previous.copy(entryId = checkNotNull(result.value.entryId))
                        store.write(restored)
                        return@exclusive publishPending(restored)
                    }
                    else -> return@exclusive PublicationStep.Failed(
                        "The earlier publication was not cancelled (${result.value.status}).", true)
                }
                NetworkResult.AuthenticationRequired -> return@exclusive PublicationStep.NeedsAccount(
                    "Sign in again to reconcile the frozen publication before replacing it.")
                else -> return@exclusive PublicationStep.Failed(
                    "Cancellation was not confirmed. The old request remains frozen.", true)
            }
        }

        if (!isIdentityCurrent()) throw PublicationIdentityChangedException()
        val fresh = freeze()
        require(fresh.accountId == accountId && fresh.draftId == draftId) { "The fresh publication identity is invalid." }
        require(previous == null || fresh.request.requestId != previous.frozen.request.requestId) {
            "The fresh retry must use a new request identity."
        }
        val pending = PendingPublication(fresh)
        store.write(pending)
        publishPending(pending)
    }

    /** Called only by a fresh user confirmation; never from startup/recovery. */
    suspend fun submit(accountId: UUID): PublicationStep = exclusive {
        var pending = store.read() ?: return@exclusive PublicationStep.Failed("The frozen publication is missing.", false)
        if (pending.frozen.accountId != accountId) return@exclusive PublicationStep.NeedsAccount("The signed-in account changed.")
        when (val known = dispatch { gateway.status(pending.frozen.request.requestId) }) {
            is NetworkResult.Success -> when (known.value.status) {
                "FOUND" -> {
                    if (!known.value.matches(pending.frozen.request))
                        return@exclusive PublicationStep.Failed("The publication receipt did not match the frozen request.", true)
                    val id = checkNotNull(known.value.entryId)
                    pending = pending.copy(entryId = id); store.write(pending)
                }
                "NOT_FOUND" -> Unit
                "DELETED", "CANCELLED" -> {
                    store.clear(pending.frozen.request.requestId)
                    return@exclusive PublicationStep.Rejected(
                        "This frozen publication request is permanently ${known.value.status.lowercase()}. The draft and its photos remain on this device.",
                        known.value.status,
                    )
                }
                else -> return@exclusive PublicationStep.Failed("The previous publication state is ${known.value.status}.", false)
            }
            NetworkResult.AuthenticationRequired -> return@exclusive PublicationStep.NeedsAccount("Sign in again to check the frozen publication. Nothing was resent.")
            else -> return@exclusive PublicationStep.Failed("The previous attempt could not be reconciled. Nothing was resent.", true)
        }
        publishPending(pending)
    }

    private suspend fun publishPending(initial: PendingPublication): PublicationStep {
        var pending = initial
        if (pending.entryId == null) when (val sent = dispatch { gateway.publish(pending.frozen.request) }) {
            is NetworkResult.Success -> {
                if (sent.value.status !in setOf("PUBLICATION_PREPARED", "PRIVATE_SAVED"))
                    return PublicationStep.Failed("The service did not confirm publication.", false)
                if (sent.value.visibility != pending.frozen.request.visibility.wire)
                    return PublicationStep.Failed("The service returned the wrong publication visibility.", true)
                val id = sent.value.entryId ?: return PublicationStep.Failed("The service did not return an entry identity.", true)
                pending = pending.copy(entryId = id); store.write(pending)
            }
            NetworkResult.AuthenticationRequired -> return PublicationStep.NeedsAccount("Sign in again to continue this frozen publication. Nothing was resent.")
            is NetworkResult.ServiceFailure -> if (sent.status in setOf("CLOUD_ENTRY_LIMIT", "LIFETIME_SUBMISSION_LIMIT")) {
                // The service definitively rejected this never-committed request. Remove only the
                // publication fence; the durable editable draft and photo files remain untouched.
                store.clear(pending.frozen.request.requestId)
                return PublicationStep.Rejected(
                    if (sent.status == "CLOUD_ENTRY_LIMIT") CLOUD_CAPACITY_MESSAGE else LEGACY_LIFETIME_CAPACITY_MESSAGE,
                    sent.status,
                )
            } else if (sent.status in TERMINAL_PUBLICATION_REJECTIONS) {
                store.clear(pending.frozen.request.requestId)
                return PublicationStep.Rejected(definitePublishFailureMessage(sent.status), sent.status)
            } else return PublicationStep.Failed(definitePublishFailureMessage(sent.status), false)
            is NetworkResult.HttpFailure -> if (sent.statusCode in DEFINITIVE_CLIENT_REJECTIONS) {
                return PublicationStep.Failed("The service rejected this publication (HTTP ${sent.statusCode}). The frozen draft was preserved.", false)
            } else return PublicationStep.Failed("Publication outcome is unknown. Check its status before retrying.", true)
            is NetworkResult.InvalidResponse, is NetworkResult.TransportFailure ->
                return PublicationStep.Failed("Publication outcome is unknown. Check its status before retrying.", true)
        }
        val entryId = checkNotNull(pending.entryId)
        pending.frozen.request.mediaManifest.forEachIndexed { index, media ->
            if (media.id in pending.finalizedMediaIds) return@forEachIndexed
            val local = pending.frozen.localRecord.diary.photos.getOrNull(index)
                ?: return PublicationStep.Failed("The frozen photo reference is missing.", false)
            val bytes = photos.bytes(local.ref)
            if (bytes.size != media.bytes) return PublicationStep.Failed("A frozen photo changed before upload.", false)
            when (val priorFinalize = dispatch { gateway.finalize(entryId, media.id) }) {
                is NetworkResult.Success -> if (priorFinalize.value in CONFIRMED_MEDIA_STATUSES) {
                    pending = pending.copy(finalizedMediaIds = pending.finalizedMediaIds + media.id); store.write(pending)
                    return@forEachIndexed
                } else return photoValidationFailure(index, priorFinalize.value)
                is NetworkResult.ServiceFailure -> if (priorFinalize.status != "OBJECT_NOT_FOUND")
                    return photoValidationFailure(index, priorFinalize.status)
                NetworkResult.AuthenticationRequired -> return PublicationStep.NeedsAccount("Sign in again to continue validating the frozen photos.")
                else -> return PublicationStep.Failed("Photo validation status is unknown. Nothing was uploaded again.", true)
            }
            val target = when (val value = dispatch { gateway.uploadTarget(entryId, media.id) }) {
                is NetworkResult.Success -> value.value
                NetworkResult.AuthenticationRequired -> return PublicationStep.NeedsAccount("Sign in again to continue the frozen photo upload.")
                else -> return PublicationStep.Failed("Photo upload could not be prepared.", true)
            }
            when (dispatch { gateway.upload(target, bytes) }) {
                is NetworkResult.Success -> Unit
                NetworkResult.AuthenticationRequired -> return PublicationStep.NeedsAccount("Sign in again to continue the frozen photo upload.")
                else -> return PublicationStep.Failed("Photo upload was interrupted.", true)
            }
            when (val finalized = dispatch { gateway.finalize(entryId, media.id) }) {
                is NetworkResult.Success -> if (finalized.value !in CONFIRMED_MEDIA_STATUSES)
                    return photoValidationFailure(index, finalized.value)
                is NetworkResult.ServiceFailure -> return photoValidationFailure(index, finalized.status)
                NetworkResult.AuthenticationRequired -> return PublicationStep.NeedsAccount("Sign in again to finish validating the frozen photos.")
                else -> return PublicationStep.Failed("Photo validation was interrupted.", true)
            }
            pending = pending.copy(finalizedMediaIds = pending.finalizedMediaIds + media.id); store.write(pending)
        }
        if (pending.finalizedMediaIds.size != pending.frozen.request.mediaManifest.size)
            return PublicationStep.Failed("Not every photo was confirmed.", true)
        val verified = dispatch { gateway.status(pending.frozen.request.requestId) }
        if (verified === NetworkResult.AuthenticationRequired)
            return PublicationStep.NeedsAccount("Sign in again to confirm the completed publication.")
        if (verified !is NetworkResult.Success || verified.value.status != "FOUND" ||
            verified.value.requestId != pending.frozen.request.requestId || verified.value.entryId != entryId ||
            verified.value.visibility != pending.frozen.request.visibility.wire || verified.value.mediaReady != true)
            return PublicationStep.Failed("The final publication receipt did not match the frozen request.", true)
        return PublicationStep.Complete(pending)
    }

    suspend fun acknowledgeComplete(requestId: UUID) = exclusive { store.clear(requestId); Unit }

    private suspend fun reconcile(pending: PendingPublication): PublicationStep = when (val result = dispatch { gateway.status(pending.frozen.request.requestId) }) {
        is NetworkResult.Success -> when (result.value.status) {
            "FOUND" -> if (result.value.matches(pending.frozen.request)) {
                PublicationStep.NeedsExplicitSubmit(pending.copy(entryId = checkNotNull(result.value.entryId)).also { value -> store.write(value) })
            } else PublicationStep.Failed("The publication receipt did not match the frozen request.", true)
            "NOT_FOUND" -> PublicationStep.NeedsExplicitSubmit(pending)
            "DELETED", "CANCELLED" -> {
                store.clear(pending.frozen.request.requestId)
                PublicationStep.Rejected(
                    "This frozen publication request is permanently ${result.value.status.lowercase()}. The draft and its photos remain on this device.",
                    result.value.status,
                )
            }
            else -> PublicationStep.Failed("The publication cannot continue (${result.value.status}).", false)
        }
        NetworkResult.AuthenticationRequired -> PublicationStep.NeedsAccount("Sign in again to check the frozen publication. Nothing was resent.")
        else -> PublicationStep.Failed("The previous outcome could not be checked. Nothing was resent.", true)
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
    private suspend fun <T> dispatch(block: suspend () -> T): T {
        if (!isIdentityCurrent()) throw PublicationIdentityChangedException()
        val result = block()
        if (!isIdentityCurrent()) throw PublicationIdentityChangedException()
        return result
    }
}

private val TERMINAL_PUBLICATION_REJECTIONS = setOf(
    "INVALID_REQUEST",
    "REQUEST_CONFLICT",
    "LOCATION_UNRELIABLE",
)

// MEDIA_PARTIAL confirms this photo, while other photos in the entry still need validation.
private val CONFIRMED_MEDIA_STATUSES = setOf("MEDIA_READY", "MEDIA_PARTIAL", "MEDIA_ALREADY_VALIDATED")

private fun photoValidationFailure(index: Int, status: String): PublicationStep.Failed =
    PublicationStep.Failed(
        if (status == "VALIDATION_REJECTED")
            "Photo ${index + 1} was rejected by the service. Your draft and photos are kept on this device. " +
                "Use Edit to cancel this pending save and revise it. (VALIDATION_REJECTED)"
        else "Photo ${index + 1} validation was not confirmed ($status). Your pending save is preserved.",
        // A rejected photo does not undo the entry created before the upload.
        outcomeUnknown = true,
    )

private val DEFINITIVE_CLIENT_REJECTIONS = setOf(400, 403, 404, 405, 409, 410, 411, 412, 413, 414, 415, 422)

private fun PublicationStatus.matches(request: PublishEntryRequest): Boolean =
    requestId == request.requestId && entryId != null && visibility == request.visibility.wire

private fun definitePublishFailureMessage(status: String): String = when (status) {
    "INVALID_REQUEST" -> "The service rejected the frozen publication as invalid. It was not submitted."
    "REQUEST_CONFLICT" -> "The service rejected this request identity because it conflicts with an earlier operation. Nothing was resent."
    "LOCATION_UNRELIABLE" -> "The service rejected the location evidence. The frozen draft was not submitted."
    "SERVICE_DISABLED" -> "Cloud publication is currently disabled. The frozen draft was not submitted."
    else -> "The service rejected this publication ($status). The frozen draft was preserved."
}

class PublicationIdentityChangedException : Exception("The signed-in account changed during publication.")
