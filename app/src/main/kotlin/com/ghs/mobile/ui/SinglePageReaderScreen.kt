package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.ghs.mobile.core.model.Detail

/**
 * 单图阅读：一次只显示一张，左右滑动翻页。
 *
 * 与连续滚动的漫画阅读**互相独立**：数据源相同，但状态（当前页、滚动位置）分开管理，
 * 从漫画阅读双击进入，关闭后回到原来的滚动位置。
 */
@Composable
fun SinglePageReaderScreen(
    title: String,
    pages: List<Detail>,
    startIndex: Int,
    quality: ImageQuality,
    onProgress: (Int) -> Unit,
    onClose: () -> Unit,
) {
    if (pages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有图片") }
        return
    }
    val start = startIndex.coerceIn(0, pages.lastIndex)
    val pagerState = rememberPagerState(initialPage = start) { pages.size }
    var chromeVisible by remember { mutableStateOf(true) }
    val current = pagerState.currentPage

    LaunchedEffect(current) { onProgress(current) }

    Box(Modifier.fillMaxSize().background(Color(0xFF101013))) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val url = pages[index].preferredUrl
            Box(
                modifier = Modifier.fillMaxSize().clickable { chromeVisible = !chromeVisible },
                contentAlignment = Alignment.Center,
            ) {
                var failed by remember(url) { mutableStateOf(false) }
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(url)
                        .size(coil.size.Size.ORIGINAL)
                        .build(),
                    contentDescription = "第 ${index + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        failed = state is AsyncImagePainter.State.Error
                    },
                )
                if (failed) {
                    Text(
                        "第 ${index + 1} 页加载失败",
                        color = Color(0xFFE6E6EA),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // 顶部：标题 + 页码
        if (chromeVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    "$title  ${current + 1}/${pages.size}",
                    color = Color(0xFFE6E6EA),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 底部：页码进度 + 关闭按钮（始终显示，避免迷路）
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = Color(0xCC000000), shape = RoundedCornerShape(50)) {
                Text(
                    text = "${current + 1} / ${pages.size}",
                    color = Color(0xFFE6E6EA),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Surface(color = Color(0xCC000000), shape = RoundedCornerShape(50)) {
                TextButton(onClick = onClose) {
                    Text("关闭", color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
