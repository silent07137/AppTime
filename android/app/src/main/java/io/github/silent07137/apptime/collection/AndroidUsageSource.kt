// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.collection

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.UserManager
import io.github.silent07137.apptime.core.*

class AndroidUsageSource(private val context: Context) : UsageEventSource {
    override fun hasAccess(): Boolean = context.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

    override fun read(startMs: Long, endMs: Long): EventRead {
        if (!hasAccess()) return EventRead.Unavailable("未授予或已撤回使用情况访问权限")
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return EventRead.Unavailable("设备尚未完成首次解锁")
        return try {
            val result = context.getSystemService(UsageStatsManager::class.java).queryEvents(startMs, endMs)
                ?: return EventRead.Unavailable("系统事件暂不可用")
            val events = mutableListOf<UsageEvent>()
            val raw = UsageEvents.Event()
            while (result.hasNextEvent()) {
                result.getNextEvent(raw)
                val kind = when (raw.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> EventKind.RESUME
                    UsageEvents.Event.ACTIVITY_PAUSED -> EventKind.PAUSE
                    UsageEvents.Event.ACTIVITY_STOPPED -> EventKind.STOP
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> EventKind.SCREEN_OFF
                    UsageEvents.Event.SCREEN_INTERACTIVE -> EventKind.SCREEN_ON
                    UsageEvents.Event.KEYGUARD_SHOWN -> EventKind.LOCK
                    UsageEvents.Event.KEYGUARD_HIDDEN -> EventKind.UNLOCK
                    UsageEvents.Event.DEVICE_SHUTDOWN -> EventKind.SHUTDOWN
                    UsageEvents.Event.DEVICE_STARTUP -> EventKind.STARTUP
                    else -> null
                }
                // The public SDK exposes className, but no stable Activity instance identifier.
                if (kind != null) events += UsageEvent(raw.timeStamp, kind, raw.packageName.orEmpty(), raw.className.orEmpty())
                if (events.size > 250_000) return EventRead.Unavailable("事件量超过单次安全上限；已有记录已保留")
            }
            if (!hasAccess()) EventRead.Unavailable("查询期间使用情况权限被撤回") else EventRead.Available(events)
        } catch (_: SecurityException) {
            EventRead.Unavailable("系统拒绝使用情况访问")
        }
    }
}
