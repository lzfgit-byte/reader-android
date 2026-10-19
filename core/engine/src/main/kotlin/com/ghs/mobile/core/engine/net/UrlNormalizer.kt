package com.ghs.mobile.core.engine.net

import java.net.URI

/**
 * URL 归一化：进度 / 收藏 / 历史的键都建立在归一化结果上（见 `docs/03-api/local-store.md` §4）。
 *
 * 规则：
 * 1. `trim()`
 * 2. 去掉 `#fragment`
 * 3. 去掉跟踪参数（`utm_*`、`from`、`spm`），保留其余 query
 * 4. 去掉末尾多余 `/`（`https://a/b/` → `https://a/b`；根路径同样去掉）
 *
 * 解析失败时返回 `trim()` 后的原串，绝不抛异常。
 */
object UrlNormalizer {

    private val TRACKING = setOf("from", "spm")
    private val TRACKING_PREFIX = "utm_"

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val withoutFragment = trimmed.substringBefore('#')
        val uri = try {
            URI(withoutFragment)
        } catch (_: Exception) {
            return withoutFragment.trimEnd('/')
        }

        val scheme = uri.scheme ?: return withoutFragment.trimEnd('/')
        val host = uri.host ?: return withoutFragment.trimEnd('/')

        val path = (uri.rawPath ?: "").trimEnd('/')
        val query = rebuildQuery(uri.rawQuery)

        return buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
            append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
    }

    private fun rebuildQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        return rawQuery
            .split('&')
            .filter { it.isNotEmpty() }
            .filterNot { pair ->
                val name = pair.substringBefore('=').lowercase()
                name in TRACKING || name.startsWith(TRACKING_PREFIX)
            }
            .joinToString("&")
    }
}
