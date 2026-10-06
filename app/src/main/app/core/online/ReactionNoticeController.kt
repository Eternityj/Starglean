package com.starglean.app.core.online

import com.starglean.app.core.account.AccountIdentity
import com.starglean.app.core.account.AuthOutcome
import com.starglean.app.core.account.SupabaseAuthClient
import com.starglean.app.core.network.NearbyRepository
import com.starglean.app.core.network.NetworkResult
import com.starglean.app.core.network.ReactionInbox
import com.starglean.app.core.network.ReactionKind
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReactionNotice(
    val cloudEntryId: UUID,
    val localDiaryId: String?,
    val leadingKind: ReactionKind,
    val unreadCount: Int,
    val seenThrough: Instant,
    /** Stable while this entry's unread composition is unchanged, even if a later poll advances the global cursor. */
    val arrivalVersion: String,
)

data class ReactionNoticeState(
    val accountId: String? = null,
    val notices: List<ReactionNotice> = emptyList(),
    val seenThrough: Instant? = null,
) {
    val unreadCount: Int get() = notices.fold(0L) { total, notice -> total + notice.unreadCount }
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val hasUnread: Boolean get() = unreadCount > 0
}

internal fun aggregateReactionNotices(
    accountId: String,
    inbox: ReactionInbox,
    links: List<CloudEntryLink>,
): ReactionNoticeState {
    val watermark = inbox.seenThrough ?: return ReactionNoticeState(accountId)
    val localByCloud = links.associate { it.cloudEntryId to it.localDiaryId }
    val notices = inbox.items.asSequence().filter { it.unread > 0 }.groupBy { it.entryId }.map { (entryId, items) ->
        val leading = items.maxWithOrNull(compareBy<com.starglean.app.core.network.ReactionSummary> { it.unread }.thenBy { it.kind.ordinal })!!
        val unread = items.fold(0L) { total, item -> total + item.unread }
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val arrivalVersion = items.sortedBy { it.kind.ordinal }
            .joinToString("|") { "${it.kind.wire}:${it.count}:${it.unread}" }
        ReactionNotice(entryId, localByCloud[entryId], leading.kind, unread, watermark, arrivalVersion)
    }.sortedWith(compareByDescending<ReactionNotice> { it.unreadCount }.thenBy { it.cloudEntryId.toString() })
    return ReactionNoticeState(accountId, notices, watermark)
}

internal class ReactionAnimationTracker {
    private val consumed = mutableSetOf<String>()
    fun consume(accountId: String, notice: ReactionNotice): Boolean = consumed.add(
        "$accountId:${notice.cloudEntryId}:${notice.arrivalVersion}",
    )
    fun clear() = consumed.clear()
}

internal fun shouldApplyReactionInbox(current: ReactionNoticeState, accountId: String, incoming: Instant): Boolean =
    current.accountId != accountId || current.seenThrough?.let { !incoming.isBefore(it) } != false

/** Foreground-only unread observer. It never acknowledges server messages. */
class ReactionNoticeController(
    private val repository: NearbyRepository,
    private val links: CloudEntryLinkStore,
    private val auth: SupabaseAuthClient,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 60_000L,
) {
    private val mutableState = MutableStateFlow(ReactionNoticeState())
    val state: StateFlow<ReactionNoticeState> = mutableState.asStateFlow()
    private var generation = 0L
    private var activeAccount: AccountIdentity? = null
    private var polling: Job? = null
    private val animations = ReactionAnimationTracker()

    fun setActive(active: Boolean, account: AccountIdentity?) {
        if (!active) {
            generation++; polling?.cancel(); polling = null
            activeAccount = account
            if (mutableState.value.accountId != account?.userId) {
                mutableState.value = ReactionNoticeState(account?.userId)
                animations.clear()
            }
            return
        }
        if (account == null) {
            generation++; activeAccount = null; polling?.cancel(); polling = null
            mutableState.value = ReactionNoticeState()
            return
        }
        if (activeAccount?.userId == account.userId && polling?.isActive == true) return
        generation++; polling?.cancel(); activeAccount = account
        if (mutableState.value.accountId != account.userId) {
            mutableState.value = ReactionNoticeState(account.userId)
            animations.clear()
        }
        val run = generation
        polling = scope.launch {
            while (run == generation && activeAccount?.userId == account.userId) {
                refresh(run, account)
                delay(pollMillis)
            }
        }
    }

    fun observeDisplayedInbox(accountId: String, inbox: ReactionInbox) {
        if (activeAccount?.userId != accountId || inbox.status != "FOUND") return
        scope.launch { applyInbox(generation, accountId, inbox) }
    }

    fun observeAcknowledgedInbox(accountId: String, inbox: ReactionInbox) {
        observeDisplayedInbox(accountId, inbox)
    }

    fun consumeAnimation(localDiaryId: String): ReactionNotice? {
        val notice = mutableState.value.notices.firstOrNull { it.localDiaryId == localDiaryId } ?: return null
        return notice.takeIf { animations.consume(requireNotNull(mutableState.value.accountId), it) }
    }

    private suspend fun refresh(run: Long, account: AccountIdentity) {
        try {
            val lease = (auth.operationLease(account.userId) as? AuthOutcome.Success)?.value ?: return
            val result = repository.scoped(lease.accessToken).reactionInbox()
            if (!auth.isOperationLeaseCurrent(lease) || run != generation || activeAccount?.userId != account.userId) return
            val inbox = (result as? NetworkResult.Success)?.value?.takeIf { it.status == "FOUND" } ?: return
            applyInbox(run, account.userId, inbox)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Retain the last verified unread snapshot on transient failure. */ }
    }

    private suspend fun applyInbox(run: Long, accountId: String, inbox: ReactionInbox) {
        val accountLinks = try { links.linksFor(accountId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        if (run != generation || activeAccount?.userId != accountId) return
        val current = mutableState.value
        val incomingWatermark = inbox.seenThrough ?: return
        if (!shouldApplyReactionInbox(current, accountId, incomingWatermark)) return
        mutableState.value = aggregateReactionNotices(accountId, inbox, accountLinks)
    }
}
