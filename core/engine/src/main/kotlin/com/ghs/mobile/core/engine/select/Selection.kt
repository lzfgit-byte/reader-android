package com.ghs.mobile.core.engine.select

import org.jsoup.nodes.Element

/**
 * 选择器结果集（GHS Select）。语义对齐 `docs/03-api/config-spec.md` §8 与设计文档 §8.4：
 *
 * - **取值一律返回字符串**：取不到返回 `''`，绝不返回 null/undefined
 * - `toString()` 返回 `outerHTML`；**空集返回 `''`**（配置靠它判存在性）
 * - `hasClass` / `attr(ElementAttr.*)` / `:eq()` / 属性等值 都必须可用
 *
 * ⚠️ 与 cheerio 的已知差异：`text()` 走 Jsoup 的空白归一化（连续空白压成一个空格），
 * cheerio 保留原始空白。配置脚本若依赖原始换行，应改用 `html()` 自行处理。
 */
class Selection internal constructor(private val elements: List<Element>) {

    val length: Int get() = elements.size

    fun isEmpty(): Boolean = elements.isEmpty()

    /** 遍历；`el` 是裸元素，用 [GhsSelect.invoke] 再包装 */
    fun each(block: (index: Int, element: Element) -> Unit) {
        elements.forEachIndexed { i, el -> block(i, el) }
    }

    fun find(selector: String): Selection =
        Selection(elements.flatMap { it.select(SelectorTranslator.translate(selector)) })

    fun parent(): Selection = Selection(elements.mapNotNull { it.parent() }.distinct())

    fun children(): Selection = Selection(elements.flatMap { it.children() }.distinct())

    fun next(): Selection = Selection(elements.mapNotNull { it.nextElementSibling() }.distinct())

    fun prev(): Selection = Selection(elements.mapNotNull { it.previousElementSibling() }.distinct())

    /** 支持负索引（-1 表示最后一个） */
    fun eq(index: Int): Selection {
        val i = if (index < 0) elements.size + index else index
        return if (i in elements.indices) Selection(listOf(elements[i])) else Selection(emptyList())
    }

    fun first(): Selection = eq(0)

    fun last(): Selection = eq(-1)

    fun closest(selector: String): Selection {
        val translated = SelectorTranslator.translate(selector)
        return Selection(elements.mapNotNull { it.closest(translated) }.distinct())
    }

    /** 是否含某个 class —— 判断「当前页 / 禁用项」靠它 */
    fun hasClass(name: String): Boolean = elements.any { it.hasClass(name) }

    /** 取属性；不存在返回 '' */
    fun attr(name: String): String = elements.firstOrNull()?.attr(name) ?: ""

    /**
     * 取文本；不存在返回 ''。
     *
     * `<script>` / `<style>` 的内容在 Jsoup 里是 **DataNode** 而不是 TextNode，
     * `Element.text()` 会返回空串——但配置常用 `$("[type='application/ld+json']")`
     * 读 JSON（如剧集播放地址），所以这里退回到 `data()`。
     */
    fun text(): String = elements.joinToString("") { element ->
        val text = element.text()
        if (text.isEmpty() && element.normalName() in DATA_ONLY_TAGS) element.data() else text
    }

    /** 取内部 HTML */
    fun html(): String = elements.joinToString("") { it.html() }

    /** 表单值；等价于 `attr("value")` */
    fun value(): String = attr("value")

    fun prop(name: String): String = attr(name)

    /** `data-*` 属性 */
    fun data(name: String): String = attr("data-$name")

    fun filter(selector: String): Selection {
        val translated = SelectorTranslator.translate(selector)
        return Selection(elements.filter { it.`is`(translated) })
    }

    fun isMatched(selector: String): Boolean {
        val translated = SelectorTranslator.translate(selector)
        return elements.any { it.`is`(translated) }
    }

    fun toArray(): List<Element> = elements.toList()

    /** outerHTML；空集返回 '' */
    override fun toString(): String = elements.joinToString("") { it.outerHtml() }
}

/**
 * 把 jQuery 风格选择器翻译成 Jsoup 可接受的形式。
 *
 * Jsoup 原生支持 `:eq(n)` / `:lt` / `:gt` / `:nth-child` / `:contains`，
 * 但不支持 jQuery 的 `:first` / `:last`，这里做等价改写：
 * - `:first` → `:eq(0)`（可出现在选择器中间）
 * - `:last`  → 由 [GhsSelect] 的片段处理逻辑实现（取前一段最后一项后继续选择）
 */
internal object SelectorTranslator {

    fun translate(selector: String): String {
        val trimmed = selector.trim()
        require(trimmed.isNotEmpty()) { "选择器不能为空" }
        // `:first` 直接改写；`:last` 不在 Jsoup 支持范围内，这里退化为 `:eq(-1)` 由调用方处理
        return trimmed.replace(Regex(""":first\b"""), ":eq(0)")
    }

    /** 按顶层逗号切分（忽略括号/引号内的逗号） */
    fun split(selector: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (ch in selector) {
            when {
                quote != null -> {
                    sb.append(ch)
                    if (ch == quote) quote = null
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch; sb.append(ch)
                }
                ch == '(' || ch == '[' -> {
                    depth++; sb.append(ch)
                }
                ch == ')' || ch == ']' -> {
                    depth--; sb.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    parts += sb.toString().trim(); sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotBlank()) parts += sb.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }
}

/** 内容以 DataNode 形式存放的标签：`text()` 取不到，需要 `data()` */
private val DATA_ONLY_TAGS = setOf("script", "style")
