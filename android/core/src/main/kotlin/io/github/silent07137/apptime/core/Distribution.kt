// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import java.time.LocalDate
import java.time.ZoneId

data class AppInterval(val identityId: String, val interval: Interval)
data class HourUsage(val startMs: Long, val endMs: Long, val durationMs: Long)

/** Union each application's observations, then sum apps. Never invent hourly history. */
object UsageDistribution {
    fun hours(date: LocalDate, zone: ZoneId, records: List<AppInterval>): List<HourUsage> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val intervals = records.groupBy { it.identityId }.values.flatMap { rows ->
            UsageMath.union(rows.map { it.interval })
        }
        val result = mutableListOf<HourUsage>()
        var cursor = start
        while (cursor < end) {
            val next = minOf(end, cursor + 3_600_000L)
            val total = intervals.sumOf { (minOf(next, it.endMs) - maxOf(cursor, it.startMs)).coerceAtLeast(0) }
            result += HourUsage(cursor, next, total)
            cursor = next
        }
        return result
    }
}
