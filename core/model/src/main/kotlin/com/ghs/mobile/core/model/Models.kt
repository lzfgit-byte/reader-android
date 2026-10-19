package com.ghs.mobile.core.model

/**
 * 领域模型。字段语义以 `docs/03-api/rest-contract.md` §3 为准。
 *
 * 约定：
 * - 字符串字段在入口处统一 `trim()`，空白视为空（见 [normalized] / [Tag.isPlaceholder]）
 * - 缺失的可选字段不补默认值，由 UI 决定是否占位
 */

enum class ItemType { VIDEO, IMAGE, COMIC, TEXT }

enum class RenderType { NORMAL, FORCE }

enum class DetailType { MP4, M3U8, IMAGE, TEXT, COMIC, SERIES, WIN }

/** 进度类别（统一进度表的 `kind`） */
enum class ReaderKind { TEXT, IMAGE, VIDEO }

enum class CardStyle { COVER, COVER_TITLE, LIST }

enum class ReaderMode { VERTICAL, HORIZONTAL, AUTO }

enum class TextMode { SCROLL, PAGED }

/** 文本正文的块类型：当前只产出段落与图片 */
enum class BlockType { P, IMAGE }

/**
 * 标签。`url` 为空或缺失表示**占位/分隔**：不可点击，UI 原样展示 title。
 */
data class Tag(
    val title: String = "",
    val url: String = "",
) {
    val isPlaceholder: Boolean get() = url.isBlank()
}

data class Item(
    val jumpUrl: String,
    val title: String = "",
    val coverImg: String = "",
    val type: ItemType = ItemType.IMAGE,
    val renderType: RenderType = RenderType.NORMAL,
    val tags: List<Tag> = emptyList(),
    val author: String? = null,
) {
    /** 去除首尾空白、过滤空 tag；`jumpUrl` 仍为空时调用方应丢弃该条目 */
    fun normalized(): Item = copy(
        jumpUrl = jumpUrl.trim(),
        title = title.trim(),
        coverImg = coverImg.trim(),
        author = author?.trim()?.takeIf { it.isNotEmpty() },
        tags = tags.map { it.copy(title = it.title.trim(), url = it.url.trim()) }
            .filter { it.title.isNotEmpty() },
    )
}

data class Pagination(
    val title: String = "",
    val url: String = "",
    val isCurrent: Boolean = false,
) {
    /** `url` 为空表示禁用（既可以是"当前页"，也可以是"不可达"） */
    val disabled: Boolean get() = url.isBlank()
}

data class UrlAppend(
    val value: String = "",
    val param: String = "",
    val title: String = "",
    val current: Boolean = false,
)

data class UrlReplace(
    val schema: String = "",
    val urlAppend: List<UrlAppend> = emptyList(),
)

data class Page(
    val items: List<Item> = emptyList(),
    val pagination: List<Pagination> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val urlReplace: List<UrlReplace> = emptyList(),
)

data class Comment(
    val datetime: String = "",
    val comment: String = "",
    val image: String? = null,
)

data class Detail(
    val type: DetailType? = null,
    /** 展示地址（缩略图或直链） */
    val url: String = "",
    /** 原图地址，阅读器优先用它 */
    val fullUrl: String? = null,
    val title: String = "",
    val quality: String = "",
    val comments: List<Comment> = emptyList(),
    val uniqueKey: String? = null,
) {
    /** `fullUrl` 优先，为空时回落 `url` */
    val preferredUrl: String get() = fullUrl?.takeIf { it.isNotBlank() } ?: url
}

data class Analysis(
    val url: String = "",
    val title: String = "",
    /** 已知集数；0 表示未知/只有一集 */
    val total: Int = 0,
)

data class AnalysisDetail(
    val url: String = "",
    val title: String = "",
)

data class AnalysisVideoDetail(
    val type: DetailType? = null,
    val url: String = "",
    val name: String = "",
    val description: String = "",
)

data class DetailInfo(
    val detailType: DetailType,
    val renderType: RenderType = RenderType.NORMAL,
    val details: List<Detail> = emptyList(),
    val relations: List<Item> = emptyList(),
    val analysis: List<Analysis> = emptyList(),
)

/** 章节 / 图集目录项 */
data class CContent(
    val title: String = "",
    val url: String = "",
    val isCurrent: Boolean = false,
    /** 章节时间，可选，UI 可忽略 */
    val time: String? = null,
)

/** 图集图片 */
data class CComic(
    val url: String = "",
    /** 解图参数，作为 `adapterImageCode` 的 `extra` 传入 */
    val info: Map<String, Any?>? = null,
    /** 该图关联的评论 */
    val extra: List<Comment> = emptyList(),
)

data class TextBlock(
    val type: BlockType,
    val text: String = "",
    val src: String = "",
)

data class TextContent(
    val title: String = "",
    /** 纯文本，可内嵌 `<img src="…">` */
    val html: String = "",
    /** 作者已拆好的块，优先于 html */
    val blocks: List<TextBlock> = emptyList(),
    val prevUrl: String = "",
    val nextUrl: String = "",
)

/** 统一进度：文本章节 / 图集页码 / 视频位置 */
data class ReadingProgress(
    val itemUrl: String,
    val kind: ReaderKind,
    val chapterUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterOffset: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val percent: Double = 0.0,
    val updatedAt: Long = 0,
)
