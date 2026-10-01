// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silent07137.apptime.core.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagementTest {
    private lateinit var db: AppDatabase
    private var clock = 100_000L
    private var events = listOf(UsageEvent(0, EventKind.RESUME, "A", "Main"), UsageEvent(60_000, EventKind.PAUSE, "A", "Main"))
    private val source = object : UsageEventSource {
        override fun hasAccess() = true
        override fun read(startMs: Long, endMs: Long) = EventRead.Available(events)
    }
    private fun repo(history: UsageHistorySource? = null, inspect: ((String) -> AppInspection)? = null) =
        UsageRepository(db, source, { it }, "personal", { clock }, { ZoneId.of("UTC") }, history, inspect)
    private val date = LocalDate.of(1970, 1, 1)
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        db.usageDao().insertDevice(DeviceEntity("device-one", createdAt = 0, reportTimezone = "UTC"))
        db.usageDao().saveState(CollectionState(recordFromMs = 0, enabled = true))
    }
    @After fun close() { db.close() }

    @Test fun preferencesAndDateCategoryFiltersPreserveHiddenAppTotals() = runBlocking {
        val repo = repo(); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        repo.updatePreference(id, "学习", true)
        assertTrue(db.usageDao().observeApps().first().single().hidden)
        assertEquals("学习", db.usageDao().observeApps().first().single().category)
        assertEquals(60_000L, db.usageDao().observeDays(date.toString(), date.toString(), category = "学习").first().single().durationMs)
        assertTrue(db.usageDao().observeDays(date.toString(), date.toString(), category = "游戏").first().isEmpty())
        assertTrue(db.usageDao().observeDays("1970-01-02").first().isEmpty())
        assertEquals(60_000L, db.usageDao().observeDayApps(date.toString(), id).first().single().durationMs)
    }
    @Test fun manualChangesDoNotInventSessionsAndCanBeRemoved() = runBlocking {
        val repo = repo(); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        repo.addAdjustment(id, date, 120_000, "补记")
        assertEquals(180_000L, db.usageDao().observeAppDays(id).first().single().durationMs)
        assertEquals(120_000L, db.usageDao().observeApps().first().single().adjustmentMs)
        assertEquals(60_000L, db.usageDao().observeDaySessions(0, 86_400_000).first().single().let { it.endMs - it.startMs })
        repeat(3) { repo.collect() }
        assertEquals(180_000L, db.usageDao().appDay(id, date.toString())!!.durationMs)
        repo.removeAdjustment(db.usageDao().observeAdjustments(id).first().single().adjustmentId)
        assertEquals(60_000L, db.usageDao().appDay(id, date.toString())!!.durationMs)
    }
    @Test fun negativeAdjustmentsValidateAndDependentPositiveEntriesCannotBeRemoved() = runBlocking {
        val repo = repo(); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        try { repo.addAdjustment(id, date, -120_000, ""); fail("negative day") } catch (_: IllegalArgumentException) { }
        repo.addAdjustment(id, date, 120_000, "")
        val positive = db.usageDao().observeAdjustments(id).first().single()
        repo.addAdjustment(id, date, -180_000, "")
        try { repo.removeAdjustment(positive.adjustmentId); fail("dependent deduction") } catch (_: IllegalArgumentException) { }
        assertEquals(0L, db.usageDao().appDay(id, date.toString())!!.durationMs)
    }
    @Test fun ignoredIntervalsStayExcludedAfterReenableAndRollingRequery() = runBlocking {
        events = listOf(UsageEvent(0, EventKind.RESUME, "A", "Main")); clock = 10_000
        val repo = repo(); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        repo.setIgnored(id, true); clock = 20_000; repo.collect()
        assertEquals(10_000L, db.usageDao().observeApps().first().single().recordedMs)
        repo.setIgnored(id, false); clock = 30_000
        events = events + UsageEvent(30_000, EventKind.PAUSE, "A", "Main")
        repeat(3) { repo.collect() }
        val rows = db.usageDao().observeDaySessions(0, 40_000).first()
        assertEquals(20_000L, db.usageDao().observeApps().first().single().recordedMs)
        assertEquals(listOf(0L to 10_000L, 20_000L to 30_000L), rows.map { it.startMs to it.endMs })
        assertFalse(db.usageDao().observeApps().first().single().ignored)
    }
    @Test fun rebootDiscardsOnlyTheOpenFragmentAfterIgnoredPeriod() = runBlocking {
        events = listOf(UsageEvent(0, EventKind.RESUME, "A", "Main")); clock = 10_000
        val repo = repo(); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        repo.setIgnored(id, true); clock = 20_000; repo.setIgnored(id, false)
        clock = 30_000; repo.collect()
        assertEquals(20_000L, db.usageDao().observeApps().first().single().recordedMs)
        events = events + UsageEvent(40_000, EventKind.STARTUP); clock = 50_000
        repo.collect()
        assertEquals(10_000L, db.usageDao().observeApps().first().single().recordedMs)
        assertEquals(1, db.usageDao().observeDaySessions(0, 60_000).first().size)
    }
    @Test fun laterProvisionalCorrectionCannotDisplayNegativeDailyTime() = runBlocking {
        events = listOf(UsageEvent(0, EventKind.RESUME, "A", "Main")); clock = 120_000
        val repo = repo(); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        repo.addAdjustment(id, date, -60_000, "")
        events = events + UsageEvent(30_000, EventKind.PAUSE, "A", "Main")
        repo.collect()
        assertEquals(0L, db.usageDao().appDay(id, date.toString())!!.durationMs)
        assertEquals(0L, db.usageDao().observeDays(date.toString()).first().single().durationMs)
        assertEquals(0L, db.usageDao().observeDayApps(date.toString()).first().single().durationMs)
        assertEquals(30_000L, db.usageDao().observeDaySessions(0, 180_000).first().single().let { it.endMs - it.startMs })
    }
    @Test fun dailySystemBucketsCrossingIgnorePeriodAreNotReimported() = runBlocking {
        val plain = repo(); plain.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        plain.setIgnored(id, true)
        clock += 60_000
        val history = object : UsageHistorySource {
            override fun read(startMs: Long, endMs: Long) = HistoryRead.Available(emptyList())
            override fun readDaily(startMs: Long, endMs: Long) = HistoryRead.Available(listOf(HistoricalBucket("A", 0, 86_400_000, 500_000, "android_usage_stats_daily")))
        }
        repo(history).collect()
        assertNull(db.usageDao().systemDay(id, date.toString()))
        assertEquals(60_000L, db.usageDao().appDay(id, date.toString())!!.durationMs)
    }
    @Test fun frozenSystemSnapshotAddsOnlyResumedEventsAfterIgnore() = runBlocking {
        events = listOf(UsageEvent(0, EventKind.RESUME, "A", "Main")); clock = 10_000
        val history = object : UsageHistorySource {
            override fun read(startMs: Long, endMs: Long) = HistoryRead.Available(listOf(HistoricalBucket("A", 0, 86_400_000, 8_000, "android_usage_stats_best")))
            override fun readDaily(startMs: Long, endMs: Long) = HistoryRead.Available(listOf(HistoricalBucket("A", 0, 86_400_000, 8_000)))
        }
        val repo = repo(history); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        repo.setIgnored(id, true); clock = 20_000; repo.collect(); repo.setIgnored(id, false)
        clock = 30_000; events = events + UsageEvent(30_000, EventKind.PAUSE, "A", "Main")
        repeat(3) { repo.collect() }
        assertEquals(18_000L, db.usageDao().appDay(id, date.toString())!!.durationMs)
        assertEquals(8_000L, db.usageDao().systemDay(id, date.toString())!!.durationMs)
        val app = db.usageDao().observeApps().first().single()
        assertEquals(20_000L, app.recordedMs)
        assertEquals(18_000L, app.durationMs + app.historicalMs)
    }
    @Test fun signatureChangesNeedConfirmationAndUnknownVisibilityRetainsArchive() = runBlocking {
        var inspection = AppInspection("原应用", true, listOf("old"))
        val repo = repo(inspect = { inspection }); repo.collect()
        val id = db.usageDao().observeApps().first().single().identityId
        inspection = AppInspection("同包异签名", true, listOf("other"))
        events = listOf(UsageEvent(70_000, EventKind.RESUME, "A", "Main"), UsageEvent(90_000, EventKind.PAUSE, "A", "Main"))
        repo.collect()
        assertTrue(db.usageDao().observeApps().first().single().signingChanged)
        assertEquals(60_000L, db.usageDao().observeApps().first().single().recordedMs)
        repo.acceptSigning(id); repo.collect()
        assertEquals(80_000L, db.usageDao().observeApps().first().single().recordedMs)
        inspection = AppInspection(null, false); repo.collect()
        val app = db.usageDao().observeApps().first().single()
        assertEquals("unknown", app.installationStatus)
        assertEquals(80_000L, app.recordedMs)
        assertEquals(id, app.identityId)
    }
}
