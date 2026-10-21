package com.ghs.mobile.settings

import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.data.SettingScope
import com.ghs.mobile.core.model.ReaderMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
}

enum class FontScale(val key: String, val label: String, val multiplier: Float) {
    SMALL("small", "小", 0.9f),
    DEFAULT("default", "标准", 1.0f),
    LARGE("large", "大", 1.15f),
    XLARGE("xlarge", "特大", 1.3f),
}

/**
 * 应用偏好（存在 `app_setting` 的 `prefs` 作用域，可导出/同步）。
 *
 * 与 `device` 作用域的分界：这里的每一项都能跟着用户走；
 * 端口、令牌、WebDAV 凭证这类只属于本机的配置不在这里。
 */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontScale: FontScale = FontScale.DEFAULT,
    val readerMode: ReaderMode = ReaderMode.AUTO,
    val historyRemember: Int = 100,
) {
    /** 按字号偏好缩放排版；只用乘法，避免逐个写死 sp */
    fun typography(base: Typography = Typography()): Typography {
        val k = fontScale.multiplier
        if (k == 1f) return base
        return base.copy(
            displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize * k),
            displayMedium = base.displayMedium.copy(fontSize = base.displayMedium.fontSize * k),
            displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize * k),
            headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize * k),
            headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize * k),
            headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize * k),
            titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize * k),
            titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize * k),
            titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize * k),
            bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize * k),
            bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize * k),
            bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize * k),
            labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize * k),
            labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize * k),
            labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize * k),
        )
    }
}

/**
 * 设置的读写：内存里用 StateFlow 暴露给 UI，落库走 `app_setting(prefs)`。
 *
 * 每次修改先更新内存再写库，保证界面立即响应、不必等 IO。
 */
class SettingsStore(private val repository: LocalRepository) {

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    suspend fun load() {
        val saved = withContext(Dispatchers.IO) { repository.settings(SettingScope.PREFS) }
        _state.value = AppSettings(
            theme = ThemeMode.entries.firstOrNull { it.key == saved["theme"] } ?: ThemeMode.SYSTEM,
            fontScale = FontScale.entries.firstOrNull { it.key == saved["fontScale"] } ?: FontScale.DEFAULT,
            readerMode = ReaderMode.entries.firstOrNull { it.name.lowercase() == saved["readerMode"] } ?: ReaderMode.AUTO,
            historyRemember = saved["historyRemember"]?.toIntOrNull() ?: 100,
        )
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        withContext(Dispatchers.IO) {
            repository.putSetting(SettingScope.PREFS, "theme", next.theme.key)
            repository.putSetting(SettingScope.PREFS, "fontScale", next.fontScale.key)
            repository.putSetting(SettingScope.PREFS, "readerMode", next.readerMode.name.lowercase())
            repository.putSetting(SettingScope.PREFS, "historyRemember", next.historyRemember.toString())
        }
    }
}

/** 阅读器默认模式的展示名（设置页用） */
fun ReaderMode.label(): String = when (this) {
    ReaderMode.VERTICAL -> "纵向滚动"
    ReaderMode.HORIZONTAL -> "横向翻页"
    ReaderMode.AUTO -> "自动"
}

/** 字号预览用的示例文本 */
fun AppSettings.previewSp(): androidx.compose.ui.unit.TextUnit = 16.sp
