package com.ghs.mobile.core.data

import com.ghs.mobile.core.model.ReaderKind
import com.ghs.mobile.core.model.ReadingProgress

/** 设置作用域：`prefs` 跟随用户偏好导出/同步；`device` 只在本机 */
enum class SettingScope(val value: String) {
    PREFS("prefs"),
    DEVICE("device"),
}

/** 收藏项（`payload` 是条目 JSON，跨端互导时原样带走） */
data class CollectedItem(
    val configKey: String,
    val url: String,
    val payload: String,
    val count: Int,
    val createTime: Long,
)

/** 观看历史项 */
data class ViewedItem(
    val configKey: String,
    val url: String,
    val payload: String,
    val createTime: Long,
)

/**
 * 本地仓库：阅读进度 / 设置镜像 / 收藏 / 历史 / 搜索。
 *
 * 语义严格按 `docs/03-api/local-store.md`：
 * - 进度以 `itemUrl` 唯一，**`updatedAt` 更旧的写入会被丢弃**（避免多端回传覆盖新进度）
 * - 历史按 `historyRemember` 截断（默认 100），收藏不去重计数而是累加 `count`
 */
class LocalRepository(private val database: GhsDatabase) {

    private val progressDao = database.progressDao()
    private val settingDao = database.settingDao()
    private val collectDao = database.collectDao()
    private val historyDao = database.historyDao()
    private val searchDao = database.searchDao()

    // ── 进度 ────────────────────────────────────────────────────────────────

    /** 写入进度；返回是否真的写入（更旧的时间戳会被忽略） */
    suspend fun saveProgress(progress: ReadingProgress): Boolean {
        val existing = progressDao.find(progress.itemUrl)
        if (existing != null && existing.updatedAt > progress.updatedAt) return false

        progressDao.upsert(
            ProgressEntity(
                id = existing?.id ?: 0,
                itemUrl = progress.itemUrl,
                kind = progress.kind.name.lowercase(),
                chapterUrl = progress.chapterUrl,
                chapterIndex = progress.chapterIndex,
                chapterCount = progress.chapterCount,
                chapterOffset = progress.chapterOffset,
                positionMs = progress.positionMs,
                durationMs = progress.durationMs,
                percent = progress.percent,
                updatedAt = progress.updatedAt,
            ),
        )
        return true
    }

    suspend fun progress(itemUrl: String): ReadingProgress? =
        progressDao.find(itemUrl)?.toModel()

    suspend fun recentProgress(limit: Int = 20): List<ReadingProgress> =
        progressDao.recent(limit).map { it.toModel() }

    suspend fun clearProgress(itemUrl: String) = progressDao.delete(itemUrl)

    // ── 设置镜像 ────────────────────────────────────────────────────────────

    suspend fun putSetting(scope: SettingScope, key: String, value: String, now: Long = System.currentTimeMillis()) {
        settingDao.upsert(SettingEntity(scope = scope.value, key = key, value = value, updatedAt = now))
    }

    suspend fun setting(scope: SettingScope, key: String): String? =
        settingDao.find(scope.value, key)?.value

    suspend fun settings(scope: SettingScope): Map<String, String> =
        settingDao.all(scope.value).associate { it.key to it.value }

    suspend fun removeSetting(scope: SettingScope, key: String) = settingDao.delete(scope.value, key)

    // ── 收藏 ────────────────────────────────────────────────────────────────

    suspend fun collect(configKey: String, url: String, payload: String, now: Long = System.currentTimeMillis()) {
        val existing = collectDao.find(configKey, url)
        collectDao.insert(
            CollectEntity(
                id = existing?.id ?: 0,
                configKey = configKey,
                url = url,
                payload = payload,
                count = (existing?.count ?: 0) + 1,
                createTime = existing?.createTime ?: now,
            ),
        )
    }

    /**
     * 按给定次数写入收藏（导入用）。
     *
     * 与 [collect] 的区别：不累加，直接用导入文件里的次数，
     * 这样从别处迁过来的「看过多少次」不会丢。
     */
    suspend fun collectWithCount(
        configKey: String,
        url: String,
        payload: String,
        count: Int,
        createTime: Long,
    ) {
        val existing = collectDao.find(configKey, url)
        collectDao.insert(
            CollectEntity(
                id = existing?.id ?: 0,
                configKey = configKey,
                url = url,
                payload = payload,
                count = count.coerceAtLeast(0),
                createTime = if (createTime > 0) createTime else System.currentTimeMillis(),
            ),
        )
    }

    suspend fun uncollect(configKey: String, url: String) = collectDao.delete(configKey, url)

    suspend fun isCollected(configKey: String, url: String): Boolean =
        collectDao.find(configKey, url) != null

    suspend fun collects(configKey: String): List<CollectedItem> =
        collectDao.list(configKey).map {
            CollectedItem(it.configKey, it.url, it.payload, it.count, it.createTime)
        }

    // ── 观看历史 ────────────────────────────────────────────────────────────

    suspend fun recordView(
        configKey: String,
        url: String,
        payload: String,
        now: Long = System.currentTimeMillis(),
    ) {
        // 同一 url 只保留最新一条：避免历史里同一条目重复出现
        historyDao.deleteByUrl(configKey, url)
        historyDao.insert(ViewedEntity(configKey = configKey, url = url, payload = payload, createTime = now))
    }

    suspend fun history(configKey: String, limit: Int = 100): List<ViewedItem> =
        historyDao.list(configKey, limit).map { ViewedItem(it.configKey, it.url, it.payload, it.createTime) }

    /** 按保留条数截断（`historyRemember`） */
    suspend fun trimHistory(keep: Int) {
        if (keep <= 0) return
        historyDao.trim(keep)
    }


    /**
     * 一次性迁移：早期版本把收藏/历史统一写在 `demo` 名下，
     * 现在按站点隔离，把这些记录改到指定站点，避免"历史突然空了"。
     */
    suspend fun migrateLegacyHistory(from: String = "demo", to: String): Int =
        historyDao.renameConfigKey(from, to)
    suspend fun clearHistory() = historyDao.clear()

    /** 只清当前站点的历史 */
    suspend fun clearHistory(configKey: String) = historyDao.clearByConfig(configKey)

    // ── 搜索历史 ────────────────────────────────────────────────────────────

    suspend fun recordSearch(configKey: String, keyword: String, now: Long = System.currentTimeMillis()) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        searchDao.insert(SearchEntity(configKey = configKey, keyword = trimmed, createTime = now))
    }

    suspend fun searchHistory(configKey: String, limit: Int = 20): List<String> =
        searchDao.list(configKey, limit).map { it.keyword }

    suspend fun clearSearchHistory(configKey: String) = searchDao.clear(configKey)

    // ── 映射 ────────────────────────────────────────────────────────────────

    private fun ProgressEntity.toModel(): ReadingProgress = ReadingProgress(
        itemUrl = itemUrl,
        kind = when (kind) {
            "text" -> ReaderKind.TEXT
            "video" -> ReaderKind.VIDEO
            else -> ReaderKind.IMAGE
        },
        chapterUrl = chapterUrl,
        chapterIndex = chapterIndex,
        chapterCount = chapterCount,
        chapterOffset = chapterOffset,
        positionMs = positionMs,
        durationMs = durationMs,
        percent = percent,
        updatedAt = updatedAt,
    )
}
