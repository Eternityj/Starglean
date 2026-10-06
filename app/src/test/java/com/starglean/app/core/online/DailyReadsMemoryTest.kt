package com.starglean.app.core.online

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyReadsMemoryTest {
    private val melbourne = ZoneId.of("Australia/Melbourne")

    @Test fun reopeningTheFindPageKeepsTodaysCountForTheSameAccount() {
        val memory = DailyReadsMemory(TestClock(Instant.parse("2026-10-02T03:00:00Z")))
        memory.remember("account-a", melbourne, 7)
        assertEquals(7, memory.remainingFor("account-a"))
        assertNull(memory.remainingFor("account-b"))
    }

    @Test fun countExpiresAtTheAccountsMidnight() {
        val clock = TestClock(Instant.parse("2026-10-02T13:59:00Z")) // 23:59 in Melbourne
        val memory = DailyReadsMemory(clock)
        memory.remember("account-a", melbourne, 2)
        assertEquals(2, memory.remainingFor("account-a"))
        clock.now = Instant.parse("2026-10-02T14:01:00Z") // 00:01 the next day in Melbourne
        assertNull(memory.remainingFor("account-a"))
    }

    @Test fun onlyAPlausibleServerCountBecomesTheRemainingNumber() {
        assertEquals(7, dailyReadsLeft(dailyLimit = 10, used = 3))
        assertEquals(0, dailyReadsLeft(dailyLimit = 10, used = 10))
        assertNull(dailyReadsLeft(dailyLimit = 10, used = null))
        assertNull(dailyReadsLeft(dailyLimit = null, used = 3))
        assertNull(dailyReadsLeft(dailyLimit = 10, used = 11))
        assertNull(dailyReadsLeft(dailyLimit = 10, used = -1))
        assertNull(dailyReadsLeft(dailyLimit = 0, used = 0))
    }

    private class TestClock(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = Zoned(this, zone)
        override fun instant(): Instant = now

        private class Zoned(private val base: TestClock, private val zone: ZoneId) : Clock() {
            override fun getZone(): ZoneId = zone
            override fun withZone(zone: ZoneId): Clock = Zoned(base, zone)
            override fun instant(): Instant = base.now
        }
    }
}
