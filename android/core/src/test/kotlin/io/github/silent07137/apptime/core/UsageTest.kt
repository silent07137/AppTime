// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.io.File

class UsageTest {
    private fun e(t: Long, kind: EventKind, pkg: String = "A", component: String = "Main") = UsageEvent(t, kind, pkg, component)
    @Test fun appSwitchesHaveExpectedTotals() {
        val events = listOf(e(0, EventKind.RESUME), e(60_000, EventKind.PAUSE), e(60_000, EventKind.RESUME, "B"),
            e(90_000, EventKind.PAUSE, "B"), e(90_000, EventKind.RESUME), e(100_000, EventKind.PAUSE))
        val totals = UsageReplay.replay(events, 0, 110_000).sessions.groupBy { it.packageName }.mapValues { (_, s) -> s.sumOf { it.durationMs } }
        assertEquals(mapOf("A" to 70_000L, "B" to 30_000L), totals)
    }
    @Test fun activityOverlapCountsOnce() {
        val events = listOf(e(0, EventKind.RESUME), e(10_000, EventKind.RESUME, component = "Detail"),
            e(10_100, EventKind.PAUSE), e(30_000, EventKind.PAUSE, component = "Detail"))
        assertEquals(30_000L, UsageReplay.replay(events, 0, 40_000).sessions.single().durationMs)
    }
    @Test fun shortActivityTransitionIsExplicitlyEstimated() {
        val events = listOf(e(0, EventKind.RESUME), e(10_000, EventKind.PAUSE), e(10_200, EventKind.RESUME, component = "Detail"), e(30_000, EventKind.PAUSE, component = "Detail"))
        val session = UsageReplay.replay(events, 0, 40_000).sessions.single()
        assertTrue(session.transitionEstimated)
        assertEquals(30_000L, session.durationMs)
    }
    @Test fun unmatchedEndNeverInventsStart() {
        val result = UsageReplay.replay(listOf(e(5_000, EventKind.PAUSE)), 0, 10_000)
        assertTrue(result.sessions.isEmpty())
        assertEquals(1, result.unmatchedPauses)
    }
    @Test fun boundaryPreReadUsesFixedRecordingStart() {
        val result = UsageReplay.replay(listOf(e(0, EventKind.RESUME), e(20_000, EventKind.PAUSE)), 10_000, 30_000)
        assertEquals(10_000L, result.sessions.single().durationMs)
        assertEquals(0L, result.sessions.single().anchorMs)
    }
    @Test fun provisionalUpdatesKeepStableKeyAndCanShrink() {
        val first = UsageReplay.replay(listOf(e(0, EventKind.RESUME)), 0, 20_000).sessions.single()
        val closed = UsageReplay.replay(listOf(e(0, EventKind.RESUME), e(15_000, EventKind.PAUSE)), 0, 30_000).sessions.single()
        assertTrue(first.provisional)
        assertFalse(closed.provisional)
        assertEquals(first.recordId("device", "personal"), closed.recordId("device", "personal"))
        assertEquals(15_000L, closed.durationMs)
    }
    @Test fun stopWithoutPauseClosesTheActivityInsteadOfExtendingToQueryEnd() {
        val minute = 60_000L
        val result = UsageReplay.replay(listOf(e(0, EventKind.RESUME), e(57 * minute, EventKind.STOP)), 0, 80 * minute)
        assertEquals(57 * minute, result.sessions.single().durationMs)
        assertFalse(result.sessions.single().provisional)
    }
    @Test fun lateStopOfPausedInstanceDoesNotCloseResumedInstanceOfTheSameClass() {
        val events = listOf(e(0, EventKind.RESUME), e(10_000, EventKind.PAUSE),
            e(10_200, EventKind.RESUME), e(10_500, EventKind.STOP), e(30_000, EventKind.PAUSE))
        assertEquals(30_000L, UsageReplay.replay(events, 0, 40_000).sessions.single().durationMs)
    }
    @Test fun stopClosesOnlyItsOwnActivityWhenAnotherActivityIsStillResumed() {
        val events = listOf(e(0, EventKind.RESUME), e(10_000, EventKind.RESUME, component = "Detail"),
            e(11_000, EventKind.STOP), e(30_000, EventKind.PAUSE, component = "Detail"))
        assertEquals(30_000L, UsageReplay.replay(events, 0, 40_000).sessions.single().durationMs)
    }
    @Test fun screenOffAndLockCloseSessions() {
        for (kind in listOf(EventKind.SCREEN_OFF, EventKind.LOCK, EventKind.SHUTDOWN)) {
            val sessions = UsageReplay.replay(listOf(e(0, EventKind.RESUME), e(5_000, kind), e(6_000, EventKind.RESUME)), 0, 20_000).sessions
            assertEquals(5_000L, sessions.single().durationMs)
        }
    }
    @Test fun startupDoesNotExtendThroughUnknownDowntime() {
        val result = UsageReplay.replay(listOf(e(0, EventKind.RESUME), e(60_000, EventKind.STARTUP)), 0, 100_000)
        assertTrue(result.sessions.isEmpty())
        assertEquals(listOf("A" to 0L), result.discardedAnchors)
    }
    @Test fun separateDevicesProduceDifferentKeys() {
        val s = Session("A", 0, 0, 10)
        assertNotEquals(s.recordId("one", "personal"), s.recordId("two", "personal"))
        assertNotEquals(s.recordId("one", "personal"), s.recordId("one", "work"))
    }
    @Test fun unionPreventsDuplicateObservationsCountingTwice() {
        assertEquals(20L, UsageMath.union(listOf(Interval(0, 10), Interval(5, 15), Interval(0, 10), Interval(15, 20))).sumOf { it.durationMs })
    }
    @Test fun midnightSplitConservesDuration() {
        val zone = ZoneId.of("Asia/Shanghai")
        val midnight = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(mapOf(LocalDate.of(2026, 9, 29) to 60_000L, LocalDate.of(2026, 9, 30) to 90_000L), UsageMath.daily(listOf(Interval(midnight - 60_000, midnight + 90_000)), zone))
    }
    @Test fun dstDaysAre23And25Hours() {
        val zone = ZoneId.of("America/New_York")
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 8) to 23L, LocalDate.of(2026, 11, 1) to 25L)) {
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            assertEquals(mapOf(date to hours * 3_600_000), UsageMath.daily(listOf(Interval(start, end)), zone))
        }
    }
    @Test fun queryEndIsExclusive() {
        val result = UsageReplay.replay(listOf(e(10, EventKind.RESUME)), 0, 10)
        assertTrue(result.sessions.isEmpty())
    }
    @Test fun multiWindowRemainsPerAppForeground() {
        val result = UsageReplay.replay(listOf(e(0, EventKind.RESUME), e(0, EventKind.RESUME, "B"), e(10_000, EventKind.SCREEN_OFF)), 0, 20_000)
        assertEquals(20_000L, result.sessions.sumOf { it.durationMs })
        assertEquals(10_000L, UsageMath.union(result.sessions.map { Interval(it.startMs, it.endMs) }).sumOf { it.durationMs })
    }
    @Test fun sharedAppSwitchFixture() {
        val dir = File(System.getProperty("apptime.fixtures"))
        val events = File(dir, "android-switch-events.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            UsageEvent(fields[0].toLong(), EventKind.valueOf(fields[1]), fields[2], fields[3])
        }
        val expected = File(dir, "android-switch-expected.tsv").readLines().drop(1).filter { it.isNotBlank() }.associate { line ->
            val fields = line.split('\t'); fields[0] to fields[1].toLong()
        }
        val actual = UsageReplay.replay(events, 0, 110_000).sessions.groupBy { it.packageName }.mapValues { (_, sessions) -> sessions.sumOf { it.durationMs } }
        assertEquals(expected, actual)
    }
}
