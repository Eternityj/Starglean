package com.starglean.app.core.online

import com.starglean.app.core.account.AccountIdentity
import com.starglean.app.core.account.AuthOperationLease
import com.starglean.app.core.account.AuthOutcome
import com.starglean.app.core.account.SupabaseAuthClient
import com.starglean.app.core.library.DiaryDeletionStore
import com.starglean.app.core.media.AndroidPhotoStore
import com.starglean.app.core.memory.MemoryLocationFix
import com.starglean.app.core.memory.OwnDiaryRecord
import com.starglean.app.core.memory.OwnDiaryStore
import com.starglean.app.core.draft.WritingDraftStore
import com.starglean.app.core.model.Daylight
import com.starglean.app.core.model.DiaryDemo
import com.starglean.app.core.model.DiaryFont
import com.starglean.app.core.model.DiaryWeatherSnapshot
import com.starglean.app.core.model.PaperStyle
import com.starglean.app.core.model.WeatherKind
import com.starglean.app.core.network.EntryVisibility
import com.starglean.app.core.network.AccountSnapshot
import com.starglean.app.core.network.NetworkResult
import com.starglean.app.core.network.NearbyRepository
import com.starglean.app.core.network.OwnEntry
import com.starglean.app.core.network.OwnEntriesCursor
import com.starglean.app.core.network.OwnEntriesSnapshot
import com.starglean.app.core.network.ReactionInbox
import java.util.UUID
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CloudLibraryState(
    val account: AccountIdentity? = null,
    val entries: List<OwnEntry> = emptyList(),
    val inbox: ReactionInbox? = null,
    val entriesLoading: Boolean = false,
    val entriesLoaded: Boolean = false,
    val entriesMessage: String? = null,
    val hasMoreEntries: Boolean = false,
    val inboxLoading: Boolean = false,
    val inboxLoaded: Boolean = false,
    val inboxMessage: String? = null,
    val inboxEntries: Map<UUID, OwnEntry> = emptyMap(),
    val inboxEntryLoading: Set<UUID> = emptySet(),
    val inboxEntryErrors: Set<UUID> = emptySet(),
    val operationEntryId: UUID? = null,
    val message: String? = null,
) {
    val loading: Boolean get() = entriesLoading || inboxLoading
    val loaded: Boolean get() = entriesLoaded && inboxLoaded
    override fun toString() = "CloudLibraryState(account=${account?.userId}, entryCount=${entries.size}, hasInbox=${inbox != null}, entriesLoading=$entriesLoading, inboxLoading=$inboxLoading, operationEntryId=$operationEntryId)"
}

/** Own-cloud orchestration only. No method uploads old local records or changes an entry anchor. */
class CloudLibraryController internal constructor(
    private val repository: NearbyRepository,
    private val links: CloudEntryLinkStore?,
    private val localDeletion: DiaryDeletionStore?,
    private val scope: CoroutineScope,
    private val localDiaries: OwnDiaryStore? = null,
    private val localPhotos: AndroidPhotoStore? = null,
    private val localDrafts: WritingDraftStore? = null,
    private val auth: SupabaseAuthClient,
    private val onInboxAcknowledged: (String, ReactionInbox) -> Unit = { _, _ -> },
    private val loader: CloudLibraryLoader = AuthenticatedCloudLibraryLoader(repository, auth),
) {
    private val mutableState = MutableStateFlow(CloudLibraryState())
    val state: StateFlow<CloudLibraryState> = mutableState.asStateFlow()
    private var generation = 0L
    private var entriesJob: Job? = null
    private var inboxJob: Job? = null
    private val inboxEntryJobs = mutableMapOf<UUID, Job>()
    private var operationJob: Job? = null
    private var entriesRequest = 0L
    private var inboxRequest = 0L
    private var entriesCursor: OwnEntriesCursor? = null
    private var acknowledgedInboxWatermark: java.time.Instant? = null

    fun setActive(active: Boolean, account: AccountIdentity?) {
        if (!active) { reset(); return }
        if (account == null) { reset(); return }
        if (mutableState.value.account?.userId != account.userId) {
            cancelRuns()
            mutableState.value = CloudLibraryState(account = account)
        }
        if (!mutableState.value.entriesLoaded && entriesJob?.isActive != true) refreshEntries()
        if (!mutableState.value.inboxLoaded && inboxJob?.isActive != true) refreshInbox()
    }

    fun refresh() {
        refreshEntries()
        refreshInbox()
    }

    fun refreshEntries() = loadEntries(reset = true)

    fun loadMoreEntries() = loadEntries(reset = false)

    private fun loadEntries(reset: Boolean) {
        if (entriesJob?.isActive == true) return
        val account = mutableState.value.account ?: return
        if (!reset && (!mutableState.value.hasMoreEntries || entriesCursor == null)) return
        val run = generation
        val request = ++entriesRequest
        val before = if (reset) null else entriesCursor
        mutableState.value = mutableState.value.copy(entriesLoading = true, entriesMessage = null, message = null)
        entriesJob = scope.launch {
            try {
                val session = loader.open(account.userId)
                    ?: return@launch failEntries(run, request, account.userId, "The signed-in account changed. Nothing was loaded.")
                val accountReady = session.ensureAccount(ZoneId.systemDefault().id)
                if (!session.isCurrent()) return@launch
                if (accountReady !is NetworkResult.Success || accountReady.value.status != "ACCOUNT_READY")
                    return@launch failEntries(run, request, account.userId, "Cloud account setup could not be confirmed. Nothing was loaded.")
                val page = session.ownEntries(limit = PAGE_SIZE, before = before)
                if (!session.isCurrent()) return@launch
                if (!isCurrent(run, request, entriesRequest, account.userId)) return@launch
                if (page !is NetworkResult.Success || page.value.status != "FOUND")
                    return@launch failEntries(run, request, account.userId, if (reset) "Cloud entries could not be loaded." else "More cloud entries could not be loaded.")
                val items = page.value.items
                val update = mergeCloudEntryPage(mutableState.value.entries, items, page.value.nextCursor, reset, before)
                entriesCursor = update.nextCursor
                mutableState.value = mutableState.value.copy(entries = update.entries, entriesLoading = false,
                    entriesLoaded = true, hasMoreEntries = update.nextCursor != null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failEntries(run, request, account.userId, "Cloud entries could not be loaded safely.") }
        }
    }

    fun refreshInbox() {
        if (inboxJob?.isActive == true) return
        val account = mutableState.value.account ?: return
        val run = generation
        val request = ++inboxRequest
        mutableState.value = mutableState.value.copy(inboxLoading = true, inboxMessage = null, message = null)
        inboxJob = scope.launch {
            try {
                val session = loader.open(account.userId)
                    ?: return@launch failInbox(run, request, account.userId, "The signed-in account changed. Nothing was loaded.")
                val accountReady = session.ensureAccount(ZoneId.systemDefault().id)
                if (!session.isCurrent()) return@launch
                if (accountReady !is NetworkResult.Success || accountReady.value.status != "ACCOUNT_READY")
                    return@launch failInbox(run, request, account.userId, "Cloud account setup could not be confirmed. Nothing was loaded.")
                val result = session.reactionInbox()
                if (!session.isCurrent()) return@launch
                if (!isCurrent(run, request, inboxRequest, account.userId)) return@launch
                if (result !is NetworkResult.Success || result.value.status != "FOUND")
                    return@launch failInbox(run, request, account.userId, "Reaction inbox could not be loaded.")
                val ids = result.value.items.mapTo(linkedSetOf()) { it.entryId }
                val known = (mutableState.value.entries + mutableState.value.inboxEntries.values)
                    .filter { it.entryId in ids }.associateBy { it.entryId }
                mutableState.value = mutableState.value.copy(inbox = result.value, inboxLoading = false,
                    inboxLoaded = true, inboxEntries = known, inboxEntryErrors = emptySet())
                ids.filterNot(known::containsKey).forEach(::resolveInboxEntry)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failInbox(run, request, account.userId, "Reaction inbox could not be loaded safely.") }
        }
    }

    fun resolveInboxEntry(entryId: UUID) {
        if (mutableState.value.inbox?.items?.none { it.entryId == entryId } != false) return
        if (inboxEntryJobs[entryId]?.isActive == true || mutableState.value.inboxEntries.containsKey(entryId)) return
        val account = mutableState.value.account ?: return
        val run = generation
        mutableState.value = mutableState.value.copy(inboxEntryLoading = mutableState.value.inboxEntryLoading + entryId,
            inboxEntryErrors = mutableState.value.inboxEntryErrors - entryId)
        inboxEntryJobs[entryId] = scope.launch {
            try {
                val session = loader.open(account.userId)
                    ?: return@launch finishInboxEntry(run, account.userId, entryId, null)
                val result = session.ownEntry(entryId)
                if (!session.isCurrent() || run != generation || mutableState.value.account?.userId != account.userId) return@launch
                val entry = (result as? NetworkResult.Success)?.value?.takeIf { it.entryId == entryId }
                finishInboxEntry(run, account.userId, entryId, entry)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finishInboxEntry(run, account.userId, entryId, null) }
        }
    }

    /** Call only after the inbox content has entered composition. */
    fun inboxDisplayed() {
        val account = mutableState.value.account ?: return
        val inbox = mutableState.value.inbox ?: return
        if (inbox.items.isEmpty() || acknowledgedInboxWatermark == inbox.seenThrough) return
        val run = generation
        acknowledgedInboxWatermark = inbox.seenThrough
        scope.launch {
            val lease = (auth.operationLease(account.userId) as? AuthOutcome.Success)?.value ?: return@launch
            val result = repository.scoped(lease.accessToken).reactionInbox(inbox.seenThrough)
            if (!auth.isOperationLeaseCurrent(lease)) return@launch
            if (run != generation || mutableState.value.account?.userId != account.userId ||
                mutableState.value.inbox?.seenThrough != inbox.seenThrough) return@launch
            if (result is NetworkResult.Success && result.value.status == "FOUND") {
                mutableState.value = mutableState.value.copy(inbox = result.value)
                onInboxAcknowledged(account.userId, result.value)
            }
            else acknowledgedInboxWatermark = null
        }
    }

    fun setVisibility(entry: OwnEntry, visibility: EntryVisibility) = entryOperation(entry.entryId) { run ->
        // The endpoint changes visibility only; it receives no location and therefore preserves the original anchor.
        val result = run.repository.setVisibility(entry.entryId, visibility)
        requireCurrent(run)
        when (result) {
            is NetworkResult.Success -> if (result.value.status in setOf("VISIBILITY_UPDATED", "UNCHANGED")) {
                requireCurrent(run)
                val updatedVisibility = result.value.visibility ?: visibility.wire
                mutableState.value = mutableState.value.copy(entries = mutableState.value.entries.map {
                    if (it.entryId == entry.entryId) it.copy(visibility = updatedVisibility) else it
                }, inboxEntries = mutableState.value.inboxEntries.mapValues { (_, value) ->
                    if (value.entryId == entry.entryId) value.copy(visibility = updatedVisibility) else value
                }, operationEntryId = null)
            } else failOperation("The service did not confirm the visibility change.")
            else -> failOperation("The visibility change was not confirmed.")
        }
    }

    fun delete(entry: OwnEntry) = entryOperation(entry.entryId) { run ->
        val account = mutableState.value.account ?: return@entryOperation
        val result = run.repository.deleteEntryWithMedia(entry.entryId)
        requireCurrent(run)
        when (result) {
            is NetworkResult.Success -> if (result.value in setOf("ENTRY_DELETED", "NOT_FOUND")) {
                requireCurrent(run)
                val linkStore = links ?: return@entryOperation failOperation("Local deletion is unavailable.")
                val deletionStore = localDeletion ?: return@entryOperation failOperation("Local deletion is unavailable.")
                val link = linkStore.linksFor(account.userId).firstOrNull { it.cloudEntryId == entry.entryId }
                if (link != null) {
                    try {
                        requireCurrent(run)
                        deletionStore.deleteDiary(link.localDiaryId)
                        requireCurrent(run)
                        linkStore.remove(account.userId, entry.entryId)
                    }
                    catch (_: Exception) {
                        return@entryOperation failOperation("Cloud deletion was confirmed, but the linked local entry could not be removed safely.")
                    }
                }
                mutableState.value = mutableState.value.copy(
                    entries = mutableState.value.entries.filterNot { it.entryId == entry.entryId }, operationEntryId = null,
                    inboxEntries = mutableState.value.inboxEntries - entry.entryId,
                    message = "Cloud entry deleted${if (link != null) " together with its linked local star" else ""}.",
                )
            } else failOperation("The service did not confirm deletion. Nothing local was removed.")
            else -> failOperation("Deletion was not confirmed. Nothing local was removed.")
        }
    }

    fun restore(entry: OwnEntry) = entryOperation(entry.entryId) { run ->
        val account = mutableState.value.account ?: return@entryOperation
        val diaries = localDiaries ?: return@entryOperation failOperation("Local restore is unavailable.")
        val photos = localPhotos ?: return@entryOperation failOperation("Photo restore is unavailable.")
        val linkStore = links ?: return@entryOperation failOperation("Local restore is unavailable.")
        val accountLinks = linkStore.linksFor(account.userId)
        requireCurrent(run)
        if (accountLinks.any { it.cloudEntryId == entry.entryId }) {
            return@entryOperation failOperation("This cloud entry is already linked to a local memory.")
        }
        val localId = "own-${UUID.nameUUIDFromBytes("${account.userId}:${entry.entryId}".toByteArray())}"
        val restoredPhotos = mutableListOf<com.starglean.app.core.media.DiaryPhoto>()
        for (media in entry.media.sortedBy { it.ordinal }) {
            val mediaResult = run.repository.readMedia(media.id, media.bytes)
            requireCurrent(run)
            when (val result = mediaResult) {
                is NetworkResult.Success -> {
                    requireCurrent(run)
                    val stableRef = "photo-${UUID.nameUUIDFromBytes("${account.userId}:${entry.entryId}:${media.id}".toByteArray())}"
                    val download = result.value
                    if (download.contentType != media.contentType || download.sha256 != media.sha256 || download.bytes.size != media.bytes) {
                        cleanupRestorePhotos(restoredPhotos)
                        return@entryOperation failOperation("Cloud photo metadata changed during restore. No diary record was restored.")
                    }
                    try { restoredPhotos += photos.importDownloaded(download.bytes, download.contentType, download.sha256, stableRef) }
                    catch (error: Exception) { cleanupRestorePhotos(restoredPhotos); throw error }
                }
                else -> {
                    cleanupRestorePhotos(restoredPhotos)
                    return@entryOperation failOperation("A cloud photo could not be verified. No diary record was restored.")
                }
            }
        }
        val record = entry.toLocalRecord(localId, restoredPhotos)
        try {
            requireCurrent(run)
            diaries.save(record)
            requireCurrent(run)
            linkStore.put(CloudEntryLink(account.userId, localId, entry.entryId))
        } catch (error: Exception) {
            // If the diary save did not commit, remove only photos that no verified diary/draft references.
            if (runCatching { diaries.load().none { it.diary.id == localId } }.getOrDefault(false)) cleanupRestorePhotos(restoredPhotos)
            throw error
        }
        requireCurrent(run)
        mutableState.value = mutableState.value.copy(operationEntryId = null, message = "Restored to this device. Nothing else was downloaded.")
    }

    private data class RunContext(val generation: Long, val accountId: String, val lease: AuthOperationLease, val repository: NearbyRepository)

    private fun entryOperation(entryId: UUID, block: suspend (RunContext) -> Unit) {
        if (mutableState.value.operationEntryId != null) return
        val account = mutableState.value.account ?: return
        mutableState.value = mutableState.value.copy(operationEntryId = entryId, message = null)
        operationJob = scope.launch {
            try {
                val lease = (auth.operationLease(account.userId) as? AuthOutcome.Success)?.value
                    ?: return@launch failOperation("The signed-in account changed. Nothing was changed.")
                val run = RunContext(generation, account.userId, lease, repository.scoped(lease.accessToken))
                requireCurrent(run); block(run)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (mutableState.value.account?.userId == account.userId) failOperation("The cloud operation could not be confirmed.") }
        }
    }

    private suspend fun isCurrent(run: RunContext) = run.generation == generation && mutableState.value.account?.userId == run.accountId &&
        auth.isOperationLeaseCurrent(run.lease)
    private suspend fun requireCurrent(run: RunContext) { if (!isCurrent(run)) throw CancellationException("Cloud library account changed") }

    private fun reset() {
        cancelRuns()
        mutableState.value = CloudLibraryState()
    }

    private fun cancelRuns() {
        generation++
        entriesRequest++
        inboxRequest++
        entriesJob?.cancel()
        inboxJob?.cancel()
        inboxEntryJobs.values.forEach(Job::cancel)
        inboxEntryJobs.clear()
        operationJob?.cancel()
        entriesCursor = null
        acknowledgedInboxWatermark = null
    }

    private fun isCurrent(run: Long, request: Long, currentRequest: Long, accountId: String) =
        run == generation && request == currentRequest && mutableState.value.account?.userId == accountId

    private fun failEntries(run: Long, request: Long, accountId: String, message: String) {
        if (!isCurrent(run, request, entriesRequest, accountId)) return
        mutableState.value = mutableState.value.copy(entriesLoading = false, entriesMessage = message)
    }

    private fun failInbox(run: Long, request: Long, accountId: String, message: String) {
        if (!isCurrent(run, request, inboxRequest, accountId)) return
        mutableState.value = mutableState.value.copy(inboxLoading = false, inboxMessage = message)
    }

    private fun finishInboxEntry(run: Long, accountId: String, entryId: UUID, entry: OwnEntry?) {
        if (run != generation || mutableState.value.account?.userId != accountId) return
        mutableState.value = mutableState.value.copy(
            inboxEntryLoading = mutableState.value.inboxEntryLoading - entryId,
            inboxEntryErrors = if (entry == null) mutableState.value.inboxEntryErrors + entryId else mutableState.value.inboxEntryErrors - entryId,
            inboxEntries = if (entry == null) mutableState.value.inboxEntries else mutableState.value.inboxEntries + (entryId to entry),
        )
        inboxEntryJobs.remove(entryId)
    }

    private fun failOperation(message: String) { mutableState.value = mutableState.value.copy(operationEntryId = null, message = message) }

    private suspend fun cleanupRestorePhotos(imported: List<com.starglean.app.core.media.DiaryPhoto>) {
        val photos = localPhotos ?: return
        val referenced = try {
            localDiaries.orEmptyPhotos() + localDrafts?.load()?.draft?.photos.orEmpty().map { it.ref }
        } catch (_: Exception) { return }
        imported.forEach { runCatching { photos.deleteIfUnreferenced(it.ref, referenced.toSet()) } }
    }

    private suspend fun OwnDiaryStore?.orEmptyPhotos(): List<String> = this?.load().orEmpty().flatMap { record -> record.diary.photos.map { it.ref } }
}

private const val PAGE_SIZE = 10

internal interface CloudLibraryLoadSession {
    suspend fun isCurrent(): Boolean
    suspend fun ensureAccount(timeZone: String): NetworkResult<AccountSnapshot>
    suspend fun ownEntries(limit: Int, before: OwnEntriesCursor?): NetworkResult<OwnEntriesSnapshot>
    suspend fun reactionInbox(): NetworkResult<ReactionInbox>
    suspend fun ownEntry(entryId: UUID): NetworkResult<OwnEntry?>
}

internal fun interface CloudLibraryLoader {
    suspend fun open(accountId: String): CloudLibraryLoadSession?
}

private class AuthenticatedCloudLibraryLoader(
    private val repository: NearbyRepository,
    private val auth: SupabaseAuthClient,
) : CloudLibraryLoader {
    override suspend fun open(accountId: String): CloudLibraryLoadSession? {
        val lease = (auth.operationLease(accountId) as? AuthOutcome.Success)?.value ?: return null
        val scoped = repository.scoped(lease.accessToken)
        return object : CloudLibraryLoadSession {
            override suspend fun isCurrent() = auth.isOperationLeaseCurrent(lease)
            override suspend fun ensureAccount(timeZone: String) = scoped.ensureAccount(timeZone)
            override suspend fun ownEntries(limit: Int, before: OwnEntriesCursor?) = scoped.ownEntries(limit, before)
            override suspend fun reactionInbox() = scoped.reactionInbox()
            override suspend fun ownEntry(entryId: UUID) = scoped.ownEntry(entryId)
        }
    }
}

internal data class CloudEntryPageUpdate(val entries: List<OwnEntry>, val nextCursor: OwnEntriesCursor?)

internal fun mergeCloudEntryPage(current: List<OwnEntry>, page: List<OwnEntry>, serverCursor: OwnEntriesCursor?, reset: Boolean,
    requestedCursor: OwnEntriesCursor? = null): CloudEntryPageUpdate {
    val entries = if (reset) page.distinctBy { it.entryId } else (current + page).distinctBy { it.entryId }
    val madeProgress = reset || entries.size > current.size
    return CloudEntryPageUpdate(entries, serverCursor.takeIf {
        page.size == PAGE_SIZE && it != requestedCursor && madeProgress
    })
}

private fun OwnEntry.toLocalRecord(localId: String, photos: List<com.starglean.app.core.media.DiaryPhoto>) = OwnDiaryRecord(
    DiaryDemo(
        localId, title.takeIf(String::isNotBlank), body,
        java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu · HH:mm 'UTC'").withZone(java.time.ZoneOffset.UTC).format(createdAt),
        DiaryWeatherSnapshot(
            when (weather.kind.name) { "Clear" -> WeatherKind.Clear; "Cloudy" -> WeatherKind.Cloudy; "Rain" -> WeatherKind.Rain; "Snow" -> WeatherKind.Snow; "Fog" -> WeatherKind.Fog; else -> WeatherKind.Unknown },
            when (weather.daylight.name) { "Day" -> Daylight.Day; "Dusk" -> Daylight.Dusk; else -> Daylight.Night },
            weather.temperatureCelsius, weather.capturedAt?.toString(), false,
        ),
        "Original cloud anchor", PaperStyle.entries.firstOrNull { it.name.equals(paperStyle, true) } ?: PaperStyle.WarmWhite,
        DiaryFont.entries.firstOrNull { it.name.equals(fontStyle, true) } ?: DiaryFont.Clear, photos,
    ),
    MemoryLocationFix(anchor.latitude, anchor.longitude, anchor.accuracyMeters.toFloat(), anchor.capturedAt.toEpochMilli()),
    createdAt.toEpochMilli(),
)
