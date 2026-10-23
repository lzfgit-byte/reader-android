package com.ghs.mobile.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
 * 图片兜底：OkHttp 取图失败（连接被断 / 403 防盗链）时改用 WebView 加载（桌面 UA）。
 *
 * 注意两点：
 * - 403 在 Coil 里表现为 `ErrorResult` 而不是异常，必须分别判断；
 * - 滚动导致的取消（CancellationException）绝不能触发兜底，否则每张图白等十几秒。
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
        // 必须切到 IO：WebView 渲染是阻塞等待，放主线程会直接 ANR
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

/** 用隐藏 WebView（桌面 UA）把图片渲染成位图；失败返回 null */
object WebViewImageLoader {

    private const val TIMEOUT_MS = 8_000L
    /** 同时只允许一个兜底 WebView：抢不到就放弃，避免滚动时堆一堆 WebView */
    private val gate = java.util.concurrent.Semaphore(1)
    private const val MAX_SIDE = 2048
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * 兜底失败的 host 冷却表：某图床 WebView 也打不开时，5 分钟内不再逐张重试，
     * 否则一屏 10 张图会各等 12 秒，界面像卡死。
     */
    private const val COOLDOWN_MS = 5 * 60 * 1000L
    private val failedHosts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun load(context: Context, url: String): Bitmap? {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        val lastFailure = failedHosts[host]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < COOLDOWN_MS) {
            return null
        }

        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var destroyed = false

        main.post {
            val view = WebView(context.applicationContext)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.userAgentString = DESKTOP_UA
            view.setBackgroundColor(Color.WHITE)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    view.evaluateJavascript(
                        "(function(){var i=document.querySelector('img');" +
                            "if(!i)return '0x0';return i.naturalWidth+'x'+i.naturalHeight;})()",
                    ) { raw ->
                        val text = raw?.trim()?.removeSurrounding("\"")
                        val parts = text?.split('x') ?: emptyList()
                        val naturalW = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val naturalH = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val width = if (naturalW in 1..MAX_SIDE) naturalW else 1080
                        val height = if (naturalH in 1..MAX_SIDE) naturalH else 1440

                        view.measure(
                            android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY),
                        )
                        view.layout(0, 0, width, height)
                        bitmap = runCatching {
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                                view.draw(android.graphics.Canvas(bmp))
                            }
                        }.getOrNull()
                        if (!destroyed) {
                            destroyed = true
                            view.destroy()
                            latch.countDown()
                        }
                    }
                }
            }
            view.loadUrl(url)
        }

        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (bitmap == null && host.isNotEmpty()) {
            failedHosts[host] = System.currentTimeMillis()
        }
        return bitmap
    }
}

/** 便捷入口：给 `ImageLoader.Builder` 挂上兜底 */
fun ImageLoader.Builder.withWebViewFallback(context: Context): ImageLoader.Builder =
    components { add(WebViewImageFallbackInterceptor(context)) }
