package com.ghs.mobile

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.ghs.mobile.ui.DataUriFetcher
import com.ghs.mobile.ui.DecodedImageFetcher
import com.ghs.mobile.ui.WebViewImageFallbackInterceptor
import com.ghs.mobile.core.data.GhsDatabase
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.engine.proxy.RulesProxySelector
import com.ghs.mobile.settings.ProxyStore
import com.ghs.mobile.settings.SettingsStore
import com.ghs.mobile.settings.SiteProxyPolicy
import com.ghs.mobile.web.BackupService
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
        // 版本取打包时写进 BuildConfig 的值（见 version.properties 与 app/build.gradle.kts）
        WebAdminController(this, repository, configs, appVersion = VERSION)
    }

    /**
     * 备份 / 恢复（F20）：配置 + 收藏装进一个文件。
     *
     * App 端与内置 Web 服务共用同一套编解码与恢复规则（[BackupService]），
     * 两端导出的文件可以互相回灌。
     */
    val backup: BackupService by lazy {
        BackupService(configs, repository, VERSION)
    }

    /**
     * 代理判定（唯一入口）：设备侧的开关/代理列表 + **当前来源**声明的 `isProxy` / `forceProxy`。
     *
     * 每次请求现算，不缓存快照：拨开关、切代理、切站点都立刻生效，不需要重建会话。
     */
    fun proxyRules(): com.ghs.mobile.core.engine.proxy.ProxyRules {
        val site = SiteProxyPolicy.policy.value
        return proxy.rules.value.copy(siteEnabled = site.useProxy, forceProxy = site.forceProxy)
    }

    /**
     * 带代理的 HTTP 客户端：图片、视频和应用请求共用同一套代理规则。
     *
     * `ProxySelector` 按请求逐个判定，来源级策略（isProxy / forceProxy）才能真正生效。
     */
    fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
        .proxySelector(RulesProxySelector { proxyRules() })
        // 与 WebView 共用 Cookie：站点用 Set-Cookie 下发的通行凭证，图片/视频请求也要带回去。
        // 取页那条管线本来就用这个 jar，但图片/视频走的是这个客户端——少了它，站点发给页面的
        // `xxx_access=…` 一类 cookie 到不了图床，表现为「页面能开、图全 403」。
        .cookieJar(com.ghs.mobile.core.script.WebViewCookieJar())
        .build()

    /**
     * 图片专用客户端：图集是原始尺寸 webp，单张常常几 MB，
     * OkHttp 默认 10s 读超时会把它误判成失败（浏览器能开、App 打不开）。
     *
     * 顺带补 `Referer`：图床按它判盗链，缺了直接 403（mycomic 的 CDN 就是这样）。
     * 规则与浏览器一致——用**当前站点的首页地址**，已经在请求里显式指定时不动。
     */
    fun imageClient(): OkHttpClient = proxiedClient().newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val referer = request.header("Referer") ?: com.ghs.mobile.ui.ImageReferer.siteOrigin()
            if (referer.isNullOrBlank()) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header("Referer", referer).build())
            }
        }
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * 图片加载器。
     *
     * 自定义组件必须写在**同一个** `components {}` 里：Coil 2 的 `components {}`
     * 是整体替换注册表而不是追加，连写三个会互相覆盖（svg 解码器曾被后一个调用吃掉）。
     *
     * 注册表里自定义项排在 Coil 内置项之前，所以这里的解码器/取值器优先命中。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { imageClient() }
        // 仅调试包把取图失败打到 logcat：release 包不该刷屏
        .apply { if (BuildConfig.DEBUG) logger(coil.util.DebugLogger()) }
        .components {
            // 解码器：站点图标可能是矢量图（favicon 写成 svg）
            add(SvgDecoder.Factory())
            // 取值器：favicon 的 `data:` 内联图（Coil 自带 fetcher 不认这个 scheme）
            add(DataUriFetcher.Factory())
            // 取值器：配置给了解图参数的图片（adapterImageCode）
            add(DecodedImageFetcher.Factory(this@GhsApp))
            // 拦截器：OkHttp 取图失败时改用 WebView 渲染（防盗链 / 连接被断 / 403）
            add(WebViewImageFallbackInterceptor(this@GhsApp))
        }
        .build()

    override fun onCreate() {
        // WebView 窗口注册表：所有 WebView 都从这里取，生命周期统一收口
        com.ghs.mobile.core.script.window.WebWindows.initialize(this)
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

        /**
         * 应用版本（`/api/meta` 与关于信息展示）。
         *
         * 直接取 `BuildConfig.VERSION_NAME`：它来自打包时的 `version.properties`，
         * 每次打包自增一次（见 `app/build.gradle.kts` 顶部）。不再手写常量，避免与 APK 版本对不上。
         */
        val VERSION: String = BuildConfig.VERSION_NAME
    }
}
