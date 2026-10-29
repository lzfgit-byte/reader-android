package com.ghs.mobile.web

import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.model.ReaderKind
import com.ghs.mobile.core.model.ReadingProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 备份与恢复（F20）。
 *
 * 一个文件装三类数据：**全部配置**、**全部收藏**（含"看过次数"）与**全部阅读进度**
 * （文本章节 / 图集页码 / 视频位置——漫画的“读到第几话第几页”就在这里）。
 * 恢复是 **replace**：文件里有的类别整体替换，不做合并——用户选"恢复"的语义是
 * "把设备变回备份时的样子"，合并会留下备份里没有的旧条目，反而不可预期。
 *
 * 格式（SSOT 见 `docs/03-api/local-store.md` §5.1）：
 * ```
 * {
 *   "$schema": "ghs-backup/1",
 *   "appVersion": "0.1.10",
 *   "exportedAt": 1761550000000,
 *   "sections": {
 *     "configs":  [ { "key", "name", "enabled", "sort", "source" } ],
 *     "collects": [ { "configKey", "url", "payload", "count", "createTime" } ],
 *     "progress": [ { "itemUrl", "kind", "chapterUrl", "chapterIndex", … } ]
 *   }
 * }
 * ```
 *
 * 只读不改动设备设置：端口 / 绑定 / 代理 / 凭证一律不进备份（local-store L6）。
 */
class BackupService(
    private val store: ConfigStore,
    private val repository: LocalRepository,
    private val appVersion: String,
) {

    data class ConfigEntry(
        val key: String,
        val name: String,
        val enabled: Boolean,
        val sort: Int,
        val source: String,
    )

    data class CollectEntry(
        val configKey: String,
        val url: String,
        val payload: String,
        val count: Int,
        val createTime: Long,
    )

    /**
     * 一条阅读进度。
     *
     * `kind` 用 `ReadingProgress` 自己的枚举，落库时与仓库一致（小写）。
     * 图集（含漫画）靠 `chapterIndex` / `chapterOffset` 续到"第几话第几页"。
     */
    data class ProgressEntry(
        val itemUrl: String,
        val kind: ReaderKind,
        val chapterUrl: String,
        val chapterIndex: Int,
        val chapterCount: Int,
        val chapterOffset: Int,
        val positionMs: Long,
        val durationMs: Long,
        val percent: Double,
        val updatedAt: Long,
    )

    /**
     * 解析后的备份文件。
     *
     * 三个列表都可能为 `null`：表示文件里**没有这个类别**（该类别保持原样）。
     * 这与"空数组"不同——空数组是明确的"清空"意图。
     */
    data class Bundle(
        val appVersion: String,
        val exportedAt: Long,
        val configs: List<ConfigEntry>?,
        val collects: List<CollectEntry>?,
        val progress: List<ProgressEntry>? = null,
    ) {
        fun summary(): String =
            "配置 ${configs?.size ?: 0} 份 · 收藏 ${collects?.size ?: 0} 条 · 进度 ${progress?.size ?: 0} 条" +
                if (appVersion.isNotBlank()) " · 来自 $appVersion" else ""
    }

    data class Report(
        val configsAdded: Int,
        val configsUpdated: Int,
        val configsRemoved: Int,
        val collectsImported: Int,
        val collectsRemoved: Int,
        val progressImported: Int = 0,
        val progressRemoved: Int = 0,
    ) {
        fun summary(): String = buildString {
            append("配置：新增 $configsAdded · 覆盖 $configsUpdated · 删除 $configsRemoved")
            append("；收藏：写入 $collectsImported 条")
            if (collectsRemoved > 0) append("（原有 $collectsRemoved 条已清空）")
            if (progressImported > 0 || progressRemoved > 0) {
                append("；进度：写入 $progressImported 条")
                if (progressRemoved > 0) append("（原有 $progressRemoved 条已清空）")
            }
        }
    }

    /** 备份文件不可用：带错误码，Web API 与界面都按码给提示 */
    class BackupException(val code: String, message: String) : Exception(message)

    // ── 导出 ────────────────────────────────────────────────────────────────

    fun export(): String {
        val configs = JSONArray()
        store.list().forEach { config ->
            configs.put(
                JSONObject()
                    .put("key", config.key)
                    .put("name", nameOf(config.source))
                    .put("enabled", config.enabled)
                    .put("sort", config.sort)
                    .put("source", config.source),
            )
        }

        val collects = JSONArray()
        runBlocking { repository.allCollects() }.forEach { item ->
            collects.put(
                JSONObject()
                    .put("configKey", item.configKey)
                    .put("url", item.url)
                    .put("payload", item.payload)
                    .put("count", item.count)
                    .put("createTime", item.createTime),
            )
        }

        val progress = JSONArray()
        runBlocking { repository.allProgress() }.forEach { item ->
            progress.put(
                JSONObject()
                    .put("itemUrl", item.itemUrl)
                    .put("kind", item.kind.name.lowercase())
                    .put("chapterUrl", item.chapterUrl)
                    .put("chapterIndex", item.chapterIndex)
                    .put("chapterCount", item.chapterCount)
                    .put("chapterOffset", item.chapterOffset)
                    .put("positionMs", item.positionMs)
                    .put("durationMs", item.durationMs)
                    .put("percent", item.percent)
                    .put("updatedAt", item.updatedAt),
            )
        }

        return JSONObject()
            .put(SCHEMA_KEY, SCHEMA)
            .put("appVersion", appVersion)
            .put("exportedAt", System.currentTimeMillis())
            .put(
                "sections",
                JSONObject()
                    .put("configs", configs)
                    .put("collects", collects)
                    .put("progress", progress),
            )
            .toString(2)
    }

    /** 备份文件名：`ghs-backup-20261027-2105.json`（本地时区） */
    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        return "ghs-backup-$stamp.json"
    }

    // ── 解析（恢复前预览也用它） ─────────────────────────────────────────────

    fun parse(text: String): Bundle {
        if (text.isBlank()) throw BackupException("E-BAK-002", "文件是空的")
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw BackupException("E-BAK-005", "备份文件超过 ${MAX_BYTES / 1024 / 1024}MB")
        }
        val root = runCatching { JSONObject(JSONTokener(text)) }.getOrElse {
            throw BackupException("E-BAK-002", "不是合法的 JSON：${it.message ?: "解析失败"}")
        }

        val schema = root.optString(SCHEMA_KEY)
        if (schema != SCHEMA) {
            throw BackupException(
                "E-BAK-001",
                "不支持的备份格式：${schema.ifBlank { "缺少 $SCHEMA_KEY" }}（本机支持 $SCHEMA）",
            )
        }

        val sections = root.optJSONObject("sections")
            ?: throw BackupException("E-BAK-002", "缺少 sections")

        val configs = sections.optJSONArray("configs")?.let { parseConfigs(it) }
        if (configs != null && configs.isEmpty()) {
            // 明确写空数组也是"清空"，但这是极危险的低级误操作，直接拒绝
            throw BackupException("E-BAK-006", "备份文件里没有配置，已中止（避免清空现有配置）")
        }
        val collects = sections.optJSONArray("collects")?.let { parseCollects(it) }
        val progress = sections.optJSONArray("progress")?.let { parseProgress(it) }
        if (configs == null && collects == null && progress == null) {
            throw BackupException("E-BAK-002", "文件里既没有配置也没有收藏与进度")
        }

        return Bundle(
            appVersion = root.optString("appVersion"),
            exportedAt = root.optLong("exportedAt", 0L),
            configs = configs,
            collects = collects,
            progress = progress,
        )
    }

    private fun parseConfigs(array: JSONArray): List<ConfigEntry> {
        val entries = mutableListOf<ConfigEntry>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: throw BackupException("E-BAK-003", "configs[$i] 不是对象")
            val key = item.optString("key").trim()
            if (!ConfigStore.isValidKey(key)) {
                throw BackupException("E-BAK-003", "configs[$i] 的 key 不合法：$key")
            }
            if (!seen.add(key)) {
                throw BackupException("E-BAK-004", "配置 key 重复：$key")
            }
            val source = item.optString("source")
            if (source.isBlank()) {
                throw BackupException("E-BAK-003", "配置 $key 的 source 为空")
            }
            val declared = keyInSource(source)
            if (declared.isNotEmpty() && declared != key) {
                throw BackupException(
                    "E-BAK-003",
                    "配置 $key 的源码里写的是 $declared，两者不一致",
                )
            }
            entries += ConfigEntry(
                key = key,
                name = item.optString("name").ifBlank { nameOf(source) },
                enabled = item.optBoolean("enabled", true),
                sort = item.optInt("sort", i),
                source = source,
            )
        }
        return entries
    }

    private fun parseCollects(array: JSONArray): List<CollectEntry> {
        val entries = mutableListOf<CollectEntry>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isBlank()) continue
            entries += CollectEntry(
                configKey = item.optString("configKey").ifBlank { UNKNOWN_KEY },
                url = url,
                payload = item.optString("payload").ifBlank { "{}" },
                count = item.optInt("count", 0).coerceAtLeast(0),
                createTime = item.optLong("createTime", 0L),
            )
        }
        return entries
    }

    /**
     * 进度逐条校验：`itemUrl` 是唯一键（恢复时按 replace 落库），
     * `kind` 只认仓库里的三种媒介，其余一律判为文件损坏——不猜。
     */
    private fun parseProgress(array: JSONArray): List<ProgressEntry> {
        val entries = mutableListOf<ProgressEntry>()
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
                ?: throw BackupException("E-BAK-007", "progress[$i] 不是对象")
            val itemUrl = item.optString("itemUrl").trim()
            if (itemUrl.isEmpty()) {
                throw BackupException("E-BAK-007", "progress[$i] 缺少 itemUrl")
            }
            if (!seen.add(itemUrl)) {
                throw BackupException("E-BAK-007", "progress 的 itemUrl 重复：$itemUrl")
            }
            val kindName = item.optString("kind").trim().lowercase().ifBlank { KIND_IMAGE }
            val kind = ReaderKind.entries.firstOrNull { it.name.lowercase() == kindName }
                ?: throw BackupException("E-BAK-007", "progress[$i] 的 kind 不合法：$kindName")
            val percent = item.optDouble("percent", 0.0).let {
                if (it.isNaN()) 0.0 else it.coerceIn(0.0, 100.0)
            }
            entries += ProgressEntry(
                itemUrl = itemUrl,
                kind = kind,
                chapterUrl = item.optString("chapterUrl"),
                chapterIndex = item.optInt("chapterIndex", 0).coerceAtLeast(0),
                chapterCount = item.optInt("chapterCount", 0).coerceAtLeast(0),
                chapterOffset = item.optInt("chapterOffset", 0).coerceAtLeast(0),
                positionMs = item.optLong("positionMs", 0L).coerceAtLeast(0L),
                durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                percent = percent,
                updatedAt = item.optLong("updatedAt", 0L),
            )
        }
        return entries
    }

    // ── 恢复 ────────────────────────────────────────────────────────────────

    /**
     * 全量恢复：文件里出现的类别整体替换。
     *
     * 先完整解析并校验，任何一条不合法都不会落库——避免"删了一半才发现文件坏了"。
     */
    fun restore(text: String): Report {
        val bundle = parse(text)
        val existingConfigs = store.list()
        var added = 0
        var updated = 0
        var removed = 0

        bundle.configs?.let { configs ->
            val wanted = configs.associateBy { it.key }
            existingConfigs.filter { it.key !in wanted }.forEach {
                if (store.delete(it.key)) removed += 1
            }
            configs.forEach { entry ->
                val existed = existingConfigs.any { it.key == entry.key }
                store.save(entry.key, entry.source, entry.enabled, entry.sort)
                if (existed) updated += 1 else added += 1
            }
        }

        var collectsImported = 0
        var collectsRemoved = 0
        bundle.collects?.let { collects ->
            collectsRemoved = runBlocking { repository.allCollects().size }
            runBlocking { repository.clearCollects() }
            collects.forEach { entry ->
                runBlocking {
                    repository.collectWithCount(
                        configKey = entry.configKey,
                        url = entry.url,
                        payload = entry.payload,
                        count = entry.count,
                        createTime = entry.createTime,
                    )
                }
                collectsImported += 1
            }
        }

        var progressImported = 0
        var progressRemoved = 0
        bundle.progress?.let { progress ->
            progressRemoved = runBlocking { repository.allProgress().size }
            runBlocking { repository.clearAllProgress() }
            progress.forEach { entry ->
                runBlocking {
                    repository.saveProgress(
                        ReadingProgress(
                            itemUrl = entry.itemUrl,
                            kind = entry.kind,
                            chapterUrl = entry.chapterUrl,
                            chapterIndex = entry.chapterIndex,
                            chapterCount = entry.chapterCount,
                            chapterOffset = entry.chapterOffset,
                            positionMs = entry.positionMs,
                            durationMs = entry.durationMs,
                            percent = entry.percent,
                            updatedAt = entry.updatedAt,
                        ),
                    )
                }
                progressImported += 1
            }
        }

        return Report(
            configsAdded = added,
            configsUpdated = updated,
            configsRemoved = removed,
            collectsImported = collectsImported,
            collectsRemoved = collectsRemoved,
            progressImported = progressImported,
            progressRemoved = progressRemoved,
        )
    }

    companion object {
        const val SCHEMA = "ghs-backup/1"
        private const val SCHEMA_KEY = "\$schema"
        private const val UNKNOWN_KEY = "unknown"

        /** 进度里缺 `kind` 时的默认值：老备份没有这一段，真缺就是图集 */
        private const val KIND_IMAGE = "image"

        /** 16MB：正常备份远小于它，超过基本可以确定选错文件了 */
        internal const val MAX_BYTES = 16 * 1024 * 1024

        private val KEY_IN_SOURCE = Regex("""\bkey\s*:\s*(['"])([^'"]+)\1""")
        private val NAME_IN_SOURCE = Regex("""\bname\s*:\s*(['"])([^'"]+)\1""")

        private fun keyInSource(source: String): String =
            KEY_IN_SOURCE.find(source)?.groupValues?.get(2)?.trim().orEmpty()

        private fun nameOf(source: String): String =
            NAME_IN_SOURCE.find(source)?.groupValues?.get(2)?.trim().orEmpty().ifBlank { "未命名配置" }
    }
}
