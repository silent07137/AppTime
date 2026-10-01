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

@Entity(tableName = "event_daily_usage", primaryKeys = ["identityId", "reportDate"],
    foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("reportDate")])
data class EventDailyEntity(val identityId: String, val reportDate: String, val timezone: String, val durationMs: Long)

@Entity(tableName = "system_daily_usage", primaryKeys = ["identityId", "reportDate"],
    foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("reportDate")])
data class SystemDailyEntity(val identityId: String, val reportDate: String, val timezone: String, val durationMs: Long,
    val bucketStartMs: Long, val bucketEndMs: Long, val observedAtMs: Long)

@Entity(tableName = "system_daily_supplements", primaryKeys = ["identityId", "reportDate"],
    foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)])
data class SystemDaySupplementEntity(val identityId: String, val reportDate: String, val durationMs: Long)

@Entity(tableName = "daily_sync_state")
data class DailySyncState(@PrimaryKey val id: Int = 1, val initialDone: Boolean = false, val eventRebuilt: Boolean = false, val lastAttemptMs: Long? = null)

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

@Entity(tableName = "app_preferences", foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)])
data class AppPreferenceEntity(@PrimaryKey val identityId: String, val category: String = "未分类", val hidden: Boolean = false, val ignored: Boolean = false)

@Entity(tableName = "ignore_periods", foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("identityId")])
data class IgnorePeriodEntity(@PrimaryKey val periodId: String, val identityId: String, val startMs: Long, val endMs: Long? = null)

@Entity(tableName = "manual_adjustments", foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["identityId", "reportDate"])])
data class AdjustmentEntity(@PrimaryKey val adjustmentId: String, val identityId: String, val reportDate: String, val timezone: String, val deltaMs: Long, val note: String, val createdAtMs: Long)

@Entity(tableName = "app_observations", foreignKeys = [ForeignKey(entity = IdentityEntity::class, parentColumns = ["identityId"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)])
data class AppObservationEntity(@PrimaryKey val identityId: String, val status: String, val signingDigest: String?, val signingChanged: Boolean, val observedAtMs: Long)

const val DAILY_VIEW = """SELECT identityId, reportDate, SUM(durationMs) AS durationMs,
    CASE WHEN COUNT(DISTINCT source) > 1 THEN 'mixed' ELSE MIN(source) END AS source FROM (
        SELECT s.identityId, s.reportDate, s.durationMs + COALESCE(p.durationMs, 0) AS durationMs,
            CASE WHEN COALESCE(p.durationMs, 0) > 0 THEN 'mixed' ELSE 'system' END AS source FROM system_daily_usage s
        LEFT JOIN system_daily_supplements p ON p.identityId = s.identityId AND p.reportDate = s.reportDate
        UNION ALL SELECT e.identityId, e.reportDate, e.durationMs, 'events' AS source FROM event_daily_usage e
        WHERE NOT EXISTS (SELECT 1 FROM system_daily_usage s WHERE s.identityId = e.identityId AND s.reportDate = e.reportDate)
        UNION ALL SELECT identityId, reportDate, deltaMs AS durationMs, 'manual' AS source FROM manual_adjustments
    ) GROUP BY identityId, reportDate"""

@DatabaseView(value = DAILY_VIEW, viewName = "app_day_totals")
data class AppDayTotal(val identityId: String, val reportDate: String, val durationMs: Long, val source: String)

data class AppSummary(val identityId: String, val packageName: String, val displayName: String, val durationMs: Long, val recordedMs: Long, val historicalMs: Long, val historicalBuckets: Int, val firstMs: Long?, val lastMs: Long?,
    val adjustmentMs: Long, val category: String, val hidden: Boolean, val ignored: Boolean, val installationStatus: String, val signingChanged: Boolean)

data class DayAppUsage(val identityId: String, val packageName: String, val displayName: String, val category: String, val durationMs: Long, val source: String)
data class DaySession(val identityId: String, val packageName: String, val displayName: String, val startMs: Long, val endMs: Long, val provisional: Boolean, val transitionEstimated: Boolean)
data class DaySummary(val reportDate: String, val durationMs: Long, val source: String)

@Dao
interface UsageDao {
    @Query("SELECT * FROM app_identities") suspend fun identities(): List<IdentityEntity>
    @Update suspend fun updateIdentity(identity: IdentityEntity)
    @Query("SELECT * FROM app_preferences WHERE identityId = :id") suspend fun preference(id: String): AppPreferenceEntity?
    @Upsert suspend fun savePreference(preference: AppPreferenceEntity)
    @Upsert suspend fun saveIgnorePeriod(period: IgnorePeriodEntity)
    @Query("SELECT * FROM ignore_periods WHERE identityId = :id ORDER BY startMs") suspend fun ignorePeriods(id: String): List<IgnorePeriodEntity>
    @Query("SELECT * FROM app_observations WHERE identityId = :id") suspend fun observation(id: String): AppObservationEntity?
    @Upsert suspend fun saveObservation(observation: AppObservationEntity)
    @Upsert suspend fun saveAdjustment(adjustment: AdjustmentEntity)
    @Query("SELECT * FROM manual_adjustments WHERE adjustmentId = :id") suspend fun adjustment(id: String): AdjustmentEntity?
    @Query("DELETE FROM manual_adjustments WHERE adjustmentId = :id") suspend fun deleteAdjustment(id: String)
    @Query("SELECT * FROM manual_adjustments WHERE identityId = :id ORDER BY createdAtMs DESC") fun observeAdjustments(id: String): Flow<List<AdjustmentEntity>>
    @Query("SELECT identityId, reportDate, MAX(0, durationMs) AS durationMs, source FROM app_day_totals WHERE identityId = :id AND reportDate = :date") suspend fun appDay(id: String, date: String): AppDayTotal?
    @Query("""SELECT v.identityId, i.packageName, i.displayName, COALESCE(p.category, '未分类') AS category, MAX(0, v.durationMs) AS durationMs, v.source
        FROM app_day_totals v JOIN app_identities i ON i.identityId = v.identityId LEFT JOIN app_preferences p ON p.identityId = v.identityId
        WHERE reportDate = :date AND (:id IS NULL OR v.identityId = :id) AND (:category IS NULL OR COALESCE(p.category, '未分类') = :category)
        ORDER BY v.durationMs DESC, i.packageName""")
    fun observeDayApps(date: String, id: String? = null, category: String? = null): Flow<List<DayAppUsage>>
    @Query("""SELECT s.identityId, i.packageName, i.displayName, s.startMs, s.endMs, s.provisional, s.transitionEstimated
        FROM sessions s JOIN app_identities i ON i.identityId = s.identityId LEFT JOIN app_preferences p ON p.identityId = s.identityId
        WHERE s.deleted = 0 AND s.startMs < :end AND s.endMs > :start AND (:id IS NULL OR s.identityId = :id)
        AND (:category IS NULL OR COALESCE(p.category, '未分类') = :category) ORDER BY s.startMs""")
    fun observeDaySessions(start: Long, end: Long, id: String? = null, category: String? = null): Flow<List<DaySession>>
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
    @Query("SELECT * FROM sessions WHERE identityId = :id AND anchorMs = :anchor AND deleted = 0") suspend fun sessionsAtAnchor(id: String, anchor: Long): List<SessionEntity>
    @Upsert suspend fun saveSession(session: SessionEntity)
    @Query("SELECT * FROM sessions WHERE identityId = :id AND deleted = 0 AND startMs < :end AND endMs > :start")
    suspend fun overlapping(id: String, start: Long, end: Long): List<SessionEntity>
    @Query("DELETE FROM daily_usage WHERE identityId = :id AND reportDate >= :fromDate AND reportDate <= :toDate")
    suspend fun clearDays(id: String, fromDate: String, toDate: String)
    @Upsert suspend fun saveDays(days: List<DailyEntity>)
    @Query("DELETE FROM event_daily_usage WHERE identityId = :id AND reportDate >= :fromDate AND reportDate <= :toDate")
    suspend fun clearEventDays(id: String, fromDate: String, toDate: String)
    @Upsert suspend fun saveEventDays(days: List<EventDailyEntity>)
    @Query("SELECT * FROM sessions WHERE deleted = 0 ORDER BY identityId, startMs") suspend fun allSessions(): List<SessionEntity>
    @Query("SELECT * FROM daily_sync_state WHERE id = 1") suspend fun dailySyncState(): DailySyncState?
    @Upsert suspend fun saveDailySyncState(state: DailySyncState)
    @Query("SELECT * FROM system_daily_usage WHERE identityId = :id AND reportDate = :date") suspend fun systemDay(id: String, date: String): SystemDailyEntity?
    @Upsert suspend fun saveSystemDays(days: List<SystemDailyEntity>)
    @Query("SELECT * FROM system_daily_usage WHERE identityId = :id AND reportDate >= :fromDate AND reportDate <= :toDate")
    suspend fun systemDays(id: String, fromDate: String, toDate: String): List<SystemDailyEntity>
    @Upsert suspend fun saveSystemSupplement(day: SystemDaySupplementEntity)
    @Query("""WITH effective_history AS (
        SELECT h.* FROM historical_buckets h WHERE NOT EXISTS (
            SELECT 1 FROM historical_buckets o WHERE o.identityId = h.identityId AND o.bucketId != h.bucketId
            AND o.startMs <= h.startMs AND o.endMs >= h.endMs
            AND (o.startMs < h.startMs OR o.endMs > h.endMs OR
                (o.source = 'android_usage_stats_best' AND h.source != 'android_usage_stats_best'))))
        SELECT i.identityId, i.packageName, i.displayName,
        COALESCE((SELECT SUM(d.durationMs) FROM daily_usage d WHERE d.identityId = i.identityId), 0) AS durationMs,
        COALESCE((SELECT SUM(e.durationMs) FROM event_daily_usage e WHERE e.identityId = i.identityId), 0) AS recordedMs,
        COALESCE((SELECT SUM(h.usageMs) FROM effective_history h WHERE h.identityId = i.identityId), 0) AS historicalMs,
        (SELECT COUNT(*) FROM effective_history h WHERE h.identityId = i.identityId) AS historicalBuckets,
        (SELECT MIN(s.startMs) FROM sessions s WHERE s.identityId = i.identityId AND s.deleted = 0) AS firstMs,
        (SELECT MAX(s.endMs) FROM sessions s WHERE s.identityId = i.identityId AND s.deleted = 0) AS lastMs,
        COALESCE((SELECT SUM(a.deltaMs) FROM manual_adjustments a WHERE a.identityId = i.identityId), 0) AS adjustmentMs,
        COALESCE(p.category, '未分类') AS category, COALESCE(p.hidden, 0) AS hidden, COALESCE(p.ignored, 0) AS ignored,
        COALESCE(o.status, 'unknown') AS installationStatus, COALESCE(o.signingChanged, 0) AS signingChanged
        FROM app_identities i LEFT JOIN app_preferences p ON p.identityId = i.identityId LEFT JOIN app_observations o ON o.identityId = i.identityId
        ORDER BY durationMs + historicalMs + adjustmentMs DESC, i.packageName""")
    fun observeApps(): Flow<List<AppSummary>>
    @Query("""SELECT reportDate, SUM(MAX(0, durationMs)) AS durationMs,
        CASE WHEN COUNT(DISTINCT source) > 1 THEN 'mixed' ELSE MIN(source) END AS source
        FROM app_day_totals v LEFT JOIN app_preferences p ON p.identityId = v.identityId
        WHERE reportDate >= :fromDate AND reportDate <= :toDate AND (:id IS NULL OR v.identityId = :id)
        AND (:category IS NULL OR COALESCE(p.category, '未分类') = :category) GROUP BY reportDate ORDER BY reportDate DESC""")
    fun observeDays(fromDate: String, toDate: String = "9999-12-31", id: String? = null, category: String? = null): Flow<List<DaySummary>>
    @Query("SELECT reportDate, MAX(0, durationMs) AS durationMs, source FROM app_day_totals WHERE identityId = :id ORDER BY reportDate DESC")
    fun observeAppDays(id: String): Flow<List<DaySummary>>
}

@Database(entities = [DeviceEntity::class, IdentityEntity::class, SessionEntity::class, DailyEntity::class, EventDailyEntity::class, SystemDailyEntity::class, DailySyncState::class, CollectionState::class, CoverageEntity::class, HistoricalBucketEntity::class, HistoryImportState::class,
    AppPreferenceEntity::class, IgnorePeriodEntity::class, AdjustmentEntity::class, AppObservationEntity::class, SystemDaySupplementEntity::class], views = [AppDayTotal::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun usageDao(): UsageDao
    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS app_preferences (identityId TEXT NOT NULL PRIMARY KEY, category TEXT NOT NULL, hidden INTEGER NOT NULL, ignored INTEGER NOT NULL,
                    FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS ignore_periods (periodId TEXT NOT NULL PRIMARY KEY, identityId TEXT NOT NULL, startMs INTEGER NOT NULL, endMs INTEGER,
                    FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ignore_periods_identityId ON ignore_periods(identityId)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS manual_adjustments (adjustmentId TEXT NOT NULL PRIMARY KEY, identityId TEXT NOT NULL, reportDate TEXT NOT NULL, timezone TEXT NOT NULL,
                    deltaMs INTEGER NOT NULL, note TEXT NOT NULL, createdAtMs INTEGER NOT NULL,
                    FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_manual_adjustments_identityId_reportDate ON manual_adjustments(identityId, reportDate)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS app_observations (identityId TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, signingDigest TEXT, signingChanged INTEGER NOT NULL, observedAtMs INTEGER NOT NULL,
                    FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS system_daily_supplements (identityId TEXT NOT NULL, reportDate TEXT NOT NULL, durationMs INTEGER NOT NULL,
                    PRIMARY KEY(identityId, reportDate), FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("CREATE VIEW `app_day_totals` AS $DAILY_VIEW")
            }
        }
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
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS event_daily_usage (identityId TEXT NOT NULL, reportDate TEXT NOT NULL, timezone TEXT NOT NULL, durationMs INTEGER NOT NULL,
                    PRIMARY KEY(identityId, reportDate), FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_event_daily_usage_reportDate ON event_daily_usage(reportDate)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS system_daily_usage (identityId TEXT NOT NULL, reportDate TEXT NOT NULL, timezone TEXT NOT NULL, durationMs INTEGER NOT NULL,
                    bucketStartMs INTEGER NOT NULL, bucketEndMs INTEGER NOT NULL, observedAtMs INTEGER NOT NULL,
                    PRIMARY KEY(identityId, reportDate), FOREIGN KEY(identityId) REFERENCES app_identities(identityId) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_system_daily_usage_reportDate ON system_daily_usage(reportDate)")
                db.execSQL("CREATE TABLE IF NOT EXISTS daily_sync_state (id INTEGER NOT NULL PRIMARY KEY, initialDone INTEGER NOT NULL, eventRebuilt INTEGER NOT NULL, lastAttemptMs INTEGER)")
            }
        }
    }
}
