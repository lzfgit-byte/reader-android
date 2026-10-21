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

/**
 * 设置页：主题 / 字号 / 阅读器默认模式。
 *
 * 这些项属于「用户偏好」，会随导出/同步走；设备级配置（端口、凭证）不在 App 里也不在这。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (FontScale) -> Unit,
    onReaderModeChange: (ReaderMode) -> Unit,
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
