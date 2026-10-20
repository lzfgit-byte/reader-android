package com.ghs.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ghs.mobile.ui.DemoApp
import com.ghs.mobile.ui.ProbeScreen

/**
 * 入口：默认进入演示列表；顶栏可进「引擎自检」。
 *
 * 演示数据来自 `assets/demo/`，解析链路与真实配置一致（脚本 → $ → 领域模型）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showProbe by remember { mutableStateOf(false) }
                    if (showProbe) {
                        ProbeScreen(onBack = { showProbe = false })
                    } else {
                        DemoApp(onOpenProbe = { showProbe = true })
                    }
                }
            }
        }
    }
}
