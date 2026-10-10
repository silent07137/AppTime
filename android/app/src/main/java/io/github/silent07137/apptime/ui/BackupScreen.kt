// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.silent07137.apptime.backup.*
import io.github.silent07137.apptime.data.UsageRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.BadPaddingException
import kotlinx.coroutines.*

@Composable internal fun BackupScreen(repo: UsageRepository, onBusy: (Boolean) -> Unit, onRestored: () -> Unit) {
    val context = LocalContext.current
    val service = remember(repo) { ArchiveService(context.applicationContext, repo) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var plan by remember { mutableStateOf<RestorePlan?>(null) }
    var restorePassword by remember { mutableStateOf<CharArray?>(null) }
    var replace by remember { mutableStateOf(false) }
    var samePhone by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var includeNames by remember { mutableStateOf(false) }
    var hasProtection by remember { mutableStateOf(service.recoveryFile.exists()) }
    fun launch(action: suspend () -> String) {
        busy = true; message = "正在处理…"; failed = false
        scope.launch {
            try { message = withContext(Dispatchers.IO) { action() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: BadPaddingException) { failed = true; message = "口令错误或备份文件已损坏，原档案未修改" }
            catch (problem: IllegalArgumentException) { failed = true; message = problem.message?.takeIf { it.length < 100 } ?: "备份格式或内容无效，原档案未修改" }
            catch (_: Exception) { failed = true; message = "操作失败，请检查文件或保存位置；原档案已保留" }
            finally { busy = false; hasProtection = service.recoveryFile.exists() }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) { selected = uri; operation = "export" }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { selected = uri; operation = "restore" }
    }
    val protect = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) launch { service.copyVerified(service.recoveryFile, uri); "恢复前备份已验证保存，使用上次恢复时输入的口令" }
    }
    val diagnostic = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) launch {
            val file = File.createTempFile("diagnostic-", ".json", context.cacheDir)
            try { file.writeText(diagnostics(context, repo, includeNames)); service.copyVerified(file, uri) }
            finally { file.delete() }
            "诊断文件已保存"
        }
    }
    BackHandler(busy) { }
    LaunchedEffect(busy) { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { plan?.close(); restorePassword?.fill('\u0000'); onBusy(false) } }
    fun applyRestore() {
        val prepared = plan ?: return
        val password = restorePassword ?: return
        launch {
            try {
                val changes = service.restore(prepared, password, replace, samePhone)
                withContext(Dispatchers.Main) { onRestored() }
                "恢复完成，更新 $changes 条记录"
            } finally { prepared.close(); password.fill('\u0000'); plan = null; restorePassword = null }
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { EntryRow("导出加密备份", enabled = !busy && plan == null) { save.launch("AppTime-${LocalDate.now()}-${UUID.randomUUID().toString().take(8)}.atbackup") } }
        item { EntryRow("恢复备份", enabled = !busy && plan == null) { open.launch(arrayOf("*/*")) } }
        if (hasProtection) item { EntryRow("导出恢复前备份", enabled = !busy) { protect.launch("AppTime-before-restore.atbackup") } }
        item { HorizontalDivider() }
        item { Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(includeNames, enabled = !busy, role = Role.Switch, onValueChange = { includeNames = it }), verticalAlignment = Alignment.CenterVertically) {
            Text("诊断包含应用名称", modifier = Modifier.weight(1f))
            Switch(includeNames, null, enabled = !busy)
        } }
        item { EntryRow("导出诊断", enabled = !busy) { diagnostic.launch("AppTime-diagnostics-${LocalDate.now()}.json") } }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (message.isNotEmpty()) item { Text(message, style = MaterialTheme.typography.bodySmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (operation.isNotEmpty()) PasswordDialog(operation == "export", onCancel = {
        val abandoned = selected.takeIf { operation == "export" }
        operation = ""; selected = null
        if (abandoned != null) scope.launch(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, abandoned) } }
    }) { password ->
        val uri = selected ?: return@PasswordDialog
        val action = operation; operation = ""; selected = null
        launch {
            try {
                if (action == "export") { service.exportTo(uri, password); "备份已验证保存" }
                else {
                    val prepared = service.preview(requireNotNull(context.contentResolver.openInputStream(uri)), password)
                    withContext(Dispatchers.Main) { plan = prepared; restorePassword = password.copyOf(); replace = false; samePhone = false }
                    "备份校验通过"
                }
            } finally { password.fill('\u0000') }
        }
    }
    plan?.let { prepared ->
        AlertDialog(onDismissRequest = { if (!busy) { prepared.close(); plan = null; restorePassword?.fill('\u0000'); restorePassword = null } },
            title = { Text("恢复档案") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Instant.ofEpochMilli(prepared.manifest.getLong("created_at")).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")))
                Text("${prepared.devices} 台设备 · ${prepared.apps} 个应用 · ${prepared.records} 条时段")
                RestoreChoice("合并档案", !replace, !busy) { replace = false }
                RestoreChoice("替换档案", replace, !busy) { replace = true }
                if (replace && prepared.originPlatform == "android") BackupCheckbox("这是原来的手机", samePhone, !busy) { samePhone = it }
                Text(if (replace && samePhone) "沿用备份中的采集身份" else if (replace) "保留原设备来源，本机重新开始记录" else "保留本机采集身份，重复记录不会累加", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = { TextButton(enabled = !busy, onClick = { if (replace) confirmReplace = true else applyRestore() }) { Text("恢复") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { prepared.close(); plan = null; restorePassword?.fill('\u0000'); restorePassword = null }) { Text("取消") } })
    }
    if (confirmReplace) AlertDialog(onDismissRequest = { confirmReplace = false }, title = { Text("替换当前档案？") },
        text = { Text("将先保存当前档案的加密快照，再替换数据。恢复前备份使用本次输入的口令。") },
        confirmButton = { TextButton(onClick = { confirmReplace = false; applyRestore() }) { Text("替换并恢复") } },
        dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } })
}

@Composable private fun PasswordDialog(exporting: Boolean, onCancel: () -> Unit, onConfirm: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }; var repeat by remember { mutableStateOf("") }; var remembered by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("备份口令") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(password, { if (it.length <= 256) password = it }, label = { Text("口令") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (exporting) {
            OutlinedTextField(repeat, { if (it.length <= 256) repeat = it }, label = { Text("再次输入") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            BackupCheckbox("已记住口令，遗忘后无法恢复", remembered) { remembered = it }
        }
    } }, confirmButton = { TextButton(enabled = password.length >= 8 && (!exporting || remembered && password == repeat), onClick = {
        val copy = password.toCharArray(); password = ""; repeat = ""; onConfirm(copy)
    }) { Text(if (exporting) "导出" else "校验") } }, dismissButton = { TextButton(onClick = onCancel) { Text("取消") } })
}

@Composable private fun BackupCheckbox(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun RestoreChoice(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
