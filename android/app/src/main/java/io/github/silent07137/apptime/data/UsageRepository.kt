// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.data

import androidx.room.withTransaction
import io.github.silent07137.apptime.core.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UsageRepository(
    private val db: AppDatabase,
    private val source: UsageEventSource,
    private val resolveName: (String) -> String,
    private val profile: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val timezone: () -> ZoneId = ZoneId::systemDefault,
    private val historySource: UsageHistorySource? = null,
) {
    val dao = db.usageDao()
    private val mutex = Mutex()
    fun hasAccess() = source.hasAccess()

    suspend fun collect(): Boolean = mutex.withLock {
        val end = now()
        val device = dao.device() ?: DeviceEntity(UUID.randomUUID().toString(), createdAt = end, reportTimezone = timezone().id).also { dao.insertDevice(it) }
        val storedState = dao.state() ?: CollectionState(recordFromMs = end)
        val oldState = if (!storedState.enabled && source.hasAccess()) storedState.copy(recordFromMs = end, enabled = true) else storedState
        val ownedStart = maxOf(oldState.recordFromMs, end - 48 * HOUR_MS)
        // Pre-read supplies an actual resume anchor; no guessed start at a query boundary.
        val queryStart = maxOf(0, ownedStart - 24 * HOUR_MS)
        if (end < oldState.recordFromMs || (oldState.checkpointMs != null && end < oldState.checkpointMs)) {
            dao.saveState(oldState.copy(status = "存在缺口", detail = "系统时钟回拨，等待时间恢复后再采集"))
            return@withLock false
        }
        // Import the earliest retained daily history once, even if event history is empty.
        // Existing finite-range imports upgrade to the earliest range without clearing data.
        val historyState = dao.historyState()
        if (oldState.enabled && source.hasAccess() && historySource != null &&
            (historyState == null || historyState.requestedStartMs > 0 || historyState.source != "android_usage_stats_best" || historyState.status == "不可用")) {
            dao.saveState(oldState)
            importHistoryLocked(0, device, oldState, end)
        }
        when (val read = source.read(queryStart, end)) {
            is EventRead.Unavailable -> {
                db.withTransaction {
                    dao.saveState(oldState.copy(status = "不可用", detail = read.reason))
                    val start = oldState.checkpointMs ?: oldState.recordFromMs
                    if (oldState.enabled && end > start) dao.saveCoverage(CoverageEntity("unavailable:$start", device.deviceId, start, end, "unavailable", read.reason))
                }
                false
            }
            is EventRead.Available -> {
                if (read.events.isEmpty()) {
                    // Never interpret an empty query as complete coverage or overwrite history.
                    db.withTransaction {
                        dao.saveState(oldState.copy(status = "未采集", detail = "系统未返回事件，已有记录保留；无法判断是否无使用"))
                        val start = oldState.checkpointMs ?: oldState.recordFromMs
                        if (end > start) dao.saveCoverage(CoverageEntity("empty:$start", device.deviceId, start, end, "unavailable", "空事件查询，完整性未知"))
                    }
                    false
                } else {
                    val replay = UsageReplay.replay(read.events, oldState.recordFromMs, end)
                    val zone = ZoneId.of(device.reportTimezone)
                    val captureZone = timezone()
                    val affected = linkedMapOf<String, Pair<Long, Long>>()
                    fun touch(s: SessionEntity) {
                        val bounds = affected[s.identityId]
                        affected[s.identityId] = minOf(bounds?.first ?: s.startMs, s.startMs) to maxOf(bounds?.second ?: s.endMs, s.endMs)
                    }
                    db.withTransaction {
                        for ((pkg, anchor) in replay.discardedAnchors) {
                            val id = sessionId(device.deviceId, profile, pkg, anchor)
                            val existing = dao.session(id) ?: continue
                            if (existing.provisional && !existing.deleted) {
                                dao.saveSession(existing.copy(deleted = true, revision = existing.revision + 1))
                                touch(existing)
                            }
                        }
                        for (session in replay.sessions) {
                            var identity = dao.identity(device.deviceId, profile, session.packageName)
                            if (identity == null) {
                                identity = IdentityEntity(UUID.randomUUID().toString(), device.deviceId, profile, session.packageName, resolveName(session.packageName))
                                dao.insertIdentity(identity)
                            }
                            val existing = dao.session(session.recordId(device.deviceId, profile))
                            val candidate = SessionEntity(session.recordId(device.deviceId, profile), device.deviceId, identity.identityId,
                                session.anchorMs, session.startMs, session.endMs, session.durationMs, captureZone.id,
                                captureZone.rules.getOffset(Instant.ofEpochMilli(session.startMs)).totalSeconds,
                                provisional = session.provisional, transitionEstimated = session.transitionEstimated,
                                revision = existing?.revision ?: 1)
                            // A shorter rolling query must not downgrade a confirmed end to provisional.
                            if (existing != null && !existing.provisional && candidate.provisional) continue
                            if (candidate != existing) {
                                if (existing != null) touch(existing)
                                dao.saveSession(candidate.copy(revision = (existing?.revision ?: 0) + 1))
                                touch(candidate)
                            }
                        }
                        // Rebuild only affected application/day ranges, never increment cached totals.
                        for ((identityId, bounds) in affected) {
                            val firstDate = Instant.ofEpochMilli(bounds.first).atZone(zone).toLocalDate()
                            val lastDate = Instant.ofEpochMilli(bounds.second - 1).atZone(zone).toLocalDate()
                            val start = firstDate.atStartOfDay(zone).toInstant().toEpochMilli()
                            val stop = lastDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                            val intervals = dao.overlapping(identityId, start, stop).map { Interval(maxOf(start, it.startMs), minOf(stop, it.endMs)) }
                            val authority = dao.overlappingBuckets(identityId, start, stop, "").map { Interval(it.startMs, it.endMs) }
                            val days = UsageMath.daily(UsageMath.subtract(intervals, authority), zone).map { (date, duration) -> DailyEntity(identityId, date.toString(), zone.id, duration) }
                            dao.clearDays(identityId, firstDate.toString(), lastDate.toString())
                            dao.saveDays(days)
                        }
                        val previous = oldState.checkpointMs
                        if (previous != null && previous < ownedStart) dao.saveCoverage(CoverageEntity("late:$previous", device.deviceId, previous, ownedStart, "unavailable", "超过回查窗口，可能存在历史缺口"))
                        dao.saveCoverage(CoverageEntity("query:$ownedStart", device.deviceId, ownedStart, end, "partial", "系统事件完整性未知；分屏按各应用前台口径"))
                        dao.saveState(oldState.copy(checkpointMs = end, lastSuccessMs = end, status = "部分可用",
                            detail = "已保存系统返回的前台事件；未结束会话暂计至本次采集边界。无法保证事件完整。"))
                    }
                    // Keep the imported transition bucket current until it settles, rather than
                    // freezing its provisional daily aggregate while suppressing new sessions.
                    if (dao.hasHistory() && end - oldState.recordFromMs <= 72 * HOUR_MS) {
                        importHistoryLocked(1, device, oldState, end, updateOnly = true)
                    }
                    true
                }
            }
        }
    }

    suspend fun importHistory(days: Int): Boolean = mutex.withLock {
        require(days in listOf(0, 7, 30, 90, 365))
        val device = dao.device() ?: return@withLock false
        val state = dao.state() ?: return@withLock false
        if (!state.enabled) return@withLock false
        importHistoryLocked(days, device, state, now())
    }

    private suspend fun importHistoryLocked(days: Int, device: DeviceEntity, state: CollectionState, attempt: Long, updateOnly: Boolean = false): Boolean {
        val provider = historySource ?: return false
        val zone = ZoneId.of(device.reportTimezone)
        val transitionDate = Instant.ofEpochMilli(state.recordFromMs).atZone(zone).toLocalDate()
        val transitionEnd = transitionDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val requestEnd = minOf(attempt, transitionEnd)
        val earliest = if (updateOnly) dao.historyState()?.source == "android_usage_stats_best" else days == 0
        val requestStart = maxOf(0, if (earliest) 0 else if (updateOnly) transitionDate.atStartOfDay(zone).toInstant().toEpochMilli() else requestEnd - days * 24 * HOUR_MS)
        val historySourceId = if (earliest) "android_usage_stats_best" else "android_usage_stats_daily"
        suspend fun saveImportState(value: HistoryImportState) {
            db.withTransaction { dao.clearHistoryState(); dao.saveHistoryState(value.copy(source = historySourceId)) }
        }
        if (requestEnd <= requestStart) return false
        val read = provider.read(requestStart, requestEnd)
        if (read is HistoryRead.Unavailable) {
            if (!updateOnly) saveImportState(HistoryImportState(requestedStartMs = requestStart, requestedEndMs = requestEnd, lastAttemptMs = attempt, status = "不可用", detail = read.reason))
            return false
        }
        val returned = (read as HistoryRead.Available).buckets
        if (returned.isEmpty()) {
            if (!updateOnly) saveImportState(HistoryImportState(requestedStartMs = requestStart, requestedEndMs = requestEnd, lastAttemptMs = attempt, status = "未返回历史", detail = "系统没有返回旧历史；已有数据保留，不代表之前没有使用。"))
            return false
        }
        val eligible = HistoricalPolicy.eligible(returned, transitionEnd)
        var accepted = 0
        var skipped = returned.size - eligible.size
        db.withTransaction {
            for (bucket in eligible) {
                var identity = dao.identity(device.deviceId, profile, bucket.packageName)
                if (identity == null) {
                    identity = IdentityEntity(UUID.randomUUID().toString(), device.deviceId, profile, bucket.packageName, resolveName(bucket.packageName))
                    dao.insertIdentity(identity)
                }
                val bucketId = UUID.nameUUIDFromBytes("${device.deviceId}\u0000$profile\u0000${bucket.packageName}\u0000${bucket.startMs}\u0000${bucket.source}".toByteArray(Charsets.UTF_8)).toString()
                val overlaps = dao.overlappingBuckets(identity.identityId, bucket.startMs, bucket.endMs, bucketId)
                // A wider best-fit bucket can supersede fully contained legacy daily buckets.
                // Keep the originals for provenance; the summary selects the wider authority.
                if (overlaps.isNotEmpty() && !(bucket.source == "android_usage_stats_best" && overlaps.all {
                        it.startMs >= bucket.startMs && it.endMs <= bucket.endMs && it.source == "android_usage_stats_daily"
                    })) { skipped++; continue }
                val previous = dao.historicalBucket(bucketId)
                val candidate = HistoricalBucketEntity(bucketId, device.deviceId, identity.identityId, bucket.startMs, bucket.endMs, bucket.usageMs, source = bucket.source, revision = previous?.revision ?: 1)
                if (candidate != previous) {
                    dao.saveBucket(candidate.copy(revision = (previous?.revision ?: 0) + 1))
                    val from = minOf(previous?.startMs ?: bucket.startMs, bucket.startMs)
                    val until = maxOf(previous?.endMs ?: bucket.endMs, bucket.endMs)
                    // Preserve original sessions; rebuild their derived cache with aggregate ownership.
                    val firstDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
                    val lastDate = Instant.ofEpochMilli(until - 1).atZone(zone).toLocalDate()
                    val start = firstDate.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = lastDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val intervals = dao.overlapping(identity.identityId, start, end).map { Interval(maxOf(start, it.startMs), minOf(end, it.endMs)) }
                    val ownership = dao.overlappingBuckets(identity.identityId, start, end, "").map { Interval(it.startMs, it.endMs) }
                    val daysMap = UsageMath.daily(UsageMath.subtract(intervals, ownership), zone)
                    dao.clearDays(identity.identityId, firstDate.toString(), lastDate.toString())
                    dao.saveDays(daysMap.map { (date, duration) -> DailyEntity(identity.identityId, date.toString(), zone.id, duration) })
                }
                accepted++
            }
            if (!updateOnly || earliest) saveImportState(HistoryImportState(requestedStartMs = requestStart, requestedEndMs = requestEnd,
                returnedStartMs = returned.minOf { it.startMs }, returnedEndMs = returned.maxOf { it.endMs }, acceptedBuckets = accepted, skippedBuckets = skipped,
                lastAttemptMs = attempt, status = "部分可用", detail = "系统汇总，完整性和原始时区未知。跨越建立时间的系统桶整体归历史估算，重叠会话不重复计入；未裁剪系统返回桶。"))
        }
        return true
    }
    companion object { private const val HOUR_MS = 3_600_000L }
}
