package com.ghs.mobile.core.engine.text

import com.ghs.mobile.core.model.BlockType
import com.ghs.mobile.core.model.TextBlock

/**
 * 把文本正文转成结构块。
 *
 * 依据 `docs/03-api/config-spec.md` §5 与设计文档 §8.15：
 * 正文是**纯文本**，唯一标记是内嵌的 `<img src="…">`。此处只做三件事：
 * 实体解码 → 换行归一与分段 → `<img>` 提取与 URL 校验。
 *
 * 不渲染 HTML，也不做 HTML 白名单清洗。
 */
object PlainTextToBlocks {

    /** 单章文本上限 2MB，超出截断 */
    const val MAX_CHARS: Int = 2_000_000

    private val IMG_TAG = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val SRC_ATTR = Regex(
        """\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
        RegexOption.IGNORE_CASE,
    )
    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});""")

    private val NAMED = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "ensp" to " ",
        "emsp" to " ",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "ldquo" to "“",
        "rdquo" to "”",
        "lsquo" to "‘",
        "rsquo" to "’",
    )

    /**
     * @param text 配置返回的正文（纯文本，可内嵌 `<img>`）
     * @return 段落与图片块；没有任何有效内容时返回空列表（调用方按"该章无正文"处理）
     */
    fun convert(text: String): List<TextBlock> {
        if (text.isBlank()) return emptyList()

        val body = if (text.length > MAX_CHARS) text.substring(0, MAX_CHARS) else text
        val out = mutableListOf<TextBlock>()

        var cursor = 0
        for (match in IMG_TAG.findAll(body)) {
            appendText(out, body.substring(cursor, match.range.first))
            extractSrc(match.value)?.let { src ->
                if (isUsableImageUrl(src)) {
                    out += TextBlock(type = BlockType.IMAGE, src = src)
                }
            }
            cursor = match.range.last + 1
        }
        appendText(out, body.substring(cursor))

        return out
    }

    /** 没有 `blocks` 时用本方法补齐；有 `blocks` 时调用方应优先用 blocks */
    fun blocksOf(text: String, blocks: List<TextBlock>): List<TextBlock> =
        if (blocks.isNotEmpty()) blocks else convert(text)

    private fun appendText(out: MutableList<TextBlock>, segment: String) {
        if (segment.isEmpty()) return
        val normalized = decodeEntities(segment).let { BR_TAG.replace(it, "\n") }
        for (line in normalized.split('\n')) {
            val t = line.trim()
            if (t.isNotEmpty()) out += TextBlock(type = BlockType.P, text = t)
        }
    }

    private fun extractSrc(tag: String): String? {
        val m = SRC_ATTR.find(tag) ?: return null
        val raw = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return null
        return raw.trim().takeIf { it.isNotEmpty() }
    }

    /** 只接受 http / https / data:image/，以及协议相对地址 `//host/…` */
    private fun isUsableImageUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.startsWith("http://") ||
            u.startsWith("https://") ||
            u.startsWith("data:image/") ||
            u.startsWith("//")
    }

    /** 解码常见命名实体与数字实体；`&nbsp;` 统一成普通空格，便于后续 trim */
    fun decodeEntities(input: String): String {
        if (!input.contains('&')) return input
        return ENTITY.replace(input) { m ->
            val token = m.groupValues[1]
            when {
                token.startsWith("#x", ignoreCase = true) ->
                    token.drop(2).toIntOrNull(16)?.let { codePoint(it) } ?: m.value
                token.startsWith("#") ->
                    token.drop(1).toIntOrNull()?.let { codePoint(it) } ?: m.value
                else -> NAMED[token.lowercase()] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int): String =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else "�"
}
