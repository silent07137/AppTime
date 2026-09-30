// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
        val migrated = helper.runMigrationsAndValidate("migration-test", 2, true, AppDatabase.MIGRATION_1_2)
        migrated.query("SELECT durationMs FROM sessions WHERE sessionId='session-one'").use { assertTrue(it.moveToFirst()); assertEquals(15_000L, it.getLong(0)) }
        migrated.query("SELECT durationMs FROM daily_usage").use { assertTrue(it.moveToFirst()); assertEquals(15_000L, it.getLong(0)) }
        migrated.query("SELECT checkpointMs FROM collection_state").use { assertTrue(it.moveToFirst()); assertEquals(15_000L, it.getLong(0)) }
        migrated.query("SELECT COUNT(*) FROM historical_buckets").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        migrated.close()
    }
}
