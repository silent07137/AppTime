// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.silent07137.apptime.R

@Composable internal fun AboutScreen(version: String, openRepository: () -> Unit, openAsset: (String, String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)))
            Text("AppTime", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(version, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { AboutGroup("项目") {
            AboutRow(Symbol.APPS, "开源仓库", "silent07137 / AppTime", openRepository)
        } }
        item { AboutGroup("许可") {
            AboutRow(Symbol.INFO, "开源许可证", "GNU GPL v2") { openAsset("GNU GPL v2", "LICENSE") }
            HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            AboutRow(Symbol.INFO, "第三方许可证") { openAsset("第三方许可证", "THIRD_PARTY_NOTICES.txt") }
            HorizontalDivider(Modifier.padding(start = 60.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            AboutRow(Symbol.INFO, "Apache License 2.0") { openAsset("Apache License 2.0", "APACHE-2.0.txt") }
        } }
    }
}

@Composable private fun AboutGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) { Column(content = content) }
    }
}

@Composable private fun AboutRow(symbol: Symbol, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) { Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Glyph(symbol, Modifier.size(20.dp)) } }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Glyph(Symbol.CHEVRON, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
