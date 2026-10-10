// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.data

import androidx.room.withTransaction
import io.github.silent07137.apptime.core.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import java.time.LocalDate


data class AppInspection(val name: String?, val installed: Boolean, val signingDigests: List<String> = emptyList())

class UsageRepository(
    private val db: AppDatabase,
    private val source: UsageEventSource,
    private val resolveName: (String) -> String,
    private val profile: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val timezone: () -> ZoneId = ZoneId::systemDefault,
    private val historySource: UsageHistorySource? = null,
    private val inspectApp: ((String) -> AppInspection)? = null,
) {
    val dao = db.usageDao()
    private val mutex = Mutex()
    suspend fun <T> withArchiveLock(action: suspend (AppDatabase) -> T): T = mutex.withLock { action(db) }
    suspend fun rebuildArchiveCaches() {
        val devices = dao.observeDevices().first().associateBy { it.deviceId }
        val identities = dao.identities().associateBy { it.identityId }
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM daily_usage")
        sql.execSQL("DELETE FROM event_daily_usage")
        sql.execSQL("DELETE FROM system_daily_supplements")
        for (snapshot in dao.allSystemDays()) {
            val owner = requireNotNull(identities[snapshot.identityId])
            val zone = ZoneId.of(requireNotNull(devices[owner.deviceId]).reportTimezone)
            val aligned = HistoricalPolicy.isCalendarDay(snapshot.bucketStartMs, snapshot.bucketEndMs,
                LocalDate.parse(snapshot.reportDate), zone)
            if (snapshot.calendarAligned != aligned) dao.setCalendarAligned(snapshot.identityId, snapshot.reportDate, aligned)
        }
        for ((id, sessions) in dao.allSessions().groupBy { it.identityId }) {
            val device = requireNotNull(devices[sessions.first().originDeviceId])
            val zone = ZoneId.of(device.reportTimezone)
            val union = device.platform != "windows"
            val raw = sessions.map { Interval(it.startMs, it.endMs) }
            val intervals = UsageMath.subtract(raw, ignoreIntervals(id, raw.maxOf { it.endMs }), union)
            dao.saveEventDays(UsageMath.daily(intervals, zone, union).map { (date, ms) -> EventDailyEntity(id, date.toString(), zone.id, ms) })
            if (intervals.isEmpty()) continue
            val start = intervals.minOf { it.startMs }; val end = intervals.maxOf { it.endMs }
            dao.saveDays(UsageMath.daily(UsageMath.subtract(intervals, historyOwnership(id, start, end), union), zone, union).map { (date, ms) -> DailyEntity(id, date.toString(), zone.id, ms) })
            rebuildSystemSupplements(id, Instant.ofEpochMilli(start).atZone(zone).toLocalDate(), Instant.ofEpochMilli(end - 1).atZone(zone).toLocalDate(), zone)
        }
        dao.saveDailySyncState(DailySyncState(initialDone = false, eventRebuilt = true))
    }
    fun hasAccess() = source.hasAccess()

    suspend fun collect(): Boolean = mutex.withLock {
        val end = now()
        val device = dao.device() ?: DeviceEntity(UUID.randomUUID().toString(), createdAt = end, reportTimezone = timezone().id).also { dao.insertDevice(it) }
        if (inspectApp != null) for (identity in dao.identities().filter { it.deviceId == device.deviceId }) observeIdentity(identity)
        val storedState = dao.state() ?: CollectionState(recordFromMs = end)
        val oldState = if (!storedState.enabled && source.hasAccess()) storedState.copy(recordFromMs = end, enabled = true) else storedState
        rebuildEventDaysLocked()
        if (oldState.enabled && source.hasAccess()) syncSystemDaysLocked(device, end)
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
                    if (oldState.enabled && end > start) dao.saveCoverage(CoverageEntity("${device.deviceId}:unavailable:$start", device.deviceId, start, end, "unavailable", read.reason))
                }
                false
            }
            is EventRead.Available -> {
                if (read.events.isEmpty()) {
                    // Never interpret an empty query as complete coverage or overwrite history.
                    db.withTransaction {
                        dao.saveState(oldState.copy(status = "未采集", detail = "系统未返回事件，已有记录保留；无法判断是否无使用"))
                        val start = oldState.checkpointMs ?: oldState.recordFromMs
                        if (end > start) dao.saveCoverage(CoverageEntity("${device.deviceId}:empty:$start", device.deviceId, start, end, "unavailable", "空事件查询，完整性未知"))
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
                            val identity = dao.identity(device.deviceId, profile, pkg) ?: continue
                            for (existing in dao.sessionsAtAnchor(identity.identityId, anchor)) {
                                if (existing.provisional) {
                                    dao.saveSession(existing.copy(deleted = true, revision = existing.revision + 1))
                                    touch(existing)
                                }
                            }
                        }
                        for (session in replay.sessions) {
                            val identity = ensureIdentity(device, session.packageName)
                            if (dao.observation(identity.identityId)?.signingChanged == true) continue
                            val baseId = session.recordId(device.deviceId, profile)
                            val allowed = UsageMath.subtract(listOf(Interval(session.startMs, session.endMs)),
                                ignoreIntervals(identity.identityId, session.endMs))
                            if (allowed.isEmpty()) {
                                dao.session(baseId)?.takeIf { !it.deleted }?.let {
                                    dao.saveSession(it.copy(deleted = true, revision = it.revision + 1)); touch(it)
                                }
                            }
                            for ((index, fragment) in allowed.withIndex()) {
                            val recordId = if (index == 0) baseId else UUID.nameUUIDFromBytes("$baseId:${fragment.startMs}".toByteArray()).toString()
                            val existing = dao.session(recordId)
                            val candidate = SessionEntity(recordId, device.deviceId, identity.identityId,
                                session.anchorMs, fragment.startMs, fragment.endMs, fragment.durationMs, captureZone.id,
                                captureZone.rules.getOffset(Instant.ofEpochMilli(session.startMs)).totalSeconds,
                                provisional = session.provisional && fragment.endMs == session.endMs, transitionEstimated = session.transitionEstimated,
                                revision = existing?.revision ?: 1)
                            // A shorter rolling query must not downgrade a confirmed end to provisional.
                            if (existing != null && !existing.provisional && candidate.provisional) continue
                            if (candidate != existing) {
                                if (existing != null) touch(existing)
                                dao.saveSession(candidate.copy(revision = (existing?.revision ?: 0) + 1))
                                touch(candidate)
                            }
                            }
                        }
                        // Rebuild only affected application/day ranges, never increment cached totals.
                        for ((identityId, bounds) in affected) {
                            val firstDate = Instant.ofEpochMilli(bounds.first).atZone(zone).toLocalDate()
                            val lastDate = Instant.ofEpochMilli(bounds.second - 1).atZone(zone).toLocalDate()
                            val start = firstDate.atStartOfDay(zone).toInstant().toEpochMilli()
                            val stop = lastDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                            val intervals = dao.overlapping(identityId, start, stop).map { Interval(maxOf(start, it.startMs), minOf(stop, it.endMs)) }
                            val authority = historyOwnership(identityId, start, stop)
                            val eventDays = UsageMath.daily(intervals, zone).map { (date, duration) -> EventDailyEntity(identityId, date.toString(), zone.id, duration) }
                            val days = UsageMath.daily(UsageMath.subtract(intervals, authority), zone).map { (date, duration) -> DailyEntity(identityId, date.toString(), zone.id, duration) }
                            dao.clearEventDays(identityId, firstDate.toString(), lastDate.toString())
                            dao.saveEventDays(eventDays)
                            dao.clearDays(identityId, firstDate.toString(), lastDate.toString())
                            dao.saveDays(days)
                            rebuildSystemSupplements(identityId, firstDate, lastDate, zone)
                        }
                        val previous = oldState.checkpointMs
                        if (previous != null && previous < ownedStart) dao.saveCoverage(CoverageEntity("${device.deviceId}:late:$previous", device.deviceId, previous, ownedStart, "unavailable", "超过回查窗口，可能存在历史缺口"))
                        dao.saveCoverage(CoverageEntity("${device.deviceId}:query:$ownedStart", device.deviceId, ownedStart, end, "partial", "系统事件完整性未知；分屏按各应用前台口径"))
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
        val attempt = now()
        val historyImported = importHistoryLocked(days, device, state, attempt)
        val dailyImported = syncSystemDaysLocked(device, attempt, forceFull = days == 0)
        historyImported || dailyImported
    }

    private suspend fun rebuildEventDaysLocked() {
        val sync = dao.dailySyncState() ?: DailySyncState()
        if (sync.eventRebuilt) return
        db.withTransaction {
            rebuildArchiveCaches()
            dao.saveDailySyncState(sync.copy(eventRebuilt = true))
        }
    }

    private suspend fun syncSystemDaysLocked(device: DeviceEntity, attempt: Long, forceFull: Boolean = false): Boolean {
        val provider = historySource ?: return false
        val sync = dao.dailySyncState() ?: DailySyncState()
        val start = if (forceFull || !sync.initialDone) 0L else maxOf(0, attempt - 31 * 24 * HOUR_MS)
        val read = provider.readDaily(start, attempt)
        if (read is HistoryRead.Unavailable) return false
        val zone = ZoneId.of(device.reportTimezone)
        val buckets = (read as HistoryRead.Available).buckets.filter {
            it.packageName.isNotBlank() && it.startMs >= 0 && it.startMs < attempt && it.endMs > it.startMs && it.usageMs > 0
        }
        // A daily system bucket can be returned again on every refresh. Keep one observation
        // per package/day, replacing it only with a larger cumulative value.
        val byDay = buckets.groupBy { it.packageName to Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .mapValues { (_, values) -> values.maxWith(compareBy<HistoricalBucket> { it.usageMs }.thenBy { it.endMs }) }
        db.withTransaction {
            val rows = mutableListOf<SystemDailyEntity>()
            for ((key, bucket) in byDay) {
                val (pkg, date) = key
                val identity = ensureIdentity(device, pkg)
                if (dao.observation(identity.identityId)?.signingChanged == true ||
                    ignoreIntervals(identity.identityId, bucket.endMs).any { it.startMs < bucket.endMs && it.endMs > bucket.startMs }) continue
                val previous = dao.systemDay(identity.identityId, date.toString())
                if (previous == null || bucket.usageMs > previous.durationMs) {
                    rows += SystemDailyEntity(identity.identityId, date.toString(), zone.id, bucket.usageMs,
                        bucket.startMs, bucket.endMs, attempt,
                        calendarAligned = HistoricalPolicy.isCalendarDay(bucket.startMs, bucket.endMs, date, zone))
                }
            }
            if (rows.isNotEmpty()) dao.saveSystemDays(rows)
            dao.saveDailySyncState(sync.copy(initialDone = true, lastAttemptMs = attempt))
        }
        return buckets.isNotEmpty()
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
                val identity = ensureIdentity(device, bucket.packageName)
                if (dao.observation(identity.identityId)?.signingChanged == true ||
                    ignoreIntervals(identity.identityId, bucket.endMs).any { it.startMs < bucket.endMs && it.endMs > bucket.startMs }) { skipped++; continue }
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
                    val ownership = historyOwnership(identity.identityId, start, end)
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
    private suspend fun ensureIdentity(device: DeviceEntity, pkg: String): IdentityEntity {
        val identity = dao.identity(device.deviceId, profile, pkg) ?: IdentityEntity(UUID.randomUUID().toString(), device.deviceId, profile, pkg, resolveName(pkg)).also { dao.insertIdentity(it) }
        if (inspectApp != null && dao.observation(identity.identityId) == null) observeIdentity(identity)
        return identity
    }

    private suspend fun observeIdentity(identity: IdentityEntity) {
        val inspection = inspectApp?.invoke(identity.packageName) ?: return
        val previous = dao.observation(identity.identityId)
        val digest = previous?.signingDigest ?: inspection.signingDigests.firstOrNull()
        val changed = digest != null && inspection.signingDigests.isNotEmpty() && digest !in inspection.signingDigests
        dao.saveObservation(AppObservationEntity(identity.identityId, if (inspection.installed) "installed" else "unknown", digest, changed, now()))
        if (!changed && inspection.name != null && inspection.name != identity.displayName) dao.updateIdentity(identity.copy(displayName = inspection.name))
    }

    private suspend fun ignoreIntervals(id: String, end: Long): List<Interval> = dao.ignorePeriods(id).mapNotNull {
        val stop = it.endMs ?: end
        if (stop > it.startMs) Interval(it.startMs, stop) else null
    }

    private suspend fun historyOwnership(id: String, start: Long, end: Long): List<Interval> {
        val ignored = dao.ignorePeriods(id)
        return dao.overlappingBuckets(id, start, end, "").mapNotNull { bucket ->
            // A frozen system snapshot predates an ignore period. It cannot own later
            // resumed events; its original historical observation remains unchanged.
            val stop = minOf(bucket.endMs, ignored.filter { it.startMs in bucket.startMs until bucket.endMs }.minOfOrNull { it.startMs } ?: bucket.endMs)
            if (stop > bucket.startMs) Interval(bucket.startMs, stop) else null
        }
    }

    private suspend fun rebuildSystemSupplements(id: String, from: LocalDate, to: LocalDate, zone: ZoneId) {
        val ignored = dao.ignorePeriods(id)
        for (snapshot in dao.systemDays(id, from.toString(), to.toString())) {
            var duration = 0L
            if (ignored.any { it.startMs < snapshot.bucketEndMs && (it.endMs ?: Long.MAX_VALUE) > snapshot.bucketStartMs }) {
                val date = LocalDate.parse(snapshot.reportDate)
                val start = maxOf(snapshot.observedAtMs, date.atStartOfDay(zone).toInstant().toEpochMilli())
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                if (start < end) duration = UsageMath.union(dao.overlapping(id, start, end).map { Interval(maxOf(start, it.startMs), minOf(end, it.endMs)) }).sumOf { it.durationMs }
            }
            dao.saveSystemSupplement(SystemDaySupplementEntity(id, snapshot.reportDate, duration))
        }
    }

    suspend fun updatePreference(id: String, category: String? = null, hidden: Boolean? = null) = mutex.withLock {
        val old = dao.preference(id) ?: AppPreferenceEntity(id)
        require(category == null || category.trim().length in 1..24) { "分类名称需为 1–24 个字" }
        val updated = old.copy(category = category?.trim() ?: old.category, hidden = hidden ?: old.hidden)
        if (updated != old) dao.savePreference(updated.copy(revision = old.revision + 1))
    }

    suspend fun setIgnored(id: String, ignored: Boolean) = mutex.withLock {
        db.withTransaction {
            val old = dao.preference(id) ?: AppPreferenceEntity(id)
            if (old.ignored == ignored) return@withTransaction
            if (ignored) dao.saveIgnorePeriod(IgnorePeriodEntity(UUID.randomUUID().toString(), id, now()))
            else dao.ignorePeriods(id).filter { it.endMs == null }.forEach { dao.saveIgnorePeriod(it.copy(endMs = maxOf(now(), it.startMs), revision = it.revision + 1)) }
            dao.savePreference(old.copy(ignored = ignored, revision = old.revision + 1))
        }
    }

    suspend fun addAdjustment(id: String, date: LocalDate, deltaMs: Long, note: String) = mutex.withLock {
        require(deltaMs in -24 * HOUR_MS..24 * HOUR_MS && deltaMs != 0L) { "请输入 1 分钟至 24 小时的调整量" }
        val owner = dao.identities().first { it.identityId == id }.deviceId
        val device = requireNotNull(dao.observeDevices().first().firstOrNull { it.deviceId == owner })
        val zone = ZoneId.of(device.reportTimezone)
        require(!date.isAfter(Instant.ofEpochMilli(now()).atZone(zone).toLocalDate())) { "不能调整未来日期" }
        db.withTransaction {
            val total = dao.observeApps().first().first { it.identityId == id }.let { it.durationMs + it.historicalMs + it.adjustmentMs }
            require((dao.appDay(id, date.toString())?.durationMs ?: 0) + deltaMs >= 0 && total + deltaMs >= 0) { "扣减后时长不能小于零" }
            dao.saveAdjustment(AdjustmentEntity(UUID.randomUUID().toString(), id, date.toString(), zone.id, deltaMs, note.take(120), now()))
        }
    }

    suspend fun removeAdjustment(id: String) = mutex.withLock {
        db.withTransaction {
            val entry = dao.adjustment(id) ?: return@withTransaction
            val app = dao.observeApps().first().first { it.identityId == entry.identityId }
            require((dao.appDay(entry.identityId, entry.reportDate)?.durationMs ?: 0) - entry.deltaMs >= 0 &&
                app.durationMs + app.historicalMs + app.adjustmentMs - entry.deltaMs >= 0) { "请先移除依赖这条补记的扣减记录" }
            dao.deleteAdjustment(id)
        }
    }

    suspend fun acceptSigning(id: String) = mutex.withLock {
        val identity = dao.identities().first { it.identityId == id }
        val inspection = inspectApp?.invoke(identity.packageName) ?: return@withLock
        val digest = inspection.signingDigests.firstOrNull() ?: return@withLock
        dao.saveObservation(AppObservationEntity(id, "installed", digest, false, now()))
    }

    suspend fun recordFailure() = mutex.withLock {
        dao.state()?.let { dao.saveState(it.copy(status = "采集失败", detail = "后台或前台采集失败，已有记录已保留。请稍后刷新重试。")) }
    }

    companion object { private const val HOUR_MS = 3_600_000L }
}
