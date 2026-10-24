package com.ghs.mobile.core.script

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 「正在用 WebView 取页」的广播。
 *
 * 取页失败回退到无头 WebView 时，UI 需要一个入口把那个页面显示出来：
 * - 有这个页面，用户才能看到 403 / 挑战页 / 登录页
 * - 没这个页面，用户只看到转圈或错误，无法介入
 */
object WebViewFallbackHub {

    /** 当前正在取页的地址；null 表示没有进行中的 WebView 取页 */
    @Volatile
    var currentUrl: String? = null
        private set

    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()

    fun addListener(listener: (String?) -> Unit) {
        listeners += listener
        listener(currentUrl)
    }

    fun removeListener(listener: (String?) -> Unit) {
        listeners -= listener
    }

    /** 开始用 WebView 取页 */
    fun start(url: String) {
        currentUrl = url
        listeners.forEach { runCatching { it(url) } }
    }

    /** 取页结束（成功或失败都调） */
    fun stop() {
        currentUrl = null
        listeners.forEach { runCatching { it(null) } }
    }
}
