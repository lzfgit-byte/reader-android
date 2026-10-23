package com.ghs.mobile.ui

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.buffer
import okio.source
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片解码桥（F05 · `adapterImageCode`）。
 *
 * 有些站点把整图切片打散，需要配置里的 `adapterImageCode` 还原。
 * 这里保存两样东西：
 * - `extras`：图片 URL → `CComic.info`（解图参数，如 aid / scrambleId）
 * - `decoder`：把「原始字节」还原成「可直接解码的字节」，由当前会话注入
 *
 * 只有登记过 extra 的 URL 才会走这条链路，其它图片完全不受影响。
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
 * Coil Fetcher：对登记过解图参数的 URL，先取原始字节 → 交给配置还原 → 再交给 Coil 解码。
 *
 * 还原失败时退回原始字节（"先显示原图"），只记日志，不阻塞阅读。
 */
class DecodedImageFetcher(
    private val url: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val decoder = ImageDecodeBridge.decoder ?: return null
        val extra = ImageDecodeBridge.extraOf(url) ?: return null

        val raw = runCatching { fetchBytes(url) }.getOrNull() ?: return null
        val attempt = runCatching { decoder(url, raw, extra) }
        val decoded = attempt.getOrNull()
        val bytes = decoded ?: raw
        if (decoded == null) {
            android.util.Log.w(
                "GHS",
                "image decode failed(${attempt.exceptionOrNull()?.message}) raw=${raw.size}B extra=$extra url=$url",
            )
        } else {
            android.util.Log.i("GHS", "image decoded: $url ${raw.size} → ${decoded.size} bytes")
        }

        return SourceResult(
            source = coil.decode.ImageSource(bytes.inputStream().source().buffer(), options.context),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        ).let { it as FetchResult }
    }

    private fun fetchBytes(url: String): ByteArray {
        val client = com.ghs.mobile.GhsApp.instance.imageClient()
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", originOf(url))
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
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}

/** 给 ImageLoader 挂上解图链路 */
fun ImageLoader.Builder.withImageDecoding(context: Context): ImageLoader.Builder =
    components { add(DecodedImageFetcher.Factory(context)) }
