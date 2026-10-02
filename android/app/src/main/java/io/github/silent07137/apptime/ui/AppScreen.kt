// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.silent07137.apptime.data.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext

typealias RunAction = (suspend () -> Unit) -> Unit

@Composable
fun AppScreen(repo: UsageRepository, collecting: MutableStateFlow<Boolean>, importing: MutableStateFlow<Boolean>, error: MutableStateFlow<String?>,
    access: MutableStateFlow<Boolean>, refresh: () -> Unit, openPermission: () -> Unit, importHistory: (Int) -> Unit) {
    val allApps by remember(repo) { repo.dao.observeApps() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val apps = allApps.filter { it.durationMs + it.historicalMs + it.recordedMs + kotlin.math.abs(it.adjustmentMs) > 0 }
    val visible = apps.filter { !it.hidden }
    val state by remember(repo) { repo.dao.observeState() }.collectAsStateWithLifecycle(initialValue = null)
    val history by remember(repo) { repo.dao.observeHistoryState() }.collectAsStateWithLifecycle(initialValue = null)
    val gaps by remember(repo) { repo.dao.observeGapCount() }.collectAsStateWithLifecycle(initialValue = 0)
    val busy by collecting.collectAsStateWithLifecycle()
    val historyBusy by importing.collectAsStateWithLifecycle()
    val failure by error.collectAsStateWithLifecycle()
    val authorized by access.collectAsStateWithLifecycle()
    var routes by rememberSaveable { mutableStateOf(listOf("tab:0")) }
    var forwardNavigation by remember { mutableStateOf(true) }
    val pageState = rememberSaveableStateHolder()
    val page = routes.last()
    val root = page.startsWith("tab:")
    val tab = routes.first().removePrefix("tab:").toInt()
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    var localDeviceId by remember { mutableStateOf<String?>(null) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var archiveBusy by remember { mutableStateOf(false) }
    val devices by remember(repo) { repo.dao.observeDevices() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var license by remember { mutableStateOf<String?>(null) }
    var licenseTitle by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val runAction: RunAction = { action -> scope.launch {
        try { withContext(Dispatchers.IO) { action() } }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (problem: IllegalArgumentException) { snackbar.showSnackbar(problem.message ?: "无法保存，请检查输入") }
        catch (_: Exception) { snackbar.showSnackbar("保存失败，请稍后重试") }
    } }
    LaunchedEffect(state, devices) { withContext(Dispatchers.IO) { repo.dao.device() }?.let { zone = ZoneId.of(it.reportTimezone); localDeviceId = it.deviceId } }
    val deviceLabels = devices.associate { it.deviceId to if (it.deviceId == localDeviceId) "本机" else "导入设备 ${it.deviceId.take(8)}" }
    val today = LocalDate.now(zone)
    val days by remember(repo, today) { repo.dao.observeDays(today.minusDays(6).toString(), today.toString()) }.collectAsStateWithLifecycle(initialValue = emptyList())
    fun push(route: String) { if (routes.last() != route) { forwardNavigation = true; routes = routes + route } }
    fun back() { if (routes.size > 1) { forwardNavigation = false; routes = routes.dropLast(1) } }
    fun openDay(date: LocalDate, id: String? = null, category: String? = null) {
        push("day:$date|${id.orEmpty()}|${Uri.encode(category.orEmpty())}")
    }
    BackHandler(enabled = !root) { back() }
    if (showInfo) AlertDialog(onDismissRequest = { showInfo = false }, title = { Text("数据说明") },
        text = { LazyColumn { item { DataNotes(state, history, gaps, zone, devices.size > 1) } } }, confirmButton = { TextButton(onClick = { showInfo = false }) { Text("关闭") } })
    if (license != null) AlertDialog(onDismissRequest = { license = null }, title = { Text(licenseTitle) },
        text = { LazyColumn { item { Text(license!!) } } }, confirmButton = { TextButton(onClick = { license = null }) { Text("关闭") } })
    fun openAsset(title: String, name: String) {
        scope.launch {
            try {
                licenseTitle = title
                license = withContext(Dispatchers.IO) { context.assets.open(name).bufferedReader().use { it.readText() } }
            } catch (_: java.io.IOException) { snackbar.showSnackbar("无法读取许可证") }
        }
    }
    fun openRepository() {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/silent07137/AppTime"))) }
        catch (_: ActivityNotFoundException) { scope.launch { snackbar.showSnackbar("没有可用的浏览器") } }
    }
    val title = when {
        page == "about" -> "关于"
        page == "manage" -> "应用管理"
        page == "backup" -> "备份与诊断"
        page.startsWith("day:") -> "每日详情"
        page.startsWith("app:") -> "应用详情"
        else -> listOf("AppTime", "应用", "趋势", "设置")[tab]
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        AnimatedVisibility(root, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                val labels = listOf("总览", "应用", "趋势", "设置")
                val icons = listOf(Symbol.CLOCK, Symbol.APPS, Symbol.CHART, Symbol.SETTINGS)
                labels.forEachIndexed { index, label -> NavigationBarItem(selected = tab == index,
                    onClick = { routes = listOf("tab:$index") }, icon = { Glyph(icons[index]) }, label = { Text(label) }) }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!root) IconButton(onClick = ::back, enabled = !archiveBusy, modifier = Modifier.semantics { contentDescription = "返回" }) { Glyph(Symbol.BACK) }
                AnimatedContent(title, modifier = Modifier.weight(1f).padding(start = if (root) 8.dp else 0.dp), label = "标题") {
                    Text(it, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                }
                if (page !in listOf("about", "backup")) IconButton(onClick = refresh, enabled = !busy && !historyBusy,
                    modifier = Modifier.semantics { contentDescription = "刷新记录" }) { Glyph(Symbol.REFRESH) }
            }
            AnimatedVisibility(busy || historyBusy, enter = fadeIn(), exit = fadeOut()) { LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) }
            if (!authorized && page == "tab:0") Card(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state?.enabled == true) "权限已关闭" else "开启时间记录", fontWeight = FontWeight.Bold)
                    Button(onClick = openPermission) { Text("授权使用情况访问") }
                }
            }
            if (failure != null) Text(failure!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            AnimatedContent(targetState = page, modifier = Modifier.weight(1f), transitionSpec = {
                val forward = when {
                    initialState.startsWith("tab:") && targetState.startsWith("tab:") -> targetState > initialState
                    initialState.startsWith("day:") && targetState.startsWith("day:") -> targetState > initialState
                    else -> forwardNavigation
                }
                val direction = if (forward) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                (slideIntoContainer(direction, tween(320)) + fadeIn(tween(240))) togetherWith
                    (slideOutOfContainer(direction, tween(320)) + fadeOut(tween(180)))
            }, label = "页面进退") { activePage ->
                val interaction = if (activePage == page) Modifier else Modifier.clearAndSetSemantics { }.pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                }
                Box(Modifier.fillMaxSize().then(interaction)) {
                pageState.SaveableStateProvider(activePage) {
                when {
                    activePage == "about" -> AboutScreen(context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "", ::openRepository, ::openAsset)
                    activePage == "backup" -> BackupScreen(repo, { archiveBusy = it }) {
                        routes = listOf("tab:0")
                        refresh()
                        scope.launch { snackbar.showSnackbar("恢复完成") }
                    }
                    activePage == "manage" -> AppListScreen(allApps, manage = true, deviceLabels = deviceLabels) { push("app:$it") }
                    activePage.startsWith("app:") -> allApps.firstOrNull { it.identityId == activePage.removePrefix("app:") }?.let {
                        val appZone = devices.firstOrNull { d -> d.deviceId == it.deviceId }?.reportTimezone?.let(ZoneId::of) ?: zone
                        AppDetailScreen(it, repo, appZone, allApps.map { a -> a.category }.distinct(), runAction, deviceLabels[it.deviceId].takeIf { devices.size > 1 }, it.deviceId == localDeviceId) { date -> openDay(date, it.identityId) }
                    }
                    activePage.startsWith("day:") -> {
                        val parts = activePage.removePrefix("day:").split('|')
                        val date = LocalDate.parse(parts[0])
                        val owner = allApps.firstOrNull { it.identityId == parts[1] }?.deviceId
                        val dayZone = devices.firstOrNull { it.deviceId == owner }?.reportTimezone?.let(ZoneId::of) ?: zone
                        DayDetailScreen(date, parts[1].ifEmpty { null }, Uri.decode(parts[2]).ifEmpty { null }, repo, dayZone, allApps,
                            onDate = { changed -> routes = routes.dropLast(1) + "day:$changed|${parts[1]}|${parts[2]}" },
                            onApp = { push("app:$it") })
                    }
                    else -> when (activePage.removePrefix("tab:").toInt()) {
                        0 -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { TotalCard(apps, apps.any { it.recordedMs > 0 }, history, zone, devices.size > 1) { showInfo = true } }
                            if (state?.status in listOf("不可用", "存在缺口", "采集失败")) item {
                                TextButton(onClick = { showInfo = true }) { Text(state?.status ?: "数据状态"); Glyph(Symbol.CHEVRON, Modifier.size(18.dp)) }
                            }
                            item { SectionHeading("累计排行", "全部") { routes = listOf("tab:1") } }
                            if (visible.isEmpty()) item { EmptyState("暂无可显示的记录", "") }
                            if (visible.isNotEmpty()) item {
                                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                                    Column { visible.take(5).forEachIndexed { index, app ->
                                        AppRow(app, index + 1, visible.first().knownTotal) { push("app:${app.identityId}") }
                                        if (index < minOf(4, visible.lastIndex)) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
                                    } }
                                }
                            }
                            item { SectionHeading("最近 7 天", "全部") { routes = listOf("tab:2") } }
                            item { DayChart(today.minusDays(6), today, days) { openDay(it) } }
                        }
                        1 -> AppListScreen(apps, deviceLabels = deviceLabels) { push("app:$it") }
                        2 -> TrendScreen(repo, zone, allApps, ::openDay)
                        3 -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { SettingsCard("采集", if (authorized) "已授权" else "未授权") {
                                TextButton(onClick = openPermission) { Text("管理权限") }
                                if (state?.status in listOf("不可用", "存在缺口", "采集失败")) Text(state?.detail.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            } }
                            item { EntryRow("应用管理", "分类、隐藏与忽略") { push("manage") } }
                            item { EntryRow("重新读取历史", enabled = authorized && !busy && !historyBusy) { importHistory(0) } }
                            item { EntryRow("备份与诊断") { push("backup") } }
                            item { Expandable("数据说明", "来源与覆盖") { DataNotes(state, history, gaps, zone, devices.size > 1) } }
                            item { Expandable("隐私与保存", "本机离线保存") {
                                Text("不采集屏幕或输入内容。后台每 6 小时尝试采集，强停后需重新打开。", style = MaterialTheme.typography.bodySmall)
                                Text("卸载或清除数据前，请先导出备份。", style = MaterialTheme.typography.bodySmall)
                            } }
                            item { EntryRow("关于 AppTime") { push("about") } }
                        }
                    }
                }
                }
                }
            }
        }
    }
}
