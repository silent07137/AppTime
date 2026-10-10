// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DistributionTest {
    @Test fun windowsRollbackEpisodesRemainDistinctAfterIgnoreAndCalendarSplitting() {
        val raw = listOf(Interval(0, 60_000), Interval(0, 20_000))
        val rows = UsageMath.subtract(raw, listOf(Interval(10_000, 15_000)), unionObservations = false)
        val zone = ZoneId.of("UTC"); val date = LocalDate.of(1970, 1, 1)
        assertEquals(70_000L, UsageMath.daily(rows, zone, unionObservations = false)[date])
        assertEquals(70_000L, UsageDistribution.hours(date, zone, rows.map { AppInterval("A", it) }, unionObservations = false).sumOf { it.durationMs })
        assertEquals(55_000L, UsageMath.daily(rows, zone)[date]) // Android still unions observations.
    }
    @Test fun unionPerAppPreservesConcurrentAppsAndClipsMidnight() {
        val rows = listOf(AppInterval("A", Interval(-10_000, 3_700_000)),
            AppInterval("A", Interval(0, 1_800_000)), AppInterval("B", Interval(0, 60_000)))
        val hours = UsageDistribution.hours(LocalDate.of(1970, 1, 1), ZoneId.of("UTC"), rows)
        assertEquals(24, hours.size)
        assertEquals(3_660_000L, hours[0].durationMs)
        assertEquals(100_000L, hours[1].durationMs)
        assertEquals(3_760_000L, hours.sumOf { it.durationMs })
    }
    @Test fun daylightSavingDaysKeepRealHourBoundaries() {
        val zone = ZoneId.of("America/New_York")
        for ((date, count) in listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25)) {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val hours = UsageDistribution.hours(date, zone, listOf(AppInterval("A", Interval(start, end))))
            assertEquals(count, hours.size)
            assertEquals(end - start, hours.sumOf { it.durationMs })
            assertTrue(hours.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        }
    }
}
