package com.starglean.app.ui.screens

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class WritingDateTest {
    @Test fun usesTheWeatherLocationsLocalDayAcrossUtcMidnight() {
        assertEquals("16 Sep · 00:01",
            formatWritingDate(Instant.parse("2026-09-15T14:01:00Z"), ZoneId.of("Australia/Melbourne"), true))
        assertEquals("Wednesday, 16 Sep 2026",
            formatWritingDate(Instant.parse("2026-09-15T14:01:00Z"), ZoneId.of("Australia/Melbourne"), false))
    }

    @Test fun followsTimezoneDaylightSavingRatherThanFixedUtcOffset() {
        assertEquals("16 Dec · 01:01",
            formatWritingDate(Instant.parse("2026-12-15T14:01:00Z"), ZoneId.of("Australia/Melbourne"), true))
    }
}
