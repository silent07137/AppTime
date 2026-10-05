// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.silent07137.apptime.core.*
import io.github.silent07137.apptime.data.*
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

private data class DisplayPeriod(val app: DaySession, val start: Long, val end: Long)

@Composable internal fun DayDetailScreen(date: LocalDate, initialId: String?, category: String?, repo: UsageRepository, zone: ZoneId, apps: List<AppSummary>, onDate: (LocalDate) -> Unit, onApp: (String) -> Unit) {
    var identity by rememberSaveable(date, initialId) { mutableStateOf(initialId) }
    var selectedHour by rememberSaveable(date, identity) { mutableIntStateOf(-1) }
    var picking by remember { mutableStateOf(false) }
    var appMenu by remember { mutableStateOf(false) }
    val today = LocalDate.now(zone)
    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dailyApps by remember(date, identity, category) { repo.dao.observeDayApps(date.toString(), identity, category) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by remember(start, end, identity, category) { repo.dao.observeDaySessions(start, end, identity, category) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val periods = remember(sessions, start, end) {
        sessions.groupBy { it.identityId }.values.flatMap { group ->
            UsageMath.union(group.map { Interval(maxOf(start, it.startMs), minOf(end, it.endMs)) }).map { interval ->
                val overlapping = group.filter { it.startMs < interval.endMs && it.endMs > interval.startMs }
                DisplayPeriod(overlapping.first().copy(provisional = overlapping.any { it.provisional }, transitionEstimated = overlapping.any { it.transitionEstimated }), interval.startMs, interval.endMs)
            }
        }.sortedBy { it.start }
    }
    val hours = remember(periods, date, zone) { UsageDistribution.hours(date, zone, periods.map { AppInterval(it.app.identityId, Interval(it.start, it.end)) }) }
    val hour = hours.getOrNull(selectedHour)
    val visiblePeriods = if (hour == null) periods else periods.mapNotNull {
        val from = maxOf(it.start, hour.startMs); val to = minOf(it.end, hour.endMs)
        if (to > from) it.copy(start = from, end = to) else null
    }
    if (picking) PickDay(date, today, { picking = false; onDate(it) }, { picking = false })
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onDate(date.minusDays(1)) }, modifier = Modifier.semantics { contentDescription = "前一天" }) { Glyph(Symbol.BACK) }
                TextButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text(date.toString(), style = MaterialTheme.typography.titleMedium) }
                IconButton(enabled = date < today, onClick = { onDate(date.plusDays(1)) }, modifier = Modifier.semantics { contentDescription = "后一天" }) { Glyph(Symbol.CHEVRON) }
            }
            Box {
                OutlinedButton(onClick = { appMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(apps.firstOrNull { it.identityId == identity }?.displayName ?: category ?: "全部应用", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(appMenu, onDismissRequest = { appMenu = false }) {
                    DropdownMenuItem(text = { Text(category ?: "全部应用") }, onClick = { identity = null; appMenu = false })
                    apps.filter { category == null || it.category == category }.forEach { app -> DropdownMenuItem(text = { Text(app.displayName) }, onClick = { identity = app.identityId; appMenu = false }) }
                }
            }
        }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    dailyApps.isEmpty() -> "当天用时"
                    dailyApps.all { it.source == "system" } -> "系统汇总"
                    dailyApps.all { it.source == "events" } -> "已记录用时"
                    else -> "当天汇总"
                }, style = MaterialTheme.typography.labelLarge)
                Text(if (dailyApps.isEmpty()) "未采集" else duration(dailyApps.sumOf { it.durationMs }), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            }
        } }
        if (periods.isNotEmpty()) {
            item { HourChart(hours, zone, selectedHour, { selectedHour = if (selectedHour == it) -1 else it }) }
            item { Expandable("分布说明", "事件时段 ${duration(hours.sumOf { it.durationMs })}") {
                Text("每日数据和时间分布按报表时区的午夜划分。跨日的系统统计桶仅保留原始汇总，不算作某一天的用时。", style = MaterialTheme.typography.bodySmall)
                Text("系统汇总和事件时段来自不同来源，完整性和更新时点可能不同；事件缺少结束信号时还会包含暂计时长。", style = MaterialTheme.typography.bodySmall)
                Text("小时图和时间线仅展示保存的前台事件。每日汇总、手动修正及缺少边界的记录无法还原为具体时段；空白小时表示没有已记录时段。", style = MaterialTheme.typography.bodySmall)
                Text("报表时区：${zone.id}。多个应用同时在前台时分别计时。", style = MaterialTheme.typography.bodySmall)
            } }
        } else item { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Text(if (dailyApps.isEmpty()) "这一天尚无记录" else "仅有每日汇总，暂无时段记录", Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (dailyApps.isNotEmpty()) {
            item { Text(if (hour == null) "应用用时" else "所选小时 · 应用用时", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            val values = if (hour == null) dailyApps else visiblePeriods.groupBy { it.app.identityId }.map { (id, rows) ->
                DayAppUsage(id, rows.first().app.packageName, rows.first().app.displayName, "", rows.sumOf { it.end - it.start }, "events")
            }.sortedByDescending { it.durationMs }
            items(values, key = { "app:${it.identityId}" }) { app ->
                Surface(onClick = { onApp(app.identityId) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PackageIcon(app.packageName, app.displayName)
                        Text(app.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(duration(app.durationMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Glyph(Symbol.CHEVRON, Modifier.size(18.dp))
                    }
                }
            }
        }
        if (periods.isNotEmpty()) {
            item { Text(if (hour == null) "时间线" else "所选小时 · 时间线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (visiblePeriods.isEmpty()) item { Text("没有已记录时段", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(visiblePeriods, key = { "${it.app.identityId}:${it.start}:${it.end}" }) { period ->
                Surface(onClick = { onApp(period.app.identityId) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        PackageIcon(period.app.packageName, period.app.displayName)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(period.app.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val to = if (period.end == end) "24:00" else timeText(period.end, zone, "HH:mm")
                            Text("${timeText(period.start, zone, "HH:mm")} – $to" + if (period.app.provisional) " · 暂计" else if (period.app.transitionEstimated) " · 含过渡估算" else "",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(duration(period.end - period.start), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable private fun HourChart(hours: List<HourUsage>, zone: ZoneId, selected: Int, onHour: (Int) -> Unit) {
    val max = hours.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1L
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val progress by animateFloatAsState(if (entered) 1f else 0f, tween(480), label = "小时柱图")
    val primary = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.outlineVariant
    var menu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("时间分布", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Box {
                    TextButton(onClick = { menu = true }) { Text(if (selected < 0) "全天" else hourText(hours[selected], zone)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("全天") }, onClick = { if (selected >= 0) onHour(selected); menu = false })
                        hours.forEachIndexed { index, hour -> DropdownMenuItem(text = { Text("${hourText(hour, zone)}  ${if (hour.durationMs > 0) duration(hour.durationMs) else "无已记录时段"}") }, onClick = { if (selected != index) onHour(index); menu = false }) }
                    }
                }
            }
            Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "每小时使用分布，可通过上方小时选择查看时段" }
                .pointerInput(hours) { detectTapGestures { point -> onHour((point.x / size.width * hours.size).toInt().coerceIn(0, hours.lastIndex)) } }) {
                val width = size.width / hours.size
                hours.forEachIndexed { index, hour ->
                    val height = (hour.durationMs.toFloat() / max * (size.height - 8) * progress).coerceAtLeast(2f)
                    drawRoundRect(if (hour.durationMs == 0L) faint else if (selected < 0 || selected == index) primary else primary.copy(alpha = .25f),
                        Offset(index * width + 2, size.height - height), Size((width - 4).coerceAtLeast(1f), height), CornerRadius(3f))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (text in (0..3).map { timeText(hours[it * hours.size / 4].startMs, zone, "HH") } + "24") Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected >= 0) Text("${hourText(hours[selected], zone)} · ${if (hours[selected].durationMs > 0) duration(hours[selected].durationMs) else "无已记录时段"}", style = MaterialTheme.typography.labelMedium, color = primary)
        }
    }
}

private fun hourText(hour: HourUsage, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(hour.startMs).atZone(zone)
    return date.format(DateTimeFormatter.ofPattern("HH:mm")) + if (zone.rules.isFixedOffset) "" else if (zone.rules.getValidOffsets(date.toLocalDateTime()).size > 1) " (${date.offset})" else ""
}
