package com.ghs.mobile.core.engine.script

/**
 * 脚本可用的网络/页面能力。由上层注入（真机是 OkHttp + WebView，测试里是假实现）。
 *
 * 约定：**全部同步返回**。
 * 脚本侧的 `await getHtml(...)` 仍然成立——`await` 一个非 Promise 值会立即得到它；
 * 这样宿主实现可以是最直接的阻塞调用，不需要把异步塞进 JS 引擎的事件循环。
 */
interface ScriptHostTransport {

    /** 取页面 HTML；失败抛异常（脚本里的 try/catch 能捕获） */
    fun fetchHtml(url: String): String

    /** 发一次请求；返回状态码、头与响应体 */
    fun request(url: String, method: String, headers: Map<String, String>, body: String?): HostResponse

    /** 在无头页面里执行脚本并把结果序列化回来（等价于 executeJs） */
    fun executePage(url: String, code: String): String

    data class HostResponse(
        val status: Int,
        val headers: Map<String, String>,
        val body: String,
    )

    /** 什么都不做的实现：用于"不联网也能求值"的场景 */
    object None : ScriptHostTransport {
        override fun fetchHtml(url: String): String = throw UnsupportedOperationException("未配置取页能力：$url")
        override fun request(url: String, method: String, headers: Map<String, String>, body: String?): HostResponse =
            throw UnsupportedOperationException("未配置请求能力：$method $url")

        override fun executePage(url: String, code: String): String =
            throw UnsupportedOperationException("未配置无头页面能力：$url")
    }
}
