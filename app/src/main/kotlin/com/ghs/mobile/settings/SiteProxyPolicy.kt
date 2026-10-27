package com.ghs.mobile.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前来源的代理策略（来自配置，不是设备设置）。
 *
 * 为什么单独放一个进程级持有者：代理的**唯一判定点**在 [com.ghs.mobile.core.engine.proxy.ProxyRules]，
 * 而 OkHttp 的 `ProxySelector`、图片客户端、无头 WebView 都是进程级单例，
 * 它们只拿得到"要请求的地址"，拿不到"当前是哪个来源"。所以把当前来源声明的
 * `isProxy` / `forceProxy` 挂在这里，由界面在建立会话时写入，判定时实时读取。
 *
 * 同一时刻只有一个来源在使用（切站点会先关旧会话），因此这里只需要保存"当前站点"的一份。
 */
object SiteProxyPolicy {

    data class Policy(
        /** 配置 `isProxy`：默认 true，只有显式声明 false 才直连 */
        val useProxy: Boolean = true,
        /** 配置 `forceProxy`：命中即走代理，优先于 [useProxy] */
        val forceProxy: List<String> = emptyList(),
    )

    private val _policy = MutableStateFlow(Policy())
    val policy: StateFlow<Policy> = _policy.asStateFlow()

    fun apply(isProxy: Boolean, forceProxy: List<String>) {
        _policy.value = Policy(useProxy = isProxy, forceProxy = forceProxy)
    }

    /** 回到默认（没有站点时：走代理，无强制名单） */
    fun reset() {
        _policy.value = Policy()
    }
}
