// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryTest {
    @Test fun shiftedSystemDayAndPartialEveningBucketCannotRepresentCalendarDay() {
        val zone = ZoneId.of("Asia/Shanghai")
        val date = LocalDate.of(2026, 10, 5)
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val shifted = midnight + (17 * 60 + 35) * 60_000L
        assertFalse(HistoricalPolicy.isCalendarDay(shifted, shifted + 86_400_000L - 1, date, zone))
        assertFalse(HistoricalPolicy.isCalendarDay(shifted, midnight + 23 * 3_600_000L, date, zone))
        assertTrue(HistoricalPolicy.isCalendarDay(midnight, midnight + 23 * 3_600_000L, date, zone))
    }
    @Test fun calendarValidationUsesSourceTimezoneAndActualDstMidnights() {
        val zone = ZoneId.of("America/New_York")
        for (date in listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 11, 1))) {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            assertTrue(HistoricalPolicy.isCalendarDay(start, end, date, zone))
            assertTrue(HistoricalPolicy.isCalendarDay(start, end - 1, date, zone))
            assertFalse(HistoricalPolicy.isCalendarDay(start, end + 1, date, zone))
            assertFalse(HistoricalPolicy.isCalendarDay(start, end, date, ZoneId.of("UTC")))
        }
    }
    @Test fun recordingTransitionBucketIsKeptWholeWithoutProrating() {
        val old = HistoricalBucket("A", 0, 100, 80)
        val crossing = HistoricalBucket("A", 100, 200, 90)
        assertEquals(listOf(old, crossing), HistoricalPolicy.eligible(listOf(old, crossing), 150))
    }
    @Test fun returnedRangeCanPrecedeRequestedRangeAndMustRemainWhole() {
        val returned = HistoricalBucket("A", 0, 100, 80)
        assertEquals(returned, HistoricalPolicy.eligible(listOf(returned), 150).single())
    }
    @Test fun duplicateBucketsAreNotSummed() {
        val first = HistoricalBucket("A", 0, 90, 70)
        val revised = HistoricalBucket("A", 0, 100, 80)
        assertEquals(listOf(revised), HistoricalPolicy.eligible(listOf(first, revised, revised), 150))
    }
    @Test fun malformedBucketsAreRejected() {
        assertTrue(HistoricalPolicy.eligible(listOf(HistoricalBucket("", 0, 100, 5), HistoricalBucket("A", 50, 40, 5), HistoricalBucket("A", 0, 100, -1)), 150).isEmpty())
    }
    @Test fun futureBucketsAreNotImportedAsOldHistory() {
        assertTrue(HistoricalPolicy.eligible(listOf(HistoricalBucket("A", 200, 300, 50)), 150).isEmpty())
    }
    @Test fun aggregateOwnershipExcludesOverlappingNewSessions() {
        assertEquals(listOf(Interval(200, 300)), UsageMath.subtract(listOf(Interval(150, 300)), listOf(Interval(100, 200))))
        assertEquals(listOf(Interval(0, 20), Interval(80, 100)), UsageMath.subtract(listOf(Interval(0, 100)), listOf(Interval(20, 60), Interval(40, 80))))
    }
}
