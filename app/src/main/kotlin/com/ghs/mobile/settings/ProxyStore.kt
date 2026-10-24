package com.ghs.mobile.settings

import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.data.SettingScope
import com.ghs.mobile.core.engine.proxy.ProxyRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 代理设置：存在 `app_setting(device)` 作用域。
 *
 * 支持**多条代理、同一时刻只有一条生效**（`activeId` 指向它）。
 * 代理不做认证：只连本机/内网的转发端口，没有用户名密码的场景。
 *
 * 为什么是 `device` 而不是 `prefs`：代理地址属于**本机网络环境**，
 * 跟着账号同步到别的设备上只会造成「换台机器就上不了网」。
 */
class ProxyStore(private val repository: LocalRepository) {

    /** 一条已保存的代理 */
    data class Entry(val id: String, val url: String)

    /** 代理列表 + 当前生效项 */
    data class State(
        val entries: List<Entry> = emptyList(),
        val activeId: String? = null,
    ) {
        val active: Entry? get() = entries.firstOrNull { it.id == activeId } ?: entries.firstOrNull()
    }

    private val _rules = MutableStateFlow(ProxyRules.default())
    val rules: StateFlow<ProxyRules> = _rules.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun load() {
        val saved = withContext(Dispatchers.IO) { repository.settings(SettingScope.DEVICE) }
        // 三把键都没有时保留默认（启用 + 内置地址）
        if (saved["proxy_url"] == null && saved["proxy_enabled"] == null && saved["proxy_list"] == null) return

        val stored = parseEntries(saved["proxy_list"])
        // 兼容旧数据：只有 proxy_url 没有列表时，把它当成唯一一条
        val legacy = saved["proxy_url"]?.takeIf { it.isNotBlank() }
        val entries = if (stored.isEmpty() && legacy != null) {
            listOf(Entry(id = "legacy", url = legacy))
        } else {
            stored
        }
        val activeId = saved["proxy_active"]?.takeIf { id -> entries.any { it.id == id } }
            ?: entries.firstOrNull()?.id

        _state.value = State(entries, activeId)
        _rules.value = ProxyRules(
            enabled = saved["proxy_enabled"]?.toBooleanStrictOrNull() ?: true,
            endpoint = ProxyRules.parseEndpoint(_state.value.active?.url),
            whitelist = ProxyRules.parseWhitelist(saved["proxy_whitelist"]),
            forceProxy = ProxyRules.parseWhitelist(saved["proxy_force"]),
        )
    }

    /** 新增一条代理并立即生效 */
    suspend fun add(url: String) {
        val normalized = normalizeUrl(url) ?: return
        val entry = Entry(id = "p${System.currentTimeMillis()}", url = normalized)
        update { it.copy(entries = it.entries + entry, activeId = entry.id) }
    }

    suspend fun remove(id: String) {
        val next = _state.value.entries.filterNot { it.id == id }
        val activeId = if (_state.value.activeId == id) next.firstOrNull()?.id else _state.value.activeId
        update { it.copy(entries = next, activeId = activeId) }
    }

    /** 切换生效代理（同一时刻只有一条） */
    suspend fun select(id: String) {
        if (_state.value.entries.none { it.id == id }) return
        update { it.copy(activeId = id) }
    }

    /** 设置页「应用」：启用开关 + 白名单 + 强制名单（代理地址由列表决定） */
    suspend fun apply(enabled: Boolean, whitelist: String, forceProxy: String) {
        _rules.value = _rules.value.copy(
            enabled = enabled,
            whitelist = ProxyRules.parseWhitelist(whitelist),
            forceProxy = ProxyRules.parseWhitelist(forceProxy),
        )
        persist()
    }

    private suspend fun update(transform: (State) -> State) {
        val next = transform(_state.value)
        _state.value = next
        _rules.value = _rules.value.copy(endpoint = ProxyRules.parseEndpoint(next.active?.url))
        persist()
    }

    private suspend fun persist() {
        val state = _state.value
        val rules = _rules.value
        withContext(Dispatchers.IO) {
            repository.putSetting(SettingScope.DEVICE, "proxy_enabled", rules.enabled.toString())
            repository.putSetting(SettingScope.DEVICE, "proxy_list", encodeEntries(state.entries))
            repository.putSetting(SettingScope.DEVICE, "proxy_active", state.activeId.orEmpty())
            // 老键继续写：旧版本或外部工具仍会读 proxy_url
            repository.putSetting(SettingScope.DEVICE, "proxy_url", state.active?.url.orEmpty())
            repository.putSetting(SettingScope.DEVICE, "proxy_whitelist", rules.whitelist.joinToString(","))
            repository.putSetting(SettingScope.DEVICE, "proxy_force", rules.forceProxy.joinToString(","))
        }
    }

    /** `host:port` / `http://host:port` 统一成 `http://host:port` */
    private fun normalizeUrl(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val withScheme = if (text.contains("://")) text else "http://$text"
        val endpoint = ProxyRules.parseEndpoint(withScheme) ?: return null
        if (!endpoint.isConfigured) return null
        return "http://${endpoint.authority}"
    }

    private fun parseEntries(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val row = array.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id").ifBlank { return@mapNotNull null }
                val url = row.optString("url").ifBlank { return@mapNotNull null }
                Entry(id, url)
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeEntries(entries: List<Entry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().put("id", entry.id).put("url", entry.url))
        }
    }.toString()
}
