package com.ghs.mobile.core.script

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.ghs.mobile.core.engine.proxy.ProxyRules
import java.net.URI

/**
 * WebView 的代理覆盖（进程级，对同进程所有 WebView 生效）。
 *
 * 抽出来的原因：引擎的无头 WebView 与 F29 的可见校验窗口是两个 WebView，
 * 必须用同一套规则，否则会出现"直连被挡、代理没走"的分裂状态。
 *
 * 判定直接复用 [ProxyRules]（唯一决策点）：给了 [targetUrl] 就按这个地址算，
 * 没给就按"当前来源"算（`isProxy` 为 false 的来源整体直连）。
 * ProxyConfig 只能"整块代理 + bypass"，所以这里把逐请求判定收敛成**每次导航前算一次**。
 */
object WebViewProxy {

    private val main = Handler(Looper.getMainLooper())

    fun apply(rules: ProxyRules, targetUrl: String? = null) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        val endpoint = rules.endpoint
        val useProxy = if (targetUrl.isNullOrBlank()) {
            rules.enabled && endpoint?.isConfigured == true && rules.siteEnabled
        } else {
            val uri = runCatching { URI(targetUrl) }.getOrNull()
            rules.shouldProxy(uri?.host, portOf(uri))
        }

        val builder = ProxyConfig.Builder()
        if (useProxy && endpoint != null) {
            builder.addProxyRule("http://${endpoint.authority}")
        } else {
            builder.addDirect()
        }
        runCatching {
            ProxyController.getInstance()
                .setProxyOverride(builder.build(), { runnable -> main.post(runnable) }) { }
        }
    }

    private fun portOf(uri: URI?): Int = when {
        uri == null -> 443
        uri.port > 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
