package com.ghs.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.ghs.mobile.core.model.BlockType
import com.ghs.mobile.core.model.CContent
import com.ghs.mobile.core.model.Detail
import com.ghs.mobile.core.model.TextContent
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.TextButton
import coil.imageLoader
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 图片清晰度：原图（`fullUrl`）或展示图（`url`） */
enum class ImageQuality { ORIGINAL, PREVIEW }

/**
 * 图集阅读器：横向翻页 + 页码指示 + 目录 + 进度回写。
 *
 * 图片用 Coil 真实加载；演示数据的地址没有真实文件，失败时退回页号与地址占位。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComicReaderScreen(
    title: String,
    pages: List<Detail>,
    chapters: List<CContent>,
    startIndex: Int,
    quality: ImageQuality,
    imageMaxWidth: Int,
    onQualityChange: (ImageQuality) -> Unit,
    onProgress: (pageIndex: Int) -> Unit,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
    ) { pages.size }
    val current by remember { derivedStateOf { pagerState.currentPage } }
    android.util.Log.i("GHS", "reader imageMaxWidth=$imageMaxWidth (quality=$quality)")
    val scope = rememberCoroutineScope()

    var showChapters by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index -> onProgress(index) }
    }

    // 预取相邻页（±1 与 +2）：翻页时不用等下载，也不占太多内存
    val context = LocalContext.current
    LaunchedEffect(current, quality, pages) {
        val loader = context.imageLoader
        listOf(current - 1, current + 1, current + 2)
            .filter { it in pages.indices && it != current }
            .forEach { i ->
                val page = pages[i]
                val url = when (quality) {
                    ImageQuality.ORIGINAL -> page.fullUrl?.takeIf { it.isNotBlank() } ?: page.url
                    ImageQuality.PREVIEW -> page.url.ifEmpty { page.fullUrl.orEmpty() }
                }
                if (url.isNotEmpty()) {
                    android.util.Log.i("GHS", "prefetch page ${i + 1} $url")
                    loader.enqueue(
                        ImageRequest.Builder(context)
                            .data(url)
                            .size(imageMaxWidth)
                            .build(),
                    )
                }
            }
    }

    // 看图时点一下隐藏顶栏/页码，再点恢复（沉浸式）
    var chromeVisible by remember { mutableStateOf(true) }

    /** 长按保存当前页到相册（按当前清晰度取地址） */
    fun saveCurrentPage() {
        val page = pages.getOrNull(current) ?: return
        val url = when (quality) {
            ImageQuality.ORIGINAL -> page.fullUrl?.takeIf { it.isNotBlank() } ?: page.url
            ImageQuality.PREVIEW -> page.url.ifEmpty { page.fullUrl.orEmpty() }
        }
        if (url.isBlank()) return
        // 图床常校验 Referer，用图片所在站点的 origin 兜底
        val referer = runCatching {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        }.getOrNull()

        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { ImageSaver.save(context, url, referer) }
            }
            val text = result.fold(
                onSuccess = { "已保存到 $it" },
                onFailure = { "保存失败：${it.message ?: it.toString()}" },
            )
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1F)),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            ZoomablePage(
                page = pages.getOrNull(index),
                index = index,
                quality = quality,
                imageMaxWidth = imageMaxWidth,
                onTap = { chromeVisible = !chromeVisible },
                onLongPress = { saveCurrentPage() },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 图片有两级地址：url（展示用）/ fullUrl（原图），可随时切换
                    val hasBoth = pages.any { page ->
                        page.fullUrl?.isNotBlank() == true &&
                            page.url.isNotBlank() &&
                            page.fullUrl != page.url
                    }
                    if (hasBoth) {
                        TextButton(onClick = {
                            onQualityChange(
                                if (quality == ImageQuality.ORIGINAL) {
                                    ImageQuality.PREVIEW
                                } else {
                                    ImageQuality.ORIGINAL
                                },
                            )
                        }) {
                            Text(if (quality == ImageQuality.ORIGINAL) "原图" else "缩略图")
                        }
                    }
                    IconButton(onClick = { showChapters = true }) {
                        Icon(Icons.Default.List, contentDescription = "目录")
                    }
                },
            )
        }

        if (chromeVisible) {
            Text(
                "${current + 1} / ${pages.size}",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFE6E6EA),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }, sheetState = sheetState) {
            Text(
                "目录 · ${chapters.size} 项",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(chapters) { index, chapter ->
                    val isCurrent = index == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                scope.launch {
                                    pagerState.animateScrollToPage(index.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
                                    showChapters = false
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            chapter.title.ifEmpty { "第 ${index + 1} 页" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isCurrent) {
                            Text("当前", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PagePlaceholder(page: Detail?, index: Int, hint: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        Text(
            page?.title?.ifEmpty { "第 ${index + 1} 页" } ?: "第 ${index + 1} 页",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFE6E6EA),
        )
        Text(
            page?.preferredUrl.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF9A9AA5),
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.labelSmall, color = Color(0xFF9A9AA5))
        }
    }
}

/** 文本块渲染：段落与图片块（图片走 Coil，失败时退回地址占位） */
@Composable
fun TextBlocks(content: TextContent, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(content.blocks) { block ->
            when (block.type) {
                BlockType.IMAGE -> SubcomposeAsyncImage(
                    model = block.src,
                    contentDescription = "插图",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit,
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("加载中…", style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    error = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "图片块\n${block.src}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    },
                )

                BlockType.P -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/**
 * 文本阅读器：块渲染 + 章节切换。
 *
 * `prevUrl` / `nextUrl` 为空表示不可达（规范里空串即"没有上一/下一章"）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReaderScreen(
    itemTitle: String,
    content: TextContent?,
    loading: Boolean,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(content?.title?.ifEmpty { itemTitle } ?: itemTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (content == null || loading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (loading) "正在加载章节…" else "暂无正文")
                        }
                    } else {
                        TextBlocks(content)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { onNavigate(content?.prevUrl.orEmpty()) },
                        enabled = !content?.prevUrl.isNullOrEmpty() && !loading,
                        modifier = Modifier.weight(1f),
                    ) { Text("上一章") }

                    Button(
                        onClick = { onNavigate(content?.nextUrl.orEmpty()) },
                        enabled = !content?.nextUrl.isNullOrEmpty() && !loading,
                        modifier = Modifier.weight(1f),
                    ) { Text("下一章") }
                }
            }
        }
    }
}

/**
 * 单页图片：双指缩放 + 拖动，双击在 1x / 2x 之间切换。
 *
 * 缩放到 1x 时横向拖动交还给 Pager，保证翻页手势不被吃掉。
 * 选原图时若加载失败，自动退回展示图（`url`），避免整页空白。
 */
@Composable
private fun ZoomablePage(
    page: Detail?,
    index: Int,
    quality: ImageQuality,
    imageMaxWidth: Int,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var fellBack by remember(index, quality) { mutableStateOf(false) }

    val original = page?.fullUrl?.takeIf { it.isNotBlank() }
    val preview = page?.url.orEmpty()
    val model = when {
        quality == ImageQuality.ORIGINAL && !fellBack && original != null -> original
        preview.isNotEmpty() -> preview
        else -> original.orEmpty()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 只有「双指」或「已经放大」时才消费手势；否则单指横滑交还给 Pager 翻页
            .pointerInput(index) {
                awaitEachGesture {
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            offset = if (next > 1.01f) offset + pan else Offset.Zero
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(model).size(imageMaxWidth).build(),
            contentDescription = "第 ${index + 1} 页",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
            loading = { PagePlaceholder(page, index, "加载中…") },
            error = {
                // 原图挂了就退展示图；已经退过就显示占位
                if (quality == ImageQuality.ORIGINAL && !fellBack && original != null && preview.isNotEmpty()) {
                    fellBack = true
                }
                PagePlaceholder(page, index, null)
            },
        )
    }
}
