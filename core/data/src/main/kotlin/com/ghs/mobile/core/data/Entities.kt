package com.ghs.mobile.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 表结构，严格对齐 `docs/03-api/local-store.md` §3.2。
 *
 * 约定：
 * - `reading_progress` 用 **`itemUrl` 唯一** 表示"一条内容一份进度"，三种媒介靠 `kind` 区分
 * - `app_setting` 是 DataStore 的**可导出投影**，`scope` 区分 `prefs` / `device`
 * - 收藏/历史/搜索各自唯一键不同：收藏 `(configKey,url)`、历史 `url`、搜索 `(configKey,value)`
 */

@Entity(
    tableName = "app_setting",
    primaryKeys = ["scope", "key"],
)
data class SettingEntity(
    val scope: String,
    val key: String,
    val value: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "reading_progress",
    indices = [Index(value = ["itemUrl"], unique = true)],
)
data class ProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemUrl: String,
    /** text | image | video */
    val kind: String,
    val chapterUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterOffset: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0–100 */
    val percent: Double = 0.0,
    val updatedAt: Long = 0,
)

@Entity(
    tableName = "collect",
    indices = [Index(value = ["configKey", "url"], unique = true)],
)
data class CollectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configKey: String,
    val url: String,
    /** 条目 JSON 字符串（与契约一致，便于跨端互导） */
    @ColumnInfo(name = "value") val payload: String,
    val count: Int = 0,
    val createTime: Long = 0,
)

@Entity(
    tableName = "viewed_history",
    indices = [Index(value = ["url"], unique = true)],
)
data class ViewedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configKey: String,
    val url: String,
    @ColumnInfo(name = "value") val payload: String,
    val createTime: Long = 0,
)

@Entity(
    tableName = "search_history",
    indices = [Index(value = ["configKey", "value"], unique = true)],
)
data class SearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val configKey: String,
    @ColumnInfo(name = "value") val keyword: String,
    val createTime: Long = 0,
)

@Entity(
    tableName = "config_unit",
    indices = [Index(value = ["key"], unique = true)],
)
data class ConfigUnitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    /** manifest | inline */
    val format: String,
    val configJson: String,
    val script: String,
    val sort: Int = 0,
    val enabled: Boolean = true,
    val configVersion: String = "",
    val createTime: Long = 0,
)
