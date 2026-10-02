// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
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

internal val AppSummary.knownTotal: Long get() = (durationMs + historicalMs + adjustmentMs).coerceAtLeast(0)

@Composable internal fun TotalCard(apps: List<AppSummary>, hasSessions: Boolean, history: HistoryImportState?, zone: ZoneId, multipleDevices: Boolean = false, onInfo: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (multipleDevices) "各设备已知总计" else "已知总计", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onInfo, modifier = Modifier.size(40.dp).semantics { contentDescription = "累计数据说明" }) { Glyph(Symbol.INFO) }
            }
            Text(if (apps.isEmpty()) "未采集" else duration(apps.sumOf { it.knownTotal }), fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Metric("已记录", if (hasSessions) duration(apps.sumOf { it.recordedMs }) else "未采集", Modifier.weight(1f))
                Metric("可追溯旧历史", if (apps.any { it.historicalBuckets > 0 }) duration(apps.sumOf { it.historicalMs }) else "未取得", Modifier.weight(1f))
            }

        }
    }
}

@Composable internal fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalContentColor.current.copy(alpha = .7f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable internal fun SectionHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(action); Glyph(Symbol.CHEVRON, Modifier.size(18.dp)) }
    }
}

@Composable internal fun AppRow(app: AppSummary, rank: Int, max: Long, click: () -> Unit) {
    Surface(onClick = click, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(rank.toString().padStart(2, '0'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
            AppIcon(app)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val fraction by animateFloatAsState((app.knownTotal.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f), tween(420), label = "排行进度")
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = .7f), trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {})
            }
            Text(duration(app.knownTotal), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(max = 108.dp))
        }
    }
}

@Composable internal fun AppIcon(app: AppSummary, large: Boolean = false) = PackageIcon(app.packageName, app.displayName, large)

@Composable internal fun PackageIcon(pkg: String, name: String, large: Boolean = false) {
    val context = LocalContext.current
    var icon by remember(pkg) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pkg) {
        icon = withContext(Dispatchers.IO) {
            (context.applicationContext as io.github.silent07137.apptime.AppTimeApplication).catalog.icon(pkg)?.asImageBitmap()
        }
    }
    val modifier = Modifier.size(if (large) 64.dp else 40.dp).clip(RoundedCornerShape(if (large) 18.dp else 12.dp))
    if (icon != null) Image(icon!!, contentDescription = null, modifier = modifier)
    else Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable internal fun DayRow(date: String, ms: Long?, source: String? = null) {
    Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(date, style = MaterialTheme.typography.bodyMedium)
            if (source != null) Text(sourceText(source),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(ms?.let(::duration) ?: "未采集", style = MaterialTheme.typography.titleSmall, color = if (ms == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            Glyph(Symbol.CHEVRON, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable internal fun Expandable(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (expanded) "−" else "+", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
            AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Spacer(Modifier.height(8.dp)); content() }
            }
        }
    }
}

@Composable internal fun DataNotes(state: CollectionState?, history: HistoryImportState?, gaps: Int, zone: ZoneId, multipleDevices: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("已记录是保存的前台事件累计；它与旧历史可能重叠，不能直接相加。已知总计按历史范围去重。每日时长优先采用系统逐日统计，缺失时使用事件记录。", style = MaterialTheme.typography.bodyMedium)
        if (multipleDevices) Text("总计为各设备用时之和，同时使用多台设备会分别计时。每日汇总保留来源设备的报表日期；查看具体应用时采用其来源时区。", style = MaterialTheme.typography.bodySmall)
        Text("记录起点  ${timeText(state?.takeIf { it.enabled }?.recordFromMs, zone)}\n最后保存  ${timeText(state?.lastSuccessMs, zone)}\n采集状态  ${state?.status ?: "未采集"}", style = MaterialTheme.typography.bodySmall)
        if (gaps > 0) Text("$gaps 段查询范围不可用或完整性未知；记录已保留。", style = MaterialTheme.typography.bodySmall)
        if (history != null) Text("历史查询  ${if (history.requestedStartMs == 0L) "系统保留的最早记录" else timeText(history.requestedStartMs, zone)} → ${timeText(history.requestedEndMs, zone)}\n实际返回  ${timeText(history.returnedStartMs, zone)} → ${timeText(history.returnedEndMs, zone)}\n采用 ${history.acceptedBuckets} 个汇总，跳过 ${history.skippedBuckets} 个。", style = MaterialTheme.typography.bodySmall)
        Text("旧历史原始时区未知，返回起点是汇总边界。跨越建立时间的完整汇总归旧历史估算，可能包含建立后的时长；重叠会话不会再次计入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun EmptyState(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Glyph(Symbol.CLOCK, Modifier.size(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun duration(ms: Long): String {
    val seconds = ms / 1_000
    return when { ms in 1..999 -> "<1 秒"; seconds < 60 -> "$seconds 秒"; seconds < 3_600 -> "${seconds / 60} 分"; else -> "${seconds / 3_600} 小时 ${(seconds / 60) % 60} 分" }
}
internal fun timeText(ms: Long?, zone: ZoneId, pattern: String = "yyyy-MM-dd HH:mm"): String = ms?.let {
    Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern(pattern))
} ?: "未采集"

internal fun sourceText(source: String): String = when(source) { "system" -> "系统逐日统计"; "events" -> "事件记录"; "manual" -> "手动修正"; else -> "多种来源" }
