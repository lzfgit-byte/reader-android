package com.ghs.mobile.demo

import android.content.Context
import com.ghs.mobile.core.engine.config.ConfigEngine
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import com.ghs.mobile.core.model.CContent
import com.ghs.mobile.core.model.DetailInfo
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.Page
import com.ghs.mobile.core.model.TextContent
import com.ghs.mobile.core.script.WebViewScriptEngine

/**
 * 演示宿主：把 `getHtml` 指向**应用内置的样例页面**，完全不联网。
 *
 * 这样首页与详情页走的是和真实配置完全相同的链路（脚本 → $ → 领域模型），
 * 只是数据来源换成本地 assets。
 */
class DemoHost(private val context: Context) : ScriptHostTransport {

    override fun fetchHtml(url: String): String {
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

    override fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): ScriptHostTransport.HostResponse =
        ScriptHostTransport.HostResponse(status = 200, headers = emptyMap(), body = "{}")

    override fun executePage(url: String, code: String): String = ""

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
 * 一次演示会话：持有脚本引擎与配置，暴露"取列表 / 取详情 / 取正文"。
 *
 * 引擎是重对象（WebView），所以整场复用一份；[close] 时释放。
 */
class DemoSession(
    private val context: Context,
    private val proxyRules: com.ghs.mobile.core.engine.proxy.ProxyRules =
        com.ghs.mobile.core.engine.proxy.ProxyRules.direct(),
) : AutoCloseable {

    private val host = DemoHost(context)
    private val engine = WebViewScriptEngine(context, proxyRules)
    private val configEngine = ConfigEngine(engine, host)

    init {
        configEngine.load(loadConfig())
    }

    fun page(): Page {
        val html = host.fetchHtml("https://demo.local/list")
        configEngine.withHtml(html)
        return configEngine.page()
    }

    fun detail(item: Item): DetailInfo? = configEngine.detailInfo(item)

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
    }

    private fun loadConfig(): String =
        context.assets.open(CONFIG).bufferedReader().use { it.readText() }

    private companion object {
        const val CONFIG = "demo/config.js"
    }
}
