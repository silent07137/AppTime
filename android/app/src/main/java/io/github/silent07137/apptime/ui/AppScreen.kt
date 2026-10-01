// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppScreen(repo: UsageRepository, collecting: MutableStateFlow<Boolean>, importing: MutableStateFlow<Boolean>, error: MutableStateFlow<String?>,
    access: MutableStateFlow<Boolean>, refresh: () -> Unit, openPermission: () -> Unit, importHistory: (Int) -> Unit) {
    val allApps by remember(repo) { repo.dao.observeApps() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val apps = allApps.filter { it.durationMs + it.historicalMs + it.recordedMs > 0 }
    val state by remember(repo) { repo.dao.observeState() }.collectAsStateWithLifecycle(initialValue = null)
    val history by remember(repo) { repo.dao.observeHistoryState() }.collectAsStateWithLifecycle(initialValue = null)
    val gaps by remember(repo) { repo.dao.observeGapCount() }.collectAsStateWithLifecycle(initialValue = 0)
    val busy by collecting.collectAsStateWithLifecycle()
    val historyBusy by importing.collectAsStateWithLifecycle()
    val failure by error.collectAsStateWithLifecycle()
    val authorized by access.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var license by remember { mutableStateOf<String?>(null) }
    var licenseTitle by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { withContext(Dispatchers.IO) { repo.dao.device() }?.let { zone = ZoneId.of(it.reportTimezone) } }
    val today = LocalDate.now(zone)
    val days by remember(repo, today) { repo.dao.observeDays(today.minusDays(6).toString()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val selected = allApps.firstOrNull { it.identityId == selectedId }
    val page = if (aboutOpen) "about" else selectedId?.let { "app:$it" } ?: "tab:$tab"
    BackHandler(enabled = aboutOpen || selectedId != null) { if (aboutOpen) aboutOpen = false else selectedId = null }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("数据说明") },
        text = { DataNotes(state, history, gaps, zone) }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("知道了") } })
    if (license != null) AlertDialog(onDismissRequest = { license = null }, title = { Text(licenseTitle) },
        text = { LazyColumn { item { Text(license!!) } } }, confirmButton = { TextButton(onClick = { license = null }) { Text("关闭") } })
    fun openAsset(title: String, name: String) {
        scope.launch {
            licenseTitle = title
            license = withContext(Dispatchers.IO) { context.assets.open(name).bufferedReader().use { it.readText() } }
        }
    }
    fun openRepository() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/silent07137/AppTime"))) }
        catch (_: ActivityNotFoundException) { error.value = "没有可打开仓库链接的浏览器。" }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (selectedId == null && !aboutOpen) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            val labels = listOf("总览", "应用", "趋势", "设置")
            val icons = listOf(Symbol.CLOCK, Symbol.APPS, Symbol.CHART, Symbol.SETTINGS)
            labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index, onClick = { tab = index },
                icon = { Glyph(icons[index]) }, label = { Text(label) }) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selectedId != null || aboutOpen) IconButton(onClick = { if (aboutOpen) aboutOpen = false else selectedId = null }, modifier = Modifier.semantics { contentDescription = "返回" }) { Glyph(Symbol.BACK) }
                Column(Modifier.weight(1f)) {
                    Text(if (aboutOpen) "关于 AppTime" else if (selectedId != null) "应用详情" else listOf("AppTime", "应用档案", "使用趋势", "设置")[tab], fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    if (selectedId == null && !aboutOpen) Text(if (tab == 0) "把时间，留成记录" else listOf("", "${apps.size} 个应用 · 按累计排序", "最近 7 天 · 每日使用", "本机保存 · 离线运行")[tab],
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!aboutOpen) IconButton(onClick = refresh, enabled = !busy && !historyBusy, modifier = Modifier.semantics { contentDescription = "刷新记录" }) { Glyph(Symbol.REFRESH) }
            }
            if (busy || historyBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!authorized) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state?.enabled == true) "权限已关闭" else "开始你的时间档案", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (state?.enabled == true) "已有记录已保留，授权后继续。" else "授权后自动读取系统保留的最早历史。", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = openPermission) { Text("开启使用情况访问") }
                    }
                }
            }
            if (failure != null) Text(failure!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Crossfade(targetState = page, animationSpec = tween(220), label = "页面切换") { activePage ->
            if (activePage == "about") {
                AboutScreen(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "", ::openRepository, ::openAsset)
            } else if (activePage.startsWith("app:")) {
                allApps.firstOrNull { it.identityId == activePage.removePrefix("app:") }?.let { AppDetail(it, repo, zone) }
            } else when (activePage.removePrefix("tab:").toInt()) {
                0 -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item { TotalCard(apps, allApps.any { it.recordedMs > 0 }, history, zone, onInfo = { showInfo = true }) }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (busy) "正在同步记录…" else if (state?.lastSuccessMs != null) "更新于 ${timeText(state?.lastSuccessMs, zone, "HH:mm")}" else "等待采集",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { showInfo = true }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (gaps > 0 || state?.status == "不可用") "查看数据状态" else "数据说明") }
                        }
                    }
                    item { SectionHeading("累计排行", "查看全部") { tab = 1 } }
                    if (apps.isEmpty()) item { EmptyState(if (authorized) "等待系统返回记录" else "还没有时间记录", "已有数据不会被空查询清除") }
                    if (apps.isNotEmpty()) item {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                            Column { apps.take(5).forEachIndexed { index, app ->
                                AppRow(app, index + 1, apps.first().knownTotal) { selectedId = app.identityId }
                                if (index < minOf(4, apps.lastIndex)) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                            } }
                        }
                    }
                    item { SectionHeading("最近 7 天", "查看趋势") { tab = 2 } }
                    item { WeekChart(today, days) }
                }
                1 -> Column(Modifier.padding(horizontal = 20.dp)) {
                    OutlinedTextField(value = search, onValueChange = { search = it }, placeholder = { Text("搜索应用") }, leadingIcon = { Glyph(Symbol.SEARCH) },
                        singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
                    val filtered = apps.filter { it.displayName.contains(search, true) || it.packageName.contains(search, true) }
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (filtered.isEmpty()) item { EmptyState(if (apps.isEmpty()) "等待系统返回记录" else "没有找到应用", if (apps.isEmpty()) "授权后会自动导入可用历史" else "试试应用名称或包名") }
                        items(filtered, key = { it.identityId }) { app ->
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                                AppRow(app, apps.indexOf(app) + 1, apps.first().knownTotal) { selectedId = app.identityId }
                            }
                        }
                    }
                }
                2 -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { WeekChart(today, days) }
                    item { Text("每天的使用时长", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items((0L..6L).map { today.minusDays(it) }) { date ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                            days.firstOrNull { it.reportDate == date.toString() }.let { DayRow(date.toString(), it?.durationMs, it?.source) }
                        }
                    }
                    item { Expandable("关于趋势", "每日数据来源") {
                        Text("优先显示系统返回的逐日时长；系统未返回的日期使用已采集事件。更早的宽范围汇总不能可靠拆到每天。报表时区：${zone.id}。", style = MaterialTheme.typography.bodyMedium)
                    } }
                }
                3 -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { SettingsCard("采集", if (authorized) "使用情况访问已开启" else "需要使用情况访问") {
                        Text("最后保存  ${timeText(state?.lastSuccessMs, zone)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = openPermission) { Text("管理权限") }
                    } }
                    item { SettingsCard("历史", "默认导入系统保留的最早记录") {
                        Text(history?.returnedStartMs?.let { "可追溯至 ${timeText(it, zone, "yyyy-MM-dd")}" } ?: if (history == null) "授权后自动导入" else "系统未返回可用历史",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { importHistory(0) }, enabled = authorized && !busy && !historyBusy) { Text(if (historyBusy) "导入中…" else "重新读取全部历史") }
                    } }
                    item { Expandable("数据说明", "来源、覆盖与统计口径") { DataNotes(state, history, gaps, zone) } }
                    item { Expandable("隐私与保存", "离线运行，无账号和广告") {
                        Text("数据只保存在本机；不采集屏幕和输入内容。后台每 6 小时尝试采集，系统可能延迟，强停后需重新打开。", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("此版尚无备份。卸载 AppTime 或清除数据会移除本机档案。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    } }
                    item { SettingsCard("关于 AppTime", "版本、源码与许可") {
                        TextButton(onClick = { aboutOpen = true }) { Text("查看关于页面"); Glyph(Symbol.CHEVRON, Modifier.size(18.dp)) }
                    } }
                }
            }
            }
        }
    }
}

private val AppSummary.knownTotal: Long get() = durationMs + historicalMs

@Composable private fun TotalCard(apps: List<AppSummary>, hasSessions: Boolean, history: HistoryImportState?, zone: ZoneId, onInfo: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("已知总计", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onInfo, modifier = Modifier.size(40.dp).semantics { contentDescription = "累计数据说明" }) { Glyph(Symbol.INFO) }
            }
            Text(if (apps.isEmpty()) "未采集" else duration(apps.sumOf { it.knownTotal }), fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("已记录", if (hasSessions) duration(apps.sumOf { it.recordedMs }) else "未采集", Modifier.weight(1f))
                Metric("可追溯旧历史", if (apps.any { it.historicalBuckets > 0 }) duration(apps.sumOf { it.historicalMs }) else "未取得", Modifier.weight(1f))
            }
            Text(history?.returnedStartMs?.let { "可追溯至 ${timeText(it, zone, "yyyy-MM-dd")} · 完整性未知" } ?: "各应用前台时长之和", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
        }
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = .7f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(action); Glyph(Symbol.CHEVRON, Modifier.size(18.dp)) }
    }
}

@Composable private fun AppRow(app: AppSummary, rank: Int, max: Long, click: () -> Unit) {
    Surface(onClick = click, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(rank.toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
            AppIcon(app)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { (app.knownTotal.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .7f), trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {})
            }
            Text(duration(app.knownTotal), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(max = 108.dp))
        }
    }
}

@Composable private fun AppIcon(app: AppSummary, large: Boolean = false) {
    val context = LocalContext.current
    var icon by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        icon = withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(app.packageName)
                val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, 96, 96)
                drawable.draw(AndroidCanvas(bitmap))
                bitmap.asImageBitmap()
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) { null }
            catch (_: SecurityException) { null }
        }
    }
    val modifier = Modifier.size(if (large) 64.dp else 40.dp).clip(RoundedCornerShape(if (large) 18.dp else 12.dp))
    if (icon != null) Image(icon!!, contentDescription = null, modifier = modifier)
    else Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(app.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable private fun WeekChart(today: LocalDate, days: List<DaySummary>) {
    val dates = (6L downTo 0L).map { today.minusDays(it) }
    val max = days.maxOfOrNull { it.durationMs }?.coerceAtLeast(1) ?: 1L
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("每日使用", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (days.isEmpty()) "未采集" else duration(days.sumOf { it.durationMs }), fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                dates.forEach { date ->
                    val value = days.firstOrNull { it.reportDate == date.toString() }?.durationMs
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        if (value == null) Text("—", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                        else Box(Modifier.fillMaxWidth().height((value.toFloat() / max * 84).coerceAtLeast(3f).dp).clip(RoundedCornerShape(6.dp)).background(
                            if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = .35f)))
                        Spacer(Modifier.height(12.dp))
                        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("系统逐日统计优先 · 缺失时使用事件记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AppDetail(app: AppSummary, repo: UsageRepository, zone: ZoneId) {
    val days by remember(app.identityId) { repo.dao.observeAppDays(app.identityId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app, large = true)
                Column(Modifier.weight(1f)) {
                    Text(app.displayName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text("已知总计", style = MaterialTheme.typography.titleMedium)
                    Text(duration(app.knownTotal), fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("已记录", if (app.recordedMs > 0) duration(app.recordedMs) else "未采集", Modifier.weight(1f))
                        Metric("可追溯旧历史", if (app.historicalBuckets > 0) duration(app.historicalMs) else "未取得", Modifier.weight(1f))
                    }
                }
            }
        }
        item { Expandable("档案信息", "首次记录与来源") {
            Text("首次记录  ${timeText(app.firstMs, zone)}\n最近前台  ${timeText(app.lastMs, zone)}\n安装状态  未知", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Text("前台事件和系统汇总分开保存；已记录与旧历史可能重叠，不可直接相加。首次记录不代表安装时间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Text("每天的使用时长", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (days.isEmpty()) item { EmptyState("系统尚未返回每日数据", "已有宽范围历史仍保留在累计中") }
        items(days, key = { it.reportDate }) { Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) { DayRow(it.reportDate, it.durationMs, it.source) } }
    }
}

@Composable private fun DayRow(date: String, ms: Long?, source: String? = null) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(date, style = MaterialTheme.typography.bodyMedium)
            if (source != null) Text(when (source) { "system" -> "系统逐日统计"; "events" -> "事件记录"; else -> "系统与事件记录" },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(ms?.let(::duration) ?: "未采集", style = MaterialTheme.typography.titleSmall, color = if (ms == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun AboutScreen(version: String, openRepository: () -> Unit, openAsset: (String, String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SettingsCard("AppTime", "版本 $version · GPLv2") {
            Text("离线保存应用使用时长", style = MaterialTheme.typography.bodyMedium)
        } }
        item { SettingsCard("开源仓库", "github.com/silent07137/AppTime") {
            TextButton(onClick = openRepository) { Text("打开 GitHub 仓库"); Glyph(Symbol.CHEVRON, Modifier.size(18.dp)) }
        } }
        item { SettingsCard("许可证", "应用源码与第三方组件") {
            TextButton(onClick = { openAsset("GNU GPL v2", "LICENSE") }) { Text("开源许可证 · GPLv2") }
            TextButton(onClick = { openAsset("第三方许可证", "THIRD_PARTY_NOTICES.txt") }) { Text("第三方许可证") }
            TextButton(onClick = { openAsset("Apache License 2.0", "APACHE-2.0.txt") }) { Text("Apache-2.0 全文") }
        } }
    }
}

@Composable private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable private fun Expandable(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "−" else "+", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) { Spacer(Modifier.height(8.dp)); content() }
        }
    }
}

@Composable private fun DataNotes(state: CollectionState?, history: HistoryImportState?, gaps: Int, zone: ZoneId) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("已记录是保存的前台事件累计；它与旧历史可能重叠，不能直接相加。已知总计按历史范围去重。每日时长优先采用系统逐日统计，缺失时使用事件记录。", style = MaterialTheme.typography.bodyMedium)
        Text("记录起点  ${timeText(state?.takeIf { it.enabled }?.recordFromMs, zone)}\n最后保存  ${timeText(state?.lastSuccessMs, zone)}\n采集状态  ${state?.status ?: "未采集"}", style = MaterialTheme.typography.bodySmall)
        if (gaps > 0) Text("$gaps 段查询范围不可用或完整性未知；记录已保留。", style = MaterialTheme.typography.bodySmall)
        if (history != null) Text("历史查询  ${if (history.requestedStartMs == 0L) "系统保留的最早记录" else timeText(history.requestedStartMs, zone)} → ${timeText(history.requestedEndMs, zone)}\n实际返回  ${timeText(history.returnedStartMs, zone)} → ${timeText(history.returnedEndMs, zone)}\n采用 ${history.acceptedBuckets} 个汇总，跳过 ${history.skippedBuckets} 个。", style = MaterialTheme.typography.bodySmall)
        Text("旧历史原始时区未知，返回起点是汇总边界。跨越建立时间的完整汇总归旧历史估算，可能包含建立后的时长；重叠会话不会再次计入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Glyph(Symbol.CLOCK, Modifier.size(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun duration(ms: Long): String {
    val seconds = ms / 1_000
    return when { ms in 1..999 -> "<1 秒"; seconds < 60 -> "$seconds 秒"; seconds < 3_600 -> "${seconds / 60} 分"; else -> "${seconds / 3_600} 小时 ${(seconds / 60) % 60} 分" }
}
private fun timeText(ms: Long?, zone: ZoneId, pattern: String = "yyyy-MM-dd HH:mm"): String = ms?.let {
    Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern(pattern))
} ?: "未采集"
