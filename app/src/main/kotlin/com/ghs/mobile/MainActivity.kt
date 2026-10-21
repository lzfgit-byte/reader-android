package com.ghs.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ghs.mobile.settings.ThemeMode
import com.ghs.mobile.ui.DemoApp
import com.ghs.mobile.ui.ProbeScreen
import com.ghs.mobile.ui.SettingsScreen
import kotlinx.coroutines.launch

/**
 * 入口：主界面 / 引擎自检 / 设置三个页面。
 *
 * 主题与字号来自 `app_setting(prefs)`，在 MaterialTheme 外层统一应用，
 * 切换后所有界面立即生效。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

private enum class Page { MAIN, PROBE, SETTINGS }

@Composable
private fun AppRoot() {
    val store = GhsApp.instance.settings
    val settings by store.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { store.load() }

    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        typography = settings.typography(),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var page by remember { mutableStateOf(Page.MAIN) }

            when (page) {
                Page.MAIN -> DemoApp(
                    onOpenProbe = { page = Page.PROBE },
                    onOpenSettings = { page = Page.SETTINGS },
                )

                Page.PROBE -> ProbeScreen(onBack = { page = Page.MAIN })

                Page.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onThemeChange = { theme -> scope.launch { store.update { it.copy(theme = theme) } } },
                    onFontScaleChange = { scale -> scope.launch { store.update { it.copy(fontScale = scale) } } },
                    onReaderModeChange = { mode -> scope.launch { store.update { it.copy(readerMode = mode) } } },
                    onBack = { page = Page.MAIN },
                )
            }
        }
    }
}
