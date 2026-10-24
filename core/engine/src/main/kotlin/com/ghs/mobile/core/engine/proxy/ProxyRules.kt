package com.ghs.mobile.core.engine.proxy

import java.net.URI

/** 代理端点（`http://host:port`，可选 Basic 认证） */
data class ProxyEndpoint(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
) {
    val isConfigured: Boolean get() = host.isNotBlank() && port in 1..65535
    val authority: String get() = "$host:$port"
}

/**
 * 代理判定：**唯一决策点**（[ADR-0009]）。
 *
 * 规则（顺序即优先级）：
 * 1. 未启用或端点不完整 → 直连
 * 2. 命中白名单 → 直连
 * 3. 其余一律走代理
 *
 * 白名单条目支持三种写法：
 * - `example.com`：精确主机
 * - `example.com:8080`：精确主机 + 端口
 * - `.example.com` 或 `*.example.com`：后缀匹配（含子域）
 *
 * [ADR-0009]: ../../docs/02-architecture/adr/ADR-0009-proxy-whitelist-single-decision.md
 */
data class ProxyRules(
    val enabled: Boolean = false,
    val endpoint: ProxyEndpoint? = null,
    val whitelist: List<String> = emptyList(),
    /** 强制代理名单：命中即走代理，优先级高于白名单 */
    val forceProxy: List<String> = emptyList(),
) {
    fun shouldProxy(host: String?, port: Int): Boolean {
        if (!enabled) return false
        val target = endpoint?.takeIf { it.isConfigured } ?: return false
        val normalizedHost = normalizeHost(host) ?: return false
        // 代理自身所在的地址不应再走代理，避免自环
        if (normalizedHost == normalizeHost(target.host)) return false
        // 强制代理优先：命中强制名单一律走代理，即使它同时在白名单里
        if (isForceProxied(normalizedHost, port)) return true
        return !isWhitelisted(normalizedHost, port)
    }

    fun isForceProxied(host: String, port: Int): Boolean =
        forceProxy.any { entry -> matches(entry.trim(), host, port) }

    fun isWhitelisted(host: String, port: Int): Boolean =
        whitelist.any { entry -> matches(entry.trim(), host, port) }

    private fun matches(entry: String, host: String, port: Int): Boolean {
        if (entry.isEmpty()) return false
        if (entry == "*") return true

        val (entryHost, entryPort) = splitHostPort(entry)
        if (entryPort != null && entryPort != port) return false

        val suffixMatch = entryHost.startsWith("*.") || entryHost.startsWith(".")
        val bare = entryHost.removePrefix("*.").removePrefix(".").lowercase()
        if (bare.isEmpty()) return false

        // 裸域名同时覆盖其子域：用户写 example.com 时，cdn.example.com 也应直连
        // （图片/静态资源常在子域上，否则图片仍走代理被挡）
        return host == bare || host.endsWith(".$bare")
    }

    companion object {
        const val DEFAULT_PROXY_URL = "http://192.168.31.245:10808"

        /** 默认：启用，指向给定的本地代理地址，白名单为空 */
        fun default(): ProxyRules = ProxyRules(
            enabled = true,
            endpoint = parseEndpoint(DEFAULT_PROXY_URL),
            whitelist = emptyList(),
        )

        fun direct(): ProxyRules = ProxyRules(enabled = false)

        /** 解析 `http://host:port`、`host:port`、`host`；非法返回 null */
        fun parseEndpoint(raw: String?): ProxyEndpoint? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val withoutScheme = text.substringAfter("://", text).substringBefore('/')
            if (withoutScheme.isEmpty()) return null
            val (host, port) = splitHostPort(withoutScheme)
            val finalPort = port ?: 8080
            if (host.isEmpty() || finalPort !in 1..65535) return null
            return ProxyEndpoint(host = host, port = finalPort)
        }

        fun parseWhitelist(raw: String?): List<String> =
            raw.orEmpty().split(',', '，', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

        private fun splitHostPort(value: String): Pair<String, Int?> {
            val trimmed = value.trim()
            if (trimmed.startsWith("[")) {
                // IPv6 字面量：[::1]:8080
                val end = trimmed.indexOf(']')
                if (end > 0) {
                    val host = trimmed.substring(1, end)
                    val port = trimmed.substring(end + 1).removePrefix(":").toIntOrNull()
                    return host to port
                }
            }
            val colon = trimmed.lastIndexOf(':')
            if (colon <= 0) return trimmed to null
            val maybePort = trimmed.substring(colon + 1).toIntOrNull() ?: return trimmed to null
            return trimmed.substring(0, colon) to maybePort
        }

        private fun normalizeHost(host: String?): String? =
            host?.trim()?.trimStart('[')?.trimEnd(']')?.lowercase()?.takeIf { it.isNotEmpty() }
    }
}

/** OkHttp 的 ProxySelector：每个请求按 [ProxyRules] 逐个判定（白名单才能真正生效） */
class RulesProxySelector(private val rules: () -> ProxyRules) : java.net.ProxySelector() {

    override fun select(uri: URI): List<java.net.Proxy> {
        val endpoint = rules().let { current ->
            if (!current.shouldProxy(uri.host, effectivePort(uri))) null else current.endpoint
        } ?: return listOf(java.net.Proxy.NO_PROXY)

        return listOf(
            java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                java.net.InetSocketAddress(endpoint.host, endpoint.port),
            ),
        )
    }

    override fun connectFailed(uri: URI, socketAddress: java.net.SocketAddress, failure: java.io.IOException) {
        // 交给 OkHttp 的调用方处理失败；这里不需要额外动作
    }

    private fun effectivePort(uri: URI): Int = when {
        uri.port > 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
