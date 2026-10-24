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
 * 除标准 CSS 外，还支持 jQuery 扩展伪类（在**结果集**上按序号筛选）：
 * `:first`、`:last`、`:eq(n)`、`:lt(n)`、`:gt(n)`、`:even`、`:odd`。
 * 它们可以出现在选择器任意位置，例如 `.pagination:eq(0) li`、`li:first a`。
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

    /** 处理单个选择器片段（顶层逗号之间的一段） */
    private fun selectPart(part: String): List<Element> = selectWithin(null, part.trim())

    /**
     * 在 [root] 范围内解析一段选择器。
     *
     * root 为 null 表示从文档根选择；否则在该元素内部继续（用于 `li:last a` 这类串联）。
     */
    private fun selectWithin(root: Element?, selector: String): List<Element> {
        if (selector.isEmpty()) {
            return root?.let { listOf(it) } ?: document.children().toList()
        }
        val pseudo = Pseudo.find(selector)
            ?: return if (root == null) {
                document.select(SelectorTranslator.translate(selector))
            } else {
                root.select(SelectorTranslator.translate(selector))
            }

        val head = selector.substring(0, pseudo.start).trim()
        val tail = selector.substring(pseudo.end).trim()

        val base: List<Element> = when {
            head.isNotEmpty() -> if (root == null) {
                document.select(SelectorTranslator.translate(head))
            } else {
                root.select(SelectorTranslator.translate(head))
            }
            // 纯伪类（`:eq(0)`）：作用在父级子元素上
            root != null -> root.children().toList()
            else -> document.children().toList()
        }
        val filtered = pseudo.apply(base)
        if (tail.isEmpty()) return filtered
        return filtered.flatMap { element -> selectWithin(element, tail) }
    }

    /** `$(el)`：把 `each` 回调里拿到的裸元素重新包装 */
    operator fun invoke(element: Element): Selection = Selection(listOf(element))

    /** `$(elements)` */
    fun of(elements: List<Element>): Selection = Selection(elements)

    /** 原始 Jsoup 文档，供需要 DOM 级操作的地方使用 */
    fun document(): Document = document

    /** jQuery 扩展伪类：命中位置 + 序号参数 */
    private class Pseudo(
        val start: Int,
        val end: Int,
        private val kind: Kind,
        private val arg: Int,
    ) {
        enum class Kind { FIRST, LAST, EQ, LT, GT, EVEN, ODD }

        fun apply(base: List<Element>): List<Element> {
            if (base.isEmpty()) return emptyList()
            return when (kind) {
                Kind.FIRST -> listOf(base.first())
                Kind.LAST -> listOf(base.last())
                Kind.EQ -> base.getOrNull(arg)?.let { listOf(it) } ?: emptyList()
                Kind.LT -> base.filterIndexed { index, _ -> index < arg }
                Kind.GT -> base.filterIndexed { index, _ -> index > arg }
                Kind.EVEN -> base.filterIndexed { index, _ -> index % 2 == 0 }
                Kind.ODD -> base.filterIndexed { index, _ -> index % 2 == 1 }
            }
        }

        companion object {
            private val EQ = Regex(""":eq\(\s*(-?\d+)\s*\)""")
            private val LT = Regex(""":lt\(\s*(-?\d+)\s*\)""")
            private val GT = Regex(""":gt\(\s*(-?\d+)\s*\)""")

            /** 找到第一个 jQuery 扩展伪类（跳过属性选择器与引号内文本） */
            fun find(selector: String): Pseudo? {
                var i = 0
                var quote: Char? = null
                var bracket = 0
                while (i < selector.length) {
                    val ch = selector[i]
                    when {
                        quote != null -> if (ch == quote) quote = null
                        ch == '"' || ch == '\'' -> quote = ch
                        ch == '[' -> bracket++
                        ch == ']' -> bracket--
                        bracket == 0 && ch == ':' -> {
                            val rest = selector.substring(i)
                            EQ.find(rest)?.takeIf { it.range.first == 0 }?.let {
                                return Pseudo(i, i + it.value.length, Kind.EQ, it.groupValues[1].toInt())
                            }
                            LT.find(rest)?.takeIf { it.range.first == 0 }?.let {
                                return Pseudo(i, i + it.value.length, Kind.LT, it.groupValues[1].toInt())
                            }
                            GT.find(rest)?.takeIf { it.range.first == 0 }?.let {
                                return Pseudo(i, i + it.value.length, Kind.GT, it.groupValues[1].toInt())
                            }
                            if (rest.startsWith(":first") && !rest.startsWith(":first-child")) {
                                return Pseudo(i, i + ":first".length, Kind.FIRST, 0)
                            }
                            if (rest.startsWith(":last") && !rest.startsWith(":last-child")) {
                                return Pseudo(i, i + ":last".length, Kind.LAST, 0)
                            }
                            if (rest.startsWith(":even")) return Pseudo(i, i + ":even".length, Kind.EVEN, 0)
                            if (rest.startsWith(":odd")) return Pseudo(i, i + ":odd".length, Kind.ODD, 0)
                        }
                    }
                    i++
                }
                return null
            }
        }
    }

    companion object {
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
