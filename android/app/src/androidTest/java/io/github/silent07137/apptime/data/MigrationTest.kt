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
        val migrated = helper.runMigrationsAndValidate("migration-test", 3, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
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
        val migrated = helper.runMigrationsAndValidate("migration-v2-test", 3, true, AppDatabase.MIGRATION_2_3)
        migrated.query("SELECT usageMs FROM historical_buckets WHERE bucketId='bucket-one'").use { assertTrue(it.moveToFirst()); assertEquals(5000L, it.getLong(0)) }
        migrated.query("SELECT durationMs FROM sessions WHERE sessionId='session-one'").use { assertTrue(it.moveToFirst()); assertEquals(5000L, it.getLong(0)) }
        migrated.close()
        val db = Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, "migration-v2-test")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3).build()
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
