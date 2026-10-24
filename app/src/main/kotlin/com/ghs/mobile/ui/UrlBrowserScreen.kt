package com.ghs.mobile.ui

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ghs.mobile.core.engine.net.UserAgents
import com.ghs.mobile.core.engine.proxy.ProxyRules
import com.ghs.mobile.core.script.WebViewProxy
import com.ghs.mobile.settings.ProxyStore

/**
 * 内置网页浏览器：地址由用户输入，可单独选择「走代理 / 直连」。
 *
 * 代理对 WebView 是**进程级**的（ProxyController），所以这里在打开本页时按开关
 * 临时套一份规则，退出时还原成全局规则；不会影响用户原有的代理配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UrlBrowserScreen(
    proxyState: ProxyStore.State,
    globalRules: ProxyRules,
    onBack: () -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var useProxy by remember { mutableStateOf(true) }
    var loadedUrl by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 规则随开关切换；离开本页恢复全局规则
    LaunchedEffect(useProxy, proxyState.activeId, globalRules.enabled) {
        val endpoint = ProxyRules.parseEndpoint(proxyState.active?.url)
        val rules = if (useProxy && endpoint != null) {
            ProxyRules(
                enabled = true,
                endpoint = endpoint,
                whitelist = emptyList(),      // 本页不套白名单：要代理就全走代理
                forceProxy = listOf("*"),     // 强制全部走代理
            )
        } else {
            ProxyRules(enabled = false, endpoint = null)
        }
        WebViewProxy.apply(rules)
    }
    DisposableEffect(Unit) {
        onDispose { WebViewProxy.apply(globalRules) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("网页浏览") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("输入网址") },
                    )
                    TextButton(
                        onClick = {
                            val target = normalizeUrl(url)
                            if (target == null) {
                                Toast.makeText(
                                    webViewRef?.context,
                                    "网址不合法",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                loadedUrl = target
                            }
                        },
                    ) { Text("打开") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("走代理", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = useProxy, onCheckedChange = { useProxy = it })
                    Text(
                        if (useProxy) "经过 ${proxyState.active?.url ?: "未配置代理"}" else "直连（不走代理）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                loadedUrl?.let { target ->
                    Browser(target, onCreated = { webViewRef = it })
                } ?: Text(
                    "输入网址后点「打开」；切换开关只影响本页请求。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Browser(target: String, onCreated: (WebView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = UserAgents.UA
                webViewClient = WebViewClient()
                onCreated(this)
                loadUrl(target, mapOf("Accept-Language" to UserAgents.ACCEPT_LANGUAGE))
            }
        },
        update = { view ->
            if (view.url != target) {
                view.loadUrl(target, mapOf("Accept-Language" to UserAgents.ACCEPT_LANGUAGE))
            }
        },
    )
}

private fun normalizeUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val withScheme = if (text.contains("://")) text else "https://$text"
    return withScheme.takeIf { it.contains('.') || it.contains("localhost") }
}