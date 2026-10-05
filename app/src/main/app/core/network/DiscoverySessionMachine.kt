package com.starglean.app.core.network

import java.util.UUID

/**
 * Pure foreground state machine. It does not retain diary plaintext and cannot request a
 * coordinate until the user has explicitly enabled discovery for this foreground epoch.
 */
class DiscoverySessionMachine(private val newRequestId: () -> UUID = UUID::randomUUID) {
    enum class Phase { Idle, AwaitingConsent, AwaitingLocation, Preparing, Committing, Reading, Masked, Ended }
    class Operation internal constructor(val epoch: Long, val nonce: Long) {
        override fun toString() = "Operation(redacted)"
    }
    sealed interface Effect {
        data object RequestLocation : Effect
        data class Prepare(val operation: Operation, val request: PrepareOpenRequest) : Effect
        data class Commit(val operation: Operation, val request: SessionLocationRequest) : Effect
        data class Validate(val operation: Operation, val request: SessionLocationRequest) : Effect
        data class End(val sessionId: UUID) : Effect
        data object MaskContent : Effect
    }

    var phase: Phase = Phase.Idle; private set
    private var epoch = 0L
    private var nonce = 0L
    private var generation = 0L
    private var sequence = 0L
    private var authIdentity: String? = null
    private var pending: Operation? = null
    private var preparedLocation: ReaderLocation? = null
    private var sessionId: UUID? = null

    fun enterForeground(authIdentity: String?): List<Effect> {
        if (authIdentity.isNullOrBlank()) return terminate()
        epoch++
        this.authIdentity = authIdentity
        generation++
        phase = Phase.AwaitingConsent
        return emptyList()
    }

    fun grantDiscoveryConsent(): List<Effect> {
        if (phase != Phase.AwaitingConsent || authIdentity == null) return emptyList()
        phase = Phase.AwaitingLocation
        return listOf(Effect.RequestLocation)
    }

    fun locationReady(location: ReaderLocation): List<Effect> {
        if (phase != Phase.AwaitingLocation) return emptyList()
        val operation = nextOperation() ?: return terminate()
        preparedLocation = location
        phase = Phase.Preparing
        return listOf(Effect.Prepare(operation, PrepareOpenRequest(newRequestId(), generation, location)))
    }

    fun prepared(operation: Operation, result: PreparedOpen): List<Effect> {
        if (!isCurrent(operation, Phase.Preparing)) return emptyList()
        val id = result.readerSessionId
        val location = preparedLocation
        if (result.status != "CONTENT_STAGED" || id == null || result.generation != generation || location == null) {
            pending = null; preparedLocation = null; phase = Phase.Masked
            return listOf(Effect.MaskContent)
        }
        val commitOperation = nextOperation() ?: return terminate()
        sessionId = id
        sequence++
        phase = Phase.Committing
        return listOf(Effect.Commit(commitOperation, SessionLocationRequest(
            newRequestId(), id, generation, sequence, location,
        )))
    }

    fun committed(operation: Operation, result: OpenedDiary): List<Effect> {
        if (!isCurrent(operation, Phase.Committing)) return emptyList()
        pending = null; preparedLocation = null
        return if (result.status == "OPEN_COMMITTED" && result.readerSessionId == sessionId &&
            result.generation == generation && result.content != null) {
            phase = Phase.Reading; emptyList()
        } else {
            phase = Phase.Masked; listOf(Effect.MaskContent)
        }
    }

    fun requestValidation(location: ReaderLocation): List<Effect> {
        val id = sessionId ?: return emptyList()
        if (phase != Phase.Reading) return emptyList()
        val operation = nextOperation() ?: return terminate()
        sequence++
        return listOf(Effect.Validate(operation, SessionLocationRequest(newRequestId(), id, generation, sequence, location)))
    }

    fun validated(operation: Operation, result: LeaseDecision): List<Effect> {
        if (!isCurrent(operation, Phase.Reading)) return emptyList()
        pending = null
        return when (result.status) {
            "IN_RANGE" -> if (result.readerSessionId == sessionId && result.generation == generation && result.sequence == sequence) emptyList()
                else mask()
            "OUT_OF_RANGE", "ENDED" -> terminate()
            else -> mask()
        }
    }

    fun requestFailed(operation: Operation): List<Effect> =
        if (pending == operation && operation.epoch == epoch) mask() else emptyList()

    fun leaveForeground(): List<Effect> = terminate()

    fun authenticationChanged(newIdentity: String?): List<Effect> =
        if (newIdentity == authIdentity && newIdentity != null) emptyList() else terminate()

    private fun mask(): List<Effect> {
        pending = null; preparedLocation = null
        if (phase != Phase.Ended) phase = Phase.Masked
        return listOf(Effect.MaskContent)
    }

    private fun terminate(): List<Effect> {
        val oldSession = sessionId
        epoch++
        pending = null; preparedLocation = null; sessionId = null; authIdentity = null
        if (phase == Phase.Ended) return emptyList()
        phase = Phase.Ended
        return buildList {
            add(Effect.MaskContent)
            if (oldSession != null) add(Effect.End(oldSession))
        }
    }

    private fun nextOperation(): Operation? {
        if (nonce == Long.MAX_VALUE) return null
        return Operation(epoch, ++nonce).also { pending = it }
    }
    private fun isCurrent(operation: Operation, expected: Phase) =
        phase == expected && pending == operation && operation.epoch == epoch && authIdentity != null
}

/**
 * Stores only the non-sensitive immutable request identity. On restart, query
 * publication_status before asking for a new location or sending create_entry again.
 */
interface PublicationJournal {
    fun pendingRequestId(): UUID?
    fun begin(requestId: UUID)
    fun complete(requestId: UUID)
}

/** Minimal key/value boundary; production adapters must commit synchronously before dispatch. */
interface PublicationJournalStore {
    fun read(key: String): String?
    fun write(key: String, value: String?): Boolean
}

class DurablePublicationJournal(private val store: PublicationJournalStore) : PublicationJournal {
    override fun pendingRequestId(): UUID? = store.read(KEY)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    override fun begin(requestId: UUID) {
        val current = pendingRequestId()
        check(current == null || current == requestId) { "A different publication is still unresolved" }
        check(store.write(KEY, requestId.toString())) { "Publication identity was not durably stored" }
    }
    override fun complete(requestId: UUID) {
        if (pendingRequestId() == requestId) check(store.write(KEY, null)) { "Publication journal could not be cleared" }
    }
    private companion object { const val KEY = "pending_publication_request_id" }
}

class SharedPreferencesPublicationJournalStore(
    private val preferences: android.content.SharedPreferences,
) : PublicationJournalStore {
    override fun read(key: String): String? = preferences.getString(key, null)
    override fun write(key: String, value: String?): Boolean = preferences.edit().let { editor ->
        if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.commit()
    }
}

class InMemoryPublicationJournal : PublicationJournal {
    private var pending: UUID? = null
    override fun pendingRequestId() = pending
    override fun begin(requestId: UUID) { check(pending == null || pending == requestId); pending = requestId }
    override fun complete(requestId: UUID) { if (pending == requestId) pending = null }
}

/**
 * Non-secret durable fence for the frozen draft stored by the app's own encrypted/local
 * draft store. A retry is legal only when account, draft and canonical payload hash match.
 */
data class PublicationFence(
    val accountId: UUID,
    val draftId: UUID,
    val requestId: UUID,
    val payloadSha256: String,
) {
    init { require(payloadSha256.matches(Regex("[a-f0-9]{64}"))) }
}

class PublicationFenceJournal(private val store: PublicationJournalStore) {
    fun read(): PublicationFence? {
        val parts = store.read(KEY)?.split('|') ?: return null
        if (parts.size != 4) return null
        return runCatching { PublicationFence(UUID.fromString(parts[0]), UUID.fromString(parts[1]), UUID.fromString(parts[2]), parts[3]) }.getOrNull()
    }
    fun begin(fence: PublicationFence) {
        val current = read()
        check(current == null || current == fence) { "A different frozen publication is unresolved" }
        check(store.write(KEY, listOf(fence.accountId, fence.draftId, fence.requestId, fence.payloadSha256).joinToString("|")))
    }
    fun complete(fence: PublicationFence) {
        if (read() == fence) check(store.write(KEY, null))
    }
    private companion object { const val KEY = "pending_publication_fence_v1" }
}
