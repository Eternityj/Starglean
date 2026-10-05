package com.starglean.app.core.location

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ForegroundLocationFailure {
    PermissionRequired, ServicesDisabled, Unavailable, Timeout, Unreliable,
}

/** Deliberately retains neither a platform cause nor any location/provider data. */
internal class ForegroundLocationException(val code: ForegroundLocationFailure) : Exception(code.name)

internal fun interface LocationUpdateSubscription {
    fun close()
}

internal fun interface LocationUpdateSource<T : Any> {
    /** Null means absent/disabled. An unsuccessful registration must clean itself up. */
    fun subscribe(onLocation: (T) -> Unit): LocationUpdateSubscription?
}

/**
 * All eligible sources share one deadline, with no per-provider time slices. A
 * rejected candidate leaves every subscription alive to receive a better fix.
 * The platform adapter serializes registration/callbacks/cleanup on its dispatcher;
 * the channel also safely ignores late callbacks after cancellation or completion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T : Any, R : Any> awaitQualifiedLocation(
    sources: List<LocationUpdateSource<T>>,
    timeoutMillis: Long,
    checkAvailability: () -> Unit,
    qualify: (T) -> R?,
): R {
    require(timeoutMillis > 0L)
    val candidates = Channel<T>(Channel.BUFFERED)
    val subscriptions = mutableMapOf<Int, LocationUpdateSubscription>()
    var receivedUnreliableFix = false
    try {
        currentCoroutineContext().ensureActive()
        checkAvailability()
        val result = withTimeoutOrNull(timeoutMillis) {
            suspend fun subscribeAvailableSources() {
                for ((index, source) in sources.withIndex()) {
                    if (index in subscriptions) continue
                    currentCoroutineContext().ensureActive()
                    checkAvailability()
                    val subscription = try {
                        source.subscribe { candidates.trySend(it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: RuntimeException) {
                        // A provider can be temporarily unavailable. Recheck the
                        // request's permission/services guard and retry it on the
                        // next heartbeat without extending the shared deadline.
                        checkAvailability()
                        null
                    }
                    if (subscription != null) subscriptions[index] = subscription
                }
            }
            subscribeAvailableSources()
            currentCoroutineContext().ensureActive()
            checkAvailability()
            if (sources.isEmpty()) throw ForegroundLocationException(ForegroundLocationFailure.Unavailable)

            var accepted: R? = null
            while (accepted == null) {
                currentCoroutineContext().ensureActive()
                checkAvailability()
                // Recheck revocation/service changes even if a provider falls silent.
                // Atomic selection avoids consuming a fix into a cancelled
                // per-heartbeat receive when the callback and timer coincide.
                val candidate = select<T?> {
                    candidates.onReceive { it }
                    onTimeout(1_000L) { null }
                }
                if (candidate == null) {
                    subscribeAvailableSources()
                    continue
                }
                accepted = qualify(candidate)
                if (accepted == null) receivedUnreliableFix = true
            }
            currentCoroutineContext().ensureActive()
            checkAvailability()
            accepted
        }
        currentCoroutineContext().ensureActive()
        checkAvailability()
        return result ?: throw ForegroundLocationException(
            if (receivedUnreliableFix) ForegroundLocationFailure.Unreliable else ForegroundLocationFailure.Timeout,
        )
    } finally {
        // Clear queued locations before closing all sources, including after a
        // callback, qualification exception, deadline or parent cancellation.
        candidates.cancel()
        subscriptions.values.toList().asReversed().forEach { subscription ->
            try { subscription.close() } catch (_: RuntimeException) { /* Safe, best-effort OEM cleanup. */ }
        }
    }
}
