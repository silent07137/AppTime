// SPDX-License-Identifier: GPL-2.0-only
package io.github.silent07137.apptime

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import io.github.silent07137.apptime.data.*
import io.github.silent07137.apptime.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val repository get() = (application as AppTimeApplication).repository
    private val collecting = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val access = MutableStateFlow(false)
    private val importing = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTimeTheme {
                AppScreen(repository, collecting, importing, error, access, ::refresh, ::openPermission, ::importHistory)
            }
        }
    }
    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        access.value = repository.hasAccess()
        if (collecting.value) return
        collecting.value = true
        lifecycleScope.launch {
            try {
                error.value = null
                withContext(Dispatchers.IO) { repository.collect() }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                withContext(Dispatchers.IO) { repository.recordFailure() }
                error.value = "采集失败，已有记录已保留。请稍后重试。"
            } finally {
                access.value = repository.hasAccess()
                collecting.value = false
            }
        }
    }
    private fun openPermission() {
        try { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) { error.value = "系统未提供使用情况访问设置，请在系统设置中查找。" }
    }
    private fun importHistory(days: Int) {
        if (importing.value || collecting.value) return
        importing.value = true
        lifecycleScope.launch {
            try {
                error.value = null
                withContext(Dispatchers.IO) { repository.importHistory(days) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error.value = "旧历史导入失败，已有记录已保留，请稍后重试。" }
            finally { importing.value = false }
        }
    }
}
