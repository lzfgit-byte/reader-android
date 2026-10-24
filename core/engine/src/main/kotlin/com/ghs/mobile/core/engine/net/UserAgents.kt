package com.ghs.mobile.core.engine.net

/**
 * 统一的 User-Agent。
 *
 * 所有取页通道（OkHttp、无头 WebView、校验窗口、图片、播放器）都必须用**桌面 Chrome**：
 * 很多站点对移动 UA 返回精简版页面，选择器与桌面版不同，会让配置解析不到内容。
 */
object UserAgents {
    /** 桌面 Chrome（Windows） */
    const val DESKTOP_CHROME =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}
