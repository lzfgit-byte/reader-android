package com.ghs.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.engine.proxy.ProxyRules
import com.ghs.mobile.settings.ProxyStore

/**
 * 网络代理（独立页，F28）。
 *
 * 交互约定：
 * - **启用代理开关**：拨一下就生效并落库，不需要「应用」；
 * - **代理列表**：点选哪条就当场生效（同一时刻只有一条生效），删除同理；
 * - **「应用」只负责白名单与强制代理名单**这两个自由输入框，避免每敲一个字都写库。
 *
 * 变更通过 [onEnabledChange] / [onSelect] / [onRemove] / [onAdd] 直接落到 ProxyStore，
 * 由外层清缓存并重新套到 WebView；引擎侧按 provider 实时读取规则，不再重建会话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(
    state: ProxyStore.State,
    rules: ProxyRules,
    onEnabledChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    onApplyLists: (whitelist: String, forceProxy: String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网络代理") },
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
                ProxySwitchCard(state = state, rules = rules, onEnabledChange = onEnabledChange)
                ProxyListCard(
                    state = state,
                    onSelect = onSelect,
                    onRemove = onRemove,
                    onAdd = onAdd,
                )
                ProxyListsCard(rules = rules, onApplyLists = onApplyLists)
            }
        }
    }
}

/** 启用开关：拨一下即生效（不需要应用） */
@Composable
private fun ProxySwitchCard(
    state: ProxyStore.State,
    rules: ProxyRules,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("代理开关", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用代理", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = rules.enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                "开关即时生效：拨动后取页、图片、视频与网页浏览立刻改用新规则，不用重启也不用再点应用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val endpoint = state.active?.url
            Text(
                when {
                    !rules.enabled -> "当前状态：直连（不走代理）"
                    endpoint.isNullOrBlank() -> "当前状态：已启用，但没有可用代理 → 实际直连"
                    else -> "当前状态：走 $endpoint"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 代理列表：点选即生效，删除即生效 */
@Composable
private fun ProxyListCard(
    state: ProxyStore.State,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
) {
    var newUrl by remember { mutableStateOf("") }
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("代理列表", style = MaterialTheme.typography.titleMedium)
            Text(
                "同一时刻只有一条生效；点一下圆点就切过去，立即生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.entries.isEmpty()) {
                Text(
                    "还没有代理，先在下面添加一条",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = entry.id == state.active?.id,
                        onClick = { onSelect(entry.id) },
                    )
                    Text(
                        entry.url,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemove(entry.id) }) { Text("删除") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("新增代理（host:port）") },
                )
                TextButton(
                    onClick = {
                        onAdd(newUrl)
                        newUrl = ""
                    },
                    enabled = newUrl.isNotBlank(),
                ) { Text("添加") }
            }
        }
    }
}

/** 白名单 / 强制代理名单：只由「应用」保存 */
@Composable
private fun ProxyListsCard(
    rules: ProxyRules,
    onApplyLists: (String, String) -> Unit,
) {
    // 草稿不做 remember(rules)：拨开关时 rules 会变，草稿不能被清掉
    var whitelist by remember { mutableStateOf(rules.whitelist.joinToString(", ")) }
    var forceProxy by remember { mutableStateOf(rules.forceProxy.joinToString(", ")) }
    val savedWhitelist = rules.whitelist.joinToString(", ")
    val savedForce = rules.forceProxy.joinToString(", ")
    val dirty = whitelist.trim() != savedWhitelist.trim() || forceProxy.trim() != savedForce.trim()

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("白名单与强制代理名单", style = MaterialTheme.typography.titleMedium)
                if (dirty) {
                    Text(
                        "未保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            OutlinedTextField(
                value = whitelist,
                onValueChange = { whitelist = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("白名单（逗号分隔；example.com 同时覆盖其子域）") },
            )
            OutlinedTextField(
                value = forceProxy,
                onValueChange = { forceProxy = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("强制代理名单（逗号分隔；命中即走代理，优先于白名单）") },
            )
            Text(
                "这两栏改完要按「应用」才落库——它们是自由文本，边打边存会写很多次库。" +
                    "白名单内直连；强制代理命中则一律走代理；未启用代理时全部直连。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { onApplyLists(whitelist, forceProxy) },
                    enabled = dirty,
                ) { Text("应用") }
            }
        }
    }
}
