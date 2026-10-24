package com.ghs.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 顶部「灵动岛」：把最新一条调试日志浮在应用顶部。
 *
 * - 只显示**最新一条**：新日志到达时直接替换内容，不堆叠
 * - 2 秒内没有新日志就收起（再来新日志会重新弹出）
 * - 收起/弹出都用滑入滑出，避免生硬闪烁
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
        // 每条日志只停留 2 秒；期间来新日志会重启这个计时
        delay(2_000)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(Color(0xE61F1F23), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shown.orEmpty(),
                color = Color(0xFFE6E6EA),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
