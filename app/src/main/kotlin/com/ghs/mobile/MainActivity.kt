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
import com.ghs.mobile.ui.AppearanceScreen
import com.ghs.mobile.ui.ReadingScreen
import com.ghs.mobile.ui.WebAdminScreen
import kotlinx.coroutines.launch
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

private enum class Page { MAIN, PROBE, APPEARANCE, WEB, PROXY, READING, BROWSER }

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun AppRoot() {
    val store = GhsApp.instance.settings
    val proxyStore = GhsApp.instance.proxy
    val webAdmin = GhsApp.instance.webAdmin
    val settings by store.state.collectAsState()
    val proxyRules by proxyStore.rules.collectAsState()
    // 代理列表：多条代理、单选生效
    val proxyState by proxyStore.state.collectAsState()
    // 当前来源的代理策略（配置 isProxy / forceProxy）：只读展示，设置在配置里
    val siteProxy by com.ghs.mobile.settings.SiteProxyPolicy.policy.collectAsState()
    val webState by webAdmin.state.collectAsState()
    val scope = rememberCoroutineScope()

    /**
     * 代理规则一旦变了就当场落地：清 HTML/图片缓存（设计文档 §8.13），
     * 并把新规则立刻套到 WebView 通道——引擎侧按 provider 实时读取，不需要重建会话。
     */
    fun applyProxyChange(block: suspend () -> Unit) {
        scope.launch {
            block()
            com.ghs.mobile.ui.ImageCache.clearAll()
            runCatching { coil.Coil.imageLoader(GhsApp.instance).memoryCache?.clear() }
            com.ghs.mobile.core.script.WebViewProxy.apply(GhsApp.instance.proxyRules())
        }
    }

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
            // 二级页面（引擎自检 / 设置 / 网络代理 / 网页浏览）必须自己接管返回键：
            // 返回 = 退回主界面，不能交给系统直接退出应用（ui-implementation.md §4.4）
            androidx.activity.compose.BackHandler(enabled = page != Page.MAIN) {
                page = Page.MAIN
            }
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
                    onOpenProbe = { page = Page.PROBE },
                    onOpenBrowser = { page = Page.BROWSER },
                    onOpenAppearance = { page = Page.APPEARANCE },
                    onOpenWeb = { page = Page.WEB },
                    onOpenProxy = { page = Page.PROXY },
                    onOpenReading = { page = Page.READING },
                )

                Page.PROBE -> ProbeScreen(onBack = { page = Page.MAIN })

                // 外观：主题 / 字号
                Page.APPEARANCE -> AppearanceScreen(
                    settings = settings,
                    onThemeChange = { theme -> scope.launch { store.update { it.copy(theme = theme) } } },
                    onFontScaleChange = { scale -> scope.launch { store.update { it.copy(fontScale = scale) } } },
                    onBack = { page = Page.MAIN },
                )

                // Web 管理：开关 / 端口 / 局域网地址
                Page.WEB -> WebAdminScreen(
                    web = webState,
                    onToggle = { enabled -> scope.launch { webAdmin.update { it.copy(enabled = enabled) } } },
                    onPortChange = { port -> scope.launch { webAdmin.update { it.copy(port = port) } } },
                    onBindChange = { bindAll -> scope.launch { webAdmin.update { it.copy(bindAll = bindAll) } } },
                    onBack = { page = Page.MAIN },
                )

                // 网络代理：只有总开关 + 代理列表；来源级策略由配置 isProxy / forceProxy 决定
                Page.PROXY -> com.ghs.mobile.ui.ProxyScreen(
                    state = proxyState,
                    rules = proxyRules,
                    site = siteProxy,
                    onEnabledChange = { enabled -> applyProxyChange { proxyStore.setEnabled(enabled) } },
                    onSelect = { id -> applyProxyChange { proxyStore.select(id) } },
                    onRemove = { id -> applyProxyChange { proxyStore.remove(id) } },
                    onAdd = { url -> applyProxyChange { proxyStore.add(url) } },
                    onBack = { page = Page.MAIN },
                )

                // 阅读：默认阅读模式
                Page.READING -> ReadingScreen(
                    settings = settings,
                    onReaderModeChange = { mode -> scope.launch { store.update { it.copy(readerMode = mode) } } },
                    onBack = { page = Page.MAIN },
                )

                Page.BROWSER -> com.ghs.mobile.ui.UrlBrowserScreen(
                    proxyState = proxyState,
                    globalRules = GhsApp.instance.proxyRules(),
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



