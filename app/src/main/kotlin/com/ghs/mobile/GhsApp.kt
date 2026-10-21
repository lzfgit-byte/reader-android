package com.ghs.mobile

import android.app.Application
import com.ghs.mobile.core.data.GhsDatabase
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.settings.SettingsStore

/**
 * 应用级单例：本地仓库。
 *
 * 目前只持有 Room 与仓库；脚本引擎按需创建（WebView 较重，不常驻 Application）。
 */
class GhsApp : Application() {

    val database: GhsDatabase by lazy { GhsDatabase.open(this) }

    val repository: LocalRepository by lazy { LocalRepository(database) }

    val settings: SettingsStore by lazy { SettingsStore(repository) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GhsApp
            private set
    }
}
