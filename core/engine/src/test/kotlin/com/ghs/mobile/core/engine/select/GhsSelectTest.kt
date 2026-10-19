package com.ghs.mobile.core.engine.select

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GhsSelectTest {

    private val html = """
        <html><body>
          <div id="thumbContainer">
            <div class="thumb"><a href="/a1" title="第一条"><h4>Video</h4><img src="/c1.jpg"><span>tag1</span></a></div>
            <div class="thumb"><a href="/a2" title="第二条"><h4>image</h4><img data-src="/c2.jpg" src="/ignored.jpg"></a></div>
            <div class="thumb disabled"><a href="/a3" title="第三条"><h4>x</h4><img src="/c3.jpg"></a></div>
          </div>
          <ul id="tags"><li><a href="/t1">A</a><span>12</span></li><li><a href="/t2">B</a><span>3</span></li></ul>
          <div class="pagination">
            <li class="active"><a href="/p1">1</a></li>
            <li><a href="/p2">2</a></li>
            <li class="disabled"><a>3</a></li>
          </div>
          <div id="paging"><form><a class="prevnext" href="/prev">prev</a><span>cur</span><a href="/next">next</a></form></div>
          <span itemprop="name">道具名</span>
          <script type="application/ld+json">{"name":"x"}</script>
          <div data-role="hero" data-id="42">hero</div>
          <div class="w"><div class="x">1</div><div class="x">2</div><div class="x">3</div></div>
        </body></html>
    """.trimIndent()

    private val `$` = GhsSelect.parse(html)

    @Test
    fun `id 类 标签选择器`() {
        assertEquals(3, `$`("#thumbContainer .thumb").length)
        assertEquals(1, `$`("#tags").length)
        assertEquals(2, `$`("#tags li").length)
    }

    @Test
    fun `后代与子代选择器`() {
        assertEquals(3, `$`("#thumbContainer .thumb").length)
        // 子代：只取直接子节点（div.x 有三个，嵌套的不会重复计入）
        assertEquals(3, `$`("div.w > div.x").length)
        assertEquals(1, `$`("body > div#paging").length)
        // 后代：嵌套层也计入
        assertEquals(3, `$`("div.w div").length)
    }

    @Test
    fun `属性存在与属性等值选择器`() {
        // 只有第二个 img 带 data-src
        assertEquals(1, `$`("img[data-src]").length)
        assertEquals("道具名", `$`("span[itemprop=\"name\"]").text())
        assertEquals(1, `$`("[type='application/ld+json']").length)
    }

    @Test
    fun `逗号选择器合并结果`() {
        val sel = `$`("#paging form a, #paging form span")
        assertEquals(3, sel.length)
    }

    @Test
    fun `eq first last 与负索引`() {
        assertEquals("/a1", `$`("#thumbContainer .thumb a").eq(0).attr("href"))
        assertEquals("/a2", `$`("#thumbContainer .thumb a").eq(1).attr("href"))
        assertEquals("/a3", `$`("#thumbContainer .thumb a").eq(-1).attr("href"))
        assertEquals("/a1", `$`("#thumbContainer .thumb a").first().attr("href"))
        assertEquals("/a3", `$`("#thumbContainer .thumb a").last().attr("href"))
        assertTrue(`$`("#thumbContainer .thumb a").eq(99).isEmpty())
    }

    @Test
    fun `选择器内的 eq 与 first`() {
        assertEquals("/p2", `$`("div.pagination li > a").eq(1).attr("href"))
        // :eq 在选择器位置（Jsoup 原生支持）
        assertEquals("2", `$`("div.pagination li:eq(1) a").text())
        // :first 被翻译成 :eq(0)
        assertEquals("1", `$`("div.pagination li:first a").text())
        // :last 由 GhsSelect 处理
        assertEquals("3", `$`("div.pagination li:last a").text())
    }

    @Test
    fun `hasClass 用于判断当前页与禁用项`() {
        assertTrue(`$`("div.pagination li").eq(0).hasClass("active"))
        assertFalse(`$`("div.pagination li").eq(1).hasClass("active"))
        assertTrue(`$`("div.pagination li").eq(2).hasClass("disabled"))
        assertFalse(`$`("div.pagination li").eq(1).hasClass("whatever"))
    }

    @Test
    fun `取值缺失返回空串而不是 null`() {
        val none = `$`("#nothing")
        assertEquals(0, none.length)
        assertEquals("", none.attr("href"))
        assertEquals("", none.text())
        assertEquals("", none.html())
        assertEquals("", none.toString())
        // 元素存在但没有该属性
        assertEquals("", `$`("#tags li a").first().attr("nope"))
    }

    @Test
    fun `toString 返回 outerHTML 空集返回空串`() {
        val outer = `$`("div[data-id]").toString()
        assertTrue(outer.startsWith("<div"))
        assertTrue(outer.contains("data-id=\"42\""))
        assertEquals("", `$`("#none").toString())
    }

    @Test
    fun `data val prop`() {
        assertEquals("42", `$`("div[data-id]").data("id"))
        assertEquals("hero", `$`("div[data-id]").data("role"))
        assertEquals("", `$`("div[data-id]").data("missing"))
    }

    @Test
    fun `遍历 parent children next prev closest`() {
        val firstLink = `$`("#tags li a").first()

        // parent：a 的父节点是 li
        assertEquals("li", firstLink.parent().toArray().single().tagName())

        // children：ul#tags 的两个直接子节点 li
        assertEquals(2, `$`("#tags").children().length)

        // next：第二个 thumb 是第一个的兄弟
        val secondThumb = `$`("#thumbContainer .thumb").first().next()
        assertEquals("/a2", secondThumb.find("a").attr("href"))

        // prev：回到第一个
        assertEquals("/a1", secondThumb.prev().find("a").attr("href"))

        // closest：从 a 往上找 ul
        assertEquals(1, firstLink.closest("ul").length)
        assertEquals("tags", firstLink.closest("ul").attr("id"))
    }

    @Test
    fun `each 带索引且可回包元素`() {
        val titles = mutableListOf<String>()
        `$`("#thumbContainer .thumb").each { i, el ->
            titles += "$i:" + `$`(el).find("a").attr("title")
        }
        assertEquals(listOf("0:第一条", "1:第二条", "2:第三条"), titles)
    }

    @Test
    fun `filter 与 isMatched`() {
        val links = `$`("#thumbContainer .thumb a")
        assertEquals(3, links.length)
        val withTitle = links.filter("[title]")
        assertEquals(3, withTitle.length)
        val disabledThumb = `$`("#thumbContainer .thumb").filter(".disabled")
        assertEquals(1, disabledThumb.length)
        assertTrue(links.isMatched("[href='/a2']"))
        assertFalse(links.isMatched("[href='/zzz']"))
    }

    @Test
    fun `按序号取子元素`() {
        // 模拟既有配置里的 `.gl4c > div:eq(1)` 写法
        assertEquals("2", `$`("div.w > div:eq(1)").text())
        assertEquals("3", `$`("div.w > div:eq(2)").text())
    }

    @Test
    fun `选择器切分忽略方括号内的逗号`() {
        val parts = SelectorTranslator.split("a[data-x=\"1,2\"], b")
        assertEquals(listOf("a[data-x=\"1,2\"]", "b"), parts)
    }

    @Test
    fun `空 HTML 不抛异常`() {
        val empty = GhsSelect.parse("")
        assertEquals(0, empty(".anything").length)
        assertEquals(0, GhsSelect.empty()("div").length)
    }
}
