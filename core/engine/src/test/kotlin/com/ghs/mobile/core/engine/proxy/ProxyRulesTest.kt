package com.ghs.mobile.core.engine.proxy

import java.net.URI
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyRulesTest {

    /** 默认来源策略：走代理，无强制名单（与配置不写 isProxy 时一致） */
    private val rules = ProxyRules(
        enabled = true,
        endpoint = ProxyEndpoint("192.168.31.245", 4000),
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
    fun `来源默认走代理`() {
        assertTrue(rules.shouldProxy("example.org", 80))
        assertTrue(rules.shouldProxy("cdn.other.net", 443))
    }

    @Test
    fun `来源声明直连且没有强制名单时全部直连`() {
        val directSite = rules.copy(siteEnabled = false)
        assertFalse(directSite.shouldProxy("example.org", 80))
        assertFalse(directSite.shouldProxy("cdn.other.net", 443))
    }

    @Test
    fun `强制名单在直连来源上也生效`() {
        val directSite = rules.copy(
            siteEnabled = false,
            forceProxy = listOf("images.example.com", ".cdn.test"),
        )
        assertTrue(directSite.shouldProxy("images.example.com", 443))
        // 裸域名覆盖其子域
        assertTrue(directSite.shouldProxy("eu.images.example.com", 443))
        assertTrue(directSite.shouldProxy("static.cdn.test", 80))
        // 没命中的仍直连
        assertFalse(directSite.shouldProxy("example.com", 80))
        assertFalse(directSite.shouldProxy("notcdn.test", 80))
    }

    @Test
    fun `强制名单带端口时端口必须一致`() {
        val directSite = rules.copy(siteEnabled = false, forceProxy = listOf("api.local:8443"))
        assertTrue(directSite.shouldProxy("api.local", 8443))
        assertFalse(directSite.shouldProxy("api.local", 9000))
    }

    @Test
    fun `强制名单支持星号表示全部`() {
        val directSite = rules.copy(siteEnabled = false, forceProxy = listOf("*"))
        assertTrue(directSite.shouldProxy("anything.test", 443))
    }

    @Test
    fun `代理自身地址不再走代理`() {
        assertFalse(rules.shouldProxy("192.168.31.245", 4000))
        // 端口不同也按主机放行：连自己绕一圈没有意义
        assertFalse(rules.shouldProxy("192.168.31.245", 4001))
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
    fun `解析地址列表`() {
        assertEquals(
            listOf("a.com", "b.com:8080", ".c.com"),
            ProxyRules.parseHostList(" a.com , b.com:8080，.c.com \n a.com "),
        )
        assertTrue(ProxyRules.parseHostList(null).isEmpty())
    }

    @Test
    fun `默认规则指向给定地址且启用`() {
        val default = ProxyRules.default()
        assertTrue(default.enabled)
        assertEquals("192.168.31.245", default.endpoint?.host)
        assertEquals(10808, default.endpoint?.port)
        assertTrue(default.siteEnabled)
        assertTrue(default.forceProxy.isEmpty())
        assertTrue(default.shouldProxy("example.org", 443))
    }

    @Test
    fun `ProxySelector 按来源策略逐请求选择`() {
        val proxied = RulesProxySelector { rules }.select(URI("https://example.org/a"))
        assertEquals(Proxy.Type.HTTP, proxied.single().type())

        val direct = RulesProxySelector { rules.copy(siteEnabled = false) }
            .select(URI("https://example.org/a"))
        assertEquals(Proxy.NO_PROXY, direct.single())
    }

    /**
     * 回归：代理开关/列表/来源策略切换必须**当场**生效。
     *
     * 会话里的 OkHttpClient 只在创建时建一次，所以规则只能按 provider 实时读；
     * 一旦有人把它改成「创建时快照」，这条用例就会红。
     */
    @Test
    fun `ProxySelector 跟随实时规则而不是快照`() {
        var current = rules
        val selector = RulesProxySelector { current }

        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://example.org/a")).single().type())

        // 关掉开关：同一个 selector 立刻改成直连
        current = current.copy(enabled = false)
        assertEquals(Proxy.NO_PROXY, selector.select(URI("https://example.org/a")).single())

        // 再打开并换一条代理：目标地址跟着换
        current = current.copy(enabled = true, endpoint = ProxyEndpoint("10.0.0.9", 7890))
        val moved = selector.select(URI("https://example.org/a")).single()
        assertEquals(Proxy.Type.HTTP, moved.type())
        assertEquals("10.0.0.9", (moved.address() as java.net.InetSocketAddress).hostString)
    }

    @Test
    fun `来源策略变更是实时的`() {
        var current = rules
        val selector = RulesProxySelector { current }

        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://example.org/a")).single().type())

        // 切到 isProxy=false 的来源：立刻直连
        current = current.copy(siteEnabled = false)
        assertEquals(Proxy.NO_PROXY, selector.select(URI("https://example.org/a")).single())

        // 该来源声明了 forceProxy：命中的地址仍走代理
        current = current.copy(forceProxy = listOf("example.org"))
        assertEquals(Proxy.Type.HTTP, selector.select(URI("https://example.org/a")).single().type())
    }
}
