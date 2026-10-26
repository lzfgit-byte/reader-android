package com.ghs.mobile.ui

/**
 * 图片请求的 Referer：**当前站点的首页地址**（取 origin）。
 *
 * 为什么需要它：不少图床按 Referer 判盗链，缺了就 403。mycomic 的封面与页图都在
 * `biccam.com`，不带 `Referer: https://mycomic.com/` 一律 403（同一张图带上是 200
 * `image/jpeg`），表现就是「列表、目录、章节都正常，封面与页图全裂」。
 *
 * 为什么按站点推导而不是写进配置：浏览器请求子资源时本来就带当前页地址，
 * 这里照同样规则做，任何站点都不用逐个声明；配置若将来要覆盖，走
 * `behavior.headers`（见 config-spec §3.3）。
 */
object ImageReferer {

    /** 当前站点的首页地址（由 UI 在切换站点时写入） */
    @Volatile
    private var homeUrl: String? = null

    /** 切换站点时调用；传 null 表示没有站点上下文（演示配置） */
    fun setSite(homeUrl: String?) {
        this.homeUrl = homeUrl
    }

    /** 当前应发送的 Referer（站点 origin，如 `https://mycomic.com/`）；无上下文时为 null */
    fun siteOrigin(): String? = homeUrl?.let { originOf(it) }

    /** `https://host/path?q` → `https://host/`；不合法返回 null */
    fun originOf(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) null else "$scheme://$host/"
    }.getOrNull()
}
