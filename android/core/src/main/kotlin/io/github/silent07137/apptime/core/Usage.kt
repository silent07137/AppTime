// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class EventKind { RESUME, PAUSE, SCREEN_OFF, SCREEN_ON, LOCK, UNLOCK, SHUTDOWN, STARTUP }

data class UsageEvent(val timeMs: Long, val kind: EventKind, val packageName: String = "", val component: String = "")

data class Session(
    val packageName: String,
    val anchorMs: Long,
    val startMs: Long,
    val endMs: Long,
    val provisional: Boolean = false,
    val transitionEstimated: Boolean = false,
) {
    init { require(startMs >= anchorMs && endMs > startMs) }
    val durationMs: Long get() = endMs - startMs
    fun recordId(deviceId: String, profile: String): String = sessionId(deviceId, profile, packageName, anchorMs)
}

fun sessionId(deviceId: String, profile: String, packageName: String, anchorMs: Long): String =
    UUID.nameUUIDFromBytes("$deviceId\u0000$profile\u0000$packageName\u0000$anchorMs\u0000android_usage_events".toByteArray(Charsets.UTF_8)).toString()

data class ReplayResult(val sessions: List<Session>, val discardedAnchors: List<Pair<String, Long>>, val unmatchedPauses: Int)

/** Application foreground unions. Different packages may overlap in Android multi-window. */
object UsageReplay {
    private const val TRANSITION_MS = 1_000L
    private data class Open(val anchor: Long, val components: MutableSet<String>, var pendingEnd: Long? = null, var estimated: Boolean = false)

    fun replay(events: List<UsageEvent>, recordFromMs: Long, queryEndMs: Long): ReplayResult {
        require(recordFromMs <= queryEndMs)
        val open = linkedMapOf<String, Open>()
        val sessions = mutableListOf<Session>()
        val discarded = mutableListOf<Pair<String, Long>>()
        var unmatched = 0
        var screenOn = true
        var locked = false

        fun finish(pkg: String, end: Long, provisional: Boolean = false) {
            val state = open.remove(pkg) ?: return
            val start = maxOf(state.anchor, recordFromMs)
            if (end > start) sessions += Session(pkg, state.anchor, start, end, provisional, state.estimated)
        }

        // Stable ordering preserves the platform's order for events at identical timestamps.
        for (event in events.sortedBy { it.timeMs }) {
            if (event.timeMs >= queryEndMs) break // All intervals and query ownership are [start, end).
            for ((pkg, state) in open.toMap()) {
                val end = state.pendingEnd
                val continuation = event.kind == EventKind.RESUME && event.packageName == pkg && event.timeMs - (end ?: event.timeMs) <= TRANSITION_MS
                if (end != null && !continuation) finish(pkg, end)
            }
            when (event.kind) {
                EventKind.RESUME -> if (screenOn && !locked && event.packageName.isNotBlank()) {
                    val state = open.getOrPut(event.packageName) { Open(event.timeMs, mutableSetOf()) }
                    if (state.pendingEnd != null) {
                        state.estimated = state.estimated || state.pendingEnd != event.timeMs
                        state.pendingEnd = null
                    }
                    state.components += event.component
                }
                EventKind.PAUSE -> {
                    val state = open[event.packageName]
                    if (state == null || !state.components.remove(event.component)) unmatched++
                    else if (state.components.isEmpty()) state.pendingEnd = event.timeMs
                }
                EventKind.SCREEN_OFF, EventKind.LOCK, EventKind.SHUTDOWN -> {
                    for (pkg in open.keys.toList()) finish(pkg, open[pkg]?.pendingEnd ?: event.timeMs)
                    if (event.kind == EventKind.LOCK) locked = true else screenOn = false
                }
                EventKind.SCREEN_ON -> screenOn = true
                EventKind.UNLOCK -> locked = false
                EventKind.STARTUP -> {
                    // Missing shutdown: the closing time is unknown; never extend through downtime.
                    open.forEach { (pkg, state) -> discarded += pkg to state.anchor }
                    open.clear()
                    screenOn = true
                    locked = false
                }
            }
        }
        for (pkg in open.keys.toList()) {
            val end = open[pkg]?.pendingEnd
            finish(pkg, end ?: queryEndMs, provisional = end == null)
        }
        return ReplayResult(sessions, discarded, unmatched)
    }
}

data class Interval(val startMs: Long, val endMs: Long) {
    init { require(endMs > startMs) }
    val durationMs: Long get() = endMs - startMs
}

object UsageMath {
    fun subtract(intervals: List<Interval>, authority: List<Interval>): List<Interval> {
        val cuts = union(authority)
        return union(intervals).flatMap { interval ->
            var cursor = interval.startMs
            val fragments = mutableListOf<Interval>()
            for (cut in cuts) {
                if (cut.endMs <= cursor) continue
                if (cut.startMs >= interval.endMs) break
                if (cut.startMs > cursor) fragments += Interval(cursor, minOf(cut.startMs, interval.endMs))
                cursor = maxOf(cursor, cut.endMs)
                if (cursor >= interval.endMs) break
            }
            if (cursor < interval.endMs) fragments += Interval(cursor, interval.endMs)
            fragments
        }
    }
    fun union(intervals: List<Interval>): List<Interval> {
        val result = mutableListOf<Interval>()
        for (interval in intervals.sortedBy { it.startMs }) {
            val last = result.lastOrNull()
            if (last != null && interval.startMs <= last.endMs) result[result.lastIndex] = Interval(last.startMs, maxOf(last.endMs, interval.endMs))
            else result += interval
        }
        return result
    }

    fun daily(intervals: List<Interval>, zone: ZoneId): Map<LocalDate, Long> {
        val result = sortedMapOf<LocalDate, Long>()
        for (interval in union(intervals)) {
            var cursor = interval.startMs
            while (cursor < interval.endMs) {
                val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = minOf(interval.endMs, nextMidnight)
                result[date] = Math.addExact(result[date] ?: 0L, end - cursor)
                cursor = end
            }
        }
        return result
    }
}

/** System access is isolated from replay and calendar calculations. */
sealed interface EventRead {
    data class Available(val events: List<UsageEvent>) : EventRead
    data class Unavailable(val reason: String) : EventRead
}
interface UsageEventSource {
    fun hasAccess(): Boolean
    fun read(startMs: Long, endMs: Long): EventRead
}
