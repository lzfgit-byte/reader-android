package com.ghs.mobile.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ProgressDao {

    @Query("SELECT * FROM reading_progress WHERE itemUrl = :itemUrl LIMIT 1")
    suspend fun find(itemUrl: String): ProgressEntity?

    @Query("SELECT * FROM reading_progress ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ProgressEntity>

    @Query("SELECT * FROM reading_progress WHERE kind = :kind ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recentByKind(kind: String, limit: Int): List<ProgressEntity>

    @Upsert
    suspend fun upsert(entity: ProgressEntity): Long

    @Query("DELETE FROM reading_progress WHERE itemUrl = :itemUrl")
    suspend fun delete(itemUrl: String)

    @Query("SELECT COUNT(*) FROM reading_progress")
    suspend fun count(): Int
}

@Dao
interface SettingDao {

    @Query("SELECT * FROM app_setting WHERE scope = :scope AND key = :key LIMIT 1")
    suspend fun find(scope: String, key: String): SettingEntity?

    @Query("SELECT * FROM app_setting WHERE scope = :scope")
    suspend fun all(scope: String): List<SettingEntity>

    @Upsert
    suspend fun upsert(entity: SettingEntity)

    @Query("DELETE FROM app_setting WHERE scope = :scope AND key = :key")
    suspend fun delete(scope: String, key: String)
}

@Dao
interface CollectDao {

    @Query("SELECT * FROM collect WHERE configKey = :configKey ORDER BY createTime DESC")
    suspend fun list(configKey: String): List<CollectEntity>

    @Query("SELECT * FROM collect WHERE configKey = :configKey AND url = :url LIMIT 1")
    suspend fun find(configKey: String, url: String): CollectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CollectEntity): Long

    @Query("DELETE FROM collect WHERE configKey = :configKey AND url = :url")
    suspend fun delete(configKey: String, url: String)

    @Query("SELECT COUNT(*) FROM collect WHERE configKey = :configKey")
    suspend fun count(configKey: String): Int
}

@Dao
interface HistoryDao {

    @Query("SELECT * FROM viewed_history WHERE configKey = :configKey ORDER BY createTime DESC LIMIT :limit")
    suspend fun list(configKey: String, limit: Int): List<ViewedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ViewedEntity): Long

    @Query("DELETE FROM viewed_history WHERE id NOT IN (SELECT id FROM viewed_history ORDER BY createTime DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM viewed_history")
    suspend fun count(): Int

    @Query("DELETE FROM viewed_history")
    suspend fun clear()
}

@Dao
interface SearchDao {

    @Query("SELECT * FROM search_history WHERE configKey = :configKey ORDER BY createTime DESC LIMIT :limit")
    suspend fun list(configKey: String, limit: Int): List<SearchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchEntity): Long

    @Query("DELETE FROM search_history WHERE configKey = :configKey")
    suspend fun clear(configKey: String)
}

@Dao
interface ConfigUnitDao {

    @Query("SELECT * FROM config_unit ORDER BY sort ASC, id ASC")
    suspend fun all(): List<ConfigUnitEntity>

    @Query("SELECT * FROM config_unit WHERE key = :key LIMIT 1")
    suspend fun find(key: String): ConfigUnitEntity?

    @Upsert
    suspend fun upsert(entity: ConfigUnitEntity): Long

    @Query("DELETE FROM config_unit WHERE key = :key")
    suspend fun delete(key: String)
}
