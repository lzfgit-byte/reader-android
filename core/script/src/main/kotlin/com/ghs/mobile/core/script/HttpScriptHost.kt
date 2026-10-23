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

    override fun fetchHtml(url: String): String {
        val started = System.currentTimeMillis()
        android.util.Log.i("GHS", "fetchHtml start $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
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
                            // 先走人机校验（F29）：挑战必须在有界面的窗口里完成，
                            // 无头 WebView 只会撞到同一个挑战然后超时。
                            if (response.code in WEBVIEW_FALLBACK_CODES && !challengeTried) {
                                challengeTried = true
                                val handler = challengeHandler
                                if (handler != null) {
                                    android.util.Log.w("GHS", "fetchHtml ${response.code} → 请求人机校验 $url")
                                    if (handler(url)) {
                                        android.util.Log.i("GHS", "人机校验完成，重试 $url")
                                        val retried = retryOnce(request)
                                        if (retried != null) return retried
                                    }
                                }
                            }
                            // 校验未启用或没通过时，退回无头 WebView 取渲染后的 HTML
                            if (response.code in WEBVIEW_FALLBACK_CODES) {
                                android.util.Log.w("GHS", "fetchHtml ${response.code} → webview fallback $url")
                                val html = fetchWithWebView(url)
                                if (html.isNotBlank()) return html
                            }
                            throw IllegalStateException("取页失败：HTTP ${response.code} $url")
                        }
                        return response.body?.string() ?: throw IllegalStateException("取页为空：$url")
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
            if (viaWebView.isNotBlank()) return viaWebView
            throw lastError ?: IllegalStateException("取页失败：$url")
        } finally {
            logDone(url, started)
        }
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
     * 单独设上限（[FALLBACK_TIMEOUT_MS]），避免校验页一直不结束把请求拖到 180s；
     * 失败返回空串，由调用方回落到原错误。
     */
    private fun fetchWithWebView(url: String): String {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        return try {
            val task = executor.submit(
                java.util.concurrent.Callable { executePage(url, "document.documentElement.outerHTML") },
            )
            task.get(FALLBACK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            android.util.Log.w("GHS", "webview fallback failed ${error.javaClass.simpleName}: ${error.message}")
            ""
        } finally {
            executor.shutdownNow()
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
    override fun executePage(url: String, code: String): String {
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
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    loaded.countDown()
                }
            }

            view.loadUrl(url)

            Thread {
                try {
                    if (!loaded.await(PAGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        failure = "页面加载超时：$url"
                        return@Thread
                    }
                    onMain {
                        view.evaluateJavascript(
                            "(function(){ try { return ($code); } catch (e) { return '__GHS_ERR__' + e; } })()",
                        ) { raw ->
                            output = normalize(raw)
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
            Thread {
                if (!result.await(EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    failure = "页面脚本超时：$url"
                    result.countDown()
                }
            }.start()

            // 结果到齐后再销毁 WebView（必须在主线程）
            Thread {
                runCatching { result.await(EVAL_TIMEOUT_MS + 1_000, TimeUnit.MILLISECONDS) }
                onMain {
                    runCatching {
                        view.stopLoading()
                        view.destroy()
                    }
                }
            }.start()
        }

        result.await(EVAL_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS)
        failure?.let { throw IllegalStateException(it) }
        return output
    }

    override fun close() {
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
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
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
        /** WebView 兜底统一用桌面 UA（加载电脑端页面） */
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val PAGE_TIMEOUT_MS = 180_000L
        private const val REQUEST_ATTEMPTS = 3
        private const val EVAL_TIMEOUT_MS = 180_000L
        /** WebView 兜底总上限：校验页可能永不结束，不能让它拖住整次请求 */
        private const val FALLBACK_TIMEOUT_MS = 25_000L

        /** 这些状态码说明直连被挡（Cloudflare / 校验页），值得用无头 WebView 再试一次 */
        private val WEBVIEW_FALLBACK_CODES = setOf(403, 429, 503)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(rules: () -> ProxyRules): OkHttpClient = OkHttpClient.Builder()
            // 真实站点经代理时一页 HTML 可能要十几秒；20s 读超时会把正常请求判成失败
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
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
