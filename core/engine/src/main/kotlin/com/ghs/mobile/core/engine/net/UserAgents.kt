package com.ghs.mobile.core.engine.net

/**
 * 统一的 User-Agent 与桌面端请求头。
 *
 * 目标只有一个：**让所有取页通道拿到桌面版页面**。
 * 配置里的选择器是按桌面 HTML 写的，拿到移动版就一条都解析不出来。
 *
 * 做到这点光改 UA 不够：Android WebView 默认还会带
 * `sec-ch-ua-mobile: ?1`、`sec-ch-ua-platform: "Android"`，
 * 服务端据此照样返回移动版。所以 Client Hints 必须一起改成桌面值。
 */
object UserAgents {

    /** 统一要求中文页面：部分站点按语言返回不同模板 */
    const val ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8"

    /** 兜底版本：取不到设备 WebView 版本时使用 */
    private const val FALLBACK_VERSION = "131.0.0.0"

    /**
     * 当前使用的桌面 Chrome UA。
     *
     * 版本号取自设备 WebView 的真实版本：UA 声称的版本与引擎实际能力差太多时，
     * 站点风控会判定为伪造请求（这点与"桌面/移动"无关，是另一个独立问题）。
     */
    @Volatile
    var UA: String = desktopChrome(FALLBACK_VERSION)
        private set

    /** 设备真实 UA（不做任何伪装）：人机校验窗口必须与它保持一致 */
    @Volatile
    var DEVICE_UA: String = desktopChrome(FALLBACK_VERSION)
        private set

    /** 用设备默认 UA 重建桌面 UA（保留其 Chrome 版本号） */
    fun initFromSystemDefault(defaultUa: String?) {
        if (!defaultUa.isNullOrBlank()) DEVICE_UA = defaultUa
        val version = Regex("Chrome/([0-9.]+)").find(defaultUa.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_VERSION
        UA = desktopChrome(version)
    }

    fun desktopChrome(version: String): String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$version Safari/537.36"

    /**
     * 桌面端 Client Hints。
     *
     * 返回的是**完整请求头**，OkHttp 直接附加、WebView 通过 loadUrl 的 headers 附带。
     */
    fun clientHints(): Map<String, String> {
        val major = Regex("Chrome/([0-9]+)").find(UA)?.groupValues?.getOrNull(1) ?: "131"
        val brands = "\"Chromium\";v=\"$major\", \"Google Chrome\";v=\"$major\", \"Not-A.Brand\";v=\"99\""
        return mapOf(
            "Sec-CH-UA" to brands,
            "Sec-CH-UA-Mobile" to "?0",
            "Sec-CH-UA-Platform" to "\"Windows\"",
            "Sec-CH-UA-Platform-Version" to "\"15.0.0\"",
            "Sec-CH-UA-Arch" to "\"x86\"",
            "Sec-CH-UA-Bitness" to "\"64\"",
            "Sec-CH-UA-Full-Version-List" to brands,
        )
    }
}
