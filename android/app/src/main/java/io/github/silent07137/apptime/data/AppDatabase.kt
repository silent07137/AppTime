// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "devices")
data class DeviceEntity(@PrimaryKey val deviceId: String, val platform: String = "android", val createdAt: Long, val reportTimezone: String)

@Entity(tableName = "app_identities", foreignKeys = [ForeignKey(entity = DeviceEntity::class, parentColumns = ["deviceId"], childColumns = ["deviceId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["deviceId", "profileScope", "packageName"], unique = true)])
data class IdentityEntity(@PrimaryKey val identityId: String, val deviceId: String, val profileScope: String, val packageName: String, val displayName: String)

@Entity(tableName = "sessions", foreignKeys = [
    ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = DeviceEntity::class, parentColumns = ["deviceId"], childColumns = ["originDeviceId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["identityId", "startMs", "endMs"]), Index("originDeviceId")])
data class SessionEntity(
    @PrimaryKey val sessionId: String, val originDeviceId: String, val identityId: String,
    val anchorMs: Long, val startMs: Long, val endMs: Long, val durationMs: Long,
    val timezone: String, val utcOffsetSeconds: Int,
    val metric: String = "android_foreground", val source: String = "android_usage_events",
    val provisional: Boolean, val transitionEstimated: Boolean, val quality: String = "partial",
    val revision: Long = 1, val deleted: Boolean = false,
)

@Entity(tableName = "daily_usage", primaryKeys = ["identityId", "reportDate"],
    foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("reportDate")])
data class DailyEntity(val identityId: String, val reportDate: String, val timezone: String, val durationMs: Long, val quality: String = "partial")

@Entity(tableName = "collection_state")
data class CollectionState(@PrimaryKey val source: String = "android_usage_events", val recordFromMs: Long, val checkpointMs: Long? = null,
    val lastSuccessMs: Long? = null, val enabled: Boolean = false, val status: String = "未采集", val detail: String = "等待使用情况访问授权")

@Entity(tableName = "coverage", foreignKeys = [ForeignKey(entity = DeviceEntity::class, parentColumns = ["deviceId"], childColumns = ["deviceId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["startMs", "endMs"]), Index("deviceId")])
data class CoverageEntity(@PrimaryKey val coverageId: String, val deviceId: String, val startMs: Long, val endMs: Long, val status: String, val reason: String)

@Entity(tableName = "historical_buckets", foreignKeys = [
    ForeignKey(entity = DeviceEntity::class, parentColumns = ["deviceId"], childColumns = ["originDeviceId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["originDeviceId", "identityId", "source", "startMs"], unique = true), Index(value = ["identityId", "startMs", "endMs"])])
data class HistoricalBucketEntity(@PrimaryKey val bucketId: String, val originDeviceId: String, val identityId: String,
    val startMs: Long, val endMs: Long, val usageMs: Long, val source: String = "android_usage_stats_daily",
    val timezone: String = "unknown", val quality: String = "unknown", val revision: Long = 1)

@Entity(tableName = "history_import_state")
data class HistoryImportState(@PrimaryKey val source: String = "android_usage_stats_daily", val requestedStartMs: Long, val requestedEndMs: Long,
    val returnedStartMs: Long? = null, val returnedEndMs: Long? = null, val acceptedBuckets: Int = 0, val skippedBuckets: Int = 0,
    val lastAttemptMs: Long, val status: String, val detail: String)

data class AppSummary(val identityId: String, val packageName: String, val displayName: String, val durationMs: Long, val historicalMs: Long, val historicalBuckets: Int, val firstMs: Long?, val lastMs: Long?)
data class DaySummary(val reportDate: String, val durationMs: Long)

@Dao
interface UsageDao {
    @Query("SELECT * FROM devices LIMIT 1") suspend fun device(): DeviceEntity?
    @Insert suspend fun insertDevice(device: DeviceEntity)
    @Query("SELECT * FROM collection_state LIMIT 1") suspend fun state(): CollectionState?
    @Query("SELECT * FROM collection_state LIMIT 1") fun observeState(): Flow<CollectionState?>
    @Upsert suspend fun saveState(state: CollectionState)
    @Upsert suspend fun saveCoverage(coverage: CoverageEntity)
    @Query("SELECT * FROM historical_buckets WHERE bucketId = :id") suspend fun historicalBucket(id: String): HistoricalBucketEntity?
    @Query("SELECT * FROM historical_buckets WHERE identityId = :id AND startMs < :end AND endMs > :start AND bucketId != :exceptId")
    suspend fun overlappingBuckets(id: String, start: Long, end: Long, exceptId: String): List<HistoricalBucketEntity>
    @Upsert suspend fun saveBucket(bucket: HistoricalBucketEntity)
    @Query("SELECT EXISTS(SELECT 1 FROM historical_buckets)") suspend fun hasHistory(): Boolean
    @Upsert suspend fun saveHistoryState(state: HistoryImportState)
    @Query("DELETE FROM history_import_state") suspend fun clearHistoryState()
    @Query("SELECT * FROM history_import_state LIMIT 1") suspend fun historyState(): HistoryImportState?
    @Query("SELECT * FROM history_import_state LIMIT 1") fun observeHistoryState(): Flow<HistoryImportState?>
    @Query("SELECT COUNT(*) FROM coverage WHERE status = 'unavailable'") fun observeGapCount(): Flow<Int>
    @Query("SELECT * FROM app_identities WHERE deviceId = :deviceId AND profileScope = :profile AND packageName = :pkg")
    suspend fun identity(deviceId: String, profile: String, pkg: String): IdentityEntity?
    @Insert suspend fun insertIdentity(identity: IdentityEntity)
    @Query("SELECT * FROM sessions WHERE sessionId = :id") suspend fun session(id: String): SessionEntity?
    @Upsert suspend fun saveSession(session: SessionEntity)
    @Query("SELECT * FROM sessions WHERE identityId = :id AND deleted = 0 AND startMs < :end AND endMs > :start")
    suspend fun overlapping(id: String, start: Long, end: Long): List<SessionEntity>
    @Query("DELETE FROM daily_usage WHERE identityId = :id AND reportDate >= :fromDate AND reportDate <= :toDate")
    suspend fun clearDays(id: String, fromDate: String, toDate: String)
    @Upsert suspend fun saveDays(days: List<DailyEntity>)
    @Query("""WITH effective_history AS (
        SELECT h.* FROM historical_buckets h WHERE NOT EXISTS (
            SELECT 1 FROM historical_buckets o WHERE o.identityId = h.identityId AND o.bucketId != h.bucketId
            AND o.startMs <= h.startMs AND o.endMs >= h.endMs
            AND (o.startMs < h.startMs OR o.endMs > h.endMs OR
                (o.source = 'android_usage_stats_best' AND h.source != 'android_usage_stats_best'))))
        SELECT i.identityId, i.packageName, i.displayName,
        COALESCE((SELECT SUM(d.durationMs) FROM daily_usage d WHERE d.identityId = i.identityId), 0) AS durationMs,
        COALESCE((SELECT SUM(h.usageMs) FROM effective_history h WHERE h.identityId = i.identityId), 0) AS historicalMs,
        (SELECT COUNT(*) FROM effective_history h WHERE h.identityId = i.identityId) AS historicalBuckets,
        (SELECT MIN(s.startMs) FROM sessions s WHERE s.identityId = i.identityId AND s.deleted = 0) AS firstMs,
        (SELECT MAX(s.endMs) FROM sessions s WHERE s.identityId = i.identityId AND s.deleted = 0) AS lastMs
        FROM app_identities i ORDER BY durationMs + historicalMs DESC, i.packageName""")
    fun observeApps(): Flow<List<AppSummary>>
    @Query("SELECT reportDate, SUM(durationMs) AS durationMs FROM daily_usage WHERE reportDate >= :fromDate GROUP BY reportDate ORDER BY reportDate DESC")
    fun observeDays(fromDate: String): Flow<List<DaySummary>>
    @Query("SELECT reportDate, durationMs FROM daily_usage WHERE identityId = :id ORDER BY reportDate DESC LIMIT 31")
    fun observeAppDays(id: String): Flow<List<DaySummary>>
}

@Database(entities = [DeviceEntity::class, IdentityEntity::class, SessionEntity::class, DailyEntity::class, CollectionState::class, CoverageEntity::class, HistoricalBucketEntity::class, HistoryImportState::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS historical_buckets (bucketId TEXT NOT NULL PRIMARY KEY, originDeviceId TEXT NOT NULL, identityId TEXT NOT NULL,
                    startMs INTEGER NOT NULL, endMs INTEGER NOT NULL, usageMs INTEGER NOT NULL, source TEXT NOT NULL, timezone TEXT NOT NULL, quality TEXT NOT NULL, revision INTEGER NOT NULL,
                    FOREIGN KEY(originDeviceId) REFERENCES devices(deviceId) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_historical_buckets_originDeviceId_identityId_source_startMs ON historical_buckets(originDeviceId, identityId, source, startMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_historical_buckets_identityId_startMs_endMs ON historical_buckets(identityId, startMs, endMs)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS history_import_state (source TEXT NOT NULL PRIMARY KEY, requestedStartMs INTEGER NOT NULL, requestedEndMs INTEGER NOT NULL,
                    returnedStartMs INTEGER, returnedEndMs INTEGER, acceptedBuckets INTEGER NOT NULL, skippedBuckets INTEGER NOT NULL, lastAttemptMs INTEGER NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL)""")
            }
        }
    }
}
