package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghs.mobile.core.engine.proxy.ProxyEndpoint
import com.ghs.mobile.core.engine.proxy.ProxyRules
import com.ghs.mobile.core.script.HttpScriptHost
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 代理真机验证：本地起两个服务——一个"目标站点"，一个"HTTP 代理"。
 *
 * 断言：
 * - 来源声明直连（isProxy=false）时**直连**（目标站点收到，代理没有）
 * - 命中 forceProxy 的地址**走代理**（代理收到绝对 URI）
 *
 * 全程不访问外网。
 */
@RunWith(AndroidJUnit4::class)
class ProxyInstrumentedTest {

    private lateinit var target: ServerSocket
    private lateinit var proxy: ServerSocket
    private lateinit var host: HttpScriptHost

    private val proxyRequests = CopyOnWriteArrayList<String>()
    private val targetRequests = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        target = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
        proxy = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))

        serve(target, "direct-body") { line -> targetRequests += line }
        serve(proxy, "proxy-body") { line -> proxyRequests += line }

        // 来源声明直连（isProxy=false），但把目标地址写进 forceProxy：
        // 同一台设备上就能同时验证「直连」与「强制走代理」两条分支
        val rules = ProxyRules(
            enabled = true,
            endpoint = ProxyEndpoint("127.0.0.1", proxy.localPort),
            siteEnabled = false,
            forceProxy = listOf("localhost:${target.localPort}"),
        )
        host = HttpScriptHost(InstrumentationRegistry.getInstrumentation().targetContext) { rules }
    }

    @After
    fun tearDown() {
        runCatching { host.close() }
        runCatching { target.close() }
        runCatching { proxy.close() }
    }

    @Test
    fun direct_site_bypasses_proxy() {
        val body = host.fetchHtml("http://127.0.0.1:${target.localPort}/list")

        assertEquals("direct-body", body)
        assertTrue("目标站点应收到请求", targetRequests.isNotEmpty())
        assertTrue("直连来源的请求不应经过代理：$proxyRequests", proxyRequests.isEmpty())
    }

    @Test
    fun force_proxy_host_uses_proxy() {
        // localhost 与 127.0.0.1 指向同一地址；forceProxy 里写的是 localhost → 必须走代理
        val body = host.fetchHtml("http://localhost:${target.localPort}/list")

        assertEquals("proxy-body", body)
        assertTrue("代理应收到请求", proxyRequests.isNotEmpty())
        val requestLine = proxyRequests.first()
        assertTrue("代理应看到绝对 URI：$requestLine", requestLine.contains("http://localhost:"))
        assertTrue("目标站点不应被直连", targetRequests.isEmpty())
    }

    private fun serve(socket: ServerSocket, bodyText: String, onRequest: (String) -> Unit) {
        thread(isDaemon = true, name = "serve-${socket.localPort}") {
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
                            onRequest(requestLine)

                            val bytes = bodyText.toByteArray(Charsets.UTF_8)
                            val head = buildString {
                                append("HTTP/1.1 200 OK\r\n")
                                append("Content-Type: text/html; charset=utf-8\r\n")
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
}
