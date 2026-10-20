package com.ghs.mobile.core.engine.config

import com.ghs.mobile.core.engine.script.GhsScriptBridge
import com.ghs.mobile.core.engine.script.ScriptEngine
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.ItemType
import com.ghs.mobile.core.model.Page
import com.ghs.mobile.core.model.Pagination
import com.ghs.mobile.core.model.RenderType
import com.ghs.mobile.core.model.Tag

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

    private fun asList(value: Any?): List<Map<*, *>> = when (value) {
        is List<*> -> value.mapNotNull { it as? Map<*, *> }
        else -> emptyList()
    }

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim()
        else -> value.toString().trim()
    }

    private fun boolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }
}
