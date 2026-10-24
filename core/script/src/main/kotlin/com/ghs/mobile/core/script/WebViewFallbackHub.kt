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

    /**
     * 正在执行取页任务的那个 WebView 实例。
     *
     * 用户点「显示 webview」时，直接把**这个**实例挂到窗口上，
     * 而不是另开一个去重新加载——重新加载看不到任务真实状态（cookie/跳转/挑战都在原实例里）。
     */
    @Volatile
    var currentView: android.webkit.WebView? = null
        private set

    fun attach(view: android.webkit.WebView?) {
        currentView = view
    }

    fun detach(view: android.webkit.WebView?) {
        if (currentView === view) currentView = null
    }

    private val listeners = CopyOnWriteArrayList<(String?) -> Unit>()

    /**
     * 中止当前取页：由 HttpScriptHost 注入，长按「显示 webview」时调用。
     * 效果等同取页失败，调用方按失败分支处理。
     */
    @Volatile
    var abortHandler: (() -> Unit)? = null

    fun abortCurrent() {
        abortHandler?.invoke()
    }

    fun addListener(listener: (String?) -> Unit) {
        listeners += listener
        listener(currentUrl)
    }

    fun removeListener(listener: (String?) -> Unit) {
        listeners -= listener
    }

    /** 并发深度：fetchHtml 与 executePage 都会 start/stop，靠它避免提前清空 */
    private val depth = java.util.concurrent.atomic.AtomicInteger(0)

    /** 开始用 WebView 取页 */
    fun start(url: String) {
        depth.incrementAndGet()
        currentUrl = url
        listeners.forEach { runCatching { it(url) } }
    }

    /** 取页结束（成功或失败都调） */
    fun stop() {
        if (depth.decrementAndGet() > 0) return
        currentUrl = null
        currentView = null
        listeners.forEach { runCatching { it(null) } }
    }
}
