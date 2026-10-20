package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghs.mobile.core.script.HttpScriptHost
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 宿主能力真机验证：OkHttp 取页/请求 + 无头 WebView 执行页面脚本。
 *
 * 用测试进程内的**本地 HTTP 服务**，不访问外网，也不涉及任何真实来源。
 */
@RunWith(AndroidJUnit4::class)
class HostTransportInstrumentedTest {

    private lateinit var server: ServerSocket
    private lateinit var host: HttpScriptHost

    private val port: Int get() = server.localPort

    @Before
    fun setUp() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        startServer(server)
        host = HttpScriptHost(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() {
        runCatching { host.close() }
        runCatching { server.close() }
    }

    @Test
    fun fetch_html_from_local_server() {
        val html = host.fetchHtml("http://127.0.0.1:$port/list")
        assertTrue("应取到列表页：$html", html.contains("item-1"))
        assertTrue(html.contains("item-2"))
    }

    @Test
    fun request_returns_status_headers_and_body() {
        val response = host.request(
            url = "http://127.0.0.1:$port/api",
            method = "GET",
            headers = emptyMap(),
            body = null,
        )
        assertEquals(200, response.status)
        assertTrue("应带自定义头", response.headers.keys.any { it.equals("X-Local", ignoreCase = true) })
        assertTrue("应返回 JSON 体", response.body.contains("\"ok\":true"))
    }

    @Test
    fun execute_page_reads_dom_after_load() {
        val title = host.executePage("http://127.0.0.1:$port/page", "document.title")
        assertEquals("Local Page", title)

        // 页面里脚本写入的全局变量，说明"页面已执行完"再求值
        val value = host.executePage("http://127.0.0.1:$port/page", "window.__n")
        assertEquals("42", value)

        // 返回对象时 JSON 化
        val json = host.executePage(
            "http://127.0.0.1:$port/page",
            "JSON.stringify({ n: window.__n, title: document.title })",
        )
        assertTrue("应拿到 JSON：$json", json.contains("\"n\":42"))
        assertTrue(json.contains("Local Page"))
    }

    // ── 本地服务 ─────────────────────────────────────────────────────────────

    private fun startServer(socket: ServerSocket) {
        thread(isDaemon = true, name = "local-http") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    runCatching {
                        client.use { connection ->
                            val reader = connection.getInputStream().bufferedReader()
                            val requestLine = reader.readLine().orEmpty()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }

                            val path = requestLine.split(' ').getOrNull(1).orEmpty()
                            val (contentType, body) = when {
                                path.contains("/api") -> "application/json; charset=utf-8" to "{\"ok\":true}"
                                path.contains("/page") -> "text/html; charset=utf-8" to PAGE_HTML
                                else -> "text/html; charset=utf-8" to LIST_HTML
                            }
                            val bytes = body.toByteArray(Charsets.UTF_8)
                            val head = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: $contentType\r\n")
                                append("X-Local: 1\r\n")
                                append("Content-Length: ${bytes.size}\r\n")
                                append("Connection: close\r\n\r\n")
                            }
                            val out = connection.getOutputStream()
                            out.write(head.toByteArray(Charsets.US_ASCII))
                            out.write(bytes)
                            out.flush()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val LIST_HTML = """
            <html><body>
              <div class="item"><a href="/1">item-1</a></div>
              <div class="item"><a href="/2">item-2</a></div>
            </body></html>
        """.trimIndent()

        val PAGE_HTML = """
            <html><head><title>Local Page</title></head>
            <body>
              <div id="root">hello</div>
              <script>window.__n = 42;</script>
            </body></html>
        """.trimIndent()
    }
}
