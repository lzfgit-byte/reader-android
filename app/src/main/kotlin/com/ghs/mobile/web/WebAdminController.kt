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
        val token: String = "",
    ) {
        val bindHost: String get() = if (bindAll) "0.0.0.0" else "127.0.0.1"
        val baseUrl: String get() = "http://${if (bindAll) "<局域网IP>" else "127.0.0.1"}:$port"
        val consoleUrl: String get() = "$baseUrl/console/?t=$token"
    }

    private val _state = MutableStateFlow(WebSettings())
    val state: StateFlow<WebSettings> = _state.asStateFlow()

    private var server: AdminServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun load() {
        val saved = withContext(Dispatchers.IO) { repository.settings(SettingScope.DEVICE) }
        val token = saved["web_token"]?.takeIf { it.length >= 16 } ?: newToken()
        _state.value = WebSettings(
            enabled = saved["web_enabled"]?.toBooleanStrictOrNull() ?: false,
            port = saved["web_port"]?.toIntOrNull()?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT,
            bindAll = saved["web_bind_all"]?.toBooleanStrictOrNull() ?: false,
            token = token,
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

    suspend fun resetToken() {
        update { it.copy(token = newToken()) }
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
                token = settings.token,
                appVersion = appVersion,
            )
            val started = runCatching { instance.start(SOCKET_READ_TIMEOUT_MS, true) }.isSuccess
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
            repository.putSetting(SettingScope.DEVICE, "web_token", settings.token)
        }
    }

    private fun newToken(): String {
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
