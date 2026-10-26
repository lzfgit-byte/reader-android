package com.ghs.mobile.ui

import android.net.Uri
import android.util.Base64
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.buffer
import okio.source

/**
 * Coil Fetcher：`data:` 内联图（`data:image/png;base64,...`）。
 *
 * 配置规范允许 `favicon` 写成内联图（见 `docs/03-api/config-spec.md` §3.1），
 * 但 Coil 2 自带的 fetcher 只认 `http/https/file/content` 等 scheme，
 * `data:` 会直接报 `Unable to create a fetcher that supports: data:...`，
 * 图标位置永远空着。这里把内联数据取成字节，剩下的交给正常解码器
 * （所以 `data:image/svg+xml` 也能走 svg 解码）。
 */
class DataUriFetcher(
    private val uri: Uri,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        // data:[<mediatype>][;base64],<data>
        val raw = uri.schemeSpecificPart ?: return null
        val comma = raw.indexOf(',')
        if (comma < 0) return null
        val meta = raw.substring(0, comma)
        val payload = raw.substring(comma + 1)
        if (payload.isEmpty()) return null

        val mimeType = meta.substringBefore(';').trim().ifEmpty { "text/plain" }
        val bytes = runCatching {
            if (meta.contains("base64", ignoreCase = true)) {
                Base64.decode(payload, Base64.DEFAULT)
            } else {
                Uri.decode(payload).toByteArray(Charsets.UTF_8)
            }
        }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            android.util.Log.w("GHS", "data uri 解码失败 mime=$mimeType len=${payload.length}")
            return null
        }

        return SourceResult(
            source = ImageSource(bytes.inputStream().source().buffer(), options.context),
            mimeType = mimeType,
            dataSource = DataSource.MEMORY,
        )
    }

    /** 泛型必须是 Uri：Coil 会先用 StringMapper 把字符串转成 Uri */
    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (!data.scheme.equals("data", ignoreCase = true)) return null
            return DataUriFetcher(data, options)
        }
    }
}
