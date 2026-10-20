package com.ghs.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.BlockType
import com.ghs.mobile.core.model.Detail
import com.ghs.mobile.core.model.TextContent

/**
 * 图集阅读器：横向翻页 + 页码指示 + 进度回写。
 *
 * 图片本身还没接入加载（演示页没有真实图片文件），每页显示页号与地址占位；
 * 翻页会把当前页回写到 `reading_progress.chapterOffset`，下次从该页续读。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComicReaderScreen(
    title: String,
    pages: List<Detail>,
    startIndex: Int,
    onProgress: (pageIndex: Int) -> Unit,
    onBack: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
    ) { pages.size }
    val current by remember { derivedStateOf { pagerState.currentPage } }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { index -> onProgress(index) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = Color(0xFF1B1B1F)) {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { index ->
                    val page = pages.getOrNull(index)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .background(Color(0xFF26262B), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${current + 1} / ${pages.size}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFE6E6EA),
                    )
                }
            }
        }
    }
}

/** 文本块渲染：段落与图片块（图片暂用占位，接入加载后替换） */
@Composable
fun TextBlocks(content: TextContent, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(content.blocks) { block ->
            when (block.type) {
                BlockType.IMAGE -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "图片块\n${block.src}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

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
