// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import java.time.LocalDate
import java.time.ZoneId

data class HistoricalBucket(val packageName: String, val startMs: Long, val endMs: Long, val usageMs: Long, val source: String = "android_usage_stats_daily")
sealed interface HistoryRead {
    data class Available(val buckets: List<HistoricalBucket>) : HistoryRead
    data class Unavailable(val reason: String) : HistoryRead
}
interface UsageHistorySource {
    fun read(startMs: Long, endMs: Long): HistoryRead
    fun readDaily(startMs: Long, endMs: Long): HistoryRead = HistoryRead.Unavailable("逐日统计不可用")
}

object HistoricalPolicy {
    /** UsageStats intervals may cross report midnights; their totals cannot be split by date. */
    fun isCalendarDay(startMs: Long, endMs: Long, date: LocalDate, zone: ZoneId): Boolean {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return startMs == start && endMs > startMs && endMs <= end
    }

    /** Keep entire old/transition buckets. Their reported ranges own overlapping fine-grained data. */
    fun eligible(buckets: List<HistoricalBucket>, transitionDayEndMs: Long): List<HistoricalBucket> =
        buckets.filter { it.packageName.isNotBlank() && it.startMs >= 0 && it.endMs > it.startMs && it.usageMs > 0 && it.startMs < transitionDayEndMs }
            .groupBy { Triple(it.packageName, it.startMs, it.source) }
            .values.map { candidates -> candidates.maxWith(compareBy<HistoricalBucket> { it.endMs }.thenBy { it.usageMs }) }
}
