package com.ghs.mobile.web

import android.content.Context
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.data.SettingScope
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Web 管理服务的生命周期与设置。
 *
 * 设置存 `app_setting(device)`：端口、绑定、令牌都只属于本机，
 * 且 **API 里不提供修改入口**（见 web-admin-api.md §1 的归属规则）。
 */
class WebAdminController(
    private val context: Context,
    private val repository: LocalRepository,
    private val configStore: ConfigStore,
    private val appVersion: String = "0.1.0",
) {

    data class WebSettings(
        val enabled: Boolean = false,
        val port: Int = DEFAULT_PORT,
        val bindAll: Boolean = false,
    ) {
        /** 监听地址：允许局域网时绑 0.0.0.0，否则只绑本机 */
        val bindHost: String get() = if (bindAll) "0.0.0.0" else "127.0.0.1"

        /**
         * 展示给用户的所有可访问地址。
         *
         * 设备可能同时有 Wi-Fi、热点、有线等多个 IPv4（例如 192.168.x.x 与 10.x.x.x），
         * 全部列出来由用户挑；一个都没有时回落 127.0.0.1。
         */
        val consoleUrls: List<String>
            get() = if (bindAll) {
                // 允许局域网访问时才对外暴露局域网地址
                localIpAddresses()
                    .ifEmpty { listOf("127.0.0.1") }
                    .map { "http://$it:$port/console/" }
            } else {
                // 仅本机监听：局域网地址根本连不上，只给回环地址
                listOf("http://127.0.0.1:$port/console/")
            }

        /** 兼容旧调用：取第一个地址 */
        val consoleUrl: String get() = consoleUrls.first()
    }

    private val _state = MutableStateFlow(WebSettings())
    val state: StateFlow<WebSettings> = _state.asStateFlow()

    /** 配置被网页端改动时自增：App 侧据此重载当前配置 */
    private val _reloadSignal = MutableStateFlow(0)
    val reloadSignal: StateFlow<Int> = _reloadSignal.asStateFlow()

    private var server: AdminServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun load() {
        val saved = withContext(Dispatchers.IO) { repository.settings(SettingScope.DEVICE) }

        _state.value = WebSettings(
            enabled = saved["web_enabled"]?.toBooleanStrictOrNull() ?: false,
            port = saved["web_port"]?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT,
            bindAll = saved["web_bind_all"]?.toBooleanStrictOrNull() ?: false,

        )
        persist(_state.value)
        if (_state.value.enabled) start()
    }

    suspend fun update(transform: (WebSettings) -> WebSettings) {
        val next = transform(_state.value)
        _state.value = next
        persist(next)
        stop()
        if (next.enabled) start()
    }



    /**
     * 启动；端口被占用时顺延（web-admin-api.md §1："被占用时自动顺延并提示实际端口"）。
     */
    fun start() {
        if (server != null) return
        val settings = _state.value
        var candidate = settings.port
        repeat(PORT_ATTEMPTS) {
            val instance = AdminServer(
                context = context,
                store = configStore,
                port = candidate,
                bindHost = settings.bindHost,
    
                appVersion = appVersion,
                onConfigsChanged = { _reloadSignal.value = _reloadSignal.value + 1 },
            )
            val attempt = runCatching { instance.start(SOCKET_READ_TIMEOUT_MS, true) }
            val started = attempt.isSuccess
            android.util.Log.i(
                "GHS",
                "web admin start bind=${settings.bindHost} port=$candidate ok=$started" +
                    (
                        attempt.exceptionOrNull()?.let {
                            " err=${it.javaClass.simpleName}:${it.message}"
                        } ?: ""
                        ),
            )
            if (started) {
                server = instance
                if (candidate != settings.port) {
                    _state.value = settings.copy(port = candidate)
                    persist(_state.value)
                }
                return
            }
            candidate += 1
        }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    val isRunning: Boolean get() = server != null

    private fun persist(settings: WebSettings) {
        scope.launch {
            repository.putSetting(SettingScope.DEVICE, "web_enabled", settings.enabled.toString())
            repository.putSetting(SettingScope.DEVICE, "web_port", settings.port.toString())
            repository.putSetting(SettingScope.DEVICE, "web_bind_all", settings.bindAll.toString())
    
        }
    }

    private fun unusedTokenPlaceholder(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_PORT = 18600
        private const val PORT_ATTEMPTS = 10
        private const val SOCKET_READ_TIMEOUT_MS = 10_000
    }
}
/** 取本机局域网 IPv4（用于展示访问地址）；取不到返回 null */
private fun localIpAddresses(): List<String> {
    return runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .sortedBy { if (it.startsWith("192.168.")) 0 else 1 }
            .toList()
    }.getOrDefault(emptyList())
}
