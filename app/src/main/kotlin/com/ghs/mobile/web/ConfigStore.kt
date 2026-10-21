package com.ghs.mobile.web

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * 配置存储：`filesDir/configs/<key>.js` + `filesDir/configs/index.json`。
 *
 * 为什么落文件而不是数据库：配置是要被导出/导入/备份的**源代码**，
 * 文件形态便于打包（`.ghscfg.json` / bundle）与人工排查。
 */
class ConfigStore(private val context: Context) {

    data class StoredConfig(
        val key: String,
        val source: String,
        val enabled: Boolean,
        val sort: Int,
        val updatedAt: Long,
    )

    private val dir: File get() = File(context.filesDir, "configs").apply { mkdirs() }
    private val indexFile: File get() = File(dir, "index.json")

    fun list(): List<StoredConfig> =
        readIndex().entries
            .mapNotNull { (key, meta) -> get(key)?.copy(enabled = meta.enabled, sort = meta.sort) }
            .sortedWith(compareBy({ it.sort }, { it.key }))

    fun get(key: String): StoredConfig? {
        if (!isValidKey(key)) return null
        val file = File(dir, "$key.js")
        if (!file.isFile) return null
        val meta = readIndex()[key]
        return StoredConfig(
            key = key,
            source = file.readText(),
            enabled = meta?.enabled ?: true,
            sort = meta?.sort ?: 0,
            updatedAt = file.lastModified(),
        )
    }

    fun save(key: String, source: String, enabled: Boolean = true, sort: Int = 0): StoredConfig {
        require(isValidKey(key)) { "key 不合法：$key" }
        val file = File(dir, "$key.js")
        file.writeText(source)
        val index = readIndex().toMutableMap()
        index[key] = Meta(enabled = enabled, sort = sort)
        writeIndex(index)
        return get(key)!!
    }

    fun delete(key: String): Boolean {
        if (!isValidKey(key)) return false
        val removed = File(dir, "$key.js").delete()
        val index = readIndex().toMutableMap()
        index.remove(key)
        writeIndex(index)
        return removed
    }

    fun setEnabled(key: String, enabled: Boolean): Boolean {
        val index = readIndex().toMutableMap()
        val meta = index[key] ?: return false
        index[key] = meta.copy(enabled = enabled)
        writeIndex(index)
        return true
    }

    fun reorder(keys: List<String>): Boolean {
        val index = readIndex().toMutableMap()
        keys.forEachIndexed { position, key ->
            val meta = index[key] ?: return@forEachIndexed
            index[key] = meta.copy(sort = position)
        }
        writeIndex(index)
        return true
    }

    /** 首次运行：把内置示例配置落到可编辑目录，避免"列表是空的" */
    fun seedIfEmpty(assets: List<String> = listOf("demo/config.js")) {
        if (list().isNotEmpty()) return
        assets.forEach { asset ->
            runCatching {
                val source = context.assets.open(asset).bufferedReader().use { it.readText() }
                val key = Regex("""key\s*:\s*['"]([^'"]+)['"]""").find(source)?.groupValues?.get(1)
                    ?: File(asset).nameWithoutExtension
                if (isValidKey(key)) save(key, source, enabled = true)
            }
        }
    }

    private data class Meta(val enabled: Boolean, val sort: Int)

    private fun readIndex(): Map<String, Meta> {
        if (!indexFile.isFile) return emptyMap()
        return runCatching {
            val json = JSONObject(indexFile.readText())
            json.keys().asSequence().associateWith { key ->
                val item = json.getJSONObject(key)
                Meta(enabled = item.optBoolean("enabled", true), sort = item.optInt("sort", 0))
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeIndex(index: Map<String, Meta>) {
        val json = JSONObject()
        index.forEach { (key, meta) ->
            json.put(key, JSONObject().put("enabled", meta.enabled).put("sort", meta.sort))
        }
        indexFile.writeText(json.toString(2))
    }

    companion object {
        private val KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$")
        fun isValidKey(key: String): Boolean = KEY_PATTERN.matches(key)
    }
}
