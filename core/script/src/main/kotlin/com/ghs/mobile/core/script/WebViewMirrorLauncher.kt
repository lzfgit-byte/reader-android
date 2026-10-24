package com.ghs.mobile.core.script

/**
 * 「显示取页 WebView」的启动入口。
 *
 * core:script 不能依赖 app 的 Activity，由 app 在启动时注入实现。
 */
object WebViewMirrorLauncher {

    /** app 注入：打开显示窗口 */
    @Volatile
    var open: (android.content.Context, String) -> Unit = { _, _ -> }
}
