package com.ghs.mobile.core.script.window

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ghs.mobile.core.engine.net.UserAgents
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 窗口复用策略。三种策略对应实际使用场景，不再由各处各写一套生命周期：
 *
 * - [SINGLETON]：常驻一个，`show/hide` 复用（脚本引擎、取页、网页浏览、校验窗）
 * - [PER_TASK]：用一次建一个，用完销毁（需要绝对干净上下文时）
 * - [POOL]：对象池，`min` 保活、`max` 封顶、空闲回收（图片兜底）
 */
enum class WindowReuse { SINGLETON, PER_TASK, POOL }

/**
 * 窗口规格：名字 + 复用策略 + 默认设置。
 *
 * 名字同时是注册表的键，同名 [WindowReuse.SINGLETON] 只会有一个实例。
 */
data class WindowSpec(
    val name: String,
    val reuse: WindowReuse = WindowReuse.SINGLETON,
    val poolMin: Int = 0,
    val poolMax: Int = 1,
    val javaScript: Boolean = true,
    val domStorage: Boolean = true,
    /** 统一桌面 UA：站点据此返回桌面版，配置里的选择器才对得上 */
    val desktopUa: Boolean = true,
    /** 注入 `__ghsWin` 桥：取页窗口靠它回传 Promise 结果 */
    val evalBridge: Boolean = false,
)

/**
 * 一个受管窗口：**WebView 是它的实现细节，不是它的身份**。
 *
 * 与直接 `WebView(context)` 的区别：
 * - 有状态机（IDLE / LOADING / READY / CLOSED），关闭后不会被误用
 * - 有可挂载/卸载的容器概念：同一个实例可以在主界面、镜像窗、校验窗之间搬
 * - 有统一的「加载 / 求值 / 等页面就绪」，不再每处各写一套 latch
 *
 * 线程约定：所有方法都可在任意线程调用；内部自行切主线程。
 * 唯一限制是 [eval] 不能在主线程调用（会把回调堵死）。
 */
class WebWindow internal constructor(
    val spec: WindowSpec,
    private val context: Context,
) {

    enum class State { IDLE, LOADING, READY, CLOSED }

    @Volatile
    var state: State = State.IDLE
        private set

    /** 当前文档地址；重定向后以最后一次回调为准 */
    @Volatile
    var url: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var finishedLatch: CountDownLatch? = null

    @Volatile
    private var pageFinished: ((WebView, String) -> Unit)? = null

    @Volatile
    private var pageStarted: ((String) -> Unit)? = null

    @Volatile
    private var httpError: ((Int, String) -> Unit)? = null

    @Volatile
    private var loadError: ((String, String) -> Unit)? = null

    @Volatile
    private var progress: ((Int) -> Unit)? = null

    @Volatile
    private var bridgeDone: ((String) -> Unit)? = null

    @Volatile
    private var bridgeError: ((String) -> Unit)? = null

    /** WebView 本体：只在主线程操作 */
    val view: WebView

    init {
        view = buildView(context)
    }

    val isClosed: Boolean get() = state == State.CLOSED

    // ── 回调登记（每个窗口同时只有一个持有者，重复登记视为覆盖） ──────────────

    fun setPageFinishedListener(listener: ((WebView, String) -> Unit)?) {
        pageFinished = listener
    }

    fun setPageStartedListener(listener: ((String) -> Unit)?) {
        pageStarted = listener
    }

    /** 主框架 HTTP 错误（403/429/503 这类）：参数是 (状态码, 地址) */
    fun setHttpErrorListener(listener: ((Int, String) -> Unit)?) {
        httpError = listener
    }

    /** 主框架加载失败（网络层）：参数是 (描述, 地址) */
    fun setErrorListener(listener: ((String, String) -> Unit)?) {
        loadError = listener
    }

    /** 加载进度 0..100 */
    fun setProgressListener(listener: ((Int) -> Unit)?) {
        progress = listener
    }

    /** `__ghsWin` 桥：页面里 `__ghsWin.done(v)` / `__ghsWin.error(msg)` 的回调 */
    fun setBridge(done: ((String) -> Unit)?, error: ((String) -> Unit)?) {
        bridgeDone = done
        bridgeError = error
    }

    // ── 加载与求值 ─────────────────────────────────────────────────────────

    /**
     * 加载地址并等待 `onPageFinished`。
     *
     * 在主线程调用时**不阻塞**（返回 true，结果靠回调）；后台线程调用会等到页面就绪或超时。
     */
    fun load(
        target: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_LOAD_TIMEOUT_MS,
    ): Boolean {
        if (state == State.CLOSED) return false
        val latch = CountDownLatch(1)
        finishedLatch = latch
        url = target
        onMain {
            if (state == State.CLOSED) return@onMain
            state = State.LOADING
            if (headers.isEmpty()) view.loadUrl(target) else view.loadUrl(target, headers)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return true
        return runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    /** 在页面上下文里求值并等待结果（`evaluateJavascript` 的同步包装） */
    fun eval(script: String, timeoutMs: Long = DEFAULT_EVAL_TIMEOUT_MS): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "不能在主线程等待 WebView 求值结果"
        }
        if (state == State.CLOSED) return null
        val latch = CountDownLatch(1)
        var output: String? = null
        onMain {
            if (state == State.CLOSED) return@onMain
            view.evaluateJavascript(script) { raw ->
                output = raw
                latch.countDown()
            }
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) output else null
    }

    fun stop() {
        onMain { runCatching { view.stopLoading() } }
    }

    // ── 容器挂载（对应 Electron 的 BrowserView / win.show） ───────────────────

    fun attachTo(container: ViewGroup) {
        onMain {
            if (state == State.CLOSED) return@onMain
            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
            container.removeView(view)
            container.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /** 从容器摘下来但不销毁：窗口仍然活着，可以再挂到别处 */
    fun detachFromParent() {
        onMain { runCatching { (view.parent as? ViewGroup)?.removeView(view) } }
    }

    /** 关闭并销毁（之后该实例不可再用） */
    fun destroy() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        runCatching {
            onMain {
                runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                runCatching { view.stopLoading() }
                runCatching { view.removeJavascriptInterface(BRIDGE) }
                runCatching { view.destroy() }
            }
        }
        WebWindows.forget(this)
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildView(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = spec.javaScript
        settings.domStorageEnabled = spec.domStorage
        if (spec.desktopUa) {
            settings.userAgentString = UserAgents.UA
        } else {
            // 真实设备标识：JS 侧的 platform / userAgentData 与 UA 一致，
            // 人机校验（Turnstile 一类）靠一致性才判得过
            settings.userAgentString = UserAgents.DEVICE_UA
        }
        // Cookie 统一交给 CookieManager（与 OkHttp 共用一个存储），但不开第三方 Cookie：
        // 开了之后 Cloudflare 的挑战页反而白屏（实测 2026-10-25，18comic）
        runCatching { CookieManager.getInstance().setAcceptCookie(true) }
        webViewClient = Client()
        webChromeClient = Chrome()
        if (spec.evalBridge) addJavascriptInterface(EvalBridge(), BRIDGE)
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            state = State.LOADING
            url?.let { this@WebWindow.url = it }
            pageStarted?.invoke(url.orEmpty())
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            state = State.READY
            url?.let { this@WebWindow.url = it }
            finishedLatch?.countDown()
            pageFinished?.invoke(view ?: this@WebWindow.view, url.orEmpty())
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?,
        ) {
            if (request?.isForMainFrame != true) return
            httpError?.invoke(errorResponse?.statusCode ?: 0, request.url.toString())
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (request?.isForMainFrame != true) return
            loadError?.invoke(
                error?.description?.toString().orEmpty(),
                request.url.toString(),
            )
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            progress?.invoke(newProgress)
        }

        /**
         * 挑战页（Cloudflare / Turnstile 一类）不把失败原因显示给用户，只写在控制台里。
         * 不接管这个回调，logcat 里就一条都看不到，问题只能靠猜。
         */
        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
            message ?: return true
            android.util.Log.i(
                "GHS",
                "console[${message.messageLevel()}]: ${message.message()} " +
                    "(${message.sourceId()}:${message.lineNumber()})",
            )
            return true
        }
    }

    private inner class EvalBridge {
        @JavascriptInterface
        fun done(value: String) {
            bridgeDone?.invoke(value)
        }

        @JavascriptInterface
        fun error(message: String) {
            bridgeError?.invoke(message)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
    }

    companion object {
        private const val BRIDGE = "__ghsWin"
        private const val DEFAULT_LOAD_TIMEOUT_MS = 45_000L
        private const val DEFAULT_EVAL_TIMEOUT_MS = 20_000L
    }
}
