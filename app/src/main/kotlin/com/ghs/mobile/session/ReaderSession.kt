package com.ghs.mobile.session

import android.content.Context
import com.ghs.mobile.core.engine.config.ConfigEngine
import com.ghs.mobile.core.engine.config.InlineConfigScanner
import com.ghs.mobile.core.engine.proxy.ProxyRules
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import com.ghs.mobile.core.model.CContent
import com.ghs.mobile.core.model.DetailInfo
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.Page
import com.ghs.mobile.core.model.TextContent
import com.ghs.mobile.core.script.HttpScriptHost
import com.ghs.mobile.core.script.WebViewScriptEngine
import com.ghs.mobile.core.model.Analysis
import com.ghs.mobile.core.model.AnalysisDetail
import com.ghs.mobile.core.model.AnalysisVideoDetail
import com.ghs.mobile.core.model.UrlReplace

/**
 * 阅读宿主：演示域名走内置样例页面，其它域名走真实网络。
 *
 * 这样同一套配置链路既能离线演示，也能接真实来源；
 * 是否联网只取决于配置里的地址，不需要切换代码。
 */
class ReaderHost(
    context: Context,
    proxyRules: ProxyRules,
) : ScriptHostTransport {

    private val http = HttpScriptHost(context) { proxyRules }

    override fun fetchHtml(url: String): String =
        if (isDemo(url)) assetHtml(url) else http.fetchHtml(url)

    override fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): ScriptHostTransport.HostResponse =
        if (isDemo(url)) {
            ScriptHostTransport.HostResponse(status = 200, headers = emptyMap(), body = "{}")
        } else {
            http.request(url, method, headers, body)
        }

    override fun executePage(url: String, code: String): String =
        if (isDemo(url)) "" else http.executePage(url, code)

    override fun close() {
        runCatching { http.close() }
    }

    private fun isDemo(url: String): Boolean = url.contains("demo.local")

    private fun assetHtml(url: String): String {
        val asset = when {
            url.contains("search?q=") -> SEARCH
            url.contains("type=comic") -> COMIC
            url.contains("type=text") && url.contains("page=2") -> TEXT2
            url.contains("type=text") -> TEXT
            url.contains("type=video") -> VIDEO
            else -> LIST
        }
        return context.assets.open(asset).bufferedReader().use { it.readText() }
    }

    private val context: Context = context

    private companion object {
        const val LIST = "demo/list.html"
        const val COMIC = "demo/detail-comic.html"
        const val TEXT = "demo/detail-text.html"
        const val TEXT2 = "demo/detail-text-2.html"
        const val SEARCH = "demo/search.html"
        const val VIDEO = "demo/detail-video.html"
    }
}

/**
 * 一次阅读会话：配置源码 + 脚本引擎 + 宿主。
 *
 * 配置来自 `files/configs`（浏览器里编辑的那份），不再是编译进 APK 的常量。
 * 引擎是重对象（WebView），整场复用一份；[close] 时释放。
 */
class ReaderSession(
    private val context: Context,
    private val source: String,
    private val proxyRules: ProxyRules = ProxyRules.direct(),
) : AutoCloseable {

    /** 配置声明的首页地址；缺失时退回演示首页 */
    val homeUrl: String = InlineConfigScanner.scan(source).homeUrl?.takeIf { it.isNotBlank() }
        ?: "https://demo.local/list"

    /**
     * 图集单图解码宽度上限（像素），来自配置 `comicImgMaxWidth`。
     *
     * 默认 `1080px`；支持 `px` / `dp` / `vw` / `%` / 纯数字（按 dp）。
     * 作用是把超长图按屏宽解码，避免一次解码整张导致内存暴涨。
     */
    val imageMaxWidth: Int = resolveImageMaxWidth(
        context,
        InlineConfigScanner.scan(source).comicImgMaxWidth,
    )

    private val host = ReaderHost(context, proxyRules)
    private val engine = WebViewScriptEngine(context, proxyRules)
    private val configEngine = ConfigEngine(engine, host).load(source)

    fun page(): Page = pageFrom(homeUrl)

    fun pageFrom(url: String): Page {
        // 配置若实现了 adapterGetHtml，优先用它取页（自己处理头 / Cookie / 二次请求）
        val html = configEngine.adapterGetHtml(url) ?: host.fetchHtml(url)
        android.util.Log.i("GHS", "page html=${html.length} hasOwl=${html.contains("owl-stage-outer")} hasWell=${html.contains("div class=\"well")} head=${html.take(200).replace("\n"," ")}")
        configEngine.withHtml(html)
        val page = configEngine.page()
        android.util.Log.i(
            "GHS",
            "page items=${page.items.size} tags=${page.tags.size} urlReplace=${page.urlReplace.size}",
        )
        return page
    }

    /** 筛选/排序后的最终地址（配置的 adapterLoadUrl） */
    fun loadUrl(url: String, replaces: List<UrlReplace>): String = configEngine.loadUrl(url, replaces)

    /** 剧集：分析项 → 集列表 */
    fun episodes(analysis: Analysis): List<AnalysisDetail> = configEngine.analysisDetail(analysis)

    /** 剧集：单集 → 播放信息 */
    fun episodeVideo(episode: AnalysisDetail): AnalysisVideoDetail? =
        configEngine.analysisVideoDetail(episode)

    fun detail(item: Item): DetailInfo? {
        val started = System.currentTimeMillis()
        android.util.Log.i("GHS", "detail start ${item.jumpUrl}")
        try {
            val info = configEngine.detailInfo(item)
            android.util.Log.i(
                "GHS",
                "detail result=" + (info?.let { "${it.detailType} details=${it.details.size}" } ?: "null"),
            )
            return info
        } finally {
            android.util.Log.i("GHS", "detail done ${System.currentTimeMillis() - started}ms")
        }
    }

    fun textContent(url: String): TextContent = configEngine.textContent(url)

    fun contents(url: String): List<CContent> = configEngine.contents(url)

    /** 搜索：走 adapterSearchUrl 取地址，再按普通列表解析 */
    fun search(keyword: String): Pair<String, Page>? {
        val url = configEngine.searchUrl(keyword)
        if (url.isEmpty()) return null
        configEngine.withHtml(host.fetchHtml(url))
        return url to configEngine.page()
    }

    override fun close() {
        runCatching { configEngine.close() }
        host.close()
    }
}

/**
 * 把配置里的宽度写法换算成像素。
 *
 * 支持 `px`、`dp`、`vw`（视口宽百分比）、`%`、纯数字（按 dp）；非法值退回 1080。
 */
private fun resolveImageMaxWidth(context: Context, raw: String?): Int {
    val metrics = context.resources.displayMetrics
    val screenWidth = metrics.widthPixels
    val text = raw?.trim()?.lowercase().orEmpty()
    // 规范默认值就是 1080px（不是 dp）；字段缺失时不要乘密度
    if (text.isEmpty()) return 1080
    val value: Float = when {
        text.endsWith("vw") -> (text.removeSuffix("vw").toFloatOrNull() ?: 100f) * screenWidth / 100f
        text.endsWith("%") -> (text.removeSuffix("%").toFloatOrNull() ?: 100f) * screenWidth / 100f
        text.endsWith("px") -> text.removeSuffix("px").toFloatOrNull() ?: 1080f
        text.endsWith("dp") -> (text.removeSuffix("dp").toFloatOrNull() ?: 1080f) * metrics.density
        else -> (text.toFloatOrNull() ?: 1080f) * metrics.density
    }
    return value.toInt().coerceIn(240, screenWidth * 3)
}
