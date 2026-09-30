// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silent07137.apptime.core.*
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: UsageRepository
    private var clock = 100_000L
    private val source = FakeSource()
    private val zone = ZoneId.of("UTC")
    private class FakeSource : UsageEventSource {
        var allowed = true
        var result: EventRead = EventRead.Available(emptyList())
        override fun hasAccess() = allowed
        override fun read(startMs: Long, endMs: Long): EventRead = if (allowed) result else EventRead.Unavailable("permission_missing")
    }
    private fun e(t: Long, kind: EventKind, pkg: String = "A", component: String = "Main") = UsageEvent(t, kind, pkg, component)
    private suspend fun total() = db.usageDao().observeApps().first().sumOf { it.durationMs }

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        db.usageDao().insertDevice(DeviceEntity("device-one", createdAt = 0, reportTimezone = "UTC"))
        db.usageDao().saveState(CollectionState(recordFromMs = 0, enabled = true))
        repo = UsageRepository(db, source, { it }, "personal", { clock }, { zone })
    }
    @After fun cleanup() { db.close() }

    @Test fun refreshTenTimesIsIdempotentForSessionsDaysAndRevisions() = runBlocking {
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(60_000, EventKind.PAUSE), e(60_000, EventKind.RESUME, "B"), e(90_000, EventKind.PAUSE, "B")))
        repeat(10) { assertTrue(repo.collect()) }
        assertEquals(90_000L, total())
        val a = db.usageDao().observeApps().first().first { it.packageName == "A" }
        assertEquals(1, db.usageDao().overlapping(a.identityId, 0, 100_000).size)
        assertEquals(1L, db.usageDao().overlapping(a.identityId, 0, 100_000).single().revision)
        assertEquals(60_000L, db.usageDao().observeAppDays(a.identityId).first().single().durationMs)
    }
    @Test fun emptyQueryAndRevocationPreserveExistingRecords() = runBlocking {
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(20_000, EventKind.PAUSE)))
        repo.collect()
        clock += 10_000
        source.result = EventRead.Available(emptyList())
        assertFalse(repo.collect())
        assertEquals(20_000L, total())
        source.allowed = false
        assertFalse(repo.collect())
        assertEquals(20_000L, total())
        assertEquals("不可用", db.usageDao().state()!!.status)
        assertEquals(100_000L, db.usageDao().state()!!.lastSuccessMs)
    }
    @Test fun observedStopCorrectsProvisionalTailInsteadOfAdding() = runBlocking {
        clock = 20_000
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME)))
        repo.collect()
        assertEquals(20_000L, total())
        clock = 30_000
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(15_000, EventKind.PAUSE)))
        repo.collect()
        assertEquals(15_000L, total())
        assertEquals(2L, db.usageDao().session(sessionId("device-one", "personal", "A", 0))!!.revision)
    }
    @Test fun overlappingAnchorsAreUnionedPerIdentity() = runBlocking {
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(10_000, EventKind.PAUSE)))
        repo.collect()
        source.result = EventRead.Available(listOf(e(8_000, EventKind.RESUME), e(15_000, EventKind.PAUSE)))
        repo.collect()
        assertEquals(15_000L, total())
    }
    @Test fun unknownRebootTailIsTombstoned() = runBlocking {
        clock = 20_000
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME)))
        repo.collect()
        clock = 100_000
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(60_000, EventKind.STARTUP)))
        repo.collect()
        assertEquals(0L, total())
        assertTrue(db.usageDao().session(sessionId("device-one", "personal", "A", 0))!!.deleted)
    }
    @Test fun missingPackageStillKeepsArchiveAndReinstallationKeepsIdentity() = runBlocking {
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(10_000, EventKind.PAUSE)))
        repo.collect()
        val original = db.usageDao().observeApps().first().single()
        source.result = EventRead.Available(emptyList())
        repo.collect()
        source.result = EventRead.Available(listOf(e(50_000, EventKind.RESUME), e(60_000, EventKind.PAUSE)))
        repo.collect()
        val restored = db.usageDao().observeApps().first().single()
        assertEquals(original.identityId, restored.identityId)
        assertEquals(20_000L, restored.durationMs)
    }
    @Test fun clockRollbackDoesNotCreateHugeDurations() = runBlocking {
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(10_000, EventKind.PAUSE)))
        repo.collect()
        clock = 50_000
        assertFalse(repo.collect())
        assertEquals(10_000L, total())
        assertEquals("存在缺口", db.usageDao().state()!!.status)
    }
    @Test fun concurrentManualAndWorkerRefreshesSerialize() = runBlocking {
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(10_000, EventKind.PAUSE)))
        coroutineScope { List(10) { async(Dispatchers.IO) { repo.collect() } }.awaitAll() }
        assertEquals(10_000L, total())
        assertEquals(1L, db.usageDao().session(sessionId("device-one", "personal", "A", 0))!!.revision)
    }
    @Test fun failedWriteRollsBackSessionsAndCheckpointTogether() = runBlocking {
        source.result = EventRead.Available(listOf(e(0, EventKind.RESUME), e(10_000, EventKind.PAUSE), e(20_000, EventKind.RESUME, "B"), e(30_000, EventKind.PAUSE, "B")))
        val failing = UsageRepository(db, source, { if (it == "B") error("injected failure") else it }, "personal", { clock }, { zone })
        try { failing.collect(); fail("Expected transaction failure") } catch (_: IllegalStateException) { }
        assertEquals(0L, total())
        assertNull(db.usageDao().state()!!.checkpointMs)
        assertNull(db.usageDao().identity("device-one", "personal", "A"))
    }
    @Test fun oldHistoryAndTransitionBucketsDoNotDoubleCountNewEvents() = runBlocking {
        val day = 86_400_000L
        val recordFrom = day + day / 2
        clock = 2 * day + 30_000
        db.usageDao().saveState(CollectionState(recordFromMs = recordFrom, enabled = true))
        source.result = EventRead.Available(listOf(e(recordFrom, EventKind.RESUME), e(2 * day + 10_000, EventKind.PAUSE)))
        val history = object : UsageHistorySource {
            override fun read(startMs: Long, endMs: Long) = HistoryRead.Available(listOf(
                HistoricalBucket("A", 0, day, 3_600_000), HistoricalBucket("A", day, 2 * day, 10_800_000)))
        }
        val withHistory = UsageRepository(db, source, { it }, "personal", { clock }, { zone }, history)
        withHistory.collect()
        assertEquals(10_000L, total()) // First collection automatically imports retained history.
        assertTrue(withHistory.importHistory(7))
        repeat(10) { withHistory.importHistory(7) }
        val app = db.usageDao().observeApps().first().single()
        assertEquals(10_000L, app.durationMs)
        assertEquals(14_400_000L, app.historicalMs)
        assertEquals(2, app.historicalBuckets)
        assertEquals(1L, db.usageDao().overlappingBuckets(app.identityId, 0, 3 * day, "").first().revision)
        // A later event refresh also respects the historical authority ranges.
        withHistory.collect()
        assertEquals(10_000L, total())
    }
    @Test fun emptyHistoricalQueryPreservesExistingBuckets() = runBlocking {
        var result: HistoryRead = HistoryRead.Available(listOf(HistoricalBucket("A", 0, 20_000, 5_000)))
        val history = object : UsageHistorySource { override fun read(startMs: Long, endMs: Long) = result }
        val withHistory = UsageRepository(db, source, { it }, "personal", { clock }, { zone }, history)
        assertTrue(withHistory.importHistory(7))
        result = HistoryRead.Available(emptyList())
        assertFalse(withHistory.importHistory(7))
        assertEquals(5_000L, db.usageDao().observeApps().first().single().historicalMs)
        result = HistoryRead.Unavailable("permission_missing")
        assertFalse(withHistory.importHistory(7))
        assertEquals(5_000L, db.usageDao().observeApps().first().single().historicalMs)
    }
    @Test fun earliestHistoryAutomaticallyImportsOnceEvenWithoutEvents() = runBlocking {
        clock = 1_000L * 86_400_000
        db.usageDao().saveState(CollectionState(recordFromMs = clock, enabled = false))
        var queries = 0
        var requestedStart = -1L
        val history = object : UsageHistorySource {
            override fun read(startMs: Long, endMs: Long): HistoryRead {
                queries++
                requestedStart = startMs
                return HistoryRead.Available(listOf(HistoricalBucket("A", 0, 86_400_000, 5_000)))
            }
        }
        val automatic = UsageRepository(db, source, { it }, "personal", { clock }, { zone }, history)
        assertFalse(automatic.collect()) // Event source is empty; old history is still usable.
        assertEquals(0L, requestedStart)
        assertEquals(5_000L, db.usageDao().observeApps().first().single().historicalMs)
        repeat(5) { automatic.collect() }
        assertEquals(1, queries)
        val app = db.usageDao().observeApps().first().single()
        assertEquals(1L, db.usageDao().overlappingBuckets(app.identityId, 0, clock, "").single().revision)
    }
    @Test fun earliestImportWaitsForAuthorizationAndEmptyResultCanBeRetriedManually() = runBlocking {
        db.usageDao().saveState(CollectionState(recordFromMs = 0, enabled = false))
        source.allowed = false
        var queries = 0
        val history = object : UsageHistorySource {
            override fun read(startMs: Long, endMs: Long): HistoryRead { queries++; return HistoryRead.Available(emptyList()) }
        }
        val automatic = UsageRepository(db, source, { it }, "personal", { clock }, { zone }, history)
        automatic.collect()
        assertEquals(0, queries)
        source.allowed = true
        automatic.collect()
        automatic.collect()
        assertEquals(1, queries)
        assertFalse(automatic.importHistory(0))
        assertEquals(2, queries)
        assertEquals("未返回历史", db.usageDao().historyState()!!.status)
    }
    @Test fun widerEarliestAggregateSupersedesDailyWithoutDeletingOrAddingIt() = runBlocking {
        clock = 1_000L * 86_400_000
        db.usageDao().saveState(CollectionState(recordFromMs = clock, enabled = true))
        var returned = listOf(HistoricalBucket("A", clock - 86_400_000, clock, 5_000))
        val history = object : UsageHistorySource { override fun read(startMs: Long, endMs: Long) = HistoryRead.Available(returned) }
        val automatic = UsageRepository(db, source, { it }, "personal", { clock }, { zone }, history)
        automatic.importHistory(7)
        returned = listOf(HistoricalBucket("A", 0, clock, 10_000, "android_usage_stats_best"))
        repeat(10) { automatic.importHistory(0) }
        val app = db.usageDao().observeApps().first().single()
        assertEquals(10_000L, app.historicalMs)
        assertEquals(1, app.historicalBuckets)
        val raw = db.usageDao().overlappingBuckets(app.identityId, 0, clock, "")
        assertEquals(2, raw.size) // Preserve both raw observations, count only the wider authority.
        assertTrue(raw.all { it.revision == 1L })
        assertEquals("android_usage_stats_best", db.usageDao().historyState()!!.source)
    }
}
