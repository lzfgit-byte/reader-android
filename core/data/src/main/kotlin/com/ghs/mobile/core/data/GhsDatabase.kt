package com.ghs.mobile.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SettingEntity::class,
        ProgressEntity::class,
        CollectEntity::class,
        ViewedEntity::class,
        SearchEntity::class,
        ConfigUnitEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GhsDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao

    abstract fun settingDao(): SettingDao

    abstract fun collectDao(): CollectDao

    abstract fun historyDao(): HistoryDao

    abstract fun searchDao(): SearchDao

    abstract fun configUnitDao(): ConfigUnitDao

    companion object {
        const val FILE_NAME = "ghs.db"

        fun open(context: Context): GhsDatabase =
            Room.databaseBuilder(context.applicationContext, GhsDatabase::class.java, FILE_NAME)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /** 测试用：内存库，进程退出即消失 */
        fun inMemory(context: Context): GhsDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, GhsDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
