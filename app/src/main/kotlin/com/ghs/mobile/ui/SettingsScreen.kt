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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.ReaderMode
import com.ghs.mobile.settings.AppSettings
import com.ghs.mobile.settings.FontScale
import com.ghs.mobile.settings.ThemeMode
import com.ghs.mobile.settings.label
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ghs.mobile.core.engine.proxy.ProxyRules
import androidx.compose.ui.Alignment

/**
 * 设置页：主题 / 字号 / 阅读器默认模式。
 *
 * 这些项属于「用户偏好」，会随导出/同步走；设备级配置（端口、凭证）不在 App 里也不在这。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    proxy: ProxyRules,
    onThemeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (FontScale) -> Unit,
    onReaderModeChange: (ReaderMode) -> Unit,
    onProxyApply: (enabled: Boolean, url: String, username: String, password: String, whitelist: String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingSection(title = "外观") {
                    SettingRow(
                        label = "主题",
                        options = ThemeMode.entries.map { it.label },
                        selectedIndex = ThemeMode.entries.indexOf(settings.theme),
                        onSelect = { onThemeChange(ThemeMode.entries[it]) },
                    )
                    SettingRow(
                        label = "字号",
                        options = FontScale.entries.map { it.label },
                        selectedIndex = FontScale.entries.indexOf(settings.fontScale),
                        onSelect = { onFontScaleChange(FontScale.entries[it]) },
                    )
                    Text(
                        "预览：这一段文字会按当前字号显示。",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                SettingSection(title = "网络代理") {
                    ProxyForm(proxy = proxy, onApply = onProxyApply)
                }

                SettingSection(title = "阅读") {
                    SettingRow(
                        label = "阅读器默认模式",
                        options = ReaderMode.entries.map { it.label() },
                        selectedIndex = ReaderMode.entries.indexOf(settings.readerMode),
                        onSelect = { onReaderModeChange(ReaderMode.entries[it]) },
                    )
                    Text(
                        "配置里的 readerMode 会被这里的用户选择覆盖，仅对当前来源生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingRow(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                FilterChip(
                    selected = index == selectedIndex,
                    onClick = { onSelect(index) },
                    label = { Text(option) },
                )
            }
        }
    }
}

/** 代理表单：改动后点「应用」才落库，避免每敲一个字都写一次数据库 */
@Composable
private fun ProxyForm(
    proxy: ProxyRules,
    onApply: (Boolean, String, String, String, String) -> Unit,
) {
    var enabled by remember(proxy) { mutableStateOf(proxy.enabled) }
    var url by remember(proxy) {
        mutableStateOf(proxy.endpoint?.let { "http://${it.authority}" } ?: ProxyRules.DEFAULT_PROXY_URL)
    }
    var username by remember(proxy) { mutableStateOf(proxy.endpoint?.username.orEmpty()) }
    var password by remember(proxy) { mutableStateOf(proxy.endpoint?.password.orEmpty()) }
    var whitelist by remember(proxy) { mutableStateOf(proxy.whitelist.joinToString(", ")) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用代理", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("代理地址（http://host:port）") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("用户名（可选）") },
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("密码（可选）") },
            )
        }
        OutlinedTextField(
            value = whitelist,
            onValueChange = { whitelist = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("白名单（逗号分隔，.example.com 匹配子域）") },
        )

        Text(
            "白名单内的地址直连；未启用代理时全部直连。代理设置只存本机，不随导出包走。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onApply(enabled, url, username, password, whitelist) }) {
                Text("应用")
            }
        }
    }
}
