package com.ghs.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ghs.mobile.ui.withWebViewFallback
import com.ghs.mobile.ui.withImageDecoding
import com.ghs.mobile.core.data.GhsDatabase
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.engine.proxy.RulesProxySelector
import com.ghs.mobile.settings.ProxyStore
import com.ghs.mobile.settings.SettingsStore
import com.ghs.mobile.web.ConfigStore
import com.ghs.mobile.web.WebAdminController
import okhttp3.OkHttpClient

/**
 * 应用级单例：本地仓库、设置、代理、内置 Web 管理服务。
 *
 * 同时作为 Coil 的 [ImageLoaderFactory]：封面图必须和应用请求走**同一条代理链路**，
 * 否则列表能加载、图片却全裂。
 */
class GhsApp : Application(), ImageLoaderFactory {

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

    /**
     * 带代理的 HTTP 客户端：图片、视频和应用请求共用同一套代理规则。
     *
     * `ProxySelector` 按请求逐个判定，白名单才能真正生效。
     */
    fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(RulesProxySelector { proxy.rules.value })
        .build()

    /**
     * 图片专用客户端：图集是原始尺寸 webp，单张常常几 MB，
     * OkHttp 默认 10s 读超时会把它误判成失败（浏览器能开、App 打不开）。
     */
    fun imageClient(): OkHttpClient = proxiedClient().newBuilder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { imageClient() }
        // OkHttp 取图失败时改用 WebView 渲染（防盗链 / 连接被断 / 403）
        .withWebViewFallback(this)
        .withImageDecoding(this)
        .build()

    override fun onCreate() {
        // 取页 WebView 的显示入口：core 模块不能依赖 Activity，这里注入
        com.ghs.mobile.core.script.WebViewMirrorLauncher.open = { context, url ->
            com.ghs.mobile.ui.WebViewMirrorActivity.start(context, url)
        }
        super.onCreate()
        instance = this
        // UA 必须与设备 WebView 版本一致，否则风控会判定 UA 伪造
        com.ghs.mobile.core.engine.net.UserAgents.initFromSystemDefault(
            android.webkit.WebSettings.getDefaultUserAgent(this),
        )
    }

    companion object {
        lateinit var instance: GhsApp
            private set

        /** 应用版本（服务端 /api/meta 展示） */
        const val VERSION = "0.1.0"
    }
}
