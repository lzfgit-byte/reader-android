package com.ghs.mobile.core.script

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.ghs.mobile.core.engine.proxy.ProxyRules

/**
 * WebView 的代理覆盖（进程级，对同进程所有 WebView 生效）。
 *
 * 抽出来的原因：引擎的无头 WebView 与 F29 的可见校验窗口是两个 WebView，
 * 必须用同一套规则，否则会出现"直连被挡、代理没走"的分裂状态。
 */
object WebViewProxy {

    private val main = Handler(Looper.getMainLooper())

    fun apply(rules: ProxyRules) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return
        val endpoint = rules.endpoint
        val builder = ProxyConfig.Builder()
        if (rules.enabled && endpoint?.isConfigured == true) {
            builder.addProxyRule("http://${endpoint.authority}")
            rules.whitelist.forEach { entry ->
                val pattern = entry.trim().removePrefix("*.").removePrefix(".")
                if (pattern.isNotEmpty()) runCatching { builder.addBypassRule(pattern) }
            }
        } else {
            builder.addDirect()
        }
        runCatching {
            ProxyController.getInstance()
                .setProxyOverride(builder.build(), { runnable -> main.post(runnable) }) { }
        }
    }
}
