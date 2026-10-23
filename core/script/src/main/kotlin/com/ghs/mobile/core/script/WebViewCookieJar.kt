package com.ghs.mobile.core.script

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 把 OkHttp 的 Cookie 与 WebView 的 Cookie 打通。
 *
 * 为什么必须统一：站点的人机校验（F29）在可见 WebView 里完成，通过后凭证只落在
 * WebView 的 Cookie 上（Cloudflare 一类还写 `cf_clearance`）。如果 OkHttp 用自己的
 * CookieJar，校验完成后后续请求仍然不带凭证，表现为"校验成功了但页面还是 403"。
 *
 * 这里两侧都读写 [CookieManager]，即 WebView 的存储，因此：
 * - 校验窗口拿到的 Cookie → 后续 OkHttp 请求自动携带
 * - OkHttp 响应里的 Set-Cookie → 校验窗口也看得到
 */
class WebViewCookieJar : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return
        cookies.forEach { cookie ->
            runCatching { manager.setCookie(url.toString(), cookie.toString()) }
        }
        runCatching { manager.flush() }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val manager = runCatching { CookieManager.getInstance() }.getOrNull() ?: return emptyList()
        val header = runCatching { manager.getCookie(url.toString()) }.getOrNull()
        if (header.isNullOrBlank()) return emptyList()
        return header.split(';').mapNotNull { part ->
            // Cookie.parse 需要 name=value，空片段直接丢弃
            val trimmed = part.trim()
            if (trimmed.isEmpty()) null else Cookie.parse(url, trimmed)
        }
    }
}
