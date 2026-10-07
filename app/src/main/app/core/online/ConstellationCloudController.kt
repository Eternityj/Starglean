package com.starglean.app.core.online

import com.starglean.app.core.account.AccountIdentity
import com.starglean.app.core.account.AuthOperationLease
import com.starglean.app.core.account.AuthOutcome
import com.starglean.app.core.account.SupabaseAuthClient
import com.starglean.app.core.library.LibraryStorageCoordinator
import com.starglean.app.core.network.NearbyRepository
import com.starglean.app.core.network.NetworkResult
import com.starglean.app.core.network.NearbyResponse
import com.starglean.app.core.stars.ConstellationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.ZoneId

enum class ConstellationSyncConflict { CLOUD_HAS_STATE, VERSION_CHANGED, CLOUD_ENTRIES_NOT_ON_DEVICE, LOCAL_ENTRIES_NOT_IN_CLOUD }
data class ConstellationCloudUiState(
    val account: AccountIdentity? = null,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val version: Long = 0,
    val conflict: ConstellationSyncConflict? = null,
    val message: String? = null,
)

internal fun shouldPushConstellationRevision(state: ConstellationCloudUiState): Boolean =
    state.account != null && state.enabled && !state.busy && state.conflict == null

/** A locally changed copy is not a conflict unless the server also advanced. */
internal fun mayPushUnchangedRemote(savedVersion: Long, remoteVersion: Long): Boolean =
    savedVersion >= 0 && savedVersion == remoteVersion

/** A local revision emitted by applying the confirmed cloud copy is not a new edit. */
internal fun needsConstellationPush(local: CloudConstellationState, confirmedRemote: CloudConstellationState?): Boolean =
    confirmedRemote == null || local != confirmedRemote

class ConstellationCloudController(
    private val repository: NearbyRepository,
    private val local: ConstellationStore,
    private val links: CloudEntryLinkStore,
    private val preferences: ConstellationCloudPreferenceStore,
    private val scope: CoroutineScope,
    private val auth: SupabaseAuthClient,
) {
    private val mutableState = MutableStateFlow(ConstellationCloudUiState())
    val state: StateFlow<ConstellationCloudUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var active = false
    private var operation: Job? = null
    private var remoteSnapshot: CloudConstellationState? = null

    init {
        scope.launch {
            LibraryStorageCoordinator.revisionFlow.collectLatest {
                delay(600)
                // Do not lose edits made while a previous request is in flight.
                while (active && mutableState.value.busy) delay(200)
                if (active && shouldPushConstellationRevision(mutableState.value)) pushRevisionIfChanged()
            }
        }
        scope.launch {
            while (true) {
                delay(30_000)
                if (active && shouldPushConstellationRevision(mutableState.value)) {
                    launchOperation { fetchAndCompare(it) }
                }
            }
        }
    }

    fun setActive(value: Boolean, account: AccountIdentity?) {
        if (!value || account == null) {
            active = false; generation++; operation?.cancel(); remoteSnapshot = null
            mutableState.value = ConstellationCloudUiState()
            return
        }
        active = true
        if (mutableState.value.account?.userId != account.userId) {
            generation++; operation?.cancel(); remoteSnapshot = null
            mutableState.value = ConstellationCloudUiState(account = account, busy = true)
            val run = generation
            operation = scope.launch {
                try {
                    val preference = preferences.get(account.userId)
                    if (!current(run, account.userId)) return@launch
                    mutableState.value = mutableState.value.copy(enabled = preference.enabled, version = preference.version,
                        busy = preference.enabled)
                    if (preference.enabled) {
                        val lease = (auth.operationLease(account.userId) as? AuthOutcome.Success)?.value ?: return@launch fail("The signed-in account changed.")
                        fetchAndCompare(RunContext(run, account, lease, repository.scoped(lease.accessToken)))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (current(run, account.userId)) fail("Cloud constellation settings could not be loaded.") }
            }
        }
    }

    fun enable() = launchOperation { run ->
        val remote = fetchRemote(run.repository)
        requireCurrent(run)
        if (remote == null) return@launchOperation fail("Cloud constellation state could not be verified.")
        remoteSnapshot = remote.state
        if (!ConstellationCloudCodec.isEmpty(remote.state)) {
            mutableState.value = mutableState.value.copy(busy = false, version = remote.version,
                conflict = ConstellationSyncConflict.CLOUD_HAS_STATE,
                message = "This account already has a cloud constellation. Choose which copy to keep.")
            return@launchOperation
        }
        pushLocal(run, remote.version)
    }

    fun keepDeviceCopy() = launchOperation { run ->
        pushLocal(run, mutableState.value.version)
    }

    fun restoreCloudCopy() = launchOperation { run ->
        val account = run.account
        // A conflict snapshot may already be stale. Restoration always re-reads the current
        // CAS version so an explicit "keep cloud" choice cannot restore an older cloud copy.
        val latest = fetchRemote(run.repository)
        requireCurrent(run)
        if (latest == null) return@launchOperation fail("Cloud constellation state could not be verified.")
        val mapped = try { ConstellationCloudCodec.toLocal(latest.state, links.linksFor(account.userId)) }
        catch (error: ConstellationSyncException) {
            requireCurrent(run)
            mutableState.value = mutableState.value.copy(busy = false, conflict = ConstellationSyncConflict.CLOUD_ENTRIES_NOT_ON_DEVICE, message = error.message)
            return@launchOperation
        }
        requireCurrent(run)
        local.replaceFromCloud(mapped)
        requireCurrent(run)
        val pref = ConstellationCloudPreference(account.userId, true, latest.version)
        try { preferences.put(pref) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return@launchOperation fail("The local sky was restored, but saving sync settings failed. Reopen sync to verify before making more changes.") }
        requireCurrent(run)
        remoteSnapshot = latest.state
        mutableState.value = mutableState.value.copy(enabled = true, busy = false, version = latest.version,
            conflict = null, message = "Cloud constellation restored on this device.")
    }

    fun disable() = launchOperation { run ->
        val account = run.account
        requireCurrent(run)
        preferences.put(ConstellationCloudPreference(account.userId, false, mutableState.value.version))
        requireCurrent(run)
        mutableState.value = mutableState.value.copy(enabled = false, busy = false, conflict = null,
            message = "Cloud constellation sync is off. Local stars were kept.")
    }

    fun retry() { if (mutableState.value.enabled) pushCurrent() else enable() }

    private fun pushCurrent() = launchOperation { run -> pushLocal(run, mutableState.value.version) }

    private suspend fun pushRevisionIfChanged() {
        val account = mutableState.value.account ?: return
        val run = generation
        val localSnapshot = try {
            ConstellationCloudCodec.fromLocal(local.load(), links.linksFor(account.userId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Preserve the existing fail-closed path and its user-facing conflict/error handling.
            if (current(run, account.userId) && shouldPushConstellationRevision(mutableState.value)) pushCurrent()
            return
        }
        if (!current(run, account.userId) || !shouldPushConstellationRevision(mutableState.value)) return
        if (needsConstellationPush(localSnapshot, remoteSnapshot)) pushCurrent()
    }

    private suspend fun pushLocal(run: RunContext, expectedVersion: Long) {
        val account = run.account
        val cloud = try { ConstellationCloudCodec.fromLocal(local.load(), links.linksFor(account.userId)) }
        catch (error: ConstellationSyncException) {
            mutableState.value = mutableState.value.copy(busy = false, conflict = ConstellationSyncConflict.LOCAL_ENTRIES_NOT_IN_CLOUD, message = error.message)
            return
        }
        requireCurrent(run)
        val putResult = run.repository.accountStatePut(expectedVersion, ConstellationCloudCodec.toWire(cloud))
        requireCurrent(run)
        when (val result = putResult) {
            is NetworkResult.Success -> {
                val parsed = parseEnvelope(result.value) ?: return fail("Cloud sync returned an invalid state. Local stars were preserved.")
                if (result.value.status == "UPDATED" || (result.value.status == "VERSION_CONFLICT" && parsed.state == cloud)) {
                    preferences.put(ConstellationCloudPreference(account.userId, true, parsed.version))
                    requireCurrent(run)
                    remoteSnapshot = parsed.state
                    mutableState.value = mutableState.value.copy(enabled = true, busy = false, version = parsed.version, conflict = null, message = "Constellations are in sync.")
                } else {
                    remoteSnapshot = parsed.state
                    mutableState.value = mutableState.value.copy(busy = false, version = parsed.version,
                        conflict = ConstellationSyncConflict.VERSION_CHANGED,
                        message = "The cloud constellation changed elsewhere. Choose which copy to keep.")
                }
            }
            else -> fail("Cloud sync was not confirmed. Local stars were preserved.")
        }
    }

    private suspend fun fetchAndCompare(run: RunContext) {
        val remote = fetchRemote(run.repository)
        requireCurrent(run)
        if (remote == null) return fail("Cloud constellation state could not be verified.")
        remoteSnapshot = remote.state
        val localCloud = runCatching { ConstellationCloudCodec.fromLocal(local.load(), links.linksFor(run.account.userId)) }.getOrNull()
        requireCurrent(run)
        if (localCloud == remote.state) {
            preferences.put(ConstellationCloudPreference(run.account.userId, true, remote.version))
            requireCurrent(run)
            mutableState.value = mutableState.value.copy(busy = false, version = remote.version, conflict = null,
                message = "Constellations are in sync.")
        } else if (mayPushUnchangedRemote(mutableState.value.version, remote.version)) {
            pushLocal(run, remote.version)
        } else {
            mutableState.value = mutableState.value.copy(busy = false, version = remote.version,
                conflict = ConstellationSyncConflict.VERSION_CHANGED,
                message = "The cloud changed since your last sync. Choose which copy to keep.")
        }
    }

    private suspend fun fetchRemote(repository: NearbyRepository): Envelope? {
        val ready = repository.ensureAccount(ZoneId.systemDefault().id)
        if (ready !is NetworkResult.Success || ready.value.status != "ACCOUNT_READY") return null
        return when (val result = repository.accountStateGet()) {
            is NetworkResult.Success -> if (result.value.status == "FOUND") parseEnvelope(result.value) else null
            else -> null
        }
    }
    private data class Envelope(val version: Long, val state: CloudConstellationState)
    private fun parseEnvelope(response: NearbyResponse): Envelope? = runCatching {
        Envelope(requireNotNull(response.nonNegativeInteger("version")), ConstellationCloudCodec.fromWire(requireNotNull(response.objectValue("state"))))
    }.getOrNull()

    private data class RunContext(val generation: Long, val account: AccountIdentity, val lease: AuthOperationLease, val repository: NearbyRepository)
    private fun launchOperation(block: suspend (RunContext) -> Unit) {
        val account = mutableState.value.account ?: return
        if (mutableState.value.busy) return
        val run = generation
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        operation = scope.launch {
            try {
                val lease = (auth.operationLease(account.userId) as? AuthOutcome.Success)?.value ?: return@launch fail("The signed-in account changed.")
                val context = RunContext(run, account, lease, repository.scoped(lease.accessToken))
                requireCurrent(context); block(context)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (current(run, account.userId)) fail("Cloud constellation sync could not be confirmed.") }
        }
    }
    private fun current(run: Long, accountId: String) = active && generation == run && mutableState.value.account?.userId == accountId
    private fun requireCurrent(run: Long, accountId: String) { if (!current(run, accountId)) throw CancellationException("Cloud constellation account changed") }
    private suspend fun requireCurrent(run: RunContext) {
        if (!current(run.generation, run.account.userId) || !auth.isOperationLeaseCurrent(run.lease)) throw CancellationException("Cloud constellation account changed")
    }
    private fun fail(message: String) { mutableState.value = mutableState.value.copy(busy = false, message = message) }
}
