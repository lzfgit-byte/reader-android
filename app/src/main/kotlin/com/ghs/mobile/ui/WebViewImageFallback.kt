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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * 如果打开的是挑战页（contentType 不是图片类型），
 * 交给 [ChallengeGate] 让用户手动完成校验。
 */
class WebViewImageFallbackInterceptor(private val context: Context) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data.toString()

        val result = try {
            chain.proceed(request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            android.util.Log.w("GHS", "image okhttp failed(${error.message}) → webview $url")
            return fallback(request, url) ?: throw error
        }

        // 403/404 在 Coil 里是 ErrorResult 而不是异常，同样要兜底
        if (result is coil.request.ErrorResult) {
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
                    challenge = true
                    android.util.Log.w("GHS", "image webview got '$type' (可能是挑战页) $url")
                    latch.countDown()
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

        main.post { runCatching { window.view.loadUrl(url) } }

        Thread {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (!stopped.get() && System.currentTimeMillis() < deadline && output == null && !challenge) {
                Thread.sleep(POLL_MS)
                if (stopped.get() || challenge) break
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
            android.util.Log.i("GHS", "image webview done bytes=${output?.size ?: 0} challenge=$challenge $url")
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

/** 便捷入口：给 `ImageLoader.Builder` 挂上兜底 */
fun ImageLoader.Builder.withWebViewFallback(context: Context): ImageLoader.Builder =
    components { add(WebViewImageFallbackInterceptor(context)) }
