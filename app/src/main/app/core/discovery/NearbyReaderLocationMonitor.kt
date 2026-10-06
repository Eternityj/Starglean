package com.starglean.app.core.discovery

import com.starglean.app.core.location.LocationUpdateSource
import com.starglean.app.core.location.LocationUpdateSubscription

internal enum class NearbyReaderLocationStatus {
    WaitingForFix, Ready, Stopped, ConsentRequired, NotForeground,
    PrecisePermissionRequired, ServicesDisabled, NoProvider, UnreliableFix,
    FixExpired, ClockInvalid,
}

internal enum class NearbyLocationPurpose { Reading, Publishing }
internal enum class NearbyLocationProblem {
    AccuracyMissing, AccuracyTooWide, InvalidCoordinates, InvalidTimestamp, MockLocation, StaleSample,
}

/** Diagnostics contain only quality metadata, never coordinates or provider messages. */
internal data class NearbyLocationIssue(val problem: NearbyLocationProblem, val accuracyMeters: Double? = null)

/** A transient provider sample. Never log, persist, or use this as an author anchor. */
internal class NearbyReaderLocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double?,
    val sampledAtElapsedNanos: Long,
    val isMock: Boolean,
) {
    override fun toString(): String = "NearbyReaderLocationSample(redacted)"
}

/**
 * One original reader fix, with the SAME quality instance for gate and transport.
 * This is a local prerequisite, never a server grant or evidence against spoofing.
 */
internal class NearbyReaderLocationFix internal constructor(
    val latitude: Double,
    val longitude: Double,
    val sampledAtElapsedNanos: Long,
    val quality: NearbyReaderFixQuality,
) {
    override fun toString(): String = "NearbyReaderLocationFix(redacted)"
}

internal class NearbyReaderLocationUpdate(
    val status: NearbyReaderLocationStatus,
    val fix: NearbyReaderLocationFix? = null,
    val issue: NearbyLocationIssue? = null,
) {
    init { require((status == NearbyReaderLocationStatus.Ready) == (fix != null)) }
    override fun toString(): String = "NearbyReaderLocationUpdate(status=$status, fix=redacted)"
}

/**
 * Single-use, dispatcher-confined coordinator. The platform owns its heartbeat and
 * must also stop it synchronously on pause, destroy, explicit stop or consent loss.
 * Termination clears the current fix, unregisters providers and ignores late callbacks.
 * Reading and publishing both keep an already qualified original sample when another
 * provider reports only coarse accuracy; that never renews the sample's deadline or uses
 * coarse coordinates. Otherwise a coarse network fix every few seconds would keep
 * discarding a precise fix and resetting the movement check.
 */
internal class NearbyReaderLocationMonitor(
    private val sources: List<LocationUpdateSource<NearbyReaderLocationSample>>,
    private val clockNanos: () -> Long,
    private val blocker: () -> NearbyReaderLocationStatus?,
    private val purpose: NearbyLocationPurpose = NearbyLocationPurpose.Reading,
    private val onUpdate: (NearbyReaderLocationUpdate) -> Unit,
) {
    private val subscriptions = mutableMapOf<Int, LocationUpdateSubscription>()
    private var started = false
    private var startedAtNanos = 0L
    private var lastClockNanos: Long? = null
    private val newestSampleNanosBySource = mutableMapOf<Int, Long>()
    private var newestAuthoritativeSampleNanos: Long? = null
    private var currentFix: NearbyReaderLocationFix? = null
    private var status = NearbyReaderLocationStatus.Stopped
    private var issue: NearbyLocationIssue? = null
    var isRunning: Boolean = false
        private set

    fun start() {
        if (started) return
        started = true
        isRunning = true
        val now = checkEnvironment() ?: return
        startedAtNanos = now
        publish(NearbyReaderLocationStatus.WaitingForFix)
        try {
            subscribeAvailableSources()
        } catch (failure: Throwable) {
            release()
            throw failure
        }
    }

    fun tick() {
        if (!isRunning) return
        val now = checkEnvironment() ?: return
        subscribeAvailableSources()
        if (!isRunning) return
        val fix = currentFix ?: return
        // Match the gate's conservative millisecond boundary, not a later rounded-up deadline.
        if (!fix.quality.isQualifiedAt(now / NANOS_PER_MILLI)) publish(NearbyReaderLocationStatus.FixExpired)
    }

    /** Recheck platform state at least four times/second and at the current fix's deadline. */
    fun nextPollDelayMillis(): Long {
        val now = lastClockNanos ?: return POLL_MILLIS
        val fix = currentFix ?: return POLL_MILLIS
        val ageMillis = now / NANOS_PER_MILLI - fix.quality.sampledAtElapsedMillis
        return (NearbyReadingPolicy.MAX_FIX_AGE_MILLIS - ageMillis).coerceIn(1L, POLL_MILLIS)
    }

    fun stop(status: NearbyReaderLocationStatus = NearbyReaderLocationStatus.Stopped) {
        require(status != NearbyReaderLocationStatus.Ready)
        if (isRunning) terminate(status)
    }

    private fun receive(sourceIndex: Int, sample: NearbyReaderLocationSample) {
        if (!isRunning) return
        val now = checkEnvironment() ?: return
        // Never reuse pre-session (possibly cached) data, even if its age is otherwise acceptable.
        // Provider callbacks can race: preserve ordering within one source, while allowing a
        // different source's slightly older precise initial fix to follow a newer coarse fix.
        // Authoritative fixes and integrity failures remain a global fail-closed watermark.
        if (sample.sampledAtElapsedNanos in 0 until startedAtNanos ||
            newestSampleNanosBySource[sourceIndex]?.let {
                sample.sampledAtElapsedNanos in 0 until it
            } == true ||
            newestAuthoritativeSampleNanos?.let {
                sample.sampledAtElapsedNanos in 0 until it
            } == true
        ) return
        val accuracy = sample.accuracyMeters
        val timestamp = sample.sampledAtElapsedNanos
        if (timestamp < 0L || timestamp > now) {
            publish(NearbyReaderLocationStatus.UnreliableFix,
                issue = NearbyLocationIssue(NearbyLocationProblem.InvalidTimestamp))
            return
        }
        val problem = when {
            sample.isMock -> NearbyLocationProblem.MockLocation
            !sample.latitude.isFinite() || sample.latitude !in -90.0..90.0 ||
                !sample.longitude.isFinite() || sample.longitude !in -180.0..180.0 -> NearbyLocationProblem.InvalidCoordinates
            (now / NANOS_PER_MILLI - timestamp / NANOS_PER_MILLI) >= NearbyReadingPolicy.MAX_FIX_AGE_MILLIS -> NearbyLocationProblem.StaleSample
            accuracy == null || !accuracy.isFinite() || accuracy <= 0.0 -> NearbyLocationProblem.AccuracyMissing
            accuracy > NearbyReadingPolicy.MAX_HORIZONTAL_ACCURACY_METERS -> NearbyLocationProblem.AccuracyTooWide
            else -> null
        }
        // A coarse sample is never used, but it does not discard a still-qualified fix.
        // Keep the exact old fix and its original timestamp, so its 8 s deadline holds.
        if (problem in setOf(NearbyLocationProblem.AccuracyMissing, NearbyLocationProblem.AccuracyTooWide) &&
            currentFix?.quality?.isQualifiedAt(now / NANOS_PER_MILLI) == true
        ) return
        newestSampleNanosBySource[sourceIndex] = timestamp
        if (problem != null) {
            if (problem == NearbyLocationProblem.MockLocation ||
                problem == NearbyLocationProblem.InvalidCoordinates
            ) advanceAuthoritativeWatermark(timestamp)
            publish(NearbyReaderLocationStatus.UnreliableFix, issue = NearbyLocationIssue(problem,
                accuracy?.takeIf { it.isFinite() && it > 0.0 }))
            return
        }
        val quality = NearbyReaderFixQuality(timestamp / NANOS_PER_MILLI, requireNotNull(accuracy), sample.isMock)
        if (!quality.isQualifiedAt(now / NANOS_PER_MILLI)) {
            publish(NearbyReaderLocationStatus.UnreliableFix,
                issue = NearbyLocationIssue(NearbyLocationProblem.StaleSample))
            return
        }
        advanceAuthoritativeWatermark(timestamp)
        publish(
            NearbyReaderLocationStatus.Ready,
            NearbyReaderLocationFix(sample.latitude, sample.longitude, timestamp, quality),
        )
    }

    private fun advanceAuthoritativeWatermark(timestamp: Long) {
        newestAuthoritativeSampleNanos = maxOf(newestAuthoritativeSampleNanos ?: timestamp, timestamp)
    }

    private fun subscribeAvailableSources() {
        for ((sourceIndex, source) in sources.withIndex()) {
            if (sourceIndex in subscriptions) continue
            if (!isRunning || checkEnvironment() == null) return
            val subscription = try {
                source.subscribe { sample -> receive(sourceIndex, sample) }
            } catch (_: RuntimeException) {
                // Each adapter cleans a failed/partial registration itself. A
                // disabled provider is retried by the foreground heartbeat.
                if (!isRunning || checkEnvironment() == null) return
                null
            }
            if (subscription != null) {
                if (isRunning) subscriptions[sourceIndex] = subscription else close(subscription)
            }
        }
    }

    private fun checkEnvironment(): Long? {
        val now = clockNanos()
        if (now < 0L || lastClockNanos?.let { now < it } == true) {
            terminate(NearbyReaderLocationStatus.ClockInvalid)
            return null
        }
        lastClockNanos = now
        val unavailable = try { blocker() } catch (_: RuntimeException) { NearbyReaderLocationStatus.NoProvider }
        if (unavailable != null) {
            terminate(unavailable)
            return null
        }
        return now
    }

    private fun publish(newStatus: NearbyReaderLocationStatus, fix: NearbyReaderLocationFix? = null,
        issue: NearbyLocationIssue? = null) {
        if (status == newStatus && currentFix === fix && this.issue == issue) return
        status = newStatus
        currentFix = fix
        this.issue = issue
        try {
            onUpdate(NearbyReaderLocationUpdate(newStatus, fix, issue))
        } catch (failure: Throwable) {
            // A consumer exception must not leave sensors collecting in the background.
            release()
            throw failure
        }
    }

    private fun terminate(newStatus: NearbyReaderLocationStatus) {
        release()
        publish(newStatus)
    }

    private fun release() {
        isRunning = false
        currentFix = null
        newestSampleNanosBySource.clear()
        newestAuthoritativeSampleNanos = null
        val closing = subscriptions.values.toList()
        subscriptions.clear()
        closing.asReversed().forEach(::close)
    }

    private fun close(subscription: LocationUpdateSubscription) {
        try { subscription.close() } catch (_: RuntimeException) { /* No OEM messages retained. */ }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val POLL_MILLIS = 250L
    }
}
