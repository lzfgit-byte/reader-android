package com.ghs.mobile.core.script

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
) : ScriptHostTransport {

    // 刻意不把 OkHttpClient 放进公开构造：okhttp 是 implementation 依赖，
    // 暴露它会让使用方的编译类路径被迫携带 okhttp。
    private val client: OkHttpClient = defaultClient()

    override fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("取页失败：HTTP ${response.code} $url")
            }
            return response.body?.string() ?: throw IllegalStateException("取页为空：$url")
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
            @SuppressLint("SetJavaScriptEnabled")
            val view = WebView(context.applicationContext)
            view.settings.javaScriptEnabled = true
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
        private const val PAGE_TIMEOUT_MS = 20_000L
        private const val EVAL_TIMEOUT_MS = 15_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
