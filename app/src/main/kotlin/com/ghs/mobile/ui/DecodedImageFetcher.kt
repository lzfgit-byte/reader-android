package com.ghs.mobile.ui

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okhttp3.Request
import okio.buffer
import okio.source
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片解码桥（F05 · `adapterImageCode`）。
 *
 * - `extras`：图片 URL → `CComic.info`（解图参数，如 aid / scrambleId）
 * - `decoder`：原始字节 → 还原后的字节，由当前会话注入
 *
 * 只有登记过 extra 的 URL 才会走这条链路。
 */
object ImageDecodeBridge {

    private val extras = ConcurrentHashMap<String, Map<String, Any?>>()

    @Volatile
    var decoder: ((url: String, bytes: ByteArray, extra: Map<String, Any?>?) -> ByteArray?)? = null

    fun putExtra(url: String, info: Map<String, Any?>?) {
        if (url.isBlank() || info.isNullOrEmpty()) return
        extras[url] = info
    }

    fun extraOf(url: String): Map<String, Any?>? = extras[url]

    fun hasExtra(url: String): Boolean = extras.containsKey(url)

    fun clear() {
        extras.clear()
        decoder = null
    }
}

/**
 * Coil Fetcher：对登记过解图参数的 URL，先取原始字节（带缓存）→ 交给配置还原 → 再解码。
 */
class DecodedImageFetcher(
    private val url: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val decoder = ImageDecodeBridge.decoder ?: return null
        val extra = ImageDecodeBridge.extraOf(url) ?: return null

        val raw = runCatching { fetchBytesCached(url) }.getOrNull() ?: return null
        val attempt = runCatching { decoder(url, raw, extra) }
        val decoded = attempt.getOrNull()
        // 有解图参数却解码失败：不回退原图 —— 原图是打散的切片，画出来是错的。
        // 返回 null 让 Coil 报错，阅读器会显示「加载失败 + 重新加载 / 复制地址」。
        val bytes = decoded
        if (bytes == null) {
            android.util.Log.w(
                "GHS",
                "image decode failed(${attempt.exceptionOrNull()?.message}) raw=${raw.size}B extra=$extra url=$url",
            )
            return null
        } else {
            android.util.Log.i("GHS", "image decoded: $url ${raw.size} → ${decoded.size} bytes")
        }

        return SourceResult(
            source = coil.decode.ImageSource(bytes.inputStream().source().buffer(), options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    private fun fetchBytesCached(url: String): ByteArray {
        // 解码前字节缓存：滚动回看不必重新下载
        ImageCache.rawBytes(url)?.let { return it }
        val bytes = fetchBytes(url)
        ImageCache.putRawBytes(url, bytes)
        return bytes
    }

    private fun fetchBytes(url: String): ByteArray {
        val client = com.ghs.mobile.GhsApp.instance.imageClient()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            // 优先用站点首页当 Referer（图床认的是站点，不是图床自己）
            .header("Referer", ImageReferer.siteOrigin() ?: originOf(url))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.bytes() ?: error("empty body")
        }
    }

    private fun originOf(url: String): String = runCatching {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}/"
    }.getOrDefault("")

    /**
     * 泛型必须是 Uri：Coil 会先用 StringMapper 把字符串转成 Uri，
     * 注册成 Factory<String> 永远不会被调用。
     */
    class Factory(private val context: Context) : Fetcher.Factory<android.net.Uri> {
        override fun create(data: android.net.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = data.toString()
            if (!url.startsWith("http")) return null
            if (!ImageDecodeBridge.hasExtra(url)) return null
            return DecodedImageFetcher(url, options)
        }
    }

    private companion object {
        /** 单张图解码上限：超时按失败处理（回退原图或错误卡片） */
        const val DECODE_TIMEOUT_MS = 12_000L

        val USER_AGENT: String get() = com.ghs.mobile.core.engine.net.UserAgents.UA
    }
}
