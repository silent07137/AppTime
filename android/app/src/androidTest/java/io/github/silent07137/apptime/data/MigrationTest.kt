// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silent07137.apptime.core.EventRead
import io.github.silent07137.apptime.core.UsageEventSource
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())

    @Test fun upgradeFromFiveRepairsShiftedDaysOfflineWithoutChangingRawArchive() = runBlocking {
        val date = java.time.LocalDate.of(2026, 10, 5)
        val zone = ZoneId.of("Asia/Shanghai")
        val midnight = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val start = midnight + 3_600_000L
        val end = start + 187 * 60_000L
        val shifted = midnight + (17 * 60 + 35) * 60_000L
        helper.createDatabase("migration-v5-test", 5).apply {
            execSQL("INSERT INTO devices VALUES ('device-one','android',0,'Asia/Shanghai')")
            execSQL("INSERT INTO app_identities VALUES ('identity-one','device-one','personal','A','A')")
            execSQL("INSERT INTO app_identities VALUES ('identity-two','device-one','personal','B','B')")
            execSQL("""INSERT INTO sessions (sessionId,originDeviceId,identityId,anchorMs,startMs,endMs,durationMs,timezone,utcOffsetSeconds,metric,source,provisional,transitionEstimated,quality,revision,deleted)
                VALUES ('session-one','device-one','identity-one',$start,$start,$end,11220000,'Asia/Shanghai',28800,'android_foreground','android_usage_events',0,0,'partial',7,0)""")
            execSQL("INSERT INTO system_daily_usage VALUES ('identity-one','2026-10-05','Asia/Shanghai',3900000,$shifted,${shifted + 65 * 60_000L},${shifted + 70 * 60_000L})")
            execSQL("INSERT INTO system_daily_usage VALUES ('identity-two','2026-10-05','Asia/Shanghai',60000,$midnight,${midnight + 86_400_000L},${midnight + 86_400_000L})")
            execSQL("INSERT INTO event_daily_usage VALUES ('identity-one','2026-10-05','Asia/Shanghai',11220000)")
            execSQL("INSERT INTO daily_sync_state VALUES (1,1,1,$end)")
            execSQL("INSERT INTO collection_state VALUES ('android_usage_events',$midnight,$end,$end,1,'partial','test')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v5-test", 6, true, AppDatabase.MIGRATION_5_6).use { migrated ->
            migrated.query("SELECT durationMs FROM app_day_totals WHERE identityId='identity-one'").use { assertTrue(it.moveToFirst()); assertEquals(11_220_000L, it.getLong(0)) }
            migrated.query("SELECT eventRebuilt FROM daily_sync_state").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, "migration-v5-test")
            .addMigrations(AppDatabase.MIGRATION_5_6).build()
        try {
            val unavailable = object : UsageEventSource {
                override fun hasAccess() = false
                override fun read(startMs: Long, endMs: Long) = EventRead.Unavailable("offline")
            }
            val repo = UsageRepository(db, unavailable, { it }, "personal", { midnight + 86_400_000L }, { zone })
            assertFalse(repo.collect())
            assertEquals(11_220_000L, db.usageDao().appDay("identity-one", "2026-10-05")!!.durationMs)
            assertEquals("events", db.usageDao().appDay("identity-one", "2026-10-05")!!.source)
            assertEquals(60_000L, db.usageDao().appDay("identity-two", "2026-10-05")!!.durationMs)
            assertEquals("system", db.usageDao().appDay("identity-two", "2026-10-05")!!.source)
            assertEquals(2, db.usageDao().allSystemDays().size)
            assertEquals(3_900_000L, db.usageDao().systemDay("identity-one", "2026-10-05")!!.durationMs)
            assertEquals(7L, db.usageDao().allSessions().single().revision)
            assertEquals(end, db.usageDao().state()!!.checkpointMs)
        } finally { db.close() }
    }

    @Test fun upgradeFromFourPreservesManagementAndAddsMergeRevisionsAndCollectorIdentity() {
        helper.createDatabase("migration-v4-test", 4).apply {
            execSQL("INSERT INTO devices VALUES ('device-one','android',0,'UTC')")
            execSQL("INSERT INTO app_identities VALUES ('identity-one','device-one','personal','A','A')")
            execSQL("INSERT INTO app_preferences VALUES ('identity-one','学习',1,1)")
            execSQL("INSERT INTO ignore_periods VALUES ('ignore-one','identity-one',1000,NULL)")
            execSQL("INSERT INTO manual_adjustments VALUES ('adjust-one','identity-one','1970-01-01','UTC',60000,'note',1000)")
            execSQL("INSERT INTO coverage VALUES ('query:0','device-one',0,1000,'partial','test')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v4-test", 5, true, AppDatabase.MIGRATION_4_5).use { migrated ->
            migrated.query("SELECT category,hidden,ignored,revision FROM app_preferences").use { assertTrue(it.moveToFirst()); assertEquals("学习", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals(1, it.getInt(2)); assertEquals(1L, it.getLong(3)) }
            migrated.query("SELECT endMs,revision FROM ignore_periods").use { assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals(1L, it.getLong(1)) }
            migrated.query("SELECT deltaMs,revision,deleted FROM manual_adjustments").use { assertTrue(it.moveToFirst()); assertEquals(60000L, it.getLong(0)); assertEquals(1L, it.getLong(1)); assertEquals(0, it.getInt(2)) }
            migrated.query("SELECT localDeviceId FROM local_archive_state").use { assertTrue(it.moveToFirst()); assertEquals("device-one", it.getString(0)) }
            migrated.query("SELECT coverageId FROM coverage").use { assertTrue(it.moveToFirst()); assertEquals("device-one:query:0", it.getString(0)) }
            migrated.query("SELECT durationMs FROM app_day_totals").use { assertTrue(it.moveToFirst()); assertEquals(60000L, it.getLong(0)) }
        }
    }

    @Test fun upgradeFromOnePreservesSessionsDailyCacheAndCheckpoint() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO devices VALUES ('device-one','android',0,'UTC')")
            execSQL("INSERT INTO app_identities VALUES ('identity-one','device-one','personal','A','A')")
            execSQL("""INSERT INTO sessions (sessionId,originDeviceId,identityId,anchorMs,startMs,endMs,durationMs,timezone,utcOffsetSeconds,metric,source,provisional,transitionEstimated,quality,revision,deleted)
                VALUES ('session-one','device-one','identity-one',0,0,15000,15000,'UTC',0,'android_foreground','android_usage_events',0,0,'partial',1,0)""")
            execSQL("INSERT INTO daily_usage VALUES ('identity-one','1970-01-01','UTC',15000,'partial')")
            execSQL("INSERT INTO collection_state (source,recordFromMs,checkpointMs,lastSuccessMs,enabled,status,detail) VALUES ('android_usage_events',0,15000,15000,1,'部分可用','fixture')")
            close()
        }
        val migrated = helper.runMigrationsAndValidate("migration-test", 6, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
        migrated.query("SELECT durationMs FROM sessions WHERE sessionId='session-one'").use { assertTrue(it.moveToFirst()); assertEquals(15_000L, it.getLong(0)) }
        migrated.query("SELECT durationMs FROM daily_usage").use { assertTrue(it.moveToFirst()); assertEquals(15_000L, it.getLong(0)) }
        migrated.query("SELECT checkpointMs FROM collection_state").use { assertTrue(it.moveToFirst()); assertEquals(15_000L, it.getLong(0)) }
        migrated.query("SELECT COUNT(*) FROM historical_buckets").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM event_daily_usage").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        migrated.query("SELECT COUNT(*) FROM system_daily_usage").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        migrated.close()
    }

    @Test fun upgradeFromTwoPreservesOldHistoryAndBackfillsRecordedDays() = runBlocking {
        helper.createDatabase("migration-v2-test", 2).apply {
            execSQL("INSERT INTO devices VALUES ('device-one','android',0,'UTC')")
            execSQL("INSERT INTO app_identities VALUES ('identity-one','device-one','personal','A','A')")
            execSQL("INSERT INTO historical_buckets VALUES ('bucket-one','device-one','identity-one',0,86400000,5000,'android_usage_stats_best','unknown','unknown',1)")
            execSQL("""INSERT INTO sessions (sessionId,originDeviceId,identityId,anchorMs,startMs,endMs,durationMs,timezone,utcOffsetSeconds,metric,source,provisional,transitionEstimated,quality,revision,deleted)
                VALUES ('session-one','device-one','identity-one',1000,1000,6000,5000,'UTC',0,'android_foreground','android_usage_events',0,0,'partial',1,0)""")
            close()
        }
        val migrated = helper.runMigrationsAndValidate("migration-v2-test", 6, true, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6)
        migrated.query("SELECT usageMs FROM historical_buckets WHERE bucketId='bucket-one'").use { assertTrue(it.moveToFirst()); assertEquals(5000L, it.getLong(0)) }
        migrated.query("SELECT durationMs FROM sessions WHERE sessionId='session-one'").use { assertTrue(it.moveToFirst()); assertEquals(5000L, it.getLong(0)) }
        migrated.close()
        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, "migration-v2-test")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6).build()
        val source = object : UsageEventSource {
            override fun hasAccess() = true
            override fun read(startMs: Long, endMs: Long) = EventRead.Available(emptyList())
        }
        val repo = UsageRepository(db, source, { it }, "personal", { 10_000L }, { ZoneId.of("UTC") })
        assertFalse(repo.collect())
        val app = db.usageDao().observeApps().first().single()
        assertEquals(5_000L, app.recordedMs)
        assertEquals(5_000L, db.usageDao().observeAppDays(app.identityId).first().single().durationMs)
        db.close()
    }
}
