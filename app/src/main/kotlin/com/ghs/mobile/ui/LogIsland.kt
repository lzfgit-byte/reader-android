package com.ghs.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/**
 * 顶部「灵动岛」：把最新一条调试/进度消息浮在最上层。
 *
 * 用非聚焦 Popup（窗口只有胶囊大小）：
 * - 位置在最上层，不会被页面内的弹窗盖住
 * - 不拦截胶囊以外的点击，不挡住下面的按钮/弹窗
 * - 只显示最新一条，2 秒没有新消息就消失
 */
@Composable
fun LogIsland(
    entries: List<String>,
    modifier: Modifier = Modifier,
) {
    val latest = entries.lastOrNull()
    var visible by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(latest) {
        if (latest.isNullOrBlank()) return@LaunchedEffect
        shown = latest
        visible = true
        delay(2_000)
        visible = false
    }

    if (!visible) return

    val pulse by animateFloatAsState(
        targetValue = if (visible) 1f else 0.6f,
        animationSpec = tween(600),
        label = "islandPulse",
    )

    // 贴在内容区顶部（状态栏正下方）：Popup 的坐标原点已经在状态栏之下，
    // 再叠加一次状态栏高度会让它落到标题栏上，看着"太靠下"
    val topOffset = with(androidx.compose.ui.platform.LocalDensity.current) {
        6.dp.roundToPx()
    }

    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, topOffset),
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Row(
            modifier = modifier
                .widthIn(max = 340.dp)
                .shadow(elevation = 10.dp, shape = RoundedCornerShape(50), clip = false)
                .background(
                    brush = Brush.horizontalGradient(
                        listOf(
                            Color(0xF21B1B20),
                            Color(0xF22A2630),
                            Color(0xF21B1B20),
                        ),
                    ),
                    shape = RoundedCornerShape(50),
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size((7 * pulse).dp.coerceAtLeast(4.dp))
                    .background(Color(0xFFB9A7FF), CircleShape),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = shown.orEmpty(),
                color = Color(0xFFEDEBF5),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}