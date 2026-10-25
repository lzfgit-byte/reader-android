package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.launch
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
    onProgress: (Int) -> Unit,
    onClose: () -> Unit,
) {
    // 每张图各自记住「全 / 缩」，互不影响；默认缩略
    val chosen = remember { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
    if (pages.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有图片") }
        return
    }
    val start = startIndex.coerceIn(0, pages.lastIndex)
    val pagerState = rememberPagerState(initialPage = start) { pages.size }
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val current = pagerState.currentPage

    LaunchedEffect(current) { onProgress(current) }

    Box(Modifier.fillMaxSize().background(Color(0xFF101013))) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            // 放大状态下拖动手势用于平移图片，不能同时翻页
            userScrollEnabled = !zoomed,
        ) { index ->
            val full = chosen[index] == true && !pages[index].fullUrl.isNullOrBlank()
            val url = if (full) pages[index].fullUrl.orEmpty() else pages[index].url
            // 每张图各自记住缩放与位移：翻页回来还是刚才放大的地方
            var scale by remember(index) { mutableStateOf(1f) }
            var pan by remember(index) { mutableStateOf(Offset.Zero) }
            if (index == current) androidx.compose.runtime.SideEffect { zoomed = scale > 1.001f }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(index) {
                        detectTransformGestures { _, delta, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            pan = if (next > 1.001f) {
                                // 放大后跟着手指平移，并按放大倍数限制不要拖出画面
                                val limitX = size.width * (next - 1f) / 2f
                                val limitY = size.height * (next - 1f) / 2f
                                Offset(
                                    (pan.x + delta.x).coerceIn(-limitX, limitX),
                                    (pan.y + delta.y).coerceIn(-limitY, limitY),
                                )
                            } else {
                                Offset.Zero
                            }
                        }
                    }
                    .pointerInput(index) {
                        // 双击：在 1x 与 2.5x 之间切换（双击点作为放大中心）
                        detectTapGestures(
                            onTap = { chromeVisible = !chromeVisible },
                            onDoubleTap = { tap ->
                                if (scale > 1.001f) {
                                    scale = 1f
                                    pan = Offset.Zero
                                } else {
                                    scale = 2.5f
                                    val limitX = size.width * 0.75f
                                    val limitY = size.height * 0.75f
                                    pan = Offset(
                                        ((size.width / 2f - tap.x) * 1.5f).coerceIn(-limitX, limitX),
                                        ((size.height / 2f - tap.y) * 1.5f).coerceIn(-limitY, limitY),
                                    )
                                }
                            },
                        )
                    },
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
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = pan.x
                            translationY = pan.y
                        },
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
        // 底部工具条：左侧页码，右侧 缩 / 全 / 关闭 / 上一张 / 下一张
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${current + 1}/${pages.size}",
                color = Color(0xFFE6E6EA),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))

            // 【全】【缩】两个按钮：选中态由「当前页正在用的地址」决定，逐图独立
            val useFull = chosen[current] == true && !pages[current].fullUrl.isNullOrBlank()
            TextButton(onClick = { chosen[current] = false }) {
                Text(
                    "缩",
                    color = if (!useFull) Color(0xFFB9A7FF) else Color.White,
                )
            }
            if (!pages[current].fullUrl.isNullOrBlank()) {
                TextButton(onClick = { chosen[current] = true }) {
                    Text(
                        "全",
                        color = if (useFull) Color(0xFFB9A7FF) else Color.White,
                    )
                }
            }
            // 关闭
            TextButton(onClick = onClose) { Text("×", color = Color.White) }
            // 上一张 / 下一张
            TextButton(
                enabled = current > 0,
                onClick = {
                    val target = (current - 1).coerceAtLeast(0)
                    scope.launch { pagerState.animateScrollToPage(target) }
                },
            ) { Text("‹", color = if (current > 0) Color.White else Color(0x66FFFFFF)) }
            TextButton(
                enabled = current < pages.lastIndex,
                onClick = {
                    val target = (current + 1).coerceAtMost(pages.lastIndex)
                    scope.launch { pagerState.animateScrollToPage(target) }
                },
            ) {
                Text(
                    "›",
                    color = if (current < pages.lastIndex) Color.White else Color(0x66FFFFFF),
                )
            }
        }
    }
}
