package com.ghs.mobile.demo

import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.ItemType
import com.ghs.mobile.core.model.Tag
import org.json.JSONArray
import org.json.JSONObject

/**
 * 条目 ↔ 仓库 `value` 列的编解码。
 *
 * 仓库按设计把条目存成 JSON 字符串（`local-store.md` §3.2："value 仍存条目 JSON"），
 * 收藏与历史都靠它跨端互导。`jumpUrl` 单独存在 `url` 列，不进 JSON。
 */
object ItemCodec {

    fun encode(item: Item): String = JSONObject().apply {
        put("title", item.title)
        put("coverImg", item.coverImg)
        put("type", item.type.name)
        put(
            "tags",
            JSONArray(
                item.tags.map { tag ->
                    JSONObject().put("title", tag.title).put("url", tag.url)
                },
            ),
        )
    }.toString()

    fun decode(url: String, payload: String): Item? = runCatching {
        val json = JSONObject(payload)
        val tags = json.optJSONArray("tags")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val tag = array.optJSONObject(index) ?: return@mapNotNull null
                Tag(title = tag.optString("title"), url = tag.optString("url"))
            }
        }.orEmpty()

        Item(
            jumpUrl = url,
            title = json.optString("title"),
            coverImg = json.optString("coverImg"),
            type = runCatching { ItemType.valueOf(json.optString("type")) }.getOrDefault(ItemType.IMAGE),
            tags = tags,
        ).normalized()
    }.getOrNull()
}
