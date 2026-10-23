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
 * 图片兜底：OkHttp 取图失败（连接被断 / 403 / 防盗链）时，改用 WebView 加载。
 *
 * 为什么用 WebView 而不是再发一次 HTTP：WebView 是浏览器握手与渲染，
 * 能过掉不少对 OkHttp 指纹/缺 Cookie 的拦截；代价是只能拿到"渲染后的位图"，
 * 尺寸受虚拟视口限制，所以仅在失败后启用。
 */
class WebViewImageFallbackInterceptor(private val context: Context) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        return try {
            chain.proceed(chain.request)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 列表滚动导致请求被取消：不是加载失败，绝不能转去 WebView
            throw cancelled
        } catch (error: Throwable) {
            val url = chain.request.data.toString()
            android.util.Log.w("GHS", "image okhttp failed(${error.message}) → webview $url")
            val bitmap = WebViewImageLoader.load(context, url) ?: throw error
            SuccessResult(
                drawable = BitmapDrawable(context.resources, bitmap),
                request = chain.request,
                dataSource = DataSource.NETWORK,
            )
        }
    }
}

/** 用隐藏 WebView（桌面 UA）把图片渲染成位图；失败返回 null */
object WebViewImageLoader {

    private const val TIMEOUT_MS = 15_000L
    private const val MAX_SIDE = 2048
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun load(context: Context, url: String): Bitmap? {
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        var released = false

        main.post {
            val view = WebView(context.applicationContext)
            view.settings.javaScriptEnabled = true
            view.settings.userAgentString = DESKTOP_UA
            view.setBackgroundColor(Color.WHITE)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, finishedUrl: String) {
                    // 图片页：量出自然尺寸，再把视图放大到该尺寸后截图
                    view.evaluateJavascript(
                        "(function(){var i=document.querySelector('img');" +
                            "if(!i)return '0x0';return i.naturalWidth+'x'+i.naturalHeight;})()",
                    ) { raw ->
                        val text = raw?.trim()?.removeSurrounding("\"")
                        val parts = text?.split('x') ?: emptyList()
                        val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
                        val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
                        val width = if (w in 1..MAX_SIDE) w else 1080
                        val height = if (h in 1..MAX_SIDE) h else 1080

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
                        if (!released) {
                            released = true
                            view.destroy()
                            latch.countDown()
                        }
                    }
                }
            }
            view.loadUrl(url)
        }

        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return bitmap
    }

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}

/** 便捷入口：给 `ImageLoader.Builder` 挂上兜底 */
fun ImageLoader.Builder.withWebViewFallback(context: Context): ImageLoader.Builder =
    components { add(WebViewImageFallbackInterceptor(context)) }
