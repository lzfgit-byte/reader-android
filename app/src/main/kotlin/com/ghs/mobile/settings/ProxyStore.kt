package com.ghs.mobile.settings

import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.data.SettingScope
import com.ghs.mobile.core.engine.proxy.ProxyRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 代理设置：存在 `app_setting(device)` 作用域。
 *
 * 为什么是 `device` 而不是 `prefs`：代理地址与账号密码属于**本机网络环境**，
 * 跟着账号同步到别的设备上只会造成"换台机器就上不了网"。
 */
class ProxyStore(private val repository: LocalRepository) {

    private val _rules = MutableStateFlow(ProxyRules.default())
    val rules: StateFlow<ProxyRules> = _rules.asStateFlow()

    suspend fun load() {
        val saved = withContext(Dispatchers.IO) { repository.settings(SettingScope.DEVICE) }
        // 首次使用：保留默认（启用 + 内置地址），不要覆盖成"空配置"
        if (saved.isEmpty()) return

        val url = saved["proxy_url"]?.takeIf { it.isNotBlank() }
        val endpoint = ProxyRules.parseEndpoint(url)?.copy(
            username = saved["proxy_user"]?.takeIf { it.isNotBlank() },
            password = saved["proxy_pass"]?.takeIf { it.isNotBlank() },
        )

        _rules.value = ProxyRules(
            enabled = saved["proxy_enabled"]?.toBooleanStrictOrNull() ?: true,
            endpoint = endpoint,
            whitelist = ProxyRules.parseWhitelist(saved["proxy_whitelist"]),
        )
    }

    suspend fun update(transform: (ProxyRules) -> ProxyRules) {
        val next = transform(_rules.value)
        _rules.value = next
        withContext(Dispatchers.IO) {
            repository.putSetting(SettingScope.DEVICE, "proxy_enabled", next.enabled.toString())
            repository.putSetting(
                SettingScope.DEVICE,
                "proxy_url",
                next.endpoint?.let { "http://${it.authority}" }.orEmpty(),
            )
            repository.putSetting(SettingScope.DEVICE, "proxy_user", next.endpoint?.username.orEmpty())
            repository.putSetting(SettingScope.DEVICE, "proxy_pass", next.endpoint?.password.orEmpty())
            repository.putSetting(SettingScope.DEVICE, "proxy_whitelist", next.whitelist.joinToString(","))
        }
    }

    /** 设置页"应用"按钮的入口：把表单值合并成规则 */
    suspend fun apply(
        enabled: Boolean,
        url: String,
        username: String,
        password: String,
        whitelist: String,
    ) {
        update {
            ProxyRules(
                enabled = enabled,
                endpoint = ProxyRules.parseEndpoint(url)?.copy(
                    username = username.takeIf { it.isNotBlank() },
                    password = password.takeIf { it.isNotBlank() },
                ),
                whitelist = ProxyRules.parseWhitelist(whitelist),
            )
        }
    }
}
