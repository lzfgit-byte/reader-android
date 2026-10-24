package com.ghs.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ghs.mobile.settings.ThemeMode
import com.ghs.mobile.ui.DemoApp
import com.ghs.mobile.ui.ProbeScreen
import com.ghs.mobile.ui.SettingsScreen
import kotlinx.coroutines.launch
import com.ghs.mobile.ui.SettingsContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * 入口：主界面 / 引擎自检 / 设置三个页面。
 *
 * 主题与字号来自 `app_setting(prefs)`，在 MaterialTheme 外层统一应用，
 * 切换后所有界面立即生效。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

private enum class Page { MAIN, PROBE, SETTINGS }

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun AppRoot() {
    val store = GhsApp.instance.settings
    val proxyStore = GhsApp.instance.proxy
    val webAdmin = GhsApp.instance.webAdmin
    val settings by store.state.collectAsState()
    val proxyRules by proxyStore.rules.collectAsState()
    val webState by webAdmin.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        store.load()
        proxyStore.load()
        webAdmin.load()
    }

    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        typography = settings.typography(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var page by remember { mutableStateOf(Page.MAIN) }
            // 顶部灵动岛：展示最新一条调试日志（2 秒后自动收起）
            var islandLogs by remember { mutableStateOf<List<String>>(emptyList()) }
            DisposableEffect(Unit) {
                val listener: (List<String>) -> Unit = { islandLogs = it }
                com.ghs.mobile.core.engine.script.LogHub.addListener(listener)
                onDispose { com.ghs.mobile.core.engine.script.LogHub.removeListener(listener) }
            }
            // 正在用 WebView 取页时，右上角出现「显示 webview」悬浮按钮
            var fallbackUrl by remember { mutableStateOf<String?>(null) }
            DisposableEffect(Unit) {
                val listener: (String?) -> Unit = { fallbackUrl = it }
                com.ghs.mobile.core.script.WebViewFallbackHub.addListener(listener)
                onDispose { com.ghs.mobile.core.script.WebViewFallbackHub.removeListener(listener) }
            }
            val context = androidx.compose.ui.platform.LocalContext.current
            Box(modifier = Modifier.fillMaxSize()) {
                when (page) {
                Page.MAIN -> DemoApp(
                    drawerSettings = {
                        SettingsContent(
                            settings = settings,
                            proxy = proxyRules,
                            web = webState,
                            onThemeChange = { theme -> scope.launch { store.update { it.copy(theme = theme) } } },
                            onFontScaleChange = { scale -> scope.launch { store.update { it.copy(fontScale = scale) } } },
                            onReaderModeChange = { mode -> scope.launch { store.update { it.copy(readerMode = mode) } } },
                            onProxyApply = { enabled, url, user, pass, whitelist, forceProxy ->
                                scope.launch { proxyStore.apply(enabled, url, user, pass, whitelist, forceProxy) }
                            },
                            onWebToggle = { enabled -> scope.launch { webAdmin.update { it.copy(enabled = enabled) } } },
                            onWebPortChange = { port -> scope.launch { webAdmin.update { it.copy(port = port) } } },
                            onWebBindChange = { bindAll -> scope.launch { webAdmin.update { it.copy(bindAll = bindAll) } } },
                        )
                    },
                    onOpenProbe = { page = Page.PROBE },
                    onOpenSettings = { page = Page.SETTINGS },
                )

                Page.PROBE -> ProbeScreen(onBack = { page = Page.MAIN })

                Page.SETTINGS -> SettingsScreen(
                    settings = settings,
                    proxy = proxyRules,
                    web = webState,
                    onThemeChange = { theme -> scope.launch { store.update { it.copy(theme = theme) } } },
                    onFontScaleChange = { scale -> scope.launch { store.update { it.copy(fontScale = scale) } } },
                    onReaderModeChange = { mode -> scope.launch { store.update { it.copy(readerMode = mode) } } },
                    onProxyApply = { enabled, url, user, pass, whitelist, forceProxy ->
                        scope.launch { proxyStore.apply(enabled, url, user, pass, whitelist, forceProxy) }
                    },
                    onWebToggle = { enabled -> scope.launch { webAdmin.update { it.copy(enabled = enabled) } } },
                    onWebPortChange = { port -> scope.launch { webAdmin.update { it.copy(port = port) } } },
                    onWebBindChange = { bindAll -> scope.launch { webAdmin.update { it.copy(bindAll = bindAll) } } },
                    onBack = { page = Page.MAIN },
                )
            }
                com.ghs.mobile.ui.LogIsland(
                    entries = islandLogs,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                )
                // 取页回退到 WebView 时出现的入口：点击显示、长按中止本次取页
                fallbackUrl?.let { url ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 56.dp, end = 16.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                            .combinedClickable(
                                onClick = { com.ghs.mobile.ui.WebViewMirrorActivity.start(context, url) },
                                onLongClick = {
                                    // 长按：停止当前取页，按失败返回
                                    com.ghs.mobile.core.script.WebViewFallbackHub.abortCurrent()
                                    android.widget.Toast.makeText(
                                        context,
                                        "已停止本次取页",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "显示 webview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}



