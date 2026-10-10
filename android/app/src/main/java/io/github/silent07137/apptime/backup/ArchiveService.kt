// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.backup

import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import io.github.silent07137.apptime.core.*
import io.github.silent07137.apptime.data.*
import java.io.*
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.*
import org.json.JSONArray
import org.json.JSONObject

class RestorePlan internal constructor(val directory: File, val manifest: JSONObject) : Closeable {
    val originPlatform: String = File(directory, "devices.jsonl").useLines { lines ->
        lines.map(::JSONObject).first { it.getString("deviceId") == manifest.getString("exporting_device_id") }.getString("platform")
    }
    val apps: Int get() = manifest.getJSONObject("files").getJSONObject("app_identities.jsonl").getInt("rows")
    val records: Int get() = manifest.getJSONObject("files").getJSONObject("sessions.jsonl").getInt("rows")
    val devices: Int get() = manifest.getJSONObject("files").getJSONObject("devices.jsonl").getInt("rows")
    override fun close() { directory.deleteRecursively() }
}

class ArchiveService(private val context: Context, private val repo: UsageRepository) {
    val recoveryFile: File get() = File(context.filesDir, "before-restore.atbackup")
    companion object {
        const val MAX_PLAIN = 64 * 1024 * 1024L
        const val MAX_ROWS = 200_000
        val TABLES = listOf("devices", "app_identities", "sessions", "historical_buckets", "coverage",
            "system_daily_usage", "app_preferences", "ignore_periods", "manual_adjustments", "app_observations",
            "collection_state", "history_import_state")
        // Schema v1 is independent of Room's version; future internal columns are never exported implicitly.
        val COLUMNS = mapOf(
            "devices" to "deviceId,platform,createdAt,reportTimezone",
            "app_identities" to "identityId,deviceId,profileScope,packageName,displayName",
            "sessions" to "sessionId,originDeviceId,identityId,anchorMs,startMs,endMs,durationMs,timezone,utcOffsetSeconds,metric,source,provisional,transitionEstimated,quality,revision,deleted",
            "historical_buckets" to "bucketId,originDeviceId,identityId,startMs,endMs,usageMs,source,timezone,quality,revision",
            "coverage" to "coverageId,deviceId,startMs,endMs,status,reason",
            "system_daily_usage" to "identityId,reportDate,timezone,durationMs,bucketStartMs,bucketEndMs,observedAtMs",
            "app_preferences" to "identityId,category,hidden,ignored,revision",
            "ignore_periods" to "periodId,identityId,startMs,endMs,revision",
            "manual_adjustments" to "adjustmentId,identityId,reportDate,timezone,deltaMs,note,createdAtMs,revision,deleted",
            "app_observations" to "identityId,status,signingDigest,signingChanged,observedAtMs",
            "collection_state" to "source,recordFromMs,checkpointMs,lastSuccessMs,enabled,status,detail",
            "history_import_state" to "source,requestedStartMs,requestedEndMs,returnedStartMs,returnedEndMs,acceptedBuckets,skippedBuckets,lastAttemptMs,status,detail",
        ).mapValues { it.value.split(',') }
        private val LOCAL = setOf("collection_state", "history_import_state")
        private val MAX_TIME = 253402300799999L
        fun sha(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(32768)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
    private fun workspace() = File(context.cacheDir, "archive-${UUID.randomUUID()}").apply { check(mkdirs()) }

    suspend fun export(output: OutputStream, password: CharArray) {
        val dir = workspace()
        try {
            val zip = repo.withArchiveLock { db -> snapshot(db, dir) }
            BackupEnvelope.encrypt(zip, output, password)
        } finally { dir.deleteRecursively() }
    }

    private suspend fun snapshot(db: AppDatabase, dir: File): File {
        val files = JSONObject(); var total = 0L
        val manifest = db.withTransaction {
            val sql = db.openHelper.writableDatabase
            for (table in TABLES) {
                val file = File(dir, "$table.jsonl"); var count = 0
                file.bufferedWriter(Charsets.UTF_8).use { writer ->
                    sql.query("SELECT ${COLUMNS.getValue(table).joinToString { "`$it`" }} FROM `$table` ORDER BY rowid").use { cursor ->
                        while (cursor.moveToNext()) {
                            writer.write(row(cursor).toString()); writer.write("\n"); count++
                            require(count <= MAX_ROWS) { "档案记录量超过备份限制" }
                        }
                    }
                }
                total += file.length()
                require(total <= MAX_PLAIN) { "档案超过备份大小限制" }
                files.put(file.name, JSONObject().put("rows", count).put("bytes", file.length()).put("sha256", sha(file)))
            }
            require(TABLES.sumOf { files.getJSONObject("$it.jsonl").getInt("rows") } <= MAX_ROWS) { "档案记录量超过备份限制" }
            require(files.getJSONObject("devices.jsonl").getInt("rows") in 1..32) { "设备数量超过备份限制" }
            val device = requireNotNull(db.usageDao().device()) { "尚无档案可备份" }
            val coverage = JSONArray()
            sql.query("SELECT deviceId FROM devices ORDER BY deviceId").use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    sql.query("SELECT MIN(startMs), MAX(endMs) FROM sessions WHERE originDeviceId = ? AND deleted = 0", arrayOf(id)).use { bounds ->
                        bounds.moveToFirst()
                        coverage.put(JSONObject().put("device_id", id).put("start_utc_ms", if (bounds.isNull(0)) JSONObject.NULL else bounds.getLong(0))
                            .put("end_utc_ms", if (bounds.isNull(1)) JSONObject.NULL else bounds.getLong(1)).put("quality", "partial"))
                    }
                }
            }
            JSONObject().put("format_version", 1).put("schema_version", 1).put("snapshot_id", UUID.randomUUID().toString())
                .put("created_at", System.currentTimeMillis()).put("exporting_device_id", device.deviceId)
                .put("device_coverage", coverage).put("files", files)
        }
        File(dir, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
        val zip = File(dir, "payload.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in listOf("manifest.json") + TABLES.map { "$it.jsonl" }) {
                out.putNextEntry(ZipEntry(name)); File(dir, name).inputStream().use { it.copyTo(out) }; out.closeEntry()
            }
        }
        return zip
    }

    suspend fun preview(input: InputStream, password: CharArray): RestorePlan {
        val dir = workspace()
        try {
            val zip = File(dir, "payload.zip")
            input.use { BackupEnvelope.decrypt(it, zip, password) }
            var total = 0L
            val expected = (TABLES.map { "$it.jsonl" } + "manifest.json").toSet()
            ZipFile(zip).use { archive ->
                val entries = archive.entries().asSequence().toList()
                require(entries.size == expected.size && entries.map { it.name }.toSet() == expected) { "备份内容不完整或包含未知文件" }
                for (entry in entries) {
                    require(!entry.isDirectory && entry.method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) { "备份压缩格式无效" }
                    val limit = if (entry.name == "manifest.json") 128 * 1024L else MAX_PLAIN
                    archive.getInputStream(entry).use { source -> File(dir, entry.name).outputStream().use { dest ->
                        val buffer = ByteArray(32768); var size = 0L
                        while (true) {
                            val n = source.read(buffer); if (n < 0) break
                            size += n; total += n
                            require(size <= limit && total <= MAX_PLAIN) { "备份解压大小超过限制" }
                            dest.write(buffer, 0, n)
                        }
                    } }
                }
            }
            val manifest = JSONObject(File(dir, "manifest.json").readText(Charsets.UTF_8))
            require(integer(manifest, "format_version") == 1L && integer(manifest, "schema_version") == 1L) { "暂不支持此备份数据版本" }
            UUID.fromString(manifest.getString("snapshot_id")); UUID.fromString(manifest.getString("exporting_device_id"))
            require(integer(manifest, "created_at") in 0..MAX_TIME)
            val files = manifest.getJSONObject("files")
            require(files.keys().asSequence().toSet() == TABLES.map { "$it.jsonl" }.toSet())
            var rows = 0L
            for (table in TABLES) {
                val file = File(dir, "$table.jsonl"); val info = files.getJSONObject(file.name)
                val count = integer(info, "rows")
                require(count in 0..MAX_ROWS && file.length() == integer(info, "bytes") && sha(file) == info.getString("sha256")) { "备份内容校验失败" }
                rows += count
            }
            require(rows <= MAX_ROWS && files.getJSONObject("devices.jsonl").getInt("rows") in 1..32)
            require(LOCAL.all { files.getJSONObject("$it.jsonl").getInt("rows") <= 1 })
            val stage = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
            try {
                stage.withTransaction {
                    for (table in TABLES) importTable(stage, dir, table, false)
                    stage.openHelper.writableDatabase.query("""SELECT 1 FROM sessions s JOIN app_identities i ON i.identityId = s.identityId JOIN devices d ON d.deviceId = i.deviceId
                        WHERE s.originDeviceId != i.deviceId OR (d.platform = 'windows' AND s.metric != 'windows_active_foreground') OR (d.platform = 'android' AND s.metric != 'android_foreground')
                        UNION ALL SELECT 1 FROM historical_buckets h JOIN app_identities i ON i.identityId = h.identityId JOIN devices d ON d.deviceId = i.deviceId WHERE h.originDeviceId != i.deviceId OR d.platform != 'android'
                        UNION ALL SELECT 1 FROM system_daily_usage s JOIN app_identities i ON i.identityId = s.identityId JOIN devices d ON d.deviceId = i.deviceId WHERE d.platform != 'android' LIMIT 1""").use {
                        require(!it.moveToFirst()) { "备份应用与来源设备不匹配" }
                    }
                    val origin = manifest.getString("exporting_device_id")
                    require(stage.openHelper.writableDatabase.query("SELECT 1 FROM devices WHERE deviceId = ?", arrayOf(origin)).use { it.moveToFirst() }) { "备份来源设备缺失" }
                    stage.usageDao().saveLocalState(LocalArchiveState(localDeviceId = origin))
                    val stageRepo = UsageRepository(stage, object : UsageEventSource {
                        override fun hasAccess() = false
                        override fun read(startMs: Long, endMs: Long) = EventRead.Unavailable("staged archive")
                    }, { it }, "preview")
                    stageRepo.rebuildArchiveCaches()
                    stage.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
                }
            } finally { stage.close() }
            return RestorePlan(dir, manifest)
        } catch (problem: Exception) { dir.deleteRecursively(); throw problem }
    }

    /** Existing data is protected before writes; all restore writes and cache rebuilds are atomic. */
    suspend fun restore(plan: RestorePlan, password: CharArray, replace: Boolean, samePhone: Boolean = false): Int = repo.withArchiveLock { db ->
        require(!samePhone || plan.originPlatform == "android") { "Windows 档案不能作为本机 Android 采集身份" }
        val protection = workspace()
        try {
            val zip = snapshot(db, protection)
            val safe = File(protection, "before.atbackup")
            safe.outputStream().use { BackupEnvelope.encrypt(zip, it, password) }
            val verify = File(protection, "verify.zip")
            safe.inputStream().use { BackupEnvelope.decrypt(it, verify, password) }
            require(sha(zip) == sha(verify)) { "无法验证恢复前备份" }
            // Rename inside the same private directory preserves the previous protection on failure.
            val next = File(context.filesDir, "before-restore.next")
            safe.copyTo(next, overwrite = true)
            require(next.renameTo(recoveryFile)) { "无法保存恢复前备份" }
            var changed = 0
            db.withTransaction {
                val previousDevice = requireNotNull(db.usageDao().device())
                val sql = db.openHelper.writableDatabase
                if (replace) {
                    for (table in listOf("daily_usage", "event_daily_usage", "system_daily_supplements", "daily_sync_state", "local_archive_state") + TABLES.asReversed()) sql.execSQL("DELETE FROM `$table`")
                }
                for (table in TABLES) {
                    if (table in LOCAL && (!replace || !samePhone)) continue
                    changed += importTable(db, plan.directory, table, !replace)
                }
                if (replace) {
                    val origin = plan.manifest.getString("exporting_device_id")
                    val local = if (samePhone) origin else {
                        val id = if (sql.query("SELECT 1 FROM devices WHERE deviceId = ?", arrayOf(previousDevice.deviceId)).use { it.moveToFirst() }) UUID.randomUUID().toString() else previousDevice.deviceId
                        db.usageDao().insertDevice(previousDevice.copy(deviceId = id, createdAt = System.currentTimeMillis()))
                        db.usageDao().saveState(CollectionState(recordFromMs = System.currentTimeMillis()))
                        id
                    }
                    db.usageDao().saveLocalState(LocalArchiveState(localDeviceId = local))
                } else db.usageDao().saveLocalState(LocalArchiveState(localDeviceId = previousDevice.deviceId))
                require(sql.query("SELECT COUNT(*) FROM devices").use { it.moveToFirst(); it.getLong(0) } <= 32) { "合并后设备数量超过限制" }
                require(TABLES.sumOf { table -> sql.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) } } <= MAX_ROWS) { "合并后记录量超过限制" }
                repo.rebuildArchiveCaches()
                sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
            }
            changed
        } finally { protection.deleteRecursively() }
    }

    private fun importTable(db: AppDatabase, dir: File, table: String, merge: Boolean): Int {
        val sql = db.openHelper.writableDatabase
        data class Column(val name: String, val type: String, val required: Boolean, val pk: Boolean)
        val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor -> buildList {
            while (cursor.moveToNext()) add(Column(cursor.getString(1), cursor.getString(2), cursor.getInt(3) == 1, cursor.getInt(5) != 0))
        } }.filter { it.name in COLUMNS.getValue(table) }
        require(columns.size == COLUMNS.getValue(table).size) { "当前数据库不支持备份数据版本" }
        val keys = columns.filter { it.pk }; var count = 0; var changed = 0
        File(dir, "$table.jsonl").inputStream().buffered().use { input ->
            while (true) {
                val bytes = ByteArrayOutputStream()
                var ended = false
                while (true) {
                    val char = input.read(); if (char < 0) { ended = true; break }; if (char == 10) break
                    require(bytes.size() < 16384) { "备份记录过长" }; bytes.write(char)
                }
                if (bytes.size() == 0) { require(ended) { "备份存在空记录" }; break }
                val entry = JSONObject(bytes.toString("UTF-8"))
                require(entry.keys().asSequence().toSet() == columns.map { it.name }.toSet()) { "备份字段不兼容" }
                val values = columns.map<Column, Any?> { col ->
                    val value = entry.get(col.name)
                    if (value == JSONObject.NULL) { require(!col.required); null }
                    else if (col.type == "INTEGER") {
                        require(value is Int || value is Long) { "备份数值无效" }
                        (value as Number).toLong().also { number ->
                            if (col.name in setOf("deleted", "provisional", "transitionEstimated", "hidden", "ignored", "enabled", "signingChanged")) require(number in 0..1)
                            if (col.name == "revision") require(number in 1..1_000_000_000)
                            if (col.name.endsWith("Ms") && col.name != "deltaMs" || col.name in setOf("createdAt", "durationMs", "usageMs")) require(number in 0..MAX_TIME)
                        }
                    } else {
                        require(value is String && value.length <= if (col.name in setOf("note", "detail", "reason")) 2048 else 512)
                        value
                    }
                }.toTypedArray()
                validate(table, entry)
                val predicate = keys.joinToString(" AND ") { "`${it.name}` = ?" }
                val pk = keys.map { values[columns.indexOf(it)] }.toTypedArray()
                val existing = if (merge) sql.query("SELECT ${COLUMNS.getValue(table).joinToString { "`$it`" }} FROM `$table` WHERE $predicate", pk).use { if (it.moveToFirst()) row(it) else null } else null
                var write = true
                if (existing != null) {
                    val immutable = when (table) {
                        "devices" -> listOf("platform", "createdAt")
                        "app_identities" -> listOf("deviceId", "profileScope", "packageName")
                        "sessions" -> listOf("originDeviceId", "identityId", "anchorMs", "metric", "source")
                        "historical_buckets" -> listOf("originDeviceId", "identityId", "source", "startMs")
                        "ignore_periods" -> listOf("identityId", "startMs")
                        "manual_adjustments" -> listOf("identityId", "reportDate", "timezone", "deltaMs", "note", "createdAtMs")
                        else -> emptyList()
                    }
                    require(immutable.all { existing.get(it).toString() == entry.get(it).toString() }) { "档案记录身份冲突，未修改现有数据" }
                    if (equal(existing, entry)) write = false
                    else {
                        val revision = when (table) {
                            "sessions", "historical_buckets", "app_preferences", "ignore_periods", "manual_adjustments" -> "revision"
                            "system_daily_usage", "app_observations" -> "observedAtMs"
                            "coverage" -> "endMs"
                            else -> null
                        }
                        if (table == "app_identities" && listOf("deviceId", "profileScope", "packageName").all { existing.get(it) == entry.get(it) }) write = false
                        else {
                            require(revision != null && existing.getLong(revision) != entry.getLong(revision)) { "档案存在同版本冲突，未修改现有数据" }
                            write = entry.getLong(revision) > existing.getLong(revision)
                        }
                    }
                }
                if (write) {
                    if (existing == null) sql.execSQL("INSERT INTO `$table` (${columns.joinToString { "`${it.name}`" }}) VALUES (${columns.joinToString { "?" }})", values)
                    else sql.execSQL("UPDATE `$table` SET ${columns.joinToString { "`${it.name}` = ?" }} WHERE $predicate", (values.asList() + pk.asList()).toTypedArray())
                    changed++
                }
                count++; require(count <= MAX_ROWS)
            }
        }
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        require(count == manifest.getJSONObject("files").getJSONObject("$table.jsonl").getInt("rows")) { "备份记录数不匹配" }
        return changed
    }

    private fun validate(table: String, e: JSONObject) {
        for (key in listOf("deviceId", "originDeviceId", "identityId", "sessionId", "bucketId", "periodId", "adjustmentId")) if (e.has(key)) UUID.fromString(e.getString(key))
        if (e.has("reportDate")) LocalDate.parse(e.getString("reportDate"))
        if (e.has("timezone") && e.getString("timezone") != "unknown") ZoneId.of(e.getString("timezone"))
        if (e.has("reportTimezone")) ZoneId.of(e.getString("reportTimezone"))
        if (e.has("utcOffsetSeconds")) require(e.getLong("utcOffsetSeconds") in -64800..64800)
        for (name in listOf("acceptedBuckets", "skippedBuckets")) if (e.has(name)) require(e.getLong(name) in 0..Int.MAX_VALUE.toLong())
        if (table == "collection_state") require(e.getString("source") == "android_usage_events")
        if (table == "history_import_state") require(e.getString("source") in setOf("android_usage_stats_daily", "android_usage_stats_best"))
        if (table == "devices") require(e.getString("platform") in setOf("android", "windows")) { "不支持的设备平台" }
        if (table == "sessions") require(e.getLong("endMs") > e.getLong("startMs") && e.getLong("startMs") >= e.getLong("anchorMs") && e.getLong("durationMs") == e.getLong("endMs") - e.getLong("startMs") && e.getLong("durationMs") <= 7 * 86_400_000L)
        if (table == "sessions") require((e.getString("metric") == "android_foreground" && e.getString("source") == "android_usage_events") ||
            (e.getString("metric") == "windows_active_foreground" && e.getString("source") == "windows_foreground_poll"))
        if (table == "historical_buckets") require(e.getString("source") in setOf("android_usage_stats_daily", "android_usage_stats_best"))
        if (table == "historical_buckets" || table == "coverage") require(e.getLong("endMs") > e.getLong("startMs"))
        if (table == "system_daily_usage") require(e.getLong("bucketEndMs") > e.getLong("bucketStartMs"))
        if (table == "ignore_periods" && !e.isNull("endMs")) require(e.getLong("endMs") >= e.getLong("startMs"))
        if (table == "manual_adjustments") require(e.getLong("deltaMs") in -86_400_000..86_400_000 && e.getLong("deltaMs") != 0L)
    }
    private fun integer(e: JSONObject, key: String): Long {
        val value = e.get(key); require(value is Int || value is Long) { "备份数值无效" }; return (value as Number).toLong()
    }
    private fun row(cursor: Cursor) = JSONObject().also { value ->
        for (i in 0 until cursor.columnCount) value.put(cursor.getColumnName(i), when (cursor.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
            Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
            else -> error("不支持的备份字段")
        })
    }
    private fun equal(a: JSONObject, b: JSONObject) = a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().all { a.get(it).toString() == b.get(it).toString() }

    suspend fun exportTo(uri: Uri, password: CharArray) {
        val file = File.createTempFile("encrypted-backup-", ".atbackup", context.cacheDir)
        try { file.outputStream().use { export(it, password) }; copyVerified(file, uri) }
        finally { file.delete() }
    }
    fun copyVerified(file: File, uri: Uri) {
        requireNotNull(context.contentResolver.openOutputStream(uri, "w")).use { out -> file.inputStream().use { it.copyTo(out) } }
        val digest = MessageDigest.getInstance("SHA-256"); var bytes = 0L
        requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(32768)
            while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= file.length()); digest.update(buffer, 0, n) }
        }
        require(bytes == file.length() && digest.digest().joinToString("") { "%02x".format(it) } == sha(file)) { "无法验证保存的文件，请换一个保存位置" }
    }
}
