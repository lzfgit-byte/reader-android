package com.ghs.mobile.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
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
import com.ghs.mobile.core.model.ReaderMode
import com.ghs.mobile.settings.AppSettings
import com.ghs.mobile.settings.FontScale
import com.ghs.mobile.settings.ThemeMode
import com.ghs.mobile.settings.label
import com.ghs.mobile.web.WebAdminController

/**
 * 设置类二级页：外观 / Web 管理 / 阅读。
 *
 * 三者原来是塞在抽屉里的一整块表单，现在按「一项一类一页」拆成独立页，
 * 由抽屉进入、返回键退回主界面（见 `ui-implementation.md` §4.3、§4.4）。
 * 每个页面共用 [SettingsPage] 外壳，只负责自己那张卡。
 */

/** 外观：主题 + 字号 */
@Composable
fun AppearanceScreen(
    settings: AppSettings,
    onThemeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (FontScale) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = "外观", onBack = onBack) {
        SettingSection(title = "外观") {
            Text(
                "主题与字号全局生效，切换后立刻应用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        }
    }
}

/** Web 管理：开关 / 端口 / 局域网地址 */
@Composable
fun WebAdminScreen(
    web: WebAdminController.WebSettings,
    onToggle: (Boolean) -> Unit,
    onPortChange: (Int) -> Unit,
    onBindChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = "Web 管理", onBack = onBack) {
        SettingSection(title = "Web 管理") {
            WebSection(
                web = web,
                onToggle = onToggle,
                onPortChange = onPortChange,
                onBindChange = onBindChange,
            )
        }
    }
}

/** 阅读：默认阅读模式 */
@Composable
fun ReadingScreen(
    settings: AppSettings,
    onReaderModeChange: (ReaderMode) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(title = "阅读", onBack = onBack) {
        SettingSection(title = "阅读") {
            Text(
                "阅读器打开时默认采用的翻页方式；阅读中仍可随时切换。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingRow(
                label = "阅读器默认模式",
                options = ReaderMode.entries.map { it.label() },
                selectedIndex = ReaderMode.entries.indexOf(settings.readerMode),
                onSelect = { onReaderModeChange(ReaderMode.entries[it]) },
            )
        }
    }
}

/** 设置类二级页的统一外壳：顶栏（返回 + 标题）+ 可滚动内容 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
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
                content()
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

/** Web 管理：开关、端口、绑定范围、局域网地址（全部只在应用端配置） */
@Composable
private fun WebSection(
    web: WebAdminController.WebSettings,
    onToggle: (Boolean) -> Unit,
    onPortChange: (Int) -> Unit,
    onBindChange: (Boolean) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = remember(context) {
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    }
    val copyHint = remember(context) {
        android.widget.Toast.makeText(context, "地址已复制", android.widget.Toast.LENGTH_SHORT)
    }
    var portText by remember(web.port) { mutableStateOf(web.port.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用 Web 管理", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = web.enabled, onCheckedChange = onToggle)
        }
        // 实机排查用：明确告诉用户服务是否真的起来了、绑定在哪
        Text(
            when {
                !web.enabled -> "服务未启用"
                !web.bindAll -> "服务已启用，仅本机可访问（手机浏览器打开 127.0.0.1:$portText）"
                else -> "服务已启用，局域网可访问（电脑用下面列出的地址）"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("端口（默认 18600）") },
            )
            TextButton(onClick = { portText.filter(Char::isDigit).toIntOrNull()?.let(onPortChange) }) {
                Text("应用端口")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("允许局域网访问", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = web.bindAll, onCheckedChange = onBindChange)
        }

        if (web.enabled) {
            Text(
                "打开地址（点一下复制）：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            web.consoleUrls.forEach { url ->
                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard?.setPrimaryClip(
                                android.content.ClipData.newPlainText("web-console", url),
                            )
                            copyHint.show()
                        }
                        .padding(vertical = 4.dp),
                )
            }
        } else {
            Text(
                "启用后在此显示局域网访问地址",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
