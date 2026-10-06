package com.starglean.app.ui.screens

import android.os.SystemClock
import android.graphics.BitmapFactory
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.starglean.app.core.account.AuthOutcome
import com.starglean.app.core.account.AuthOperationLease
import com.starglean.app.core.account.SupabaseAuthClient
import com.starglean.app.core.discovery.*
import com.starglean.app.core.model.ReadingEligibility
import com.starglean.app.core.network.*
import com.starglean.app.core.online.OnlineRuntime
import com.starglean.app.core.online.DailyReadsMemory
import com.starglean.app.core.online.dailyReadsLeft
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal fun NearbyReaderLocationFix.toNetworkLocation(): ReaderLocation {
    val age = SystemClock.elapsedRealtimeNanos() - sampledAtElapsedNanos
    require(age >= 0 && quality.isQualifiedAt(SystemClock.elapsedRealtime()))
    return ReaderLocation(latitude, longitude, quality.horizontalAccuracyMeters,
        Instant.ofEpochMilli(System.currentTimeMillis() - age / 1_000_000), false)
}

internal fun networkStatusMessage(status: String): String = when (status) {
    "AUTH_REQUIRED", "ACCOUNT_SETUP_REQUIRED" -> "Sign in to use your private cloud identity."
    "SERVICE_DISABLED" -> "Nearby reading is not available yet. Your own memories are still safe."
    "DAILY_LIMIT_REACHED" -> "Today's ten encounters are complete. Return tomorrow."
    "NO_ELIGIBLE_CONTENT" -> "There is no new starlight to meet here right now."
    "LOCATION_UNRELIABLE", "UNKNOWN" -> "Your location could not be confirmed. Content is hidden."
    "OUT_OF_RANGE" -> "You have left this memory's area. This encounter has ended."
    "ENDED", "EXPIRED", "NOT_READY", "NOT_FOUND" -> "This encounter is no longer available."
    "READING_IN_PROGRESS" -> "An earlier reading is still closing. Please try again shortly."
    "LIFETIME_SUBMISSION_LIMIT", "CAPACITY_REACHED" -> "Cloud storage capacity has been reached. Your draft is kept."
    "RATE_LIMITED" -> "Please wait a moment before trying again."
    else -> "The service could not confirm this action. Please retry."
}

internal fun NetworkResult<*>.failureMessage(): String = when (this) {
    is NetworkResult.ServiceFailure -> networkStatusMessage(status)
    NetworkResult.AuthenticationRequired -> networkStatusMessage("AUTH_REQUIRED")
    is NetworkResult.HttpFailure -> if (statusCode == 429) networkStatusMessage("RATE_LIMITED") else "The service is unavailable. Nothing is assumed to have succeeded."
    is NetworkResult.TransportFailure -> transportFailureMessage(cause)
    is NetworkResult.InvalidResponse -> "The service response could not be verified. Please retry."
    is NetworkResult.Success -> ""
}

internal fun NetworkResult<AccountSnapshot>.accountStatus(): String? = when (this) {
    is NetworkResult.Success -> value.status
    is NetworkResult.ServiceFailure -> status
    else -> null
}

internal fun acceptsDiscoveryShake(
    busy: Boolean,
    reading: Boolean,
    accountReady: Boolean,
    locationReady: Boolean,
    hasFix: Boolean,
): Boolean = !busy && !reading && accountReady && locationReady && hasFix

/** Why a shake could not start a search. Null means it is ignored quietly (already searching or reading). */
internal enum class DiscoveryShakeRejection { AccountNotReady, LocationNotReady }

internal fun discoveryShakeRejection(
    busy: Boolean,
    reading: Boolean,
    accountReady: Boolean,
    locationReady: Boolean,
): DiscoveryShakeRejection? = when {
    busy || reading -> null
    !accountReady -> DiscoveryShakeRejection.AccountNotReady
    !locationReady -> DiscoveryShakeRejection.LocationNotReady
    else -> null
}

/** Shown only while the reason for the rejected shake still holds. */
internal fun discoveryShakeHint(
    rejection: DiscoveryShakeRejection?,
    accountReady: Boolean,
    locationReady: Boolean,
): String? = when (rejection) {
    DiscoveryShakeRejection.AccountNotReady ->
        if (accountReady) null else "Your account isn't connected yet. Connect it, then shake again."
    DiscoveryShakeRejection.LocationNotReady ->
        if (locationReady) null else "Not yet. Shake again once your location below is ready."
    null -> null
}

/** Only a successfully parsed service reply can represent a genuine empty discovery result. */
internal fun NetworkResult<PreparedOpen>.isVerifiedNoEligibleContent(): Boolean =
    this is NetworkResult.Success && value.status == "NO_ELIGIBLE_CONTENT"

internal data class DiscoveryAttemptState(
    val acceptedShakeSequence: Long = 0L,
    val noEligibleContent: Boolean = false,
) {
    fun onShakeEvaluated(accepted: Boolean): DiscoveryAttemptState =
        if (accepted) copy(acceptedShakeSequence = acceptedShakeSequence + 1L, noEligibleContent = false) else this

    fun onPrepareCompleted(result: NetworkResult<PreparedOpen>): DiscoveryAttemptState =
        copy(noEligibleContent = result.isVerifiedNoEligibleContent())

    fun resetOutcome(): DiscoveryAttemptState = if (noEligibleContent) copy(noEligibleContent = false) else this
}

internal fun OpenedDiary.matchesPlaintextEnvelope(
    expectedStatus: String,
    expectedSession: UUID,
    expectedGeneration: Long,
    expectedSequence: Long,
): Boolean = status == expectedStatus && readerSessionId == expectedSession &&
    generation == expectedGeneration && sequence == expectedSequence && content != null

internal fun OpenedDiary.matchesOpenReceipt(
    expectedSession: UUID,
    expectedGeneration: Long,
    expectedSequence: Long,
): Boolean = status == "OPEN_COMMITTED" && readerSessionId == expectedSession &&
    generation == expectedGeneration && if (content == null) sequence == null else sequence == expectedSequence

internal fun LeaseDecision.matchesValidationEnvelope(
    expectedSession: UUID,
    expectedGeneration: Long,
    expectedSequence: Long,
): Boolean = readerSessionId == expectedSession && generation == expectedGeneration &&
    sequence == expectedSequence && (status != "IN_RANGE" ||
    validationLeaseMillis(expectedSession, expectedGeneration, expectedSequence) != null)

/**
 * The deployed fresh-validation response carries an absolute server expiry but no remaining_ms.
 * This client never retries a validate request id, so that exact fresh wire shape receives the
 * same five-second maximum measured from local dispatch that open/resume use. We deliberately do
 * not compare the server timestamp with the phone wall clock, and accept() still clamps the grant
 * to the request/fix deadlines. An explicit remaining_ms is authoritative and cannot fall back.
 */
internal fun LeaseDecision.validationLeaseMillis(
    expectedSession: UUID,
    expectedGeneration: Long,
    expectedSequence: Long,
): Long? {
    if (status != "IN_RANGE" || readerSessionId != expectedSession || generation != expectedGeneration ||
        sequence != expectedSequence || expiresAt == null) return null
    return when (val explicit = remainingMillis) {
        null -> NearbyReadingPolicy.MAX_LEASE_MILLIS
        in 1..NearbyReadingPolicy.MAX_LEASE_MILLIS -> explicit
        else -> null
    }
}

internal fun LeaseDecision.matchesTerminalEnvelope(
    expectedSession: UUID,
    expectedGeneration: Long,
    expectedSequence: Long,
): Boolean = status in setOf("ENDED", "OUT_OF_RANGE") && readerSessionId == expectedSession &&
    generation == expectedGeneration && sequence == expectedSequence

internal fun OpenedDiary.matchesTerminalEnvelope(
    expectedSession: UUID,
    expectedGeneration: Long,
    expectedSequence: Long,
): Boolean = status in setOf("ENDED", "OUT_OF_RANGE") && readerSessionId == expectedSession &&
    generation == expectedGeneration && sequence == expectedSequence

/** New content and cloud actions need a live lease; an opened note stays in memory until closed. */
internal class LiveDiscoveryController(
    private val auth: SupabaseAuthClient,
    private val nearby: NearbyRepository,
    private val dailyReads: DailyReadsMemory,
    private val runtimeScope: CoroutineScope,
    private val location: DiscoveryLocationState,
    private val scope: CoroutineScope,
    private val nowElapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    constructor(runtime: OnlineRuntime, location: DiscoveryLocationState, scope: CoroutineScope) :
        this(runtime.auth, runtime.nearby, runtime.dailyReads, runtime.scope, location, scope)

    var accountReady by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    private var liveContent by mutableStateOf<DiaryContent?>(null)
    var eligibility by mutableStateOf(ReadingEligibility.UNKNOWN); private set
    var message by mutableStateOf<String?>(null); private set
    // Starts from the app-wide memory, so reopening the Find page keeps today's count.
    var remaining by mutableStateOf(auth.accountIdentity.value?.userId?.let(dailyReads::remainingFor))
        private set
    private var accountZone: ZoneId = ZoneId.systemDefault()
    var encounterNumber by mutableIntStateOf(0); private set
    var selectedReaction by mutableStateOf<ReactionKind?>(null); private set
    var reacting by mutableStateOf(false); private set
    var reactionCompleted by mutableStateOf(false); private set
    private var livePhotos by mutableStateOf<List<ImageBitmap>>(emptyList())
    private val retained = RetainedEncounter<ImageBitmap>()
    private var retainedRevision by mutableIntStateOf(0)
    private var paused = false
    private val retainedSnapshot get() = retainedRevision.let {
        retained.forAccount(auth.accountIdentity.value?.userId)
    }
    val content: DiaryContent? get() = retainedSnapshot?.content ?: liveContent
    val photos: List<ImageBitmap> get() = retainedSnapshot?.photos ?: livePhotos
    private var attemptState by mutableStateOf(DiscoveryAttemptState())
    var shakeRejection by mutableStateOf<DiscoveryShakeRejection?>(null); private set
    val acceptedShakeSequence: Long get() = attemptState.acceptedShakeSequence
    val noEligibleContent: Boolean get() = attemptState.noEligibleContent
    private var reactionRequest: Pair<ReactionKind, UUID>? = null
    private var accountId: String? = null
    private var epoch = 0L
    private var generation = 0L
    private var session: UUID? = null
    private var sessionRepository: NearbyRepository? = null
    private var sessionLease: AuthOperationLease? = null
    private var gate: NearbyReadingGate? = null
    private var requestJob: Job? = null
    private var validationJob: Job? = null
    private var mediaJob: Job? = null
    private var accountJob: Job? = null
    private var lastValidationAt = 0L
    private var lastValidAt = 0L
    private var mediaGeneration = 0L
    private var lookingForEntry = false
    val reading: Boolean get() = session != null || retainedSnapshot != null
    private val identityJob = scope.launch {
        auth.accountIdentity.collect { authChanged(it?.userId) }
    }
    val canRead: Boolean get() = retainedSnapshot != null || (liveContent != null && livePhotos.size == liveContent?.media?.size &&
        accountReady && accountId == auth.accountIdentity.value?.userId && leaseCurrent() &&
        location.ready && eligibility == ReadingEligibility.IN_RANGE &&
        gate?.eligibilityAt(nowElapsedMillis()) == ReadingEligibility.IN_RANGE)
    val canReact: Boolean get() = canRead && session != null && eligibility == ReadingEligibility.IN_RANGE && canPresentGate()

    private fun retainVerifiedContent() {
        val id = session ?: return
        val owner = accountId ?: return
        val value = liveContent ?: return
        if (retainedSnapshot == null && canPresentGate() &&
            retained.retain(owner, id, value, livePhotos)) retainedRevision++
    }

    fun prepareAccount() {
        if (busy) return
        val capturedEpoch = epoch
        busy = true
        accountJob = scope.launch {
            try {
                val signedIn = auth.currentSession()
                val identity = (signedIn as? AuthOutcome.Success)?.value
                if (identity == null) { message = "Sign in to begin. Your identity is never shown to other readers."; return@launch }
                val lease = (auth.operationLease(identity.userId) as? AuthOutcome.Success)?.value
                    ?: return@launch
                val result = nearby.scoped(lease.accessToken).registerAccount(ZoneId.systemDefault().id)
                if (capturedEpoch != epoch || !auth.isOperationLeaseCurrent(lease)) return@launch
                val status = result.accountStatus()
                if (status == "ACCOUNT_READY" || status == "TIMEZONE_IMMUTABLE") {
                    accountReady = true; accountId = identity.userId; message = null
                    val snapshot = (result as? NetworkResult.Success)?.value
                    accountZone = snapshot?.accountTimeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: accountZone
                    // The server count wins once it reports one; otherwise keep what this app saw today.
                    updateRemaining(dailyReadsLeft(snapshot?.dailyLimit, snapshot?.readsUsedToday)
                        ?: dailyReads.remainingFor(identity.userId))
                } else message = status?.let(::networkStatusMessage) ?: result.failureMessage()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (capturedEpoch == epoch) message = "Your account could not be confirmed. Please try again." }
            finally { if (capturedEpoch == epoch) busy = false }
        }
    }

    fun shake() {
        val fix = location.fix
        val accepted = acceptsDiscoveryShake(busy, reading, accountReady, location.ready, fix != null)
        attemptState = attemptState.onShakeEvaluated(accepted)
        // A rejected shake gets a hint instead of silence; it plays no feedback and uses no quota.
        shakeRejection = if (accepted) null else discoveryShakeRejection(busy, reading, accountReady, location.ready)
        if (!accepted) return
        paused = false
        val capturedEpoch = ++epoch
        val readingGeneration = ++generation
        busy = true; message = "Looking for nearby starlight…"
        lookingForEntry = true
        requestJob = scope.launch {
            try {
                val lease = (auth.operationLease(accountId) as? AuthOutcome.Success)?.value
                    ?: return@launch closeIfCurrent(capturedEpoch)
                if (capturedEpoch != epoch) return@launch
                val repository = nearby.scoped(lease.accessToken)
                sessionLease = lease; sessionRepository = repository
                val result = repository.prepareOpen(PrepareOpenRequest(UUID.randomUUID(), readingGeneration, fix!!.toNetworkLocation()))
                val prepared = (result as? NetworkResult.Success)?.value
                val id = prepared?.readerSessionId
                if (capturedEpoch != epoch || !location.ready || !auth.isOperationLeaseCurrent(lease)) {
                    if (id != null) runtimeScope.launch { repository.endSession(id) }
                    return@launch
                }
                if (prepared?.status != "CONTENT_STAGED" || id == null || prepared.generation != readingGeneration) {
                    attemptState = attemptState.onPrepareCompleted(result)
                    message = prepared?.status?.let(::networkStatusMessage) ?: result.failureMessage()
                    if (prepared?.status == "DAILY_LIMIT_REACHED") updateRemaining(0)
                    return@launch
                }
                session = id
                lastValidAt = nowElapsedMillis()
                lastValidationAt = 0L
                gate = NearbyReadingGate(id.toString(), requireMovementEvidence = true)
                updateGate()
                var ticket = gate?.beginCheck(nowElapsedMillis()) ?: return@launch closeIfCurrent(capturedEpoch)
                var current = location.fix ?: return@launch closeIfCurrent(capturedEpoch)
                if (current.quality !== ticket.readerFix) {
                    updateGate()
                    ticket = gate?.beginCheck(nowElapsedMillis()) ?: return@launch closeIfCurrent(capturedEpoch)
                    current = location.fix ?: return@launch closeIfCurrent(capturedEpoch)
                    if (current.quality !== ticket.readerFix) return@launch closeIfCurrent(capturedEpoch)
                }
                val commitRequest = SessionLocationRequest(UUID.randomUUID(), id, readingGeneration,
                    ticket.sequence, current.toNetworkLocation())
                if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                var committed = repository.commitOpen(commitRequest)
                // A transport retry uses the exact request, never a second quota-consuming open.
                if (committed is NetworkResult.TransportFailure && capturedEpoch == epoch && location.ready &&
                    nowElapsedMillis() - ticket.requestedAtElapsedMillis < 4_000) {
                    if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                    committed = repository.commitOpen(commitRequest)
                }
                if (capturedEpoch != epoch || !auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                val receipt = (committed as? NetworkResult.Success)?.value
                if (receipt?.matchesOpenReceipt(id, readingGeneration, ticket.sequence) == true) {
                    updateRemaining(dailyReadsLeft(receipt.dailyLimit, receipt.used))
                    liveContent = receipt.content // An idempotent retry may be receipt-only; tick() resumes with a new sequence.
                    encounterNumber++
                    lastValidAt = nowElapsedMillis()
                    updateGate()
                    eligibility = gate!!.accept(ticket, id.toString(), ReadingEligibility.IN_RANGE, 5_000, nowElapsedMillis())
                    if (eligibility != ReadingEligibility.IN_RANGE) purgeContent()
                    message = if (receipt.content == null) "The encounter was confirmed. Restoring its private content…" else null
                    retainVerifiedContent()
                    if (receipt.content != null) loadPhotos()
                } else {
                    message = receipt?.status?.let(::networkStatusMessage) ?: committed.failureMessage()
                    close(clearMessage = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (capturedEpoch == epoch) {
                message = "This encounter could not be confirmed. Please shake again when ready."; close(clearMessage = false)
            } }
            finally { if (capturedEpoch == epoch) { busy = false; lookingForEntry = false } }
        }
    }

    fun tick() {
        if ((session != null || sessionLease != null) && !leaseCurrent()) { finishLiveSession(); return }
        if (!location.foreground || !location.optedIn) {
            pause()
            return
        }
        paused = false
        if (session == null) return
        updateGate()
        val now = nowElapsedMillis()
        if (eligibility == ReadingEligibility.ENDED || eligibility == ReadingEligibility.OUT_OF_RANGE || now - lastValidAt >= 30_000) {
            finishLiveSession(); return
        }
        if (!location.ready || busy || validationJob?.isActive == true || now - lastValidationAt < 1_200) return
        val id = session ?: return
        val repository = sessionRepository ?: return
        val lease = sessionLease ?: return
        val currentGate = gate ?: return
        val ticket = currentGate.beginCheck(now) ?: return
        val fix = location.fix ?: return
        if (fix.quality !== ticket.readerFix) {
            // The provider delivered a new original fix between gate capture and transport
            // capture. Invalidate this ticket; the next tick will mint one for that exact fix.
            updateGate()
            return
        }
        val capturedEpoch = epoch
        lastValidationAt = now
        validationJob = scope.launch {
          try {
            if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
            if (liveContent == null) {
                // Resumption is itself a location check and consumes this NEW sequence.
                // Validating first then resuming with the same sequence would end the session.
                val result = repository.resumeContent(ResumeContentRequest(id, generation, ticket.sequence, fix.toNetworkLocation()))
                if (capturedEpoch != epoch) return@launch
                if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                updateGate()
                val resumed = (result as? NetworkResult.Success)?.value
                val verifiedTerminal = resumed?.matchesTerminalEnvelope(id, generation, ticket.sequence) == true
                if (verifiedTerminal) {
                    eligibility = currentGate.acceptVerifiedTerminal(
                        if (resumed!!.status == "OUT_OF_RANGE") ReadingEligibility.OUT_OF_RANGE else ReadingEligibility.ENDED,
                        nowElapsedMillis(),
                    )
                    finishLiveSession()
                    return@launch
                }
                if (!currentGate.isPending(ticket)) return@launch
                val valid = resumed?.matchesPlaintextEnvelope("CONTENT_RESUMED", id, generation, ticket.sequence) == true
                eligibility = if (valid) currentGate.accept(ticket, id.toString(), ReadingEligibility.IN_RANGE,
                    5_000, nowElapsedMillis()) else currentGate.verificationFailed(ticket, nowElapsedMillis())
                if (eligibility == ReadingEligibility.IN_RANGE && valid) {
                    liveContent = resumed!!.content
                    lastValidAt = nowElapsedMillis()
                    retainVerifiedContent()
                    loadPhotos()
                } else {
                    purgeContent()
                    if (resumed?.status in setOf("ENDED", "OUT_OF_RANGE", "AUTH_REQUIRED", "SERVICE_DISABLED", "NOT_FOUND")) {
                        finishLiveSession()
                    }
                }
                return@launch
            }
            val result = repository.validate(SessionLocationRequest(UUID.randomUUID(), id, generation, ticket.sequence, fix.toNetworkLocation()))
            if (capturedEpoch != epoch) return@launch
            if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
            updateGate()
            val decision = (result as? NetworkResult.Success)?.value
            val validationLease = decision?.validationLeaseMillis(id, generation, ticket.sequence)
            val validEnvelope = decision?.matchesValidationEnvelope(id, generation, ticket.sequence) == true
            if (decision?.matchesTerminalEnvelope(id, generation, ticket.sequence) == true) {
                eligibility = currentGate.acceptVerifiedTerminal(
                    if (decision.status == "OUT_OF_RANGE") ReadingEligibility.OUT_OF_RANGE else ReadingEligibility.ENDED,
                    nowElapsedMillis(),
                )
                finishLiveSession()
                return@launch
            }
            if (!currentGate.isPending(ticket)) return@launch
            eligibility = if (validEnvelope) currentGate.accept(ticket, id.toString(), when (decision!!.status) {
                "IN_RANGE" -> ReadingEligibility.IN_RANGE
                "OUT_OF_RANGE" -> ReadingEligibility.OUT_OF_RANGE
                "ENDED" -> ReadingEligibility.ENDED
                else -> ReadingEligibility.UNKNOWN
            }, validationLease ?: 0L, nowElapsedMillis())
            else currentGate.verificationFailed(ticket, nowElapsedMillis())
            if (eligibility != ReadingEligibility.IN_RANGE) purgeContent()
            if (validEnvelope && decision?.status in setOf("AUTH_REQUIRED", "SERVICE_DISABLED", "NOT_FOUND")) {
                finishLiveSession()
            } else if (eligibility == ReadingEligibility.IN_RANGE) {
                lastValidAt = nowElapsedMillis()
                loadPhotos()
            }
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { if (capturedEpoch == epoch) {
              eligibility = currentGate.verificationFailed(ticket, nowElapsedMillis())
              purgeContent()
              message = if (retainedSnapshot != null) "Your note is still here. Reconnecting online responses…"
                  else "The connection could not be verified. This memory is hidden."
          } }
        }
    }

    /** Backgrounding pauses live work without discarding an already opened note. */
    fun pause() {
        if (paused) return
        paused = true
        if (retainedSnapshot == null) {
            if (session != null || lookingForEntry) close()
            else attemptState = attemptState.resetOutcome()
            return
        }
        validationJob?.cancel(); validationJob = null
        eligibility = gate?.pause(nowElapsedMillis()) ?: ReadingEligibility.UNKNOWN
        purgeContent()
    }

    /** Expiry stops server actions, but is not a user request to close the displayed note. */
    private fun finishLiveSession() {
        if (retainedSnapshot == null) {
            message = "This encounter has ended. Shake again to meet another memory."
            close(clearMessage = false)
            return
        }
        val old = session
        val repository = sessionRepository
        epoch++
        validationJob?.cancel(); validationJob = null
        requestJob?.cancel(); requestJob = null
        gate?.end(nowElapsedMillis()); gate = null
        session = null; sessionRepository = null; sessionLease = null
        eligibility = ReadingEligibility.ENDED
        purgeContent()
        busy = false; reacting = false
        message = "This note stays open until you close it. Responses are unavailable because the live encounter has ended."
        if (old != null && repository != null) runtimeScope.launch { repository.endSession(old) }
    }

    /** Anonymous images never touch disk; decode only bounded, manifest-verified bytes. */
    private fun loadPhotos() {
        val descriptors = liveContent?.media ?: return
        val repository = sessionRepository ?: return
        val lease = sessionLease ?: return
        val previous = retainedSnapshot
        if (previous != null && previous.content.media == descriptors && canPresentGate()) {
            livePhotos = previous.photos
            message = null
            return
        }
        if (descriptors.size == livePhotos.size || mediaJob?.isActive == true || !canPresentGate()) return
        val capturedEpoch = epoch
        val capturedMediaGeneration = mediaGeneration
        mediaJob = scope.launch {
            try {
                val decoded = mutableListOf<ImageBitmap>()
                for (descriptor in descriptors.sortedBy { it.ordinal }) {
                    if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                    val result = repository.readMedia(descriptor.id, descriptor.bytes)
                    if (capturedEpoch != epoch || capturedMediaGeneration != mediaGeneration) return@launch
                    if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                    val image = (result as? NetworkResult.Success)?.value ?: throw IllegalStateException("Media unavailable")
                    val bitmap = try {
                        require(image.bytes.size == descriptor.bytes && image.sha256 == descriptor.sha256 && image.contentType == descriptor.contentType)
                        withContext(Dispatchers.Default) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
                            require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096 &&
                                bounds.outWidth == descriptor.width && bounds.outHeight == descriptor.height)
                            val options = BitmapFactory.Options().apply {
                                inScaled = false
                                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 1536)
                                    inSampleSize = inSampleSize.coerceAtLeast(1) * 2
                            }
                            requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)).asImageBitmap()
                        }
                    } finally { image.bytes.fill(0) }
                    decoded.add(bitmap)
                }
                if (capturedEpoch == epoch && capturedMediaGeneration == mediaGeneration && canPresentGate()) {
                    livePhotos = decoded; message = null
                    retainVerifiedContent()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: OutOfMemoryError) { if (capturedEpoch == epoch) {
                if (retainedSnapshot != null) finishLiveSession()
                else { message = "Not enough memory to safely display these photos."; close(false) }
            } }
            catch (_: Exception) { if (capturedEpoch == epoch) message = "Waiting for every photo to be verified. The entry stays hidden until ready." }
        }
    }

    private fun updateGate() {
        val previous = eligibility
        eligibility = gate?.updateEnvironment(location.foreground && location.optedIn, location.precisePermission,
            location.networkAvailable, location.fix?.quality, nowElapsedMillis(), location.movement) ?: ReadingEligibility.UNKNOWN
        if (previous == ReadingEligibility.IN_RANGE && eligibility != ReadingEligibility.IN_RANGE) purgeContent()
    }
    private fun purgeContent() {
        mediaGeneration++
        liveContent = null; livePhotos = emptyList()
        mediaJob?.cancel(); mediaJob = null
    }
    private fun leaseCurrent() = sessionLease?.let { it.userId == auth.accountIdentity.value?.userId &&
        it.sessionRevision == auth.sessionRevision.value } == true
    private fun canPresentGate() = leaseCurrent() && location.ready && gate?.eligibilityAt(nowElapsedMillis()) == ReadingEligibility.IN_RANGE

    fun react(kind: ReactionKind) {
        val id = session ?: return
        val repository = sessionRepository ?: return
        val lease = sessionLease ?: return
        if (!canReact || reacting || reactionCompleted) return
        if (reactionRequest != null && reactionRequest?.first != kind) {
            message = "Confirm your previous reaction before choosing another."; return
        }
        val attempt = reactionRequest ?: (kind to UUID.randomUUID()).also { reactionRequest = it }
        val capturedEpoch = epoch
        reacting = true
        scope.launch {
            try {
                if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                val result = repository.react(id, kind, attempt.second)
                if (capturedEpoch != epoch) return@launch
                if (!auth.isOperationLeaseCurrent(lease)) return@launch closeIfCurrent(capturedEpoch)
                val status = (result as? NetworkResult.Success)?.value
                if (status == "REACTION_ACCEPTED" || status == "ALREADY_REACTED") {
                    reactionCompleted = true
                    // A generic ALREADY_REACTED response does not prove which animation was sent.
                    if (status == "REACTION_ACCEPTED") selectedReaction = kind
                    message = if (status == "REACTION_ACCEPTED") "Your kindness was delivered." else "A reaction was already sent for this memory."
                } else message = status?.let(::networkStatusMessage) ?: result.failureMessage()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (capturedEpoch == epoch) message = "Delivery is not confirmed. Tap the same reaction to retry." }
            finally { if (capturedEpoch == epoch) reacting = false }
        }
    }

    fun report(category: ReportCategory, detail: String) = safetyAction { repo, id -> repo.report(id, category, detail) }
    fun block() = safetyAction { repo, id -> repo.block(id) }
    private fun safetyAction(action: suspend (NearbyRepository, UUID) -> NetworkResult<String>) {
        // Reporting/blocking remain authorized for this reader's ended sessions too.
        val id = retainedSnapshot?.sessionId ?: session ?: return
        val owner = accountId ?: return
        if (reacting || !canRead) return
        reacting = true
        scope.launch {
            try {
                val lease = (auth.operationLease(owner) as? AuthOutcome.Success)?.value
                    ?: run { message = "Sign in again to send this action. Your note remains open."; return@launch }
                if ((retainedSnapshot?.sessionId ?: session) != id || !auth.isOperationLeaseCurrent(lease)) return@launch
                val repository = nearby.scoped(lease.accessToken)
                val result = action(repository, id)
                if ((retainedSnapshot?.sessionId ?: session) != id || !auth.isOperationLeaseCurrent(lease)) return@launch
                val status = (result as? NetworkResult.Success)?.value
                if (status in setOf("REPORT_RECEIVED", "BLOCKED")) {
                    close(clearMessage = false)
                    message = if (status == "BLOCKED") "You will no longer meet this author's entries." else "Report received. This entry is hidden from you."
                } else message = status?.let(::networkStatusMessage) ?: result.failureMessage()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if ((retainedSnapshot?.sessionId ?: session) == id) message = "This action was not confirmed. Please retry." }
            finally { if ((retainedSnapshot?.sessionId ?: session) == id) reacting = false }
        }
    }

    private fun closeIfCurrent(capturedEpoch: Long) { if (epoch == capturedEpoch) finishLiveSession() }

    fun close(clearMessage: Boolean = true) {
        val old = session
        val oldRepository = sessionRepository
        epoch++
        retained.clear(); retainedRevision++
        gate?.end(nowElapsedMillis()); gate = null
        session = null; purgeContent(); eligibility = ReadingEligibility.ENDED
        sessionRepository = null; sessionLease = null
        busy = false; reacting = false; selectedReaction = null; reactionRequest = null; reactionCompleted = false
        attemptState = attemptState.resetOutcome()
        lookingForEntry = false
        validationJob?.cancel(); validationJob = null
        mediaJob?.cancel(); mediaJob = null
        if (clearMessage) message = null
        if (old != null && oldRepository != null) runtimeScope.launch { oldRepository.endSession(old) }
    }
    fun authChanged(id: String?) {
        retained.onIdentityChanged(id); retainedRevision++
        if (accountId != null && accountId != id) {
            close(); accountReady = false; accountId = null
            remaining = id?.let(dailyReads::remainingFor)
        }
    }
    private fun updateRemaining(value: Int?) {
        remaining = value
        val id = accountId
        if (value != null && id != null) dailyReads.remember(id, accountZone, value)
    }
    fun dispose() { close(); identityJob.cancel(); accountJob?.cancel() }
}
