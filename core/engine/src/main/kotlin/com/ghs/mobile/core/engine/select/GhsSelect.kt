package com.ghs.mobile.core.engine.select

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 选择器入口（GHS Select）。配置脚本里的 `$` 就是这个对象。
 *
 * 用法：
 * ```
 * val $ = GhsSelect.parse(html)
 * $(".item").each { i, el -> $(el).find("a").attr("href") }
 * ```
 *
 * 选择器能力见 `docs/01-product/design.md` §8.4；与 cheerio 的差异见 [Selection]。
 */
class GhsSelect private constructor(private val document: Document) {

    /** `$(selector)` */
    operator fun invoke(selector: String): Selection {
        val parts = SelectorTranslator.split(selector)
        val out = LinkedHashSet<Element>()
        for (part in parts) {
            out += selectPart(part)
        }
        return Selection(out.toList())
    }

    /**
     * 处理单个选择器片段。`:last` 允许出现在中间（`li:last a`），
     * 语义是「取前一段的最后一项，再在其内部继续选择」。
     */
    private fun selectPart(part: String): List<Element> {
        val idx = part.indexOf(":last")
        if (idx < 0) return document.select(SelectorTranslator.translate(part))

        val head = part.substring(0, idx).trim()
        val tail = part.substring(idx + LAST.length).trim()
        val heads = if (head.isEmpty()) document.children() else document.select(SelectorTranslator.translate(head))
        val lastElement = heads.lastOrNull() ?: return emptyList()
        return if (tail.isEmpty()) listOf(lastElement) else lastElement.select(SelectorTranslator.translate(tail))
    }

    /** `$(el)`：把 `each` 回调里拿到的裸元素重新包装 */
    operator fun invoke(element: Element): Selection = Selection(listOf(element))

    /** `$(elements)` */
    fun of(elements: List<Element>): Selection = Selection(elements)

    /** 原始 Jsoup 文档，供需要 DOM 级操作的地方使用 */
    fun document(): Document = document

    companion object {
        private const val LAST = ":last"

        /** 把 HTML 字符串解析为选择器根（`cheerio.load` 的等价物） */
        @JvmStatic
        fun parse(html: String): GhsSelect = GhsSelect(Jsoup.parse(html.ifEmpty { "" }))

        @JvmStatic
        fun of(document: Document): GhsSelect = GhsSelect(document)

        /** 空文档（用于"没有 HTML"时给出空结果而不是抛错） */
        @JvmStatic
        fun empty(): GhsSelect = GhsSelect(Jsoup.parse(""))
    }
}
