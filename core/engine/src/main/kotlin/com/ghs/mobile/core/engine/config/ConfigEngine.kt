package com.ghs.mobile.core.engine.config

import com.ghs.mobile.core.engine.script.GhsScriptBridge
import com.ghs.mobile.core.engine.script.ScriptEngine
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import com.ghs.mobile.core.engine.text.PlainTextToBlocks
import com.ghs.mobile.core.model.Analysis
import com.ghs.mobile.core.model.CComic
import com.ghs.mobile.core.model.CContent
import com.ghs.mobile.core.model.Comment
import com.ghs.mobile.core.model.Detail
import com.ghs.mobile.core.model.DetailInfo
import com.ghs.mobile.core.model.DetailType
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.ItemType
import com.ghs.mobile.core.model.Page
import com.ghs.mobile.core.model.Pagination
import com.ghs.mobile.core.model.RenderType
import com.ghs.mobile.core.model.Tag
import com.ghs.mobile.core.model.TextBlock
import com.ghs.mobile.core.model.TextContent

/**
 * 配置执行引擎：把「配置脚本 + 页面 HTML」变成领域模型。
 *
 * 这是列表页的最小闭环：
 * ```
 * 配置正文 → 脚本引擎求值 → 绑定 $ → 调用 getItems/getPagination/getTags
 *          → JSON 语义的返回值 → 领域模型（Item / Pagination / Tag）
 * ```
 *
 * 约定：**同步调用**。需要异步的 `getDetailInfo` 走 [GhsScriptBridge.invokeAwait]（WebView 引擎支持）。
 */
class ConfigEngine(
    private val script: ScriptEngine,
    private val transport: ScriptHostTransport = ScriptHostTransport.None,
) : AutoCloseable {

    private val bridge = GhsScriptBridge(script).install(transport)

    /** 加载内联配置（按 break 标记切分后求值） */
    fun load(source: String): ConfigEngine {
        bridge.loadConfig(source)
        return this
    }

    /** 把当前页面的 HTML 交给 `$` */
    fun withHtml(html: String): ConfigEngine {
        bridge.loadDocument(html)
        return this
    }

    fun hasMethod(method: String): Boolean = bridge.hasMethod(method)

    /**
     * `adapterSearchUrl(keyword)` → 搜索页地址。
     *
     * 返回空串表示该配置**不支持搜索**。判定依据始终是这个方法的返回值，
     * 不能用 `searchUrl` 字段是否为空来判断（见 config-spec §9.2 第 19 条）。
     */
    fun searchUrl(keyword: String): String =
        (bridge.invoke("adapterSearchUrl", quote(keyword)) as? String).orEmpty().trim()

    /** `getItems($)` → 条目列表；无效条目（缺 jumpUrl）被丢弃 */
    fun items(): List<Item> =
        asList(bridge.invoke("getItems", "$"))
            .mapNotNull { toItem(it) }

    /** `getPagination($)` → 分页 */
    fun pagination(): List<Pagination> =
        asList(bridge.invoke("getPagination", "$"))
            .mapNotNull { row ->
                val url = text(row["url"])
                val title = text(row["title"])
                if (title.isEmpty() && url.isEmpty()) return@mapNotNull null
                Pagination(
                    title = title,
                    url = url,
                    isCurrent = boolean(row["isCurrent"]),
                )
            }

    /** `getTags($)` → 标签（`url` 为空表示占位，保留不丢弃） */
    fun tags(): List<Tag> =
        asList(bridge.invoke("getTags", "$"))
            .mapNotNull { row ->
                val title = text(row["title"])
                if (title.isEmpty()) return@mapNotNull null
                Tag(title = title, url = text(row["url"]))
            }

    /** 一次性取整页 */
    fun page(): Page = Page(
        items = items(),
        pagination = pagination(),
        tags = tags(),
    )

    // ── 详情链路 ─────────────────────────────────────────────────────────────

    /**
     * `getDetailInfo(item, cheerio)` → 详情。返回 null 表示该配置不支持此类型。
     *
     * 走 [GhsScriptBridge.invokeAwait]：真实配置里这个方法几乎都是 `async`。
     */
    fun detailInfo(item: Item): DetailInfo? {
        val argument = "{ jumpUrl: ${quote(item.jumpUrl)}, title: ${quote(item.title)} }, cheerio"
        val raw = bridge.invokeAwait("getDetailInfo", argument) as? Map<*, *> ?: return null

        return DetailInfo(
            detailType = detailType(raw["detailType"]),
            renderType = if (text(raw["renderType"]).equals("force", ignoreCase = true)) {
                RenderType.FORCE
            } else {
                RenderType.NORMAL
            },
            details = asList(raw["details"]).mapNotNull { toDetail(it) },
            relations = asList(raw["relations"]).mapNotNull { toItem(it) },
            analysis = asList(raw["analysis"]).mapNotNull { row ->
                val url = text(row["url"])
                if (url.isEmpty()) {
                    null
                } else {
                    Analysis(
                        url = url,
                        title = text(row["title"]),
                        total = number(row["total"]).toInt(),
                    )
                }
            },
        )
    }

    /** `getContents(url, cheerio)` → 章节 / 图集目录 */
    fun contents(url: String): List<CContent> =
        asList(bridge.invokeAwait("getContents", "${quote(url)}, cheerio"))
            .mapNotNull { row ->
                val url2 = text(row["url"])
                if (url2.isEmpty()) return@mapNotNull null
                CContent(
                    title = text(row["title"]),
                    url = url2,
                    isCurrent = boolean(row["isCurrent"]),
                    time = text(row["time"]).takeIf { it.isNotEmpty() },
                )
            }

    /** `getComicImages(url, cheerio)` → 图集图片 */
    fun comicImages(url: String): List<CComic> =
        asList(bridge.invokeAwait("getComicImages", "${quote(url)}, cheerio"))
            .mapNotNull { row ->
                val imageUrl = text(row["url"])
                if (imageUrl.isEmpty()) return@mapNotNull null
                CComic(
                    url = imageUrl,
                    info = (row["info"] as? Map<*, *>)?.entries
                        ?.associate { it.key.toString() to it.value },
                    extra = asList(row["extra"]).map { comment ->
                        Comment(
                            datetime = text(comment["datetime"]),
                            comment = text(comment["comment"]),
                            image = text(comment["image"]).takeIf { it.isNotEmpty() },
                        )
                    },
                )
            }

    /**
     * `getTextContent(url, cheerio)` → 文本正文。
     *
     * 约定（`config-spec` §5.13）：正文是纯文本，可内嵌 `<img src="…">`。
     * 作者给了 `blocks` 就用作者的，否则由 [PlainTextToBlocks] 切分。
     */
    fun textContent(url: String): TextContent {
        val raw = bridge.invokeAwait("getTextContent", "${quote(url)}, cheerio") as? Map<*, *>
            ?: return TextContent()

        val html = text(raw["html"])
        val authorBlocks = asListOfBlocks(raw["blocks"])
        val blocks: List<TextBlock> =
            if (authorBlocks.isNotEmpty()) authorBlocks else PlainTextToBlocks.convert(html)

        return TextContent(
            title = text(raw["title"]),
            html = html,
            blocks = blocks,
            prevUrl = text(raw["prevUrl"]),
            nextUrl = text(raw["nextUrl"]),
        )
    }

    override fun close() {
        runCatching { script.close() }
        runCatching { transport.close() }
    }

    // ── 映射 ────────────────────────────────────────────────────────────────

    private fun toItem(row: Map<*, *>): Item? {
        val jumpUrl = text(row["jumpUrl"])
        if (jumpUrl.isEmpty()) return null

        return Item(
            jumpUrl = jumpUrl,
            title = text(row["title"]),
            coverImg = text(row["coverImg"]),
            type = itemType(row["type"]),
            renderType = if (text(row["renderType"]).equals("force", ignoreCase = true)) {
                RenderType.FORCE
            } else {
                RenderType.NORMAL
            },
            tags = asList(row["tags"]).mapNotNull { tagRow ->
                val title = text(tagRow["title"])
                if (title.isEmpty()) null else Tag(title = title, url = text(tagRow["url"]))
            },
            author = text(row["author"]).takeIf { it.isNotEmpty() },
        ).normalized()
    }

    private fun itemType(value: Any?): ItemType = when (text(value).lowercase()) {
        "video" -> ItemType.VIDEO
        "comic" -> ItemType.COMIC
        "text" -> ItemType.TEXT
        else -> ItemType.IMAGE
    }

    private fun detailType(value: Any?): DetailType = when (text(value).lowercase()) {
        "mp4" -> DetailType.MP4
        "m3u8" -> DetailType.M3U8
        "comic" -> DetailType.COMIC
        "text" -> DetailType.TEXT
        "series" -> DetailType.SERIES
        "win" -> DetailType.WIN
        else -> DetailType.IMAGE
    }

    private fun toDetail(row: Map<*, *>): Detail? {
        val url = text(row["url"])
        val fullUrl = text(row["fullUrl"]).takeIf { it.isNotEmpty() }
        if (url.isEmpty() && fullUrl == null) return null

        return Detail(
            type = text(row["type"]).takeIf { it.isNotEmpty() }?.let { detailType(it) },
            url = url,
            fullUrl = fullUrl,
            title = text(row["title"]),
            quality = text(row["quality"]),
            comments = asList(row["comments"]).map { comment ->
                Comment(
                    datetime = text(comment["datetime"]),
                    comment = text(comment["comment"]),
                    image = text(comment["image"]).takeIf { it.isNotEmpty() },
                )
            },
            uniqueKey = text(row["uniqueKey"]).takeIf { it.isNotEmpty() },
        )
    }

    /** 作者自己拆好的块（`blocks`），按 `type` 区分段落与图片 */
    private fun asListOfBlocks(value: Any?): List<TextBlock> = when (value) {
        is List<*> -> value.mapNotNull { item ->
            val row = item as? Map<*, *> ?: return@mapNotNull null
            when (text(row["type"]).lowercase()) {
                "image" -> TextBlock(
                    type = com.ghs.mobile.core.model.BlockType.IMAGE,
                    src = text(row["src"]),
                ).takeIf { it.src.isNotEmpty() }

                else -> TextBlock(
                    type = com.ghs.mobile.core.model.BlockType.P,
                    text = text(row["text"]),
                ).takeIf { it.text.isNotEmpty() }
            }
        }

        else -> emptyList()
    }

    private fun asList(value: Any?): List<Map<*, *>> = when (value) {
        is List<*> -> value.mapNotNull { it as? Map<*, *> }
        else -> emptyList()
    }

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim()
        else -> value.toString().trim()
    }

    private fun number(value: Any?): Number = when (value) {
        is Number -> value
        is String -> value.toDoubleOrNull() ?: 0
        else -> 0
    }

    /** 生成 JS 字符串字面量（转义引号与换行） */
    private fun quote(value: String): String =
        "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

    private fun boolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }
}
