package com.ghs.mobile.core.engine.proxy

import java.net.URI
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyRulesTest {

    private val rules = ProxyRules(
        enabled = true,
        endpoint = ProxyEndpoint("192.168.31.245", 4000),
        whitelist = listOf("example.com", ".internal.test", "api.local:8443"),
    )

    @Test
    fun `未启用时一律直连`() {
        val off = rules.copy(enabled = false)
        assertFalse(off.shouldProxy("example.org", 80))
        assertFalse(off.shouldProxy("anything.test", 443))
    }

    @Test
    fun `端点不完整时直连`() {
        val broken = rules.copy(endpoint = ProxyEndpoint("", 0))
        assertFalse(broken.shouldProxy("example.org", 80))
    }

    @Test
    fun `白名单精确匹配并覆盖子域`() {
        assertFalse(rules.shouldProxy("example.com", 80))
        assertFalse(rules.shouldProxy("EXAMPLE.COM", 443))
        // 裸域名同样覆盖子域：图片/静态资源常在不同子域上
        assertFalse(rules.shouldProxy("sub.example.com", 80))
        assertFalse(rules.shouldProxy("cdn-msp3.example.com", 443))
        // 相似但不同域名的不能误伤
        assertTrue(rules.shouldProxy("notexample.com", 80))
    }

    @Test
    fun `白名单后缀匹配包含子域`() {
        assertFalse(rules.shouldProxy("internal.test", 80))
        assertFalse(rules.shouldProxy("api.internal.test", 443))
        assertTrue(rules.shouldProxy("notinternal.test", 80))
    }

    @Test
    fun `白名单带端口时端口必须一致`() {
        assertFalse(rules.shouldProxy("api.local", 8443))
        assertTrue(rules.shouldProxy("api.local", 9000))
    }

    @Test
    fun `其余主机走代理`() {
        assertTrue(rules.shouldProxy("example.org", 80))
        assertTrue(rules.shouldProxy("cdn.other.net", 443))
    }

    @Test
    fun `代理自身地址不再走代理`() {
        assertFalse(rules.shouldProxy("192.168.31.245", 4000))
    }

    @Test
    fun `解析端点`() {
        val parsed = ProxyRules.parseEndpoint("http://192.168.31.245:4000")
        assertEquals("192.168.31.245", parsed?.host)
        assertEquals(4000, parsed?.port)

        assertEquals(3128, ProxyRules.parseEndpoint("proxy.local:3128")?.port)
        assertEquals(8080, ProxyRules.parseEndpoint("proxy.local")?.port)
        assertNull(ProxyRules.parseEndpoint("  "))
        assertNull(ProxyRules.parseEndpoint("http://"))
    }

    @Test
    fun `解析白名单`() {
        assertEquals(
            listOf("a.com", "b.com:8080", ".c.com"),
            ProxyRules.parseWhitelist(" a.com , b.com:8080，.c.com \n a.com "),
        )
        assertTrue(ProxyRules.parseWhitelist(null).isEmpty())
    }

    @Test
    fun `默认规则指向给定地址且启用`() {
        val default = ProxyRules.default()
        assertTrue(default.enabled)
        assertEquals("192.168.31.245", default.endpoint?.host)
        assertEquals(10808, default.endpoint?.port)
        assertTrue(default.whitelist.isEmpty())
        assertTrue(default.shouldProxy("example.org", 443))
    }

    @Test
    fun `ProxySelector 按规则逐请求选择`() {
        val selector = RulesProxySelector { rules }

        val proxied = selector.select(URI("https://example.org/a"))
        assertEquals(Proxy.Type.HTTP, proxied.single().type())

        val bypass = selector.select(URI("https://example.com/a"))
        assertEquals(Proxy.NO_PROXY, bypass.single())

        val httpsDefaultPort = selector.select(URI("https://example.org/a"))
        assertEquals(Proxy.Type.HTTP, httpsDefaultPort.single().type())
    }
}
