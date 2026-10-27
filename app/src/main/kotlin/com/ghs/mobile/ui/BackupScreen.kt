package com.ghs.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ghs.mobile.GhsApp
import com.ghs.mobile.web.BackupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复（F20）。
 *
 * 只做一件事：把**全部配置 + 全部收藏**导成一个 JSON 文件，或从这个文件全量恢复。
 * 文件经由系统文件选择器读写（SAF），不申请存储权限。
 *
 * 与 Web 端「备份 / 恢复」是同一个 [BackupService]，格式一致，可互相回灌。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = GhsApp.instance.backup

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<Pair<String, BackupService.Bundle>?>(null) }

    // 导出：选保存位置 → 写文件
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = ""
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = backup.export()
                    context.contentResolver.openOutputStream(target)?.use { it.write(text.toByteArray()) }
                        ?: error("无法写入所选位置")
                    text
                }
            }
            busy = false
            result.onSuccess { text ->
                val bundle = runCatching { backup.parse(text) }.getOrNull()
                message = if (bundle == null) {
                    "已导出备份"
                } else {
                    "已导出备份（配置 ${bundle.configs?.size ?: 0} 份 · 收藏 ${bundle.collects?.size ?: 0} 条）"
                }
            }.onFailure { failure ->
                error = "导出失败：${failure.message ?: failure}"
            }
        }
    }

    // 恢复：先选文件并解析预览，用户确认后才落库
    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            error = ""
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(target)?.bufferedReader()
                        ?.use { it.readText() } ?: error("无法读取所选文件")
                    text to backup.parse(text)
                }
            }
            busy = false
            result.onSuccess { (text, bundle) -> pendingRestore = text to bundle }
                .onFailure { failure ->
                    error = (failure as? BackupService.BackupException)?.message
                        ?: "读取备份失败：${failure.message ?: failure}"
                }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("备份", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "把本机的全部站点配置与全部收藏（含看过次数）打包成一个 JSON 文件。" +
                                "不含端口、代理、Cookie 这类只属于本机的设置。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { createFile.launch(backup.suggestedFileName()) },
                            enabled = !busy,
                        ) {
                            Text("导出备份文件")
                        }
                    }
                }

                Card {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("恢复", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "从备份文件全量恢复：文件里的配置与收藏会替换本机现有数据，" +
                                "备份里没有的站点会被删除。这个操作不可撤销。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                            },
                            enabled = !busy,
                        ) {
                            Text("选择备份文件…")
                        }
                    }
                }

                if (busy) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("处理中…", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (error.isNotBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    // 恢复确认：列清楚"将替换什么"，避免误选文件直接清库
    pendingRestore?.let { (text, bundle) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("确认全量恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(bundle.summary())
                    Text("本机现有的配置与收藏会被清空后按备份重建，且不可撤销。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRestore = null
                        scope.launch {
                            busy = true
                            error = ""
                            message = ""
                            val result = withContext(Dispatchers.IO) {
                                runCatching { backup.restore(text) }
                            }
                            busy = false
                            result.onSuccess { report -> message = "恢复完成 · ${report.summary()}" }
                                .onFailure { failure ->
                                    error = (failure as? BackupService.BackupException)?.message
                                        ?: "恢复失败：${failure.message ?: failure}"
                                }
                        }
                    },
                ) { Text("清空并恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
        )
    }
}
