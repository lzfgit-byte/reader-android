package com.ghs.mobile.core.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 取页结果三分类的判据：**只认标题与结构标记，不拿长度当挑战判据**。
 *
 * 回归背景：WebView 的网络错误页（离线 / 代理不通 / DNS 失败）同样空白或很短，
 * 早先 `isChallengePage` 以 `length < 512` 判挑战，于是纯网络故障被当成"需要人机校验"：
 * 先白等 120 秒，再弹一个没有任何可确认内容的空白校验窗（见 CHANGELOG
 * 「网络失败被误判成人机校验」）。短页现在只走"取页失败"，不进等待、不弹窗。
 */
class PageClassifierTest {

    @Test
    fun `空白页既不是挑战页也不是正文`() {
        assertFalse(PageClassifier.isChallenge(""))
        assertFalse(PageClassifier.isChallenge("   "))
        assertTrue(PageClassifier.tooShortToBeContent(""))
    }

    @Test
    fun `几十字节的网络错误页不是挑战页`() {
        val errorPage = "<html><head></head><body><h1>无法访问此网站</h1></body></html>"
        assertFalse(PageClassifier.isChallenge(errorPage))
        assertTrue(PageClassifier.tooShortToBeContent(errorPage))
    }

    @Test
    fun `正常列表页按正文处理`() {
        val page = buildString {
            append("<html><head><title>最近更新 - 示例站</title></head><body>")
            repeat(40) { append("<div class='item'><a href='/p/$it'>条目 $it 的标题</a></div>") }
            append("</body></html>")
        }
        assertTrue(page.length > PageClassifier.MIN_CONTENT_LENGTH)
        assertFalse(PageClassifier.isChallenge(page))
        assertFalse(PageClassifier.tooShortToBeContent(page))
    }

    @Test
    fun `英文挑战页命中标题`() {
        val html = "<html><head><title>Just a moment...</title></head><body><div>等待中</div></body></html>"
        assertTrue(PageClassifier.isChallenge(html))
    }

    @Test
    fun `中文挑战页命中本地化标题`() {
        val html = "<html><head><title>请稍候…</title></head><body><div>等待中</div></body></html>"
        assertTrue(PageClassifier.isChallenge(html))
    }

    @Test
    fun `标题被改掉时命中结构标记`() {
        val html = "<html><head><title>示例站</title></head>" +
            "<body><script>window._cf_chl_opt = {};</script><div>正常外观的页面</div></body></html>"
        assertTrue(PageClassifier.isChallenge(html))
    }

    @Test
    fun `短页面但带挑战标记仍按挑战页处理`() {
        val html = "<html><body><script src='/cdn-cgi/challenge-platform/x.js'></script></body></html>"
        assertTrue(html.length < PageClassifier.MIN_CONTENT_LENGTH)
        // 标记优先于长度：真有挑战特征时，页面再短也得让用户去完成校验
        assertTrue(PageClassifier.isChallenge(html))
    }

    @Test
    fun `标题提取`() {
        assertEquals("示例站", PageClassifier.titleOf("<html><head><title> 示例站 </title></head></html>"))
        assertEquals("", PageClassifier.titleOf("<html></html>"))
    }

    @Test
    fun `WebView 错误页够长但按取页失败处理`() {
        // Android WebView 打不开页面时渲染的本地错误页：两千多字节、带错误码与样例链接，
        // 长度超过 MIN_CONTENT_LENGTH，早先会被当成正常页面解析 → 列表 0 条
        val errorPage = buildString {
            append("<html><head><title>Webpage not available</title></head><body>")
            append("<h1>Webpage not available</h1>")
            append("<p>The webpage at <b>https://bad.news/tag/porn</b> could not be loaded because:</p>")
            append("<p>net::ERR_PROXY_CONNECTION_FAILED</p>")
            append("<p>Check your connection and try again.</p>")
            repeat(40) { append("<div class='tips'>排查建议 $it：检查网络连接、代理设置、DNS 解析</div>") }
            append("</body></html>")
        }
        assertTrue(errorPage.length > PageClassifier.MIN_CONTENT_LENGTH)
        assertTrue(PageClassifier.isWebViewErrorPage(errorPage))
        assertFalse(PageClassifier.isChallenge(errorPage))
        assertFalse(PageClassifier.tooShortToBeContent(errorPage))
        assertEquals("WebView 打不开页面（Webpage not available）", PageClassifier.describeNonContent(errorPage))
    }

    @Test
    fun `chromium 内部地址也认作错误页`() {
        val html = "<html><head><title>示例站</title></head><body><a href='chrome-error://chromewebdata/'>重试</a></body></html>"
        assertTrue(PageClassifier.isWebViewErrorPage(html))
    }

    @Test
    fun `正常页面不会被误判成错误页`() {
        val page = buildString {
            append("<html><head><title>最近更新 - 示例站</title></head><body>")
            repeat(40) { append("<div class='item'><a href='/p/$it'>条目 $it 的标题</a></div>") }
            append("</body></html>")
        }
        assertFalse(PageClassifier.isWebViewErrorPage(page))
        assertEquals("取回的不是正文页", PageClassifier.describeNonContent(page))
    }
}
