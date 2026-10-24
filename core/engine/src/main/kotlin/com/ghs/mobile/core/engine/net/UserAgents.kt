package com.ghs.mobile.core.engine.net

/**
 * 统一的 User-Agent。
 *
 * **必须与设备实际 WebView 版本一致**：UA 写死一个旧版本、而引擎是新版本时，
 * Cloudflare 这类风控会通过 `userAgentData` / 特性检测发现不一致，直接判定为机器人，
 * 校验页永远过不去。所以启动时用设备默认 UA 里的版本号重建桌面 UA。
 */
object UserAgents {

    /** 统一要求中文页面：不少站点按语言返回不同模板，选择器会跟着变 */
    const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

    /** 兜底值：启动前或取不到设备 UA 时使用 */
    private const val FALLBACK_VERSION = "131.0.0.0"

    /**
     * 当前使用的桌面 Chrome UA。
     *
     * 由 [initFromSystemDefault] 在应用启动时按设备 WebView 版本重建；
     * 所有取页通道（OkHttp / WebView / 播放器 / 图片）都读这一个值。
     */
    @Volatile
    var UA: String = desktopChrome(FALLBACK_VERSION)
        private set

    /** 用设备默认 UA 重建桌面 UA（保留其 Chrome 版本号） */
    fun initFromSystemDefault(defaultUa: String?) {
        val version = Regex("Chrome/([0-9.]+)").find(defaultUa.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_VERSION
        UA = desktopChrome(version)
    }

    fun desktopChrome(version: String): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Safari/537.36"
}