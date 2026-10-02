// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.silent07137.apptime.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun EntryRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Glyph(Symbol.CHEVRON, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun Choice(label: String, choices: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("⌄", Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); open = false }) }
        }
    }
}

@Composable internal fun AppListScreen(apps: List<AppSummary>, manage: Boolean = false, deviceLabels: Map<String, String> = emptyMap(), onApp: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("全部分类") }
    var filter by rememberSaveable { mutableStateOf(if (manage) "全部档案" else "可见应用") }
    var device by rememberSaveable { mutableStateOf("全部设备") }
    val categories = listOf("全部分类") + apps.map { it.category }.distinct().sorted()
    val filtered = apps.filter {
        (device == "全部设备" || deviceLabels[it.deviceId] == device) &&
        (category == "全部分类" || it.category == category) &&
        (it.displayName.contains(search, true) || it.packageName.contains(search, true)) &&
        when (filter) { "可见应用" -> !it.hidden; "已隐藏" -> it.hidden; "已忽略" -> it.ignored; "已安装" -> it.installationStatus == "installed"; "状态未知" -> it.installationStatus == "unknown"; else -> true }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("搜索应用") }, leadingIcon = { Glyph(Symbol.SEARCH) },
            singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(category, categories, { category = it }, Modifier.weight(1f))
            Choice(filter, listOf("可见应用", "全部档案", "已安装", "状态未知", "已隐藏", "已忽略"), { filter = it }, Modifier.weight(1f))
        }
        if (deviceLabels.size > 1) Choice(device, listOf("全部设备") + deviceLabels.values, { device = it })
        LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) item { EmptyState("暂无应用", "") }
            items(filtered, key = { it.identityId }) { app ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column {
                        AppRow(app, filtered.indexOf(app) + 1, filtered.first().knownTotal) { onApp(app.identityId) }
                        if (deviceLabels.size > 1 || manage || app.category != "未分类" || app.ignored || app.signingChanged) Row(Modifier.padding(start = 62.dp, end = 16.dp, bottom = 12.dp)) {
                            Text(listOfNotNull(deviceLabels[app.deviceId].takeIf { deviceLabels.size > 1 }, app.category.takeIf { it != "未分类" }, "已隐藏".takeIf { app.hidden }, "已忽略".takeIf { app.ignored },
                                "签名待确认".takeIf { app.signingChanged }, installationText(app).takeIf { manage }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun TrendScreen(repo: UsageRepository, zone: ZoneId, apps: List<AppSummary>, onDay: (LocalDate, String?, String?) -> Unit) {
    val today = LocalDate.now(zone)
    var range by rememberSaveable { mutableIntStateOf(7) }
    var startText by rememberSaveable { mutableStateOf(today.minusDays(6).toString()) }
    var endText by rememberSaveable { mutableStateOf(today.toString()) }
    var category by rememberSaveable { mutableStateOf("全部分类") }
    var identity by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val start = LocalDate.parse(startText)
    val end = LocalDate.parse(endText)
    val cat = category.takeIf { it != "全部分类" }
    val days by remember(startText, endText, identity, cat) { repo.dao.observeDays(startText, endText, identity, cat) }.collectAsStateWithLifecycle(initialValue = emptyList())
    if (picking) {
        val picker = rememberDateRangePickerState(initialSelectedStartDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() })
        DatePickerDialog(onDismissRequest = { picking = false }, confirmButton = {
            TextButton(enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null, onClick = {
                startText = Instant.ofEpochMilli(picker.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate().toString()
                endText = Instant.ofEpochMilli(picker.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate().toString()
                range = 0; picking = false
            }) { Text("确定") }
        }, dismissButton = { TextButton(onClick = { picking = false }) { Text("取消") } }) {
            DateRangePicker(picker, modifier = Modifier.heightIn(max = 480.dp), title = { Text("选择日期范围", Modifier.padding(20.dp)) },
                headline = {
                    fun dateText(value: Long?) = value?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() } ?: "选择日期"
                    Text("${dateText(picker.selectedStartDateMillis)} – ${dateText(picker.selectedEndDateMillis)}",
                        Modifier.padding(start = 24.dp, end = 64.dp, bottom = 16.dp), style = MaterialTheme.typography.titleMedium)
                }, showModeToggle = true)
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (count in listOf(7, 30)) FilterChip(selected = range == count, onClick = { range = count; endText = today.toString(); startText = today.minusDays(count - 1L).toString() }, label = { Text("${count}天") })
                FilterChip(selected = range == 0, onClick = { picking = true }, label = { Text("自选") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(category, listOf("全部分类") + apps.map { it.category }.distinct().sorted(), { category = it; identity = null }, Modifier.weight(1f))
                var menu by remember { mutableStateOf(false) }
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(apps.firstOrNull { it.identityId == identity }?.displayName ?: "全部应用", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("全部应用") }, onClick = { identity = null; menu = false })
                        apps.filter { cat == null || it.category == cat }.forEach { app -> DropdownMenuItem(text = { Text(app.displayName) }, onClick = { identity = app.identityId; menu = false }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val size = ChronoUnit.DAYS.between(start, end) + 1
                IconButton(onClick = { startText = start.minusDays(size).toString(); endText = end.minusDays(size).toString() }, modifier = Modifier.semantics { contentDescription = "上一段日期" }) { Glyph(Symbol.BACK) }
                Text("${start.monthValue}/${start.dayOfMonth} – ${end.monthValue}/${end.dayOfMonth}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(enabled = end < today, onClick = {
                    val stop = minOf(today, end.plusDays(size)); endText = stop.toString(); startText = stop.minusDays(size - 1).toString()
                }, modifier = Modifier.semantics { contentDescription = "下一段日期" }) { Glyph(Symbol.CHEVRON) }
            }
        }
        item { DayChart(start, end, days) { onDay(it, identity, cat) } }
        items(count = (ChronoUnit.DAYS.between(start, end) + 1).toInt(), key = { end.minusDays(it.toLong()).toString() }) { index ->
            val date = end.minusDays(index.toLong())
            val day = days.firstOrNull { it.reportDate == date.toString() }
            Surface(onClick = { onDay(date, identity, cat) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) { DayRow(date.toString(), day?.durationMs, day?.source) }
        }
    }
}

@Composable internal fun DayChart(start: LocalDate, end: LocalDate, days: List<DaySummary>, onDay: (LocalDate) -> Unit) {
    val max = days.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1L
    val count = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (days.isEmpty()) "未采集" else duration(days.sumOf { it.durationMs }), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (count <= 7) Row(Modifier.fillMaxWidth().height(142.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(count) { index -> val date = start.plusDays(index.toLong()); DayBar(date, days.firstOrNull { it.reportDate == date.toString() }?.durationMs, max, Modifier.weight(1f)) { onDay(date) } }
            } else LazyRow(Modifier.height(142.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(count = count, key = { start.plusDays(it.toLong()).toString() }) { index ->
                    val date = start.plusDays(index.toLong())
                    DayBar(date, days.firstOrNull { it.reportDate == date.toString() }?.durationMs, max, Modifier.width(48.dp)) { onDay(date) }
                }
            }
        }
    }
}

@Composable private fun DayBar(date: LocalDate, ms: Long?, max: Long, modifier: Modifier, onClick: () -> Unit) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val fraction by animateFloatAsState(if (entered && ms != null) (ms.toFloat() / max).coerceIn(0f, 1f) else 0f, tween(480), label = "每日柱图")
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
        .semantics { contentDescription = "$date ${ms?.let(::duration) ?: "未采集"}，查看每日详情" },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        if (ms == null) Text("—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        else Box(Modifier.padding(horizontal = 6.dp).fillMaxWidth().height((fraction * 100).coerceAtLeast(3f).dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .65f)))
        Text(date.dayOfMonth.toString(), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable internal fun AppDetailScreen(app: AppSummary, repo: UsageRepository, zone: ZoneId, categories: List<String>, runAction: RunAction, deviceLabel: String? = null, local: Boolean = true, onDay: (LocalDate) -> Unit) {
    val days by remember(app.identityId) { repo.dao.observeAppDays(app.identityId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val adjustments by remember(app.identityId) { repo.dao.observeAdjustments(app.identityId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = LocalDate.now(zone)
    var editingCategory by rememberSaveable { mutableStateOf(false) }
    var editingTime by rememberSaveable { mutableStateOf(false) }
    var confirmSignature by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<AdjustmentEntity?>(null) }
    if (editingCategory) CategoryDialog(app.category, categories) { value -> if (value != null) runAction { repo.updatePreference(app.identityId, category = value) }; editingCategory = false }
    if (editingTime) AdjustmentDialog(app, repo, today) { editingTime = false }
    if (confirmSignature) AlertDialog(onDismissRequest = { confirmSignature = false }, title = { Text("继续当前档案？") },
        text = { Text("当前安装的应用签名与原档案不同。确认后将继续记入同一档案。") }, confirmButton = { TextButton(onClick = { confirmSignature = false; runAction { repo.acceptSigning(app.identityId) } }) { Text("确认续接") } }, dismissButton = { TextButton(onClick = { confirmSignature = false }) { Text("取消") } })
    if (remove != null) AlertDialog(onDismissRequest = { remove = null }, title = { Text("撤销这条修正？") },
        confirmButton = { TextButton(onClick = { val id = remove!!.adjustmentId; remove = null; runAction { repo.removeAdjustment(id) } }) { Text("撤销") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } })
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app, true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (local) installationText(app) else deviceLabel ?: "导入档案", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("已知总计", style = MaterialTheme.typography.labelLarge)
                Text(duration(app.knownTotal), fontSize = 36.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val month = today.withDayOfMonth(1)
                    for ((label, from) in listOf("今日" to today, "本周" to week, "本月" to month)) {
                        val included = days.filter { it.reportDate >= from.toString() && it.reportDate <= today.toString() }
                        Metric(label, if (included.isEmpty()) "未采集" else duration(included.sumOf { it.durationMs }), Modifier.weight(1f))
                    }
                }
            }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("已知使用天数", "${days.count { it.durationMs > 0 }} 天", Modifier.weight(1f))
            Metric("最长日", days.maxByOrNull { it.durationMs }?.let { "${it.reportDate}\n${duration(it.durationMs)}" } ?: "未采集", Modifier.weight(1f))
        } }
        item { EntryRow("分类", app.category) { editingCategory = true } }
        item { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                ToggleRow("隐藏应用", app.hidden) { runAction { repo.updatePreference(app.identityId, hidden = it) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (local) ToggleRow("忽略后续采集", app.ignored) { runAction { repo.setIgnored(app.identityId, it) } }
            }
        } }
        if (local && app.signingChanged) item { EntryRow("签名变化", "暂停写入，点击确认档案续接") { confirmSignature = true } }
        item { EntryRow("补记／修正时长") { editingTime = true } }
        item { Expandable("档案信息", "记录与来源") {
            if (deviceLabel != null) Text("来源  $deviceLabel", style = MaterialTheme.typography.bodySmall)
            Text("${app.packageName}\n首次记录  ${timeText(app.firstMs, zone)}\n最近前台  ${timeText(app.lastMs, zone)}", style = MaterialTheme.typography.bodySmall)
            Text("已记录  ${duration(app.recordedMs)}\n旧历史  ${duration(app.historicalMs)}\n手动修正  ${signedDuration(app.adjustmentMs)}", style = MaterialTheme.typography.bodySmall)
        } }
        if (adjustments.isNotEmpty()) item { Expandable("手动修正", "${adjustments.size} 条") {
            adjustments.forEach { entry -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("${entry.reportDate}  ${signedDuration(entry.deltaMs)}", style = MaterialTheme.typography.bodyMedium); if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { remove = entry }) { Text("撤销") }
            } }
        } }
        item { Text("每日用时", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (days.isEmpty()) item { EmptyState("暂无每日数据", "") }
        items(days, key = { it.reportDate }) { day -> Surface(onClick = { onDay(LocalDate.parse(day.reportDate)) }, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) { DayRow(day.reportDate, day.durationMs, day.source) } }
    }
}

@Composable private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked, onChange) }
}

@Composable private fun CategoryDialog(current: String, categories: List<String>, onClose: (String?) -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(onDismissRequest = { onClose(null) }, title = { Text("应用分类") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(name, (listOf("未分类", "社交", "影音", "游戏", "工具", "工作", "学习") + categories).distinct(), { name = it })
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("分类名称") }, singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onClose(name.trim()) }) { Text("保存") } }, dismissButton = { TextButton(onClick = { onClose(null) }) { Text("取消") } })
}

@Composable private fun AdjustmentDialog(app: AppSummary, repo: UsageRepository, today: LocalDate, onClose: () -> Unit) {
    var date by rememberSaveable { mutableStateOf(today.toString()) }
    var minutes by rememberSaveable { mutableStateOf("") }
    var sign by rememberSaveable { mutableStateOf("补记") }
    var note by rememberSaveable { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (picking) PickDay(LocalDate.parse(date), today, { date = it.toString(); picking = false }, { picking = false })
    AlertDialog(onDismissRequest = { if (!saving) onClose() }, title = { Text("补记／修正") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { picking = true }) { Text(date) }
            Choice(sign, listOf("补记", "扣减"), { sign = it })
            OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("分钟") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            OutlinedTextField(note, { note = it.take(120) }, label = { Text("备注（可选）") }, maxLines = 2)
            if (problem != null) Text(problem!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        val amount = minutes.toLongOrNull()
        if (amount == null || amount !in 1..1440) problem = "请输入 1–1440 分钟"
        else { saving = true; scope.launch {
            try { withContext(Dispatchers.IO) { repo.addAdjustment(app.identityId, LocalDate.parse(date), amount * 60_000 * if (sign == "扣减") -1 else 1, note) }; onClose() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: IllegalArgumentException) { problem = e.message }
            catch (_: Exception) { problem = "保存失败，请稍后重试" }
            finally { saving = false }
        } }
    }) { Text(if (saving) "保存中" else "保存") } }, dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PickDay(date: LocalDate, latest: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    })
    DatePickerDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(enabled = state.selectedDateMillis != null, onClick = { onPick(Instant.ofEpochMilli(state.selectedDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }) { DatePicker(state, title = { Text("选择日期", Modifier.padding(20.dp)) }) }
}

internal fun installationText(app: AppSummary) = when { app.signingChanged -> "签名待确认"; app.installationStatus == "installed" -> "已安装"; else -> "安装状态未知" }
internal fun signedDuration(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "") + duration(kotlin.math.abs(ms))
