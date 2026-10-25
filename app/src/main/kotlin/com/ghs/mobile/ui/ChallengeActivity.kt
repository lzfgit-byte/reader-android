package com.ghs.mobile.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.ghs.mobile.GhsApp
import com.ghs.mobile.core.script.window.WebWindow
import com.ghs.mobile.core.script.window.WebWindows

/**
 * 人机校验窗口（F29）。
 *
 * 取页被站点挡住时由 [ChallengeGate] 拉起：顶部说明、中间可见 WebView、
 * 底部取消 / 我已完成。页面不再命中挑战特征时自动关闭，原请求继续。
 *
 * 注意：站点直接返回 403 时 WebView 不会触发 onPageFinished，
 * 所以这里既靠页面回调，也有定时兜底检测，并且把 HTTP 错误显示给用户看。
 */
class ChallengeActivity : Activity() {

    private var window: WebWindow? = null

    /** 主框架 403/429 次数：连续两次就放弃，避免无意义等待 */
    private var httpErrorCount = 0

    private var host: String = ""
    private var finished = false
    private var pollToken = 0
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(ChallengeGate.EXTRA_HOST).orEmpty()
        val url = intent.getStringExtra(ChallengeGate.EXTRA_URL).orEmpty()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val titleView = TextView(this).apply {
            text = "${host.ifEmpty { "该来源" }} 需要确认"
            textSize = 16f
            setPadding(32, 32, 32, 8)
        }
        root.addView(titleView, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "完成页面上的验证后会自动继续"
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(32, 0, 32, 12)
        }
        root.addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val window = WebWindows.acquire(WebWindows.CHALLENGE_SPEC)
        this.window = window
        val view = window.view
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        // 校验窗口与普通请求使用同一套代理规则：
        // 白名单内的地址直连，不在白名单的走代理。窗口不绕过代理。
        runCatching {
            com.ghs.mobile.core.script.WebViewProxy.apply(GhsApp.instance.proxy.rules.value)
        }
        // 进度条：加载中显示，加载完自动隐藏
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, 8))

        // 描边容器：白页面和白底分不清边界，套一层 2px 边框
        val frame = android.widget.FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.WHITE)
                setStroke(2, android.graphics.Color.parseColor("#B9A7FF"))
                cornerRadius = 8f
            }
            setPadding(3, 3, 3, 3)
        }
        window.attachTo(frame)
        root.addView(frame, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // 兜底检测：挑战页常常不触发 onPageFinished，所以既听回调也靠定时轮询
        window.setPageFinishedListener { _, finishedUrl ->
            android.util.Log.i("GHS", "challenge page finished: $finishedUrl")
            scheduleDetection(POLL_INTERVAL_MS)
        }
        window.setErrorListener { description, url ->
            android.util.Log.w("GHS", "challenge error $description $url")
            scheduleDetection(POLL_INTERVAL_MS)
        }
        window.setHttpErrorListener { code, url ->
            android.util.Log.w("GHS", "challenge http $code $url")
            // 主框架连续 403/429：这条链路根本出不了页面（多半是代理出口被封），
            // 让用户点「我已完成」也没意义，直接结束，避免卡在无意义的循环里。
            if (code == 403 || code == 429) {
                httpErrorCount += 1
                if (httpErrorCount >= 2) {
                    android.util.Log.w("GHS", "challenge 连续 $httpErrorCount 次 $code，放弃本次校验")
                    finish()
                    return@setHttpErrorListener
                }
            }
            scheduleDetection(POLL_INTERVAL_MS)
        }
        window.setProgressListener { newProgress ->
            progress.progress = newProgress
            progress.visibility = if (newProgress >= 100) android.view.View.GONE else android.view.View.VISIBLE
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(16, 16, 16, 32)
        }
        actions.addView(
            Button(this).apply {
                text = "取消"
                setOnClickListener { resolve(false) }
            },
        )
        actions.addView(
            Button(this).apply {
                text = "我已完成"
                setOnClickListener {
                    // 手动出口：Cookie 已由 WebView 写入
                    runCatching { CookieManager.getInstance().flush() }
                    resolve(true)
                }
            },
        )
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(root)
        // 桌面页面：UA 已在 settings 里设置，这里补中文与桌面 Client Hints         val pageHeaders = mutableMapOf("Accept-Language" to com.ghs.mobile.core.engine.net.UserAgents.ACCEPT_LANGUAGE)         pageHeaders.putAll(com.ghs.mobile.core.engine.net.UserAgents.clientHints())         view.loadUrl(url, pageHeaders)
        // 兜底：即使页面不回调，也要开始检测（403/挑战页常见）
        scheduleDetection(FIRST_CHECK_DELAY_MS)
    }

    override fun onDestroy() {
        pollToken += 1
        handler.removeCallbacksAndMessages(null)
        // 校验窗每次用完即销毁：下次校验是一个干净的页面
        window?.destroy()
        window = null
        if (!finished) ChallengeGate.resolve(host, false)
        super.onDestroy()
    }

    /** 轮询页面是否还处于挑战态；不再命中即视为完成 */
    private fun scheduleDetection(delayMs: Long) {
        val token = ++pollToken
        handler.postDelayed({
            if (token != pollToken || finished) return@postDelayed
            val view = window?.view ?: return@postDelayed
            view.evaluateJavascript(DETECT_JS) { raw ->
                if (finished || token != pollToken) return@evaluateJavascript
                val text = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"")
                if (text != null && text.startsWith("OK:")) {
                    runCatching { CookieManager.getInstance().flush() }
                    resolve(true)
                } else {
                    scheduleDetection(POLL_INTERVAL_MS)
                }
            }
        }, delayMs)
    }

    private fun resolve(ok: Boolean) {
        if (finished) return
        finished = true
        pollToken += 1
        ChallengeGate.resolve(host, ok)
        setResult(if (ok) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 800L
        const val FIRST_CHECK_DELAY_MS = 2_500L
        val USER_AGENT: String get() = com.ghs.mobile.core.engine.net.UserAgents.UA

        /** 通过返回 `OK:<长度>`，仍在等待返回 `WAIT` */
        val DETECT_JS = """
            (function() {
              try {
                // 与 checkBoot 一致：先看标题是否命中挑战特征
                var title = (document.title || '').trim();
                var marks = ['Just a moment', 'CAPTCHA', 'Checking your Browser',
                             'Attention Required', '安全验证', '人机验证'];
                for (var i = 0; i < marks.length; i++) {
                  if (title.indexOf(marks[i]) >= 0) return 'WAIT';
                }
                var body = document.body ? (document.body.innerText || '') : '';
                if (body.trim().length < 200) return 'WAIT';
                return 'OK:' + body.length;
              } catch (e) {
                return 'WAIT';
              }
            })()
        """.trimIndent()
    }
}

/**
 * 人机校验闸门（F29）。
 *
 * - 同一 host 只开一个窗口：并发请求共享同一个结果，不会连弹多个
 * - 调用方在 IO 线程上阻塞等待（有上限），返回 true 表示可以重试
 */
object ChallengeGate {

    const val EXTRA_URL = "challenge_url"
    const val EXTRA_HOST = "challenge_host"
    private const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

    private val lock = Any()
    private val pending = mutableMapOf<String, java.util.concurrent.CompletableFuture<Boolean>>()

    /** 请求人为该 host 完成校验；返回 true 表示已完成（Cookie 已就绪） */
    fun await(url: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val host = hostOf(url)
        val future: java.util.concurrent.CompletableFuture<Boolean>
        var leader = false
        synchronized(lock) {
            future = pending[host] ?: java.util.concurrent.CompletableFuture<Boolean>().also {
                pending[host] = it
                leader = true
            }
        }

        if (!leader) {
            // 已有窗口在跑：等同一个结果，不再弹第二个
            return runCatching { future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
        }

        try {
            val app = GhsApp.instance
            val intent = Intent(app, ChallengeActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_HOST, host)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            app.startActivity(intent)
            return runCatching { future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
        } catch (error: Throwable) {
            android.util.Log.w("GHS", "人机校验窗口启动失败：${error.message}")
            return false
        } finally {
            synchronized(lock) { pending.remove(host) }
        }
    }

    /** 窗口关闭时回填结果；重复调用无副作用 */
    fun resolve(host: String, ok: Boolean) {
        val future = synchronized(lock) { pending[host] } ?: return
        future.complete(ok)
    }

    private fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host.orEmpty()
    }.getOrDefault("")
}
