// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.collection

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.UserManager
import io.github.silent07137.apptime.core.*

class AndroidUsageHistorySource(private val context: Context) : UsageHistorySource {
    override fun read(startMs: Long, endMs: Long): HistoryRead {
        val permissions = AndroidUsageSource(context)
        if (!permissions.hasAccess()) return HistoryRead.Unavailable("需要使用情况访问权限")
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return HistoryRead.Unavailable("设备尚未完成首次解锁")
        return try {
            val earliest = startMs == 0L
            val buckets = context.getSystemService(UsageStatsManager::class.java)
                .queryUsageStats(if (earliest) UsageStatsManager.INTERVAL_BEST else UsageStatsManager.INTERVAL_DAILY, startMs, endMs)
                ?: return HistoryRead.Unavailable("系统旧历史暂不可用")
            if (buckets.size > 100_000) return HistoryRead.Unavailable("返回历史量超过安全上限，已有数据保留")
            if (!permissions.hasAccess()) return HistoryRead.Unavailable("查询期间使用情况权限被撤回")
            HistoryRead.Available(buckets.map { HistoricalBucket(it.packageName.orEmpty(), it.firstTimeStamp, it.lastTimeStamp, it.totalTimeInForeground,
                if (earliest) "android_usage_stats_best" else "android_usage_stats_daily") })
        } catch (_: SecurityException) { HistoryRead.Unavailable("系统拒绝旧历史查询") }
    }
}
