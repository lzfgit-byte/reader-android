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
    onProgress: (pageIndex: Int) -> Unit,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
    ) { pages.size }
    val current by remember { derivedStateOf { pagerState.currentPage } }
    val scope = rememberCoroutineScope()

    var showChapters by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index -> onProgress(index) }
    }

    // 看图时点一下隐藏顶栏/页码，再点恢复（沉浸式）
    var chromeVisible by remember { mutableStateOf(true) }

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
                onTap = { chromeVisible = !chromeVisible },
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
 * 单页图片：双指缩放 + 拖动，双击在 1x / 2.5x 之间切换。
 *
 * 缩放到 1x 时横向拖动交还给 Pager，保证翻页手势不被吃掉。
 */
@Composable
private fun ZoomablePage(page: Detail?, index: Int, onTap: () -> Unit) {
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(index) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 5f)
                    scale = next
                    offset = if (next > 1.01f) offset + pan else Offset.Zero
                }
            }
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = page?.preferredUrl.orEmpty(),
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
            error = { PagePlaceholder(page, index, null) },
        )
    }
}