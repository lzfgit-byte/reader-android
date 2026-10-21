package com.ghs.mobile

import android.app.Application
import com.ghs.mobile.core.data.GhsDatabase
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.settings.ProxyStore
import com.ghs.mobile.settings.SettingsStore
import com.ghs.mobile.web.ConfigStore
import com.ghs.mobile.web.WebAdminController

/**
 * 应用级单例：本地仓库、设置、代理、内置 Web 管理服务。
 *
 * 脚本引擎不常驻（WebView 较重），按需创建。
 */
class GhsApp : Application() {

    val database: GhsDatabase by lazy { GhsDatabase.open(this) }

    val repository: LocalRepository by lazy { LocalRepository(database) }

    val settings: SettingsStore by lazy { SettingsStore(repository) }

    val proxy: ProxyStore by lazy { ProxyStore(repository) }

    val configs: ConfigStore by lazy {
        ConfigStore(this).also { it.seedIfEmpty() }
    }

    val webAdmin: WebAdminController by lazy {
        WebAdminController(this, repository, configs)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: GhsApp
            private set

        /** 应用版本（服务端 /api/meta 展示） */
        const val VERSION = "0.1.0"
    }
}
