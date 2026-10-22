package com.ghs.mobile.core.engine.select

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SelectionTest {

    /**
     * 带引号的属性选择器 + 脚本内容读取。
     *
     * 真实配置会用 `$("[type='application/ld+json']")` 取 JSON 拿播放地址；
     * Jsoup 里脚本内容是 DataNode，`text()` 默认返回空串，必须回落到 `data()`。
     */
    @Test
    fun `script 内容可被 text 取到，属性选择器支持引号`() {
        val html = """
            <html><head>
            <script type="application/ld+json">{"contentUrl":"https://cdn.example.com/a.m3u8"}</script>
            </head><body><div class="well">x</div></body></html>
        """.trimIndent()

        val root = GhsSelect.parse(html)
        val script = root.invoke("[type='application/ld+json']")

        assertEquals(1, script.length)
        assertTrue(script.text().contains("contentUrl"), "取到的脚本内容应含 contentUrl")
    }

    @Test
    fun `普通元素 text 不受脚本回退影响`() {
        val root = GhsSelect.parse("""<div class="well"> 标题 </div>""")
        assertEquals("标题", root.invoke("div.well").text().trim())
    }
}
