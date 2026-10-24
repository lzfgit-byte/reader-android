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

    private var webView: WebView? = null
    private var statusView: TextView? = null
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

        // 状态条：显示 HTTP 错误 / 网络错误，避免"白屏不知道为什么"
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#B3261E"))
            setPadding(32, 8, 32, 8)
            visibility = TextView.GONE
        }
        statusView = status
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val view = WebView(this)
        webView = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = USER_AGENT
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        // 与引擎的 WebView 用同一套代理规则：直连被挡的站点必须走代理才能出页面
        runCatching { com.ghs.mobile.core.script.WebViewProxy.apply(GhsApp.instance.proxy.rules.value) }

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                android.util.Log.i("GHS", "challenge page finished: $finishedUrl")
                scheduleDetection(POLL_INTERVAL_MS)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                val message = "网络错误：${error.description}（${error.errorCode}）"
                android.util.Log.w("GHS", "challenge error $message ${request.url}")
                showStatus(message)
                scheduleDetection(POLL_INTERVAL_MS)
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (!request.isForMainFrame) return
                val code = errorResponse.statusCode
                android.util.Log.w("GHS", "challenge http $code ${request.url}")
                showStatus("站点返回 HTTP $code，可能拒绝了当前网络出口（代理 IP 被挡）")
                scheduleDetection(POLL_INTERVAL_MS)
            }
        }
        root.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

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
        view.loadUrl(url)
        // 兜底：即使页面不回调，也要开始检测（403/挑战页常见）
        scheduleDetection(FIRST_CHECK_DELAY_MS)
    }

    override fun onDestroy() {
        pollToken += 1
        handler.removeCallbacksAndMessages(null)
        webView?.destroy()
        webView = null
        if (!finished) ChallengeGate.resolve(host, false)
        super.onDestroy()
    }

    private fun showStatus(text: String) {
        statusView?.apply {
            this.text = text
            visibility = TextView.VISIBLE
        }
    }

    /** 轮询页面是否还处于挑战态；不再命中即视为完成 */
    private fun scheduleDetection(delayMs: Long) {
        val token = ++pollToken
        handler.postDelayed({
            if (token != pollToken || finished) return@postDelayed
            val view = webView ?: return@postDelayed
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
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

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
