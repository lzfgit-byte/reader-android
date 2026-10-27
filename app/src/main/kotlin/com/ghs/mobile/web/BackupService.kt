package com.ghs.mobile.web

import com.ghs.mobile.core.data.LocalRepository
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
 * 一个文件装两类数据：**全部配置**与**全部收藏**（含"看过次数"）。
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
 *     "collects": [ { "configKey", "url", "payload", "count", "createTime" } ]
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
     * 解析后的备份文件。
     *
     * 两个列表都可能为 `null`：表示文件里**没有这个类别**（该类别保持原样）。
     * 这与"空数组"不同——空数组是明确的"清空"意图。
     */
    data class Bundle(
        val appVersion: String,
        val exportedAt: Long,
        val configs: List<ConfigEntry>?,
        val collects: List<CollectEntry>?,
    ) {
        fun summary(): String =
            "配置 ${configs?.size ?: 0} 份 · 收藏 ${collects?.size ?: 0} 条" +
                if (appVersion.isNotBlank()) " · 来自 $appVersion" else ""
    }

    data class Report(
        val configsAdded: Int,
        val configsUpdated: Int,
        val configsRemoved: Int,
        val collectsImported: Int,
        val collectsRemoved: Int,
    ) {
        fun summary(): String = buildString {
            append("配置：新增 $configsAdded · 覆盖 $configsUpdated · 删除 $configsRemoved")
            append("；收藏：写入 $collectsImported 条")
            if (collectsRemoved > 0) append("（原有 $collectsRemoved 条已清空）")
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

        return JSONObject()
            .put(SCHEMA_KEY, SCHEMA)
            .put("appVersion", appVersion)
            .put("exportedAt", System.currentTimeMillis())
            .put(
                "sections",
                JSONObject()
                    .put("configs", configs)
                    .put("collects", collects),
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
        if (configs == null && collects == null) {
            throw BackupException("E-BAK-002", "文件里既没有配置也没有收藏")
        }

        return Bundle(
            appVersion = root.optString("appVersion"),
            exportedAt = root.optLong("exportedAt", 0L),
            configs = configs,
            collects = collects,
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

        return Report(
            configsAdded = added,
            configsUpdated = updated,
            configsRemoved = removed,
            collectsImported = collectsImported,
            collectsRemoved = collectsRemoved,
        )
    }

    companion object {
        const val SCHEMA = "ghs-backup/1"
        private const val SCHEMA_KEY = "\$schema"
        private const val UNKNOWN_KEY = "unknown"

        /** 16MB：正常备份远小于它，超过基本可以确定选错文件了 */
        private const val MAX_BYTES = 16 * 1024 * 1024

        private val KEY_IN_SOURCE = Regex("""\bkey\s*:\s*(['"])([^'"]+)\1""")
        private val NAME_IN_SOURCE = Regex("""\bname\s*:\s*(['"])([^'"]+)\1""")

        private fun keyInSource(source: String): String =
            KEY_IN_SOURCE.find(source)?.groupValues?.get(2)?.trim().orEmpty()

        private fun nameOf(source: String): String =
            NAME_IN_SOURCE.find(source)?.groupValues?.get(2)?.trim().orEmpty().ifBlank { "未命名配置" }
    }
}
