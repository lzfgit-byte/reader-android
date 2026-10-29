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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ghs.mobile.GhsApp
import com.ghs.mobile.web.BackupDownloader
import com.ghs.mobile.web.BackupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 待确认的恢复动作：文本 + 解析结果 + 来源（文件 / 链接），确认框里要展示来源 */
private data class PendingRestore(
    val text: String,
    val bundle: BackupService.Bundle,
    val source: String,
)

/**
 * 从链接取备份：先按设备代理规则取，失败再**直连**试一次。
 *
 * 为什么要重试：备份常见放在局域网 / 回环地址（NAS、本机起的临时 HTTP 服务），
 * 而来源级代理策略（`isProxy` / `forceProxy`）是按站点算的——当前来源要求走代理时，
 * 去局域网地址的请求会被塞进代理，必然连不上。链接是用户显式给的一次性下载，
 * 代理不通就直连一次比让人去改代理设置更合理；两次都失败才把错误摊给用户。
 */
private fun downloadBackup(url: String): String {
    val proxied = runCatching { BackupDownloader.download(GhsApp.instance.proxiedClient(), url) }
    proxied.getOrNull()?.let { return it }
    val failure = proxied.exceptionOrNull() ?: error("取不到链接")
    if ((failure as? BackupService.BackupException)?.code != "E-BAK-008") throw failure
    val direct = GhsApp.instance.proxiedClient().newBuilder()
        .proxy(java.net.Proxy.NO_PROXY)
        .build()
    return BackupDownloader.download(direct, url)
}

/**
 * 备份与恢复（F20）。
 *
 * 两件事：把**全部配置 + 全部收藏 + 全部阅读进度**导成一个 JSON 文件；或从**文件**、
 * **链接**两种来源全量恢复。文件经由系统文件选择器读写（SAF），不申请存储权限；
 * 链接则走 [BackupDownloader]（同一个 `E-BAK-*` 错误码集合）。
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
    var restoreUrl by remember { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }

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
                    "已导出备份（配置 ${bundle.configs?.size ?: 0} 份 · " +
                        "收藏 ${bundle.collects?.size ?: 0} 条 · 进度 ${bundle.progress?.size ?: 0} 条）"
                }
            }.onFailure { failure ->
                error = "导出失败：${failure.message ?: failure}"
            }
        }
    }

    /** 读到备份文本 → 解析成预览，交给确认框（文件与链接共用这一段） */
    fun offerRestore(text: String, source: String) {
        scope.launch {
            busy = true
            error = ""
            message = ""
            val parsed = withContext(Dispatchers.IO) { runCatching { text to backup.parse(text) } }
            busy = false
            parsed.onSuccess { (raw, bundle) -> pendingRestore = PendingRestore(raw, bundle, source) }
                .onFailure { failure ->
                    error = (failure as? BackupService.BackupException)?.message
                        ?: "读取备份失败：${failure.message ?: failure}"
                }
        }
    }

    // 恢复（一）：从系统文件选择器选一个 JSON
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
                    text
                }
            }
            busy = false
            result.onSuccess { text ->
                val name = target.lastPathSegment?.substringAfterLast('/').orEmpty()
                offerRestore(text, if (name.isBlank()) "文件" else "文件 $name")
            }.onFailure { failure ->
                error = "读取备份失败：${failure.message ?: failure}"
            }
        }
    }

    // 恢复（二）：填一个 http/https 链接，下载后走同一条预览 → 确认 → 落库流程
    fun restoreFromUrl() {
        val url = restoreUrl.trim()
        if (url.isEmpty() || busy) return
        scope.launch {
            busy = true
            error = ""
            message = ""
            val result = withContext(Dispatchers.IO) {
                runCatching { downloadBackup(url) }
            }
            busy = false
            result.onSuccess { text -> offerRestore(text, "链接 $url") }
                .onFailure { failure ->
                    error = (failure as? BackupService.BackupException)?.message
                        ?: "取不到链接：${failure.message ?: failure}"
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
                            "把本机的全部站点配置、全部收藏（含看过次数）与全部阅读进度" +
                                "（漫画读到第几话第几页、视频看到哪）打包成一个 JSON 文件。" +
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
                            "从备份文件全量恢复：文件里的配置、收藏与阅读进度会替换本机现有数据，" +
                                "备份里没有的站点会被删除。这个操作不可撤销。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                openFile.launch(
                                    arrayOf("application/json", "text/plain", "application/octet-stream"),
                                )
                            },
                            enabled = !busy,
                        ) {
                            Text("选择备份文件…")
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                        Text(
                            "或者填一个链接：换机时备份常放在网盘 / NAS 上，不必先下载到手机上。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = restoreUrl,
                            onValueChange = { restoreUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("备份文件链接") },
                            placeholder = { Text("https://…/ghs-backup.json") },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { restoreFromUrl() }),
                        )
                        Button(
                            onClick = { restoreFromUrl() },
                            enabled = !busy && restoreUrl.isNotBlank(),
                        ) {
                            Text("从链接恢复")
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

    // 恢复确认：列清楚"将替换什么"与"从哪来"，避免误选文件/链接直接清库
    pendingRestore?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("确认全量恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.bundle.summary())
                    Text(
                        "来源：${pending.source}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("本机现有的配置、收藏与阅读进度会被清空后按备份重建，且不可撤销。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val text = pending.text
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
