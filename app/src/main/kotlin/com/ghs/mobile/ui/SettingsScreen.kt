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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ghs.mobile.core.engine.proxy.ProxyRules
import androidx.compose.ui.Alignment
import com.ghs.mobile.web.WebAdminController
import androidx.compose.foundation.clickable

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
    web: WebAdminController.WebSettings,
    onThemeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (FontScale) -> Unit,
    onReaderModeChange: (ReaderMode) -> Unit,
    onProxyApply: (enabled: Boolean, whitelist: String, forceProxy: String) -> Unit,
    proxyState: com.ghs.mobile.settings.ProxyStore.State,
    onProxyAdd: (String) -> Unit,
    onProxySelect: (String) -> Unit,
    onProxyRemove: (String) -> Unit,
    onWebToggle: (Boolean) -> Unit,
    onWebPortChange: (Int) -> Unit,
    onWebBindChange: (Boolean) -> Unit,
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
            SettingsContent(
                settings = settings,
                proxy = proxy,
                web = web,
                onThemeChange = onThemeChange,
                onFontScaleChange = onFontScaleChange,
                onReaderModeChange = onReaderModeChange,
                onProxyApply = onProxyApply,
                proxyState = proxyState,
                onProxyAdd = onProxyAdd,
                onProxySelect = onProxySelect,
                onProxyRemove = onProxyRemove,
                onWebToggle = onWebToggle,
                onWebPortChange = onWebPortChange,
                onWebBindChange = onWebBindChange,

            )
        }
    }
}

/** 设置内容本体：左侧抽屉与独立设置页共用（不含 Scaffold） */
@Composable
fun SettingsContent(
    settings: AppSettings,
    proxy: ProxyRules,
    web: WebAdminController.WebSettings,
    onThemeChange: (ThemeMode) -> Unit,
    onFontScaleChange: (FontScale) -> Unit,
    onReaderModeChange: (ReaderMode) -> Unit,
    onProxyApply: (enabled: Boolean, whitelist: String, forceProxy: String) -> Unit,
    proxyState: com.ghs.mobile.settings.ProxyStore.State,
    onProxyAdd: (String) -> Unit,
    onProxySelect: (String) -> Unit,
    onProxyRemove: (String) -> Unit,
    onWebToggle: (Boolean) -> Unit,
    onWebPortChange: (Int) -> Unit,
    onWebBindChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 抽屉里由外层统一滚动时传 false，避免嵌套垂直滚动直接崩（infinity 高度约束） */
    scrollable: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
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
        }

        SettingSection(title = "Web 管理") {
            WebSection(
                web = web,
                onToggle = onWebToggle,
                onPortChange = onWebPortChange,
                onBindChange = onWebBindChange,

            )
        }

        SettingSection(title = "网络代理") {
            ProxyForm(
                proxy = proxy,
                state = proxyState,
                onApply = onProxyApply,
                onAdd = onProxyAdd,
                onSelect = onProxySelect,
                onRemove = onProxyRemove,
            )
        }

        SettingSection(title = "阅读") {
            SettingRow(
                label = "阅读器默认模式",
                options = ReaderMode.entries.map { it.label() },
                selectedIndex = ReaderMode.entries.indexOf(settings.readerMode),
                onSelect = { onReaderModeChange(ReaderMode.entries[it]) },
            )
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
/** 代理表单：多条代理、单选生效；只填地址，不做认证 */
@Composable
private fun ProxyForm(
    proxy: ProxyRules,
    state: com.ghs.mobile.settings.ProxyStore.State,
    onApply: (Boolean, String, String) -> Unit,
    onAdd: (String) -> Unit,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var enabled by remember(proxy) { mutableStateOf(proxy.enabled) }
    var whitelist by remember(proxy) { mutableStateOf(proxy.whitelist.joinToString(", ")) }
    var forceProxy by remember(proxy) { mutableStateOf(proxy.forceProxy.joinToString(", ")) }
    var newUrl by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("启用代理", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = enabled,
                onCheckedChange = { next ->
                    // 开关本身就是"启用/不启用"，立即落库，不需要再点应用
                    enabled = next
                    onApply(next, whitelist, forceProxy)
                },
            )
        }

        Text("代理列表（同时只有一条生效）", style = MaterialTheme.typography.labelMedium)
        if (state.entries.isEmpty()) {
            Text(
                "还没有代理，先在下面添加一条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = entry.id == state.activeId,
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
            "白名单内直连；强制代理命中则一律走代理；未启用时全部直连。代理设置只存本机，不随导出包走。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onApply(enabled, whitelist, forceProxy) }) { Text("应用") }
        }
    }
}

/** Web 管理：开关、端口、绑定范围、令牌与访问地址（全部只在应用端配置） */
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
