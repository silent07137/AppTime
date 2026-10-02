// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.backup

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.silent07137.apptime.core.*
import io.github.silent07137.apptime.data.*
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArchiveServiceTest {
    private val password = "AppTime-备份-golden-123".toCharArray() // Public test-only password.
    private lateinit var context: Context
    private lateinit var directory: File
    private val databases = mutableListOf<AppDatabase>()
    private val noSource = object : UsageEventSource {
        override fun hasAccess() = false
        override fun read(startMs: Long, endMs: Long) = EventRead.Unavailable("test")
    }
    private data class Archive(val db: AppDatabase, val repo: UsageRepository, val service: ArchiveService, val device: String, val app: String)
    @Before fun setup() {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(parent.cacheDir, "backup-tests-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(parent) {
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }
    }
    @After fun cleanup() { databases.forEach { it.close() }; directory.deleteRecursively(); password.fill('\u0000') }
    private suspend fun archive(device: String = UUID.randomUUID().toString(), app: String = UUID.randomUUID().toString(), records: Boolean = true): Archive {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build(); databases += db
        val dao = db.usageDao()
        dao.insertDevice(DeviceEntity(device, createdAt = 0, reportTimezone = "UTC"))
        dao.saveLocalState(LocalArchiveState(localDeviceId = device))
        dao.saveState(CollectionState(recordFromMs = 0, checkpointMs = 60_000, enabled = true))
        dao.insertIdentity(IdentityEntity(app, device, "personal", "example.test", "Private test name"))
        if (records) {
            dao.saveSession(SessionEntity(UUID.randomUUID().toString(), device, app, 0, 0, 60_000, 60_000, "UTC", 0, provisional = false, transitionEstimated = false))
            dao.saveBucket(HistoricalBucketEntity(UUID.randomUUID().toString(), device, app, 0, 86_400_000, 100_000))
            dao.saveSystemDays(listOf(SystemDailyEntity(app, "1970-01-01", "UTC", 50_000, 0, 86_400_000, 70_000)))
            dao.savePreference(AppPreferenceEntity(app, "学习", hidden = true))
            dao.saveIgnorePeriod(IgnorePeriodEntity(UUID.randomUUID().toString(), app, 80_000, 90_000))
            dao.saveAdjustment(AdjustmentEntity(UUID.randomUUID().toString(), app, "1970-01-01", "UTC", 10_000, "Private note", 60_000))
            dao.saveCoverage(CoverageEntity("$device:query:0", device, 0, 60_000, "partial", "test"))
            dao.saveObservation(AppObservationEntity(app, "installed", "test-signature", false, 60_000))
            dao.saveHistoryState(HistoryImportState(source = "android_usage_stats_best", requestedStartMs = 0, requestedEndMs = 86_400_000, acceptedBuckets = 1, lastAttemptMs = 60_000, status = "partial", detail = "test"))
        }
        val repo = UsageRepository(db, noSource, { it }, "personal")
        repo.rebuildArchiveCaches()
        return Archive(db, repo, ArchiveService(context, repo), device, app)
    }
    private suspend fun bytes(a: Archive) = ByteArrayOutputStream().also { a.service.export(it, password) }.toByteArray()
    private suspend fun merge(a: Archive, data: ByteArray, replace: Boolean = false, same: Boolean = false): Int =
        a.service.preview(data.inputStream(), password).use { a.service.restore(it, password, replace, same) }
    private fun scalar(a: Archive, sql: String) = a.db.openHelper.writableDatabase.query(sql).use { assertTrue(it.moveToFirst()); it.getLong(0) }

    @Test fun portableDotNetFixtureRestoresAndRebuildsDerivedCaches() = runBlocking {
        val a = archive(records = false)
        val start = android.os.SystemClock.elapsedRealtime()
        val fixture = InstrumentationRegistry.getInstrumentation().context.assets.open("golden.atbackup").use { it.readBytes() }
        merge(a, fixture)
        Log.i("AppTimeBackupTest", "Golden preview/protect/restore ms=${android.os.SystemClock.elapsedRealtime() - start}")
        assertEquals(60_000L, a.db.usageDao().appDay("00000000-0000-4000-8000-000000000002", "1970-01-01")!!.durationMs)
        assertEquals(a.device, a.db.usageDao().device()!!.deviceId)
        assertEquals(0, merge(a, fixture))
    }
    @Test fun roundTripPreservesEveryRawTableAndDuplicateMergeIsZeroChanges() = runBlocking {
        val source = archive(); val target = archive(records = false)
        val data = bytes(source)
        assertTrue(merge(target, data) > 0)
        assertEquals(0, merge(target, data))
        assertEquals(60_000L, target.db.usageDao().appDay(source.app, "1970-01-01")!!.durationMs)
        assertEquals(60_000L, target.db.usageDao().observeApps().first().first { it.identityId == source.app }.recordedMs)
        assertEquals("学习", target.db.usageDao().preference(source.app)!!.category)
        assertEquals(1, target.db.usageDao().ignorePeriods(source.app).size)
        assertEquals(1, target.db.usageDao().observeAdjustments(source.app).first().size)
        assertEquals("installed", target.db.usageDao().observation(source.app)!!.status)
        assertEquals(target.device, target.db.usageDao().device()!!.deviceId)
        assertEquals(60_000L, target.db.usageDao().state()!!.checkpointMs)
        target.service.recoveryFile.inputStream().use { target.service.preview(it, password).use { assertEquals(2, it.devices) } }
    }
    @Test fun replacementOnAnotherPhoneCreatesSeparateCollectorAndPreservesOrigin() = runBlocking {
        val source = archive(); val target = archive()
        merge(target, bytes(source), replace = true)
        assertEquals(target.device, target.db.usageDao().device()!!.deviceId)
        assertEquals(source.device, target.db.usageDao().identities().single().deviceId)
        assertNull(target.db.usageDao().state()!!.checkpointMs)
        assertFalse(target.db.usageDao().state()!!.enabled)
        assertNull(target.db.usageDao().historyState())
        assertEquals(60_000L, target.db.usageDao().appDay(source.app, "1970-01-01")!!.durationMs)
        target.service.recoveryFile.inputStream().use { target.service.preview(it, password).use { assertEquals(1, it.apps) } }
    }
    @Test fun replacementSamePhonePreservesSourceIdentityAndLocalCheckpoint() = runBlocking {
        val source = archive(); val target = archive()
        merge(target, bytes(source), replace = true, same = true)
        assertEquals(source.device, target.db.usageDao().device()!!.deviceId)
        assertEquals(60_000L, target.db.usageDao().state()!!.checkpointMs)
        assertEquals(1, target.db.usageDao().historyState()!!.acceptedBuckets)
        assertEquals(1, target.db.usageDao().observeDevices().first().size)
    }
    @Test fun treatingOriginalPhoneAsNewPhoneAllocatesFreshDeviceIdentity() = runBlocking {
        val a = archive(); val data = bytes(a)
        merge(a, data, replace = true)
        assertNotEquals(a.device, a.db.usageDao().device()!!.deviceId)
        assertEquals(a.device, a.db.usageDao().identities().single().deviceId)
        assertEquals(2, a.db.usageDao().observeDevices().first().size)
    }
    @Test fun importedSessionsKeepTheirSourceReportTimezone() = runBlocking {
        val source = archive(records = false); val target = archive(records = false)
        source.db.openHelper.writableDatabase.execSQL("UPDATE devices SET reportTimezone = 'Asia/Tokyo'")
        source.db.usageDao().saveSession(SessionEntity(UUID.randomUUID().toString(), source.device, source.app, 75_600_000, 75_600_000, 79_200_000, 3_600_000,
            "Asia/Tokyo", 32400, provisional = false, transitionEstimated = false))
        merge(target, bytes(source))
        assertEquals(3_600_000L, target.db.usageDao().appDay(source.app, "1970-01-02")!!.durationMs)
        assertNull(target.db.usageDao().appDay(source.app, "1970-01-01"))
    }
    @Test fun higherRevisionUpdatesAndTombstonesCannotBeResurrectedByOldBackup() = runBlocking {
        val source = archive(); val target = archive(records = false); val old = bytes(source)
        merge(target, old)
        val session = source.db.usageDao().allSessions().single()
        source.db.usageDao().saveSession(session.copy(endMs = 40_000, durationMs = 40_000, revision = 2))
        source.repo.updatePreference(source.app, category = "影音")
        source.db.usageDao().deleteAdjustment(source.db.usageDao().observeAdjustments(source.app).first().single().adjustmentId)
        assertTrue(merge(target, bytes(source)) > 0)
        assertEquals(40_000L, target.db.usageDao().allSessions().single().durationMs)
        assertEquals("影音", target.db.usageDao().preference(source.app)!!.category)
        assertTrue(target.db.usageDao().observeAdjustments(source.app).first().isEmpty())
        assertEquals(50_000L, target.db.usageDao().appDay(source.app, "1970-01-01")!!.durationMs)
        assertEquals(0, merge(target, old))
        source.db.usageDao().saveSession(session.copy(deleted = true, revision = 3))
        merge(target, bytes(source)); merge(target, old)
        assertTrue(target.db.usageDao().allSessions().isEmpty())
        assertEquals(3L, target.db.usageDao().session(session.sessionId)!!.revision)
    }
    @Test fun sameRevisionConflictRollsBackEarlierWritesAndKeepsProtection() = runBlocking {
        val source = archive(); val target = archive(records = false)
        merge(target, bytes(source))
        source.db.usageDao().insertIdentity(IdentityEntity(UUID.randomUUID().toString(), source.device, "personal", "example.added", "Added"))
        val s = source.db.usageDao().allSessions().single()
        source.db.usageDao().saveSession(s.copy(endMs = 30_000, durationMs = 30_000))
        try { merge(target, bytes(source)); fail("conflict must reject") } catch (_: IllegalArgumentException) { }
        assertEquals(2, target.db.usageDao().identities().size)
        assertEquals(60_000L, target.db.usageDao().session(s.sessionId)!!.durationMs)
        target.service.recoveryFile.inputStream().use { target.service.preview(it, password).use { assertEquals(2, it.apps) } }
    }
    @Test fun higherRevisionCannotChangeSessionOwner() = runBlocking {
        val source = archive(); val target = archive(records = false); merge(target, bytes(source))
        val another = UUID.randomUUID().toString()
        source.db.usageDao().insertIdentity(IdentityEntity(another, source.device, "personal", "example.another", "Another"))
        val s = source.db.usageDao().allSessions().single(); source.db.usageDao().saveSession(s.copy(identityId = another, revision = 2))
        try { merge(target, bytes(source)); fail("owner must be immutable") } catch (_: IllegalArgumentException) { }
        assertEquals(source.app, target.db.usageDao().session(s.sessionId)!!.identityId)
    }
    @Test fun wrongPasswordCorruptionAndFutureHeaderLeaveCurrentArchiveUnchanged() = runBlocking {
        val source = archive(); val target = archive(); val data = bytes(source)
        for (candidate in listOf(data to "wrong-password".toCharArray(), data.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to password,
            data.copyOf().also { it[9] = 2 } to password)) {
            try { target.service.preview(candidate.first.inputStream(), candidate.second).close(); fail("invalid backup accepted") } catch (_: Exception) { }
            assertEquals(1, target.db.usageDao().identities().size)
            assertEquals(60_000L, target.db.usageDao().allSessions().single().durationMs)
            assertFalse(target.service.recoveryFile.exists())
        }
    }
    private fun repackage(plan: RestorePlan, wrongHash: Boolean = false, mutate: (File) -> Unit): ByteArray {
        mutate(plan.directory)
        val manifestFile = File(plan.directory, "manifest.json"); val manifest = JSONObject(manifestFile.readText())
        for (table in ArchiveService.TABLES) {
            val file = File(plan.directory, "$table.jsonl")
            manifest.getJSONObject("files").getJSONObject(file.name).put("bytes", file.length()).put("sha256", ArchiveService.sha(file))
        }
        if (wrongHash) manifest.getJSONObject("files").getJSONObject("sessions.jsonl").put("sha256", "0".repeat(64))
        manifestFile.writeText(manifest.toString())
        val zip = File(directory, "mutated.zip")
        ZipOutputStream(zip.outputStream()).use { out -> for (name in listOf("manifest.json") + ArchiveService.TABLES.map { "$it.jsonl" }) {
            out.putNextEntry(ZipEntry(name)); File(plan.directory, name).inputStream().use { it.copyTo(out) }; out.closeEntry()
        } }
        return ByteArrayOutputStream().also { BackupEnvelope.encrypt(zip, it, password) }.toByteArray()
    }
    @Test fun mismatchedDeviceOwnershipRejectedBeforePreviewWithoutCurrentWrites() = runBlocking {
        val source = archive(); val target = archive()
        source.db.usageDao().insertDevice(DeviceEntity(UUID.randomUUID().toString(), createdAt = 1, reportTimezone = "UTC"))
        val otherDevice = source.db.usageDao().observeDevices().first().last().deviceId
        val data = source.service.preview(bytes(source).inputStream(), password).use { plan -> repackage(plan) { dir ->
            val file = File(dir, "sessions.jsonl"); val row = JSONObject(file.readText())
            row.put("originDeviceId", otherDevice); file.writeText(row.toString() + "\n")
        } }
        try { target.service.preview(data.inputStream(), password).close(); fail("ownership mismatch accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, target.db.usageDao().identities().size)
    }
    @Test fun authenticatedInvalidRowsHashesAndFutureSchemaAreRejected() = runBlocking {
        val source = archive(); val target = archive()
        val original = bytes(source)
        for (kind in listOf("rowcount", "future", "hash", "missing-device", "offset", "blank-line")) {
            val data = source.service.preview(original.inputStream(), password).use { plan -> repackage(plan, wrongHash = kind == "hash") { dir ->
                val manifest = File(dir, "manifest.json"); val value = JSONObject(manifest.readText())
                when (kind) {
                    "rowcount" -> value.getJSONObject("files").getJSONObject("sessions.jsonl").put("rows", 2)
                    "future" -> value.put("schema_version", 2)
                    "missing-device", "offset" -> {
                        val file = File(dir, "sessions.jsonl"); val row = JSONObject(file.readText())
                        if (kind == "offset") row.put("utcOffsetSeconds", 999999) else row.put("originDeviceId", UUID.randomUUID().toString())
                        file.writeText(row.toString() + "\n")
                    }
                    "blank-line" -> File(dir, "sessions.jsonl").appendText("\n")
                }
                manifest.writeText(value.toString())
            } }
            try { target.service.preview(data.inputStream(), password).close(); fail("invalid $kind accepted") } catch (_: Exception) { }
            assertEquals(1, target.db.usageDao().identities().size)
        }
    }
    @Test fun traversalAndDecompressionBombAreRejectedWithoutExtractingOutsideWorkspace() = runBlocking {
        val a = archive()
        for (bomb in listOf(false, true)) {
            val zip = File(directory, "hostile.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                for (name in listOf("manifest.json") + ArchiveService.TABLES.map { "$it.jsonl" }) {
                    out.putNextEntry(ZipEntry(if (!bomb && name == "sessions.jsonl") "../escape" else name))
                    if (bomb && name == "sessions.jsonl") repeat(65) { out.write(ByteArray(1024 * 1024)) }
                    out.closeEntry()
                }
            }
            val data = ByteArrayOutputStream().also { BackupEnvelope.encrypt(zip, it, password) }.toByteArray()
            try { a.service.preview(data.inputStream(), password).close(); fail("hostile zip accepted") }
            catch (_: IllegalArgumentException) { }
            catch (_: java.util.zip.ZipException) { } // Android 14+ rejects traversal before our entry whitelist.
            assertFalse(File(context.cacheDir, "escape").exists())
            assertEquals(1, a.db.usageDao().identities().size)
        }
    }
    @Test fun diagnosticNamesAreOptInAndNeverIncludeNotesDeviceIdsOrPaths() = runBlocking {
        val a = archive(); val safe = diagnostics(context, a.repo, false)
        for (secret in listOf("example.test", "Private test name", "Private note", a.device, a.app, directory.absolutePath)) assertFalse(secret, safe.contains(secret))
        val detailed = diagnostics(context, a.repo, true)
        assertTrue(detailed.contains("example.test")); assertTrue(detailed.contains("Private test name"))
        assertFalse(detailed.contains("Private note")); assertFalse(detailed.contains(a.device))
    }
}
