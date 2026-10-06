package com.starglean.app.core.online

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Today's remaining nearby encounters for one account, kept for the whole app process so
 * leaving and reopening the Find page does not lose it. It expires at the account's midnight.
 */
class DailyReadsMemory(private val clock: Clock = Clock.systemUTC()) {
    private class Entry(val accountId: String, val zone: ZoneId, val date: LocalDate, val remaining: Int)
    private var entry: Entry? = null

    fun remember(accountId: String, zone: ZoneId, remaining: Int) {
        entry = Entry(accountId, zone, LocalDate.now(clock.withZone(zone)), remaining)
    }

    fun remainingFor(accountId: String): Int? = entry
        ?.takeIf { it.accountId == accountId && it.date == LocalDate.now(clock.withZone(it.zone)) }
        ?.remaining
}

/** Remaining encounters from a server count, or null when the server did not report one. */
fun dailyReadsLeft(dailyLimit: Int?, used: Int?): Int? =
    if (dailyLimit != null && dailyLimit > 0 && used != null && used in 0..dailyLimit) dailyLimit - used else null
