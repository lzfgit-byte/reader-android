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
import com.ghs.mobile.core.script.window.WebWindow
import com.ghs.mobile.core.script.window.WebWindows
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

    /** 取页窗口是常驻的，同一时刻只允许一个任务占用它 */
    private val fetchLock = java.util.concurrent.locks.ReentrantLock()

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
        logProxyDecision(url)
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", LANGUAGE)
            .header("Accept", "text/html,application/xhtml+xml")
        // 桌面 Client Hints：不加的话服务端按 sec-ch-ua-mobile=?1 仍回移动版
        com.ghs.mobile.core.engine.net.UserAgents.clientHints().forEach { (name, value) ->
            requestBuilder.header(name, value)
        }
        val request = requestBuilder.build()

        try {
            // 代理偶尔在响应收尾时断开（"connection closed"），重试一次通常就好
            var lastError: Exception? = null
            var challengeTried = false
            repeat(2) { attempt ->
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            // 站点的拒绝页常常就是「挑战 / 验证」页（例如 Cloudflare 的
                            // 工作量验证：HTTP 492 + 一段会自动执行的脚本）。只看状态码会把它
                            // 当成普通失败直接抛出，所以这里读一次正文再判断。
                            val peek = runCatching { response.peekBody(64 * 1024).string() }.getOrDefault("")
                            val challenge = looksLikeChallenge(peek)
                            val blocked = response.code in WEBVIEW_FALLBACK_CODES || challenge
                            android.util.Log.w(
                                "GHS",
                                "fetchHtml ${response.code} $url server=${response.header("server")} " +
                                    "blocked=$blocked body=${peek.replace("\n", " ").take(160)}",
                            )
                            // 顺序：先用无头 WebView 试一次（它能执行 JS 过掉多数挑战），
                            // 只有拿回的仍是挑战页/空页时，才把窗口弹给用户手动完成。
                            if (blocked) {
                                android.util.Log.w("GHS", "fetchHtml ${response.code} → webview fallback $url")
                                // 需要人点一下的挑战页（Cloudflare 托管挑战）等它自己过没用，
                                // 等只会让界面白转两分钟；只有会自算的（工作量验证）才值得等
                                val selfSolving = challenge && !isInteractiveChallenge(peek)
                                val html = fetchWithWebView(url, expectChallenge = selfSolving)
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
                                            // 校验后仍拿回挑战页：说明这次校验没换来通行凭证，
                                            // 不能把挑战页当正文交出去（否则界面就"没有更多了"）
                                            if (retried != null && !isChallengePage(retried)) {
                                                htmlCache?.put(url, retried)
                                                return retried
                                            }
                                            android.util.Log.w("GHS", "人机校验后仍被挡（挑战页） $url")
                                        }
                                    }
                                }
                                throw IllegalStateException("取页失败：站点要求人机校验且未通过 $url")
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
                    if (retried != null && !isChallengePage(retried)) {
                        htmlCache?.put(url, retried)
                        return retried
                    }
                }
            }
            throw lastError ?: IllegalStateException("取页失败：站点要求人机校验且未通过 $url")
        } finally {
            logDone(url, started)
        }
    }

    /**
     * 判断拿回来的是不是「挑战页 / 拦截页」。
     *
     * 这类页面不能当成内容：不能缓存，也不能直接解析，需要弹窗口给用户完成。
     * 只按标题判断是不够的：Cloudflare 的挑战页会按浏览器语言本地化标题
     * （中文环境是「请稍候…」而不是 "Just a moment..."），漏判的后果是把
     * 挑战页当成正文缓存下来，界面就一直是"没有更多了"。
     */
    private fun isChallengePage(html: String): Boolean {
        if (html.length < 512) return true
        return looksLikeChallenge(html)
    }

    /**
     * 标题或结构标记任一命中即视为挑战页。
     *
     * 用于非 2xx 的拒绝页：这类页面可能很短，但"短"本身不能作为挑战判据
     * （否则一个空的 404 也会被当成挑战，被当成正文缓存下来）。
     */
    private fun looksLikeChallenge(html: String): Boolean {
        if (html.isBlank()) return false
        if (looksLikeChallengeTitle(titleOf(html))) return true
        return CHALLENGE_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    private fun looksLikeChallengeTitle(title: String): Boolean {
        if (title.isEmpty()) return false
        return CHALLENGE_TITLES.any { title.contains(it, ignoreCase = true) }
    }

    /** Cloudflare「托管挑战」需要人点一下（Turnstile 组件），等它自己过是白等 */
    private fun isInteractiveChallenge(html: String): Boolean =
        html.isNotBlank() && CF_INTERACTIVE_MARKERS.any { html.contains(it, ignoreCase = true) }

    private fun titleOf(html: String): String =
        TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()

    /** 校验完成后重放一次原请求；仍失败则返回 null 交给上层兜底 */
    private fun retryOnce(request: Request): String? = runCatching {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()


    private fun logDone(url: String, started: Long) {
        android.util.Log.i("GHS", "fetchHtml done ${System.currentTimeMillis() - started}ms $url")
    }

    /**
     * 取页前把**代理判定结果**打一行日志。
     *
     * 排障时"到底走了代理没有"是第一个要回答的问题：只报"连接失败"没法区分
     * 是代理不生效还是代理本身不通。这里只打一行、只在取页时打（不是每个图片请求）。
     */
    private fun logProxyDecision(url: String) {
        runCatching {
            val uri = java.net.URI(url)
            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
            val rules = proxyRules()
            val via = rules.shouldProxy(uri.host, port)
            val target = if (via) "经 ${rules.endpoint?.authority}" else "直连"
            android.util.Log.i("GHS", "proxy ${uri.host}:$port -> $target")
        }
    }

    /** 给无头 WebView 套上与 OkHttp 相同的代理判定（WebView 代理是进程级的） */
    private fun applyWebViewProxy(url: String) {
        WebViewProxy.apply(proxyRules(), url)
    }
    /**
     * 无头 WebView 取渲染后的 HTML。
     *
     * **不设超时**：WebView 方式可能真的要等人机验证/登录，
     * 超时只会把「还能救」的页面判死。要放弃由用户点「取消」（cancelPending）。
     * 失败返回空串，由调用方回落到原错误。
     */
    private fun fetchWithWebView(url: String, expectChallenge: Boolean = false): String {
        var last = try {
            executePageInternal(url, OUTER_HTML_JS, show = false, waitRedirect = expectChallenge)
        } catch (error: Throwable) {
            android.util.Log.w("GHS", "webview fallback failed ${error.javaClass.simpleName}: ${error.message}")
            ""
        }
        if (last.isNotBlank() && !isChallengePage(last)) {
            android.util.Log.i(
                "GHS",
                "webview html len=${last.length} title=${titleOf(last)} url=$url",
            )
            return last
        }
        if (last.isBlank()) return last

        // 取回的是验证页。**不能重载**：重载会把用户刚完成的验证清掉，表现就是
        // "点了人机验证 → 页面又刷新回验证页"，永远走不出来。
        // 需要人点选的（Cloudflare 托管挑战）等也没有用，直接交给上层弹可见窗口；
        // 会自己算完的（工作量验证）才在原地等它离开验证态。
        if (isInteractiveChallenge(last)) {
            android.util.Log.w("GHS", "webview 取回需人工验证的页面（title=${titleOf(last)}），交给校验窗口 $url")
            return last
        }
        android.util.Log.i("GHS", "webview 仍取回验证页（title=${titleOf(last)}），原地等它通过 $url")
        waitChallengeGone()
        // 等完之后重新读同一个文档（不重新加载）
        val refreshed = runCatching {
            executePageInternal(url, OUTER_HTML_JS, show = false, waitRedirect = false, reload = false)
        }.getOrNull().orEmpty()
        return if (refreshed.isNotBlank()) refreshed else last
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
     *
     * 容器用常驻的 [WebWindows.FETCH_SPEC] 窗口（对齐"单例隐藏窗口"的用法）：
     * 第一次建，之后每次只换文档，省掉 1.5–3s 的 WebView 冷启动。
     */
    override fun executePage(url: String, code: String, show: Boolean): String =
        executePageInternal(url, code, show, waitRedirect = false)

    /**
     * 无头页面执行（真实实现）。
     *
     * [waitRedirect] 用于「工作量验证」这类拦截页：它先返回一段自己执行脚本的页面，
     * 算完后**跳转**或**种 Cookie**，此时求值会被验证脚本堵住，必须等它通过。
     *
     * [reload] 为 false 时**不重新加载**，只在当前文档里求值：验证页通过后要读正文，
     * 此时重载会把刚拿到的通行 Cookie 和页面状态一起清掉。
     */
    private fun executePageInternal(
        url: String,
        code: String,
        show: Boolean,
        waitRedirect: Boolean,
        reload: Boolean = true,
    ): String {
        // 只要在用 WebView 取页，就让 UI 出现「显示 webview」入口
        if (reload) WebViewFallbackHub.start(url)
        try {
            // 记住本次调用的代号：只有这之后发生的「取消」才作数
            return runInFetchWindow(url, code, show, cancelEpoch.get(), waitRedirect, reload)
        } finally {
            // 撤掉入口：失败分支会 throw，放在其后就永远不执行
            if (reload) WebViewFallbackHub.stop()
        }
    }

    /** 同一时刻只允许一个取页任务占着这个窗口 */
    private fun runInFetchWindow(
        url: String,
        code: String,
        show: Boolean,
        epoch: Long,
        waitRedirect: Boolean,
        reload: Boolean,
    ): String {
        // 只读当前文档时不新建窗口：没有窗口说明页面已经不在了
        val window = if (reload) {
            WebWindows.acquire(WebWindows.FETCH_SPEC)
        } else {
            WebWindows.current(WebWindows.FETCH) ?: return ""
        }
        if (!fetchLock.tryLock(FETCH_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("取页窗口正忙，请稍后再试")
        }
        try {
            val task = FetchTask()
            if (reload) {
                // 桥与回调都指向「当前任务」：窗口复用后不会把结果写进上一个任务的槽位
                window.setBridge(
                    done = { value -> task.finish(value) },
                    error = { message -> task.fail("页面脚本出错：$message") },
                )
                window.setPageFinishedListener { _, _ ->
                    task.finishes.incrementAndGet()
                    task.loaded.countDown()
                }
                // 403 这类主框架错误不会触发 onPageFinished：也要让等待方继续，
                // 否则取页会一直挂到用户手动取消
                window.setHttpErrorListener { _, _ -> task.loaded.countDown() }
                window.setErrorListener { _, _ -> task.loaded.countDown() }

                onMain {
                    // WebView 兜底也必须走代理，否则直连会被站点挡掉
                    applyWebViewProxy(url)
                    val view = window.view
                    runCatching { view.stopLoading() }
                    runCatching { view.clearHistory() }
                    // 登记实例：用户点「显示 webview」时把它直接挂到窗口上
                    WebViewFallbackHub.attach(view)
                    // 配置要求显示（executeJs show=true）：自动打开显示窗口
                    if (show) WebViewMirrorLauncher.open(context, url)
                    // WebView 也要桌面页面：中文 + 桌面 Client Hints 一起附上
                    val pageHeaders = mutableMapOf("Accept-Language" to LANGUAGE)
                    pageHeaders.putAll(com.ghs.mobile.core.engine.net.UserAgents.clientHints())
                    view.loadUrl(url, pageHeaders)
                }

                // 桌面页资源多、广告脚本常卡住 onPageFinished（实测 25s 都不来）：
                // 并行轮询 DOM 就绪，正文出现就取，不再干等"页面彻底加载完"
                startDomPoll(window, task, epoch)
                awaitLoaded(task, epoch)
                // 验证页会自己跳转或需要重载：等它通过再求值，否则会被验证脚本堵住
                if (waitRedirect && task.failure == null) waitChallengeGone()
            }

            if (task.failure == null) {
                val raw = runCatching { window.eval(executeJs(code), EVAL_TIMEOUT_MS) }.getOrNull()
                if (raw == null) {
                    task.fail("页面脚本执行超时")
                } else {
                    val value = normalize(raw)
                    when {
                        // Promise 挂起：结果稍后由 __ghsWin.done/error 回传
                        value == "__GHS_PENDING__" -> Unit
                        value.startsWith("__GHS_ERR__") ->
                            task.fail("页面脚本出错：${value.removePrefix("__GHS_ERR__")}")
                        else -> task.finish(value)
                    }
                }
            }

            awaitResult(task, epoch)
            task.failure?.let { throw IllegalStateException(it) }
            return task.output
        } finally {
            fetchLock.unlock()
        }
    }

    private fun startDomPoll(window: WebWindow, task: FetchTask, epoch: Long) {
        Thread {
            repeat(DOM_POLL_ATTEMPTS) {
                Thread.sleep(DOM_POLL_INTERVAL_MS)
                if (task.loaded.count == 0L) return@Thread
                if (cancelEpoch.get() != epoch) return@Thread
                val ready = runCatching { window.eval(READY_JS) }.getOrNull()?.trim() == "true"
                if (ready) {
                    android.util.Log.i("GHS", "webview DOM ready: ${window.url}")
                    task.loaded.countDown()
                    return@Thread
                }
            }
        }.start()
    }

    /**
     * 等页面就绪：响应「取消」，也设上限。
     *
     * 不设上限时，一个卡在验证脚本里的页面会把取页线程拖到几分钟，
     * 界面表现就是"一直转圈，永远不结束"。
     */
    private fun awaitLoaded(task: FetchTask, epoch: Long) {
        val deadline = System.currentTimeMillis() + PAGE_WAIT_MS
        while (!task.loaded.await(200, TimeUnit.MILLISECONDS)) {
            if (cancelEpoch.get() != epoch) {
                task.fail("已取消")
                return
            }
            if (System.currentTimeMillis() > deadline) {
                android.util.Log.w("GHS", "页面 ${PAGE_WAIT_MS}ms 内未就绪，按当前 DOM 处理")
                return
            }
        }
    }

    /** 等脚本结果：同样只响应「取消」 */
    private fun awaitResult(task: FetchTask, epoch: Long) {
        while (!task.result.await(200, TimeUnit.MILLISECONDS)) {
            if (cancelEpoch.get() != epoch) task.fail("已取消")
        }
    }

    /**
     * 等「验证页」自己离开验证态。
     *
     * **只探测、不重载**：工作量验证算完后自己会跳转或种 Cookie，重载反而把页面状态
     * 清掉；对需要人点击的挑战页，重载更是把用户刚过的验证直接作废
     * （现场表现就是"点了人机验证 → 又刷新回验证页"）。
     *
     * 返回 true 表示已离开验证态；false 表示等到上限仍未通过。
     */
    private fun waitChallengeGone(): Boolean {
        val window = WebWindows.current(WebWindows.FETCH) ?: return false
        val started = System.currentTimeMillis()
        val deadline = started + CHALLENGE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            // 2s 超时：验证脚本没跑完时这里返回 null，正好当作"还没好"
            val raw = runCatching { window.eval(CHALLENGE_PROBE_JS, 2_000) }.getOrNull()
            if (raw != null) {
                val probe = normalize(raw)
                val title = probe.substringBefore(SEP)
                val bodyLength = probe.substringAfter(SEP, "0").substringBefore(SEP).toIntOrNull() ?: 0
                val marker = probe.substringAfterLast(SEP, "0") == "1"
                if (!marker && !looksLikeChallengeTitle(title) && bodyLength > 200) {
                    android.util.Log.i(
                        "GHS",
                        "验证页已通过（${System.currentTimeMillis() - started}ms, title=$title, body=$bodyLength）",
                    )
                    return true
                }
            }
            runCatching { Thread.sleep(CHALLENGE_POLL_MS) }
        }
        android.util.Log.w("GHS", "验证页 ${CHALLENGE_WAIT_MS}ms 内未通过，按当前页面处理")
        return false
    }

    /** 在页面里执行配置给的表达式；Promise 结果经 `__ghsWin` 桥回传 */
    private fun executeJs(code: String): String =
        "(function(){" +
            "try{" +
            "var v=(" + code + ");" +
            "if(v&&typeof v.then==='function'){" +
            "v.then(function(r){__ghsWin.done(String(r));}," +
            "function(e){__ghsWin.error(String(e));});" +
            "return '__GHS_PENDING__';" +
            "}" +
            "return v;" +
            "}catch(e){return '__GHS_ERR__'+e;}" +
            "})()"

    /** 一次取页任务的状态：窗口复用时靠它把回调隔离开 */
    private class FetchTask {
        @Volatile
        var output = ""

        @Volatile
        var failure: String? = null

        val loaded = CountDownLatch(1)
        val result = CountDownLatch(1)

        /** 第几次 onPageFinished：验证页 → 正文通常是两次 */
        val finishes = java.util.concurrent.atomic.AtomicInteger(0)

        fun finish(value: String) {
            output = value
            result.countDown()
        }

        fun fail(message: String) {
            failure = message
            result.countDown()
        }
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

    /**
     * 还原 `evaluateJavascript` 的返回值。
     *
     * 回传的是 JSON 编码值：字符串带引号，且 Android 的序列化会把 `<` `>` `&` `'`
     * 转成 `\u003C` 这类转义。只做 `\"` / `\\n` 的替换是不够的——
     * 取回来的整页 HTML 会变成 `\u003Chtml ...`，交给 jsoup 解析直接是 0 个节点。
     */
    private fun normalize(raw: String?): String {
        if (raw == null || raw == "null") return ""
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            // 用 JSON 解析器完整还原转义（\uXXXX / \" / \\ / \n ...）
            return runCatching {
                org.json.JSONTokener(trimmed).nextValue() as? String ?: ""
            }.getOrElse {
                trimmed.substring(1, trimmed.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\\\", "\\")
            }
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

        // UA 随设备 WebView 版本变化，必须是 val + getter，不能是 const
        private val USER_AGENT: String get() = com.ghs.mobile.core.engine.net.UserAgents.UA
        /** WebView 兜底统一用桌面 UA（加载电脑端页面） */
        /** WebView 兜底统一用桌面 UA（加载电脑端页面） */
        private val DESKTOP_UA: String get() = com.ghs.mobile.core.engine.net.UserAgents.UA
        private const val PAGE_TIMEOUT_MS = 25_000L
        private const val REQUEST_ATTEMPTS = 3
        private const val EVAL_TIMEOUT_MS = 20_000L

        /** 等别的取页任务让出窗口的上限 */
        private const val FETCH_LOCK_WAIT_MS = 15_000L

        /** 等页面就绪的上限：卡在验证脚本里的页面不能无限拖住取页 */
        private const val PAGE_WAIT_MS = 45_000L

        /** 等验证页通过的上限：手机算工作量验证实测要 1~2 分钟，留足余量 */
        private const val CHALLENGE_WAIT_MS = 120_000L

        /** 验证页轮询间隔 */
        private const val CHALLENGE_POLL_MS = 800L

        /** 探测串里的分隔符（\u0001 不会出现在标题与正文里） */
        private const val SEP = "\u0001"

        /**
         * 验证页探测：一次求值同时拿「标题 / 正文长度 / 是否仍是验证页」。
         *
         * 只看标题会漏：挑战页按语言本地化标题，中文环境是「请稍候…」；
         * 结构标记 `_cf_chl_opt` / `challenge-platform` 是 Cloudflare 挑战页的固有特征。
         */
        private val CHALLENGE_PROBE_JS = """
            (function(){
              try{
                var t=((document.title||'')).trim();
                var b=document.body?(document.body.innerText||''):'';
                var h=document.documentElement?document.documentElement.outerHTML:'';
                var m=(h.indexOf('_cf_chl_opt')>=0||h.indexOf('challenge-platform')>=0)?1:0;
                return t+'$SEP'+b.length+'$SEP'+m;
              }catch(e){return '$SEP'+0+'$SEP'+0;}
            })()
        """.trimIndent()

        /** 取回整页 HTML 的表达式（配置里的 getHtml 走的就是它） */
        private const val OUTER_HTML_JS = "document.documentElement.outerHTML"
        /** DOM 就绪轮询：每 800ms 探一次，最多 16 次（约 13s） */
        private const val DOM_POLL_INTERVAL_MS = 800L
        private const val DOM_POLL_ATTEMPTS = 16

        /** 正文出现即视为可解析，不要求图片 / 广告脚本全部加载完 */
        private const val READY_JS =
            "(document.readyState==='interactive'||document.readyState==='complete')" +
                "&&!!document.body&&document.body.innerHTML.length>200"

        /** 这些状态码说明直连被挡（Cloudflare / 校验页），值得用无头 WebView 再试一次 */
        private val WEBVIEW_FALLBACK_CODES = setOf(403, 492, 429, 503)

        /** 挑战页标题特征：命中即视为需要人机校验 */
        private val CHALLENGE_TITLES = listOf(
            // Cloudflare 一类：标题按浏览器语言本地化，英文、中文都要覆盖
            "just a moment", "checking your browser", "attention required",
            "please wait", "captcha", "verify you are human",
            "请稍候", "請稍候", "正在进行安全验证", "正在验证您是否", "安全验证",
            "人机验证", "人机校验",
            // 工作量验证（proof-of-work）类拦截页：标题常见「安全防御 - 工作量验证」
            "工作量验证", "安全防御",
        )

        /** 挑战页结构标记：标题被改掉也认得出（只取挑战页独有、正文页不会出现的） */
        private val CHALLENGE_MARKERS = listOf(
            "_cf_chl_opt", "cf-chl-", "challenge-platform", "cf-browser-verification",
        )

        /** 需要人点一下的挑战页（Cloudflare 托管挑战 / Turnstile）：等它自己过没用 */
        private val CF_INTERACTIVE_MARKERS = listOf("_cf_chl_opt", "challenge-platform")
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
