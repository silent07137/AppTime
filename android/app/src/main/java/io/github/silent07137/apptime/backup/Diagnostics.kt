// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.backup

import android.content.Context
import android.os.Build
import io.github.silent07137.apptime.collection.AndroidUsageSource
import io.github.silent07137.apptime.core.*
import io.github.silent07137.apptime.data.*
import org.json.JSONArray
import org.json.JSONObject

/** Local, opt-in report. No package names, classes, UUIDs or paths in the default report. */
suspend fun diagnostics(context: Context, repo: UsageRepository, names: Boolean): String = repo.withArchiveLock { db ->
    val ids = db.usageDao().identities()
    val aliases = ids.mapIndexed { index, id -> id.identityId to "app-${index + 1}" }.toMap()
    val packages = ids.map { it.packageName }.distinct().mapIndexed { index, name -> name to "package-${index + 1}" }.toMap().toMutableMap()
    fun pkg(name: String) = packages.getOrPut(name) { "package-${packages.size + 1}" }
    val apps = JSONArray()
    val localDevice = db.usageDao().device()?.deviceId
    for (id in ids) {
        val item = JSONObject().put("app", aliases[id.identityId]).put("package", pkg(id.packageName)).put("local_source", id.deviceId == localDevice)
        if (names) item.put("package_name", id.packageName).put("display_name", id.displayName)
        apps.put(item)
    }
    val now = System.currentTimeMillis(); val from = maxOf(0, now - 72 * 3_600_000L)
    val state = db.usageDao().state()
    val report = JSONObject().put("diagnostics_version", 1).put("created_at", now).put("api_level", Build.VERSION.SDK_INT)
        .put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
        .put("includes_names", names).put("apps", apps)
        .put("collection", JSONObject().put("status", state?.status ?: "未采集").put("record_from_ms", state?.recordFromMs ?: JSONObject.NULL)
            .put("checkpoint_ms", state?.checkpointMs ?: JSONObject.NULL).put("last_success_ms", state?.lastSuccessMs ?: JSONObject.NULL))
    val sql = db.openHelper.writableDatabase
    val sessions = JSONArray()
    sql.query("SELECT identityId,startMs,endMs,provisional,transitionEstimated,revision,deleted FROM sessions WHERE endMs >= ? ORDER BY startMs DESC LIMIT 5001", arrayOf(from)).use { c ->
        while (c.moveToNext() && sessions.length() < 5000) sessions.put(JSONObject().put("app", aliases[c.getString(0)])
            .put("start_ms", c.getLong(1)).put("end_ms", c.getLong(2)).put("provisional", c.getInt(3) != 0)
            .put("transition_estimated", c.getInt(4) != 0).put("revision", c.getLong(5)).put("deleted", c.getInt(6) != 0))
        report.put("sessions_truncated", c.count > 5000)
    }
    report.put("sessions", sessions)
    val daily = JSONArray()
    sql.query("SELECT identityId,reportDate,durationMs,bucketStartMs,bucketEndMs,observedAtMs FROM system_daily_usage ORDER BY reportDate DESC LIMIT 5001").use { c ->
        while (c.moveToNext() && daily.length() < 5000) daily.put(JSONObject().put("app", aliases[c.getString(0)]).put("date", c.getString(1))
            .put("system_ms", c.getLong(2)).put("bucket_start_ms", c.getLong(3)).put("bucket_end_ms", c.getLong(4)).put("observed_at_ms", c.getLong(5)))
        report.put("daily_truncated", c.count > 5000)
    }
    report.put("system_days", daily)
    val adjustments = JSONArray()
    sql.query("SELECT identityId,reportDate,deltaMs FROM manual_adjustments WHERE deleted = 0 ORDER BY createdAtMs DESC LIMIT 5001").use { c ->
        while (c.moveToNext() && adjustments.length() < 5000) adjustments.put(JSONObject().put("app", aliases[c.getString(0)]).put("date", c.getString(1)).put("delta_ms", c.getLong(2)))
        report.put("adjustments_truncated", c.count > 5000)
    }
    report.put("adjustments", adjustments)
    val events = JSONArray(); val components = mutableMapOf<Pair<String, String>, String>()
    when (val read = AndroidUsageSource(context).read(from, now)) {
        is EventRead.Unavailable -> report.put("events_status", "unavailable")
        is EventRead.Available -> {
            report.put("events_status", "partial").put("events_truncated", read.events.size > 5000)
            for (event in read.events.takeLast(5000)) {
                val component = components.getOrPut(event.packageName to event.component) { "activity-${components.size + 1}" }
                val item = JSONObject().put("time_ms", event.timeMs).put("kind", event.kind.name).put("package", pkg(event.packageName)).put("component", component)
                if (names) item.put("package_name", event.packageName).put("class_name", event.component)
                events.put(item)
            }
        }
    }
    report.put("events", events).toString(2)
}
