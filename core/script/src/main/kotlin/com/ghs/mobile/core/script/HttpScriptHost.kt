package com.ghs.mobile.core.script

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ghs.mobile.core.engine.proxy.ProxyRules
import com.ghs.mobile.core.engine.proxy.RulesProxySelector
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 宿主能力的真实现：OkHttp 负责取页与请求，WebView 负责"在页面里执行脚本"（无头兜底）。
 *
 * 设计要点：
 * - **全部同步返回**（脚本侧 `await` 会立即得到值，见 ADR-0016）
 * - 调用方通常在后台线程；[executePage] 内部自行切到主线程
 * - 无头页面每次调用都新建、用完销毁，避免 Cookie/全局状态互相污染（Cookie 同步在 M2 统一做）
 */
class HttpScriptHost(
    private val context: Context,
    /** 代理规则提供者：按请求逐个判定，白名单才能真正生效（ADR-0009） */
    private val proxyRules: () -> ProxyRules = { ProxyRules.direct() },
) : ScriptHostTransport {

    // 刻意不把 OkHttpClient 放进公开构造：okhttp 是 implementation 依赖，
    // 暴露它会让使用方的编译类路径被迫携带 okhttp。
    private val client: OkHttpClient = defaultClient(proxyRules)


    /**
     * 人机校验回调（F29）。
     *
     * 取页被挡时调用；返回 true 表示用户已在顶层窗口完成校验、
     * 且凭证已写进 WebView Cookie，可以重试原请求。
     */
    @Volatile
    var challengeHandler: ((String) -> Boolean)? = null

    /**
     * 页面 HTML 缓存（按 URL 的 MD5）。由 App 侧注入：手动刷新、切换站点时清空。
     * 缓存不过期，只按容量淘汰；失败页不会被写入（由实现方判断）。
     */
    @Volatile
    var htmlCache: HtmlCache? = null

    /** 取消令牌：每次取消自增；调用方记住自己的代号，只响应之后的取消 */
    private val cancelEpoch = java.util.concurrent.atomic.AtomicLong(0)

    /** 取消当前正在等待的取页/求值（不影响之后的调用） */
    fun cancelPending() {
        cancelEpoch.incrementAndGet()
    }

    init {
        // 长按「显示 webview」→ 中止当前取页，按失败处理
        WebViewFallbackHub.abortHandler = { cancelPending() }
    }

    override fun fetchHtml(url: String): String {
        val started = System.currentTimeMillis()
        htmlCache?.get(url)?.let { cached ->
            android.util.Log.i("GHS", "fetchHtml cache hit $url (${cached.length} 字符)")
            return cached
        }
        android.util.Log.i("GHS", "fetchHtml start $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", LANGUAGE)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        try {
            // 代理偶尔在响应收尾时断开（"connection closed"），重试一次通常就好
            var lastError: Exception? = null
            var challengeTried = false
            repeat(2) { attempt ->
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            // 顺序：先用无头 WebView 试一次（它能执行 JS 过掉多数挑战），
                            // 只有拿回的仍是挑战页/空页时，才把窗口弹给用户手动完成。
                            if (response.code in WEBVIEW_FALLBACK_CODES) {
                                android.util.Log.w("GHS", "fetchHtml ${response.code} → webview fallback $url")
                                val html = fetchWithWebView(url)
                                if (html.isNotBlank() && !isChallengePage(html)) {
                                    htmlCache?.put(url, html)
                                    return html
                                }
                                if (!challengeTried) {
                                    challengeTried = true
                                    val handler = challengeHandler
                                    if (handler != null) {
                                        android.util.Log.w("GHS", "fetchHtml ${response.code} → 展示校验窗口 $url")
                                        if (handler(url)) {
                                            android.util.Log.i("GHS", "人机校验完成，重试 $url")
                                            val retried = retryOnce(request)
                                            if (retried != null) {
                                                if (!isChallengePage(retried)) htmlCache?.put(url, retried)
                                                return retried
                                            }
                                        }
                                    }
                                }
                                if (html.isNotBlank()) return html
                            }
                            throw IllegalStateException("取页失败：HTTP ${response.code} $url")
                        }
                        val body = response.body?.string()
                            ?: throw IllegalStateException("取页为空：$url")
                        // 把 Set-Cookie 同步给 WebView：否则无头取页拿到的登录态
                        // 不会带到「显示 webview」的窗口里，页面就是空白
                        syncCookies(url, response.headers)
                        // 200 也可能是挑战页：不缓存，交给上层按失败处理
                        if (!isChallengePage(body)) htmlCache?.put(url, body)
                        return body
                    }
                } catch (error: java.io.IOException) {
                    lastError = error
                    if (attempt == 0) {
                        android.util.Log.w("GHS", "fetchHtml retry after ${error.message}")
                        Thread.sleep(300)
                    }
                }
            }
            // 连接层失败（connection closed / reset / timeout）也交给 WebView：
            // WebView 是浏览器握手，抗干扰能力比 OkHttp 强，且能执行页面脚本。
            android.util.Log.w("GHS", "fetchHtml 连接失败(${lastError?.message}) → webview fallback $url")
            val viaWebView = fetchWithWebView(url)
            if (viaWebView.isNotBlank() && !isChallengePage(viaWebView)) {
                htmlCache?.put(url, viaWebView)
                return viaWebView
            }
            // WebView 也撞到挑战：弹窗口让用户完成
            val handler = challengeHandler
            if (handler != null) {
                android.util.Log.w("GHS", "fetchHtml 回退仍是校验页 → 展示校验窗口 $url")
                if (handler(url)) {
                    val retried = retryOnce(request)
                    if (retried != null) return retried
                }
            }
            if (viaWebView.isNotBlank()) return viaWebView
            throw lastError ?: IllegalStateException("取页失败：$url")
        } finally {
            logDone(url, started)
        }
    }

    /**
     * 判断拿回来的是不是「挑战页 / 拦截页」。
     *
     * 这类页面不能当成内容：不能缓存，也不能直接解析，需要弹窗口给用户完成。
     */
    private fun isChallengePage(html: String): Boolean {
        if (html.length < 512) return true
        // 与 checkBoot 一致：看 <title> 是否命中挑战标题
        val title = TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return CHALLENGE_TITLES.any { title.contains(it, ignoreCase = true) }
    }

    /** 校验完成后重放一次原请求；仍失败则返回 null 交给上层兜底 */
    private fun retryOnce(request: Request): String? = runCatching {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()


    private fun logDone(url: String, started: Long) {
        android.util.Log.i("GHS", "fetchHtml done ${System.currentTimeMillis() - started}ms $url")
    }

    /** 给无头 WebView 套上与 OkHttp 相同的代理规则（WebView 代理是进程级的） */
    private fun applyWebViewProxy() {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.PROXY_OVERRIDE,
            )
        ) {
            return
        }
        val rules = proxyRules()
        val endpoint = rules.endpoint
        val builder = androidx.webkit.ProxyConfig.Builder()
        if (rules.enabled && endpoint?.isConfigured == true) {
            builder.addProxyRule("http://" + endpoint.authority)
            rules.whitelist.forEach { entry ->
                val pattern = entry.trim().removePrefix("*.").removePrefix(".")
                if (pattern.isNotEmpty()) runCatching { builder.addBypassRule(pattern) }
            }
        } else {
            builder.addDirect()
        }
        runCatching {
            androidx.webkit.ProxyController.getInstance().setProxyOverride(
                builder.build(),
                { runnable -> android.os.Handler(android.os.Looper.getMainLooper()).post(runnable) },
                {},
            )
        }
    }
    /**
     * 无头 WebView 取渲染后的 HTML。
     *
     * **不设超时**：WebView 方式可能真的要等人机验证/登录，
     * 超时只会把「还能救」的页面判死。要放弃由用户点「取消」（cancelPending）。
     * 失败返回空串，由调用方回落到原错误。
     */
    private fun fetchWithWebView(url: String): String {
        // 告诉 UI：正在用 WebView 取这个地址，方便提供「显示 webview」入口
        return try {
            executePage(url, "document.documentElement.outerHTML")
        } catch (error: Throwable) {
            android.util.Log.w("GHS", "webview fallback failed ${error.javaClass.simpleName}: ${error.message}")
            ""
        } finally {
        }
    }

    override fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
    ): ScriptHostTransport.HostResponse {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }

        val payload = body?.toRequestBody(JSON_MEDIA_TYPE)
        val request = when (method.uppercase()) {
            "POST" -> builder.post(payload ?: "".toRequestBody(JSON_MEDIA_TYPE)).build()
            "PUT" -> builder.put(payload ?: "".toRequestBody(JSON_MEDIA_TYPE)).build()
            "DELETE" -> builder.delete().build()
            else -> builder.get().build()
        }

        // 脚本侧取页（配置的 getHtml/net.request）同样会撞上"connection closed"，
        // 过去这里一次性失败直接把整段配置逻辑抛成脚本错误；按同样的策略重试。
        var lastError: java.io.IOException? = null
        repeat(REQUEST_ATTEMPTS) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    val headerMap = LinkedHashMap<String, String>()
                    for (name in response.headers.names()) {
                        headerMap[name] = response.header(name).orEmpty()
                    }
                    return ScriptHostTransport.HostResponse(
                        status = response.code,
                        headers = headerMap,
                        body = response.body?.string().orEmpty(),
                    )
                }
            } catch (error: java.io.IOException) {
                lastError = error
                if (attempt < REQUEST_ATTEMPTS - 1) {
                    android.util.Log.w("GHS", "request retry after ${error.message} ($url)")
                    Thread.sleep(250L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("请求失败：$url")
    }

    /**
     * 无头页面执行：加载 URL → 等页面完成 → 在页面上下文里求值 `code`。
     *
     * `code` 应当是**表达式**（配置里的写法都是 `(function(){...})()` 这类）；
     * 返回值统一转成字符串：本身就是字符串就原样返回，否则 JSON 序列化。
     */
    override fun executePage(url: String, code: String, show: Boolean): String {
        // 只要在用 WebView 取页，就让 UI 出现「显示 webview」入口
        WebViewFallbackHub.start(url)
        // 记住本次调用的代号：只有这之后发生的「取消」才作数
        val epoch = cancelEpoch.get()
        val loaded = CountDownLatch(1)
        val result = CountDownLatch(1)
        var output = ""
        var failure: String? = null

        onMain {
            // WebView 兜底也必须走代理，否则直连会被站点挡掉
            applyWebViewProxy()
            @SuppressLint("SetJavaScriptEnabled")
            val view = WebView(context.applicationContext)
            view.settings.javaScriptEnabled = true
            // 兜底用桌面 UA：不少站点对移动 UA 直接返回精简页或挑战页
            view.settings.userAgentString = DESKTOP_UA
            view.settings.domStorageEnabled = true
            // 登记实例：用户点「显示 webview」时把它直接挂到窗口上
            WebViewFallbackHub.attach(view)
            // 配置要求显示（executeJs show=true）：自动打开显示窗口
            if (show) WebViewMirrorLauncher.open(context, url)
            // 配置里的页面脚本常常返回 Promise（等 iframe / fetch 完成）：用它回传最终结果
            view.addJavascriptInterface(
                object {
                    @android.webkit.JavascriptInterface
                    fun done(value: String) {
                        output = value
                        result.countDown()
                    }

                    @android.webkit.JavascriptInterface
                    fun error(message: String) {
                        failure = "页面脚本出错：$message"
                        result.countDown()
                    }
                },
                "__ghsEval",
            )
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    loaded.countDown()
                }
            }

            // WebView 也要中文：loadUrl 支持附带请求头
            view.loadUrl(url, mapOf("Accept-Language" to LANGUAGE))

            // 桌面页资源多、广告脚本常卡住 onPageFinished（实测 25s 都不来）。
            // 这里并行轮询 DOM 就绪：正文出现就取，不再干等"页面彻底加载完"。
            Thread {
                repeat(DOM_POLL_ATTEMPTS) {
                    Thread.sleep(DOM_POLL_INTERVAL_MS)
                    if (loaded.count == 0L) return@Thread
                    val probe = CountDownLatch(1)
                    var ready = false
                    onMain {
                        view.evaluateJavascript(READY_JS) { raw ->
                            ready = raw?.trim() == "true"
                            probe.countDown()
                        }
                    }
                    probe.await(2, TimeUnit.SECONDS)
                    if (ready) {
                        android.util.Log.i("GHS", "webview DOM ready: $url")
                        loaded.countDown()
                        return@Thread
                    }
                }
            }

            Thread {
                try {
                    // 不设超时：由用户点「取消」决定放弃
                    while (!loaded.await(200, TimeUnit.MILLISECONDS)) {
                        if (cancelEpoch.get() != epoch) {
                            failure = "已取消"
                            result.countDown()
                            return@Thread
                        }
                    }
                    onMain {
                        view.evaluateJavascript(
                            "(function(){" +
                                "try{" +
                                "var v=(" + code + ");" +
                                // 返回 Promise：交给 __ghsEval 回传，这里先标记为挂起
                                "if(v&&typeof v.then==='function'){" +
                                "v.then(function(r){__ghsEval.done(String(r));}," +
                                "function(e){__ghsEval.error(String(e));});" +
                                "return '__GHS_PENDING__';" +
                                "}" +
                                "return v;" +
                                "}catch(e){return '__GHS_ERR__'+e;}" +
                                "})()",
                        ) { raw ->
                            val value = normalize(raw)
                            // Promise 挂起：结果稍后由 __ghsEval.done/error 回调
                            if (value == "__GHS_PENDING__") return@evaluateJavascript
                            output = value
                            if (output.startsWith("__GHS_ERR__")) {
                                failure = "页面脚本出错：${output.removePrefix("__GHS_ERR__")}"
                            }
                            result.countDown()
                        }
                    }
                } catch (error: Throwable) {
                    failure = error.message ?: error.toString()
                    result.countDown()
                }
            }.start()

            // 等待结果；超时则记失败
            // 等待结果：不设超时，只响应取消
            Thread {
                while (!result.await(200, TimeUnit.MILLISECONDS)) {
                    if (cancelEpoch.get() != epoch) {
                        failure = "已取消"
                        result.countDown()
                        break
                    }
                }
            }.start()

            // 结果到齐后再销毁 WebView（必须在主线程）
            Thread {
                runCatching { result.await() }
                onMain {
                    runCatching {
                        WebViewFallbackHub.detach(view)
                        view.stopLoading()
                        view.destroy()
                    }
                }
            }.start()
        }

        result.await()
        // 先撤掉「显示 webview」入口：失败分支会 throw，放在其后就永远不执行
        WebViewFallbackHub.stop()
        failure?.let { throw IllegalStateException(it) }
        return output
    }

    override fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    /** 把 OkHttp 收到的 Set-Cookie 写进 WebView 的 CookieManager */
    private fun syncCookies(url: String, headers: okhttp3.Headers) {
        val cookies = headers.values("Set-Cookie")
        if (cookies.isEmpty()) return
        val manager = android.webkit.CookieManager.getInstance()
        cookies.forEach { cookie -> runCatching { manager.setCookie(url, cookie) } }
        runCatching { manager.flush() }
    }

    private fun normalize(raw: String?): String {
        if (raw == null || raw == "null") return ""
        // evaluateJavascript 回传的是 JSON 编码值：字符串会带引号，需要还原
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\\\", "\\")
        }
        return trimmed
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    block()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(10, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val LANGUAGE = com.ghs.mobile.core.engine.net.UserAgents.ACCEPT_LANGUAGE

        private const val USER_AGENT =
            com.ghs.mobile.core.engine.net.UserAgents.DESKTOP_CHROME
        /** WebView 兜底统一用桌面 UA（加载电脑端页面） */
        private const val DESKTOP_UA =
            com.ghs.mobile.core.engine.net.UserAgents.DESKTOP_CHROME
        private const val PAGE_TIMEOUT_MS = 25_000L
        private const val REQUEST_ATTEMPTS = 3
        private const val EVAL_TIMEOUT_MS = 20_000L
        /** WebView 兜底总上限：校验页可能永不结束，不能让它拖住整次请求 */
        /** DOM 就绪轮询：每 500ms 探一次，最多 80 次（约 40s） */
        private const val DOM_POLL_INTERVAL_MS = 800L
        private const val DOM_POLL_ATTEMPTS = 16

        /** 正文出现即视为可解析，不要求图片 / 广告脚本全部加载完 */
        private const val READY_JS =
            "(document.readyState==='interactive'||document.readyState==='complete')" +
                "&&!!document.body&&document.body.innerHTML.length>200"

        /** 这些状态码说明直连被挡（Cloudflare / 校验页），值得用无头 WebView 再试一次 */
        private val WEBVIEW_FALLBACK_CODES = setOf(403, 429, 503)

        /** 挑战页标题特征：命中即视为需要人机校验（与参考实现的 checkBoot 一致） */
        private val CHALLENGE_TITLES = listOf(
            "just a moment", "captcha", "checking your browser",
            "attention required", "verify you are human",
            "安全验证", "人机验证",
        )
        private val TITLE_REGEX =
            Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(rules: () -> ProxyRules): OkHttpClient = OkHttpClient.Builder()
            // 真实站点经代理时一页 HTML 可能要十几秒；20s 读超时会把正常请求判成失败
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            // 与 WebView 共用 Cookie：人机校验拿到的凭证后续请求要带上（F29）
            .cookieJar(WebViewCookieJar())
            .proxySelector(RulesProxySelector(rules))
            .proxyAuthenticator { _, response ->
                val endpoint = rules().endpoint
                if (endpoint?.username.isNullOrEmpty()) {
                    null
                } else {
                    response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(endpoint.username!!, endpoint.password.orEmpty()))
                        .build()
                }
            }
            .build()
    }
}

/** 页面 HTML 缓存的最小契约（App 侧用 LruCache 实现） */
interface HtmlCache {
    fun get(url: String): String?
    fun put(url: String, html: String)
}
