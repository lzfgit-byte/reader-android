package com.ghs.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.request.SuccessResult
import com.ghs.mobile.GhsApp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 标记「这张图失败就按失败处理，别走 WebView 兜底」的 ImageRequest 参数。
 *
 * 站点图标一类装饰图用得上：它们的 403 背后常常是图床/站点自己的 Cloudflare 挑战页，
 * 兜底会顺手弹一个全屏校验窗口——用户只是点开「选择站点」，却突然被要求过人机校验。
 */
const val SkipImageFallbackKey = "ghs.skipImageFallback"

/**
 * 图片兜底：OkHttp 取图失败时，用 WebView 把图片**字节**取回来再交给 Coil 解码。
 *
 * 为什么取字节而不是截屏：截屏只有视口分辨率，长条图会糊、会截断；
 * base64 是原始字节，画质与直接下载一致。
 *
 * 取字节的做法：WebView 直接打开图片地址（此时文档就是该图片，同源），
 * 再在页面里 `fetch(location.href)` → FileReader → base64。
 * 同源 fetch 不受 CORS 限制，不需要图床配合。
 *
 * 如果打开的是**可交互**的挑战页（Cloudflare 挑战结构标记，contentType 也不是图片类型），
 * 交给 [ChallengeGate] 让用户手动完成校验；纯拦截页（`Sorry, you have been blocked`）
 * 用户怎么点都过不去，只按取图失败处理，不弹窗口。
 */
class WebViewImageFallbackInterceptor(private val context: Context) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data.toString()
        val skipFallback = request.parameters.value<Boolean>(SkipImageFallbackKey) == true

        val result = try {
            chain.proceed(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (skipFallback) throw error
            android.util.Log.w("GHS", "image okhttp failed(${error.message}) → webview $url")
            return fallback(request, url) ?: throw error
        }

        // 403/404 在 Coil 里是 ErrorResult 而不是异常，同样要兜底
        if (result is coil.request.ErrorResult) {
            if (skipFallback) return result
            android.util.Log.w("GHS", "image http error(${result.throwable.message}) → webview $url")
            return fallback(request, url) ?: result
        }
        return result
    }

    private suspend fun fallback(
        request: coil.request.ImageRequest,
        url: String,
    ): ImageResult? {
        // 阻塞等待必须离开主线程，否则滚动时直接 ANR
        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            WebViewImageLoader.load(context, url)
        } ?: return null
        return SuccessResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            request = request,
            dataSource = DataSource.NETWORK,
        )
    }
}

/** 用隐藏 WebView 取图片字节；失败返回 null */
object WebViewImageLoader {

    private const val TIMEOUT_MS = 15_000L
    private const val POLL_MS = 250L
    private val DESKTOP_UA: String get() = com.ghs.mobile.core.engine.net.UserAgents.UA


    /** 兜底 WebView 并发：2，兼顾速度与内存 */
    private val gate = java.util.concurrent.Semaphore(2)

    /**
     * 冷却策略：偶发失败（连接被断）不该让整个 host 停摆。
     * 连续失败 3 次才冷却 60s；任一成功即清零。
     */
    private const val COOLDOWN_MS = 60 * 1000L
    private const val FAILURES_BEFORE_COOLDOWN = 3
    private val failureCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun load(context: Context, url: String): Bitmap? {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        if (System.currentTimeMillis() < (cooldownUntil[host] ?: 0L)) return null
        if (!gate.tryAcquire(2, TimeUnit.SECONDS)) {
            android.util.Log.w("GHS", "image webview skip (busy) $url")
            return null
        }
        try {
            val bytes = fetchBytes(context, url)
            if (bytes == null) {
                recordFailure(host)
                return null
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) failureCounts.remove(host)
            return bitmap
        } catch (error: Throwable) {
            android.util.Log.w("GHS", "image webview failed(${error.message}) $url")
            recordFailure(host)
            return null
        } finally {
            gate.release()
        }
    }

    /** 连续失败到阈值才进冷却，避免一次抖动就让整个图床停摆 */
    private fun recordFailure(host: String) {
        if (host.isEmpty()) return
        val count = (failureCounts[host] ?: 0) + 1
        if (count >= FAILURES_BEFORE_COOLDOWN) {
            failureCounts[host] = 0
            cooldownUntil[host] = System.currentTimeMillis() + COOLDOWN_MS
            android.util.Log.w("GHS", "image webview cooldown 60s: $host")
        } else {
            failureCounts[host] = count
        }
    }

    /** WebView 打开图片 → 同源 fetch 取字节 → base64 → 解码 */
    private fun fetchBytes(context: Context, url: String): ByteArray? {
        val latch = CountDownLatch(1)
        var output: ByteArray? = null
        var challenge = false
        // 「不是图片」有两种：可交互的挑战页（值得弹窗让用户过）与硬拦截
        // （Cloudflare 的 Sorry, you have been blocked）。后者用户点什么都没用，
        // 弹一次顶层窗口只会盖住界面、还吞掉用户下一下点击，直接按取图失败处理。
        val hardBlocked = java.util.concurrent.atomic.AtomicBoolean(false)
        // 归还窗口后轮询线程必须停：否则会在下一个请求的页面上继续求值
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)

        // 从对象池里取：图片兜底是高频路径，每次新建 WebView 的冷启动成本最明显
        val window = com.ghs.mobile.core.script.window.WebWindows.acquire(
            com.ghs.mobile.core.script.window.WebWindows.IMAGE_SPEC,
        )

        window.setPageFinishedListener { view, _ ->
            view.evaluateJavascript(
                "(function(){return document.contentType||'';})()",
            ) { raw ->
                val type = raw?.trim()?.removeSurrounding("\"") ?: ""
                if (!type.startsWith("image/")) {
                    // 再问一次页面像不像「能人工过」的挑战页：Cloudflare 挑战页有固定结构标记，
                    // 纯拦截页（blocked）没有——据此决定要不要弹人机校验窗口
                    view.evaluateJavascript(
                        "(function(){var h=document.documentElement?document.documentElement.outerHTML:'';" +
                            "return /_cf_chl_opt|challenge-platform|cf-turnstile|verify you are human|Just a moment/i" +
                            ".test(h)?'1':'0';})()",
                    ) { flag ->
                        val interactive = flag?.contains("1") == true
                        if (interactive) challenge = true else hardBlocked.set(true)
                        android.util.Log.w("GHS", "image webview got '$type' (challenge=$interactive) $url")
                        latch.countDown()
                    }
                    return@evaluateJavascript
                }
                view.evaluateJavascript(
                    "fetch(location.href).then(function(r){return r.blob();})" +
                        ".then(function(b){var fr=new FileReader();" +
                        "fr.onload=function(){window.__ghsB64=fr.result.split(',')[1]||'';};" +
                        "fr.readAsDataURL(b);}).catch(function(e){window.__ghsB64='';});0",
                ) { }
            }
        }

        // 这里不加 Referer：Android WebView 对程序化 loadUrl 只会带上自己算的 Referer，
        // 额外传进去的 Referer 会被丢掉（实测服务端收到的就是空）。需要 Referer 的图床
        // 在 OkHttp 那一层就已经带上了，兜底只负责"连接层面取不回来"的情况。
        main.post {
            // 兜底的 WebView 也要按**这张图**的地址做代理判定：
            // 来源声明直连但 forceProxy 命中该图床时，兜底仍然要走代理，否则前面的成功判定白费
            runCatching {
                com.ghs.mobile.core.script.WebViewProxy.apply(GhsApp.instance.proxyRules(), url)
            }
            runCatching { window.view.loadUrl(url) }
        }

        Thread {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!stopped.get() && System.currentTimeMillis() < deadline && output == null &&
                !challenge && !hardBlocked.get()
            ) {
                Thread.sleep(POLL_MS)
                if (stopped.get() || challenge || hardBlocked.get()) break
                val b64 = runCatching { window.eval("window.__ghsB64||''", 1_500) }
                    .getOrNull()
                    ?.trim()
                    ?.removeSurrounding("\"")
                    ?: ""
                if (b64.length > 100) {
                    output = runCatching {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    }.getOrNull()
                    break
                }
            }
            android.util.Log.i(
                "GHS",
                "image webview done bytes=${output?.size ?: 0} challenge=$challenge blocked=${hardBlocked.get()} $url",
            )
            latch.countDown()
        }.start()

        latch.await(TIMEOUT_MS + 3_000L, TimeUnit.MILLISECONDS)
        stopped.set(true)
        // 归还前摘掉回调：池里的窗口会被下一个请求复用，别让上一页的回调打到新页面上
        window.setPageFinishedListener(null)
        com.ghs.mobile.core.script.window.WebWindows.release(window)
        if (challenge) {
            // 挑战页：弹人机校验窗口，用户完成后 Cookie 生效，下次请求即可命中
            runCatching { ChallengeGate.await(url) }
        }
        return output
    }
}
