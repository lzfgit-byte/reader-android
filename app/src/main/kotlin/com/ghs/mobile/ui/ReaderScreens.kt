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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.ExtendedFloatingActionButton
import coil.compose.AsyncImage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.graphics.asImageBitmap

/** 图片清晰度：原图（`fullUrl`）或展示图（`url`） */
enum class ImageQuality { ORIGINAL, PREVIEW }

/**
 * 图集阅读器：横向翻页 + 页码指示 + 目录 + 进度回写。
 *
 * 图片用 Coil 真实加载；演示数据的地址没有真实文件，失败时退回页号与地址占位。
 */
/** 顺序加载：同时最多两张在飞 */
private const val LOAD_CONCURRENCY = 2

/** 预取窗口：当前页之后连续多少页提前入队（不要求已进入视窗） */
private const val PREFETCH_AHEAD = 12

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ComicReaderScreen(
    title: String,
    pages: List<Detail>,
    chapters: List<CContent>,
    startIndex: Int,
    quality: ImageQuality,

    onQualityChange: (ImageQuality) -> Unit,
    onProgress: (pageIndex: Int) -> Unit,
    onBack: () -> Unit,
    chapterIndex: Int = 0,
    preloadNext: Boolean = false,
    onPreloadNextChange: (Boolean) -> Unit = {},
    onChapterSelected: (Int) -> Unit = {},
    /** 取下一话的图片地址（用于预加载，不切话）；失败返回空列表 */
    preloadChapterPages: suspend (Int) -> kotlin.collections.List<String> = { emptyList() },
    onNextChapter: (() -> Unit)? = null,
    /** 双击某张图：进入单图阅读（独立模式） */
    onOpenSinglePage: (Int) -> Unit = {},
    /**
     * 正在切换到的目录下标（`null` = 没在切）。
     *
     * 切话是**一次网络请求**（取新一话的图片列表），在拿到结果前整屏还是上一话的画面：
     * 不给状态就成了"点了目录没反应，像卡住"。这里盖一层遮罩 + 转圈，顺便挡住遮罩下的
     * 所有手势，避免连点叠出第二个请求。
     */
    switchingChapter: Int? = null,
) {
    val scope = rememberCoroutineScope()
    // 切话时整块状态重建：不这样会沿用上一话的滚动位置
    val listState = remember(chapterIndex, pages) {
        androidx.compose.foundation.lazy.LazyListState(
            firstVisibleItemIndex = startIndex.coerceIn(0, (pages.size - 1).coerceAtLeast(0)),
        )
    }
    val current by remember(listState, pages) { derivedStateOf { listState.firstVisibleItemIndex.coerceAtMost((pages.size - 1).coerceAtLeast(0)) } }
    val atBottom by remember(listState, pages) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= pages.size - 2
        }
    }
    val percent = if (pages.isEmpty()) 0 else ((current + 1) * 100 / pages.size)

    var showChapters by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chromeVisible by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index -> onProgress(index) }
    }

    // 预取：从当前页开始，按「从上到下」的顺序把后面 PREFETCH_AHEAD 页都排进队列。
    // LazyColumn 只会组合视窗内的 item，等滚到才加载就永远慢半拍——所以这里主动
    // 入队，不依赖是否可见。已经在位图缓存里的页跳过（滚回来不用重复请求）。
    val context = LocalContext.current
    LaunchedEffect(current, quality, pages) {
        val loader = context.imageLoader
        (1..PREFETCH_AHEAD).forEach { delta ->
            val i = current + delta
            val page = pages.getOrNull(i) ?: return@forEach
            val url = page.preferQuality(quality)
            if (url.isEmpty() || ImageCache.bitmap(url) != null) return@forEach
            android.util.Log.i("GHS", "prefetch page ${i + 1} $url")
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(coil.size.Size.ORIGINAL)
                    .build(),
            )
        }
    }

    // 预加载下一话：读到后段时把**下一话的图片**先拉进缓存，但**不切话**。
    // 之前这里直接调 onChapterSelected(chapterIndex + 1)，等于"预加载完就跳走"，
    // 用户还在看当前话就被切到下一话首页。真正的切话只由用户操作触发。
    var preloadFired by remember(chapterIndex) { mutableStateOf(false) }
    LaunchedEffect(preloadNext, current, pages.size) {
        if (!preloadNext || preloadFired || pages.isEmpty()) return@LaunchedEffect
        if (current < pages.size * 6 / 10) return@LaunchedEffect
        preloadFired = true
        val nextUrls = runCatching { preloadChapterPages(chapterIndex + 1) }
            .getOrDefault(emptyList())
        if (nextUrls.isEmpty()) return@LaunchedEffect
        android.util.Log.i("GHS", "preload next chapter: ${nextUrls.size} images")
        val loader = context.imageLoader
        nextUrls.forEach { url ->
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .size(coil.size.Size.ORIGINAL)
                    .build(),
            )
        }
    }

    /** 长按保存当前页到相册（按当前清晰度取地址） */
    fun saveCurrentPage() {
        val page = pages.getOrNull(current) ?: return
        val url = page.preferQuality(quality)
        if (url.isBlank()) return
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

    // ── 逐张顺序加载 ────────────────────────────────────────────────
    // frontier：已允许发请求的最大下标；每张图完成（成功或失败）才放行下一张，
    // 并发最多 CONCURRENCY 张，避免一次性把 70 张图全塞进队列。
    val imageStates = remember(pages) {
        androidx.compose.runtime.mutableStateMapOf<Int, coil.compose.AsyncImagePainter.State>()
    }
    val retries = remember(pages) { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    // 已经成功渲染过的页：即使后续状态再变 Error，也不再盖错误提示
    val loadedPages = remember(pages) { androidx.compose.runtime.mutableStateMapOf<Int, Boolean>() }
    var frontier by remember(pages) { androidx.compose.runtime.mutableIntStateOf(0) }

    fun advance(index: Int) {
        if (index >= frontier) frontier = (index + 1).coerceAtMost(pages.size - 1)
    }

    fun retry(index: Int) {
        // 清掉错误态并递增重试计数：AsyncImage 以该计数为 key 重建，
        // 否则相同 model 不会再次发起请求（点了按钮只会消失）
        imageStates.remove(index)
        retries[index] = (retries[index] ?: 0) + 1
        frontier = index
    }

    // 严格按从上到下：只放行 frontier 这一张；滚动时把 frontier 拉到可见起点，
    // 避免快速下滑时还要先把中间几十页全部加载完。
    val firstVisible = listState.firstVisibleItemIndex
    LaunchedEffect(firstVisible) {
        if (firstVisible > frontier) frontier = firstVisible
    }
    val allowUpTo = frontier.coerceAtMost(pages.size - 1)
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF151517))) {
        // 连续滚动：所有图片上下拼接，一滚到底
        LazyColumn(
            // 顶部留出标题栏高度：否则第一张图会被顶栏遮住
            contentPadding = PaddingValues(top = 64.dp),
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(pages) { index, page ->
                val url = page.preferQuality(quality)
                val state = imageStates[index]
                val retryCount = retries[index] ?: 0
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { chromeVisible = !chromeVisible },
                            onDoubleClick = { onOpenSinglePage(index) },
                            // 长按保存已移除（用户不需要）
                        ),
                ) {
                    // 已解码过的图直接从位图缓存出图：滚回不重新下载、不重新解码
                    val cachedBitmap = ImageCache.bitmap(url)
                    if (cachedBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = cachedBitmap.asImageBitmap(),
                            contentDescription = "第 ${index + 1} 页",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else if (index <= allowUpTo) {
                        androidx.compose.runtime.key(retryCount) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(url)
                                    // 需要解图的页禁用缓存：缓存里是未解码原图，命中就绕过了 Fetcher
                                    .apply {
                                        if (ImageDecodeBridge.hasExtra(url)) {
                                            memoryCachePolicy(coil.request.CachePolicy.DISABLED)
                                            diskCachePolicy(coil.request.CachePolicy.DISABLED)
                                        }
                                    }
                                    .size(coil.size.Size.ORIGINAL)
                                    .crossfade(true)
                                    .build(),
                            contentDescription = "第 ${index + 1} 页",
                            contentScale = ContentScale.FillWidth,
                            onState = { next ->
                                imageStates[index] = next
                                if (next is coil.compose.AsyncImagePainter.State.Success) {
                                    // 记下解码结果：下次滚动回来直接用位图
                                    val drawable = next.result.drawable
                                    if (drawable is android.graphics.drawable.BitmapDrawable) {
                                        drawable.bitmap?.let { ImageCache.putBitmap(url, it) }
                                    }
                                }
                                if (next is coil.compose.AsyncImagePainter.State.Success ||
                                    next is coil.compose.AsyncImagePainter.State.Error
                                ) {
                                    advance(index)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                // 未加载出高度前占位，避免列表塌陷
                                .heightIn(min = if (state is coil.compose.AsyncImagePainter.State.Success) 0.dp else 320.dp),
                        )
                        }
                    } else {
                        // 还没轮到它：占位，不占用网络
                        Box(modifier = Modifier.fillMaxWidth().height(320.dp))
                    }

                    when (state) {
                        is coil.compose.AsyncImagePainter.State.Error -> Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "第 ${index + 1} 页加载失败",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE6E6EA),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { retry(index) }) { Text("重新加载") }
                                OutlinedButton(onClick = { copyAddress(context, url) }) {
                                    Text("复制地址")
                                }
                            }
                        }

                        is coil.compose.AsyncImagePainter.State.Loading -> Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFB9A7FF),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "${index + 1}/${pages.size} 加载中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9A9AA5),
                            )
                            // 等太久时可以复制地址去浏览器排查，不必等到它失败
                            TextButton(onClick = { copyAddress(context, url) }) {
                                Text("复制地址", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            chapters.getOrNull(chapterIndex)?.title?.ifEmpty { title } ?: title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.basicMarquee(),
                        )
                        Text(
                            "${current + 1}/${pages.size} · $percent%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
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
                },
            )
        }

        // 右下角：目录（评论未实现，暂不显示）
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FloatingActionButton(
                onClick = { showChapters = true },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(Icons.Default.List, contentDescription = "目录")
            }
        }

        // 底部「下一话」：滚到最底部时出现
        if (atBottom && chapters.getOrNull(chapterIndex + 1) != null) {
            ExtendedFloatingActionButton(
                onClick = { onChapterSelected(chapterIndex + 1) },
                icon = { Icon(Icons.Default.ArrowForward, contentDescription = null) },
                text = { Text("下一话") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp),
            )
        }

        // 切话中：整屏遮罩（画在最后 = 盖住图片、顶栏、两个 FAB），挡住底下所有手势
        switchingChapter?.let { target ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xB3000000))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFB9A7FF),
                    )
                    Text(
                        "正在打开第 ${target + 1} 话…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE6E6EA),
                    )
                    val targetTitle = chapters.getOrNull(target)?.title.orEmpty()
                    if (targetTitle.isNotBlank()) {
                        Text(
                            targetTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9A9AA5),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }

    if (showChapters) {
        // 打开目录就停在当前话：几十上百话的作品从头开始翻，等于每次都要自己找位置。
        // 状态随 sheet 一起创建/销毁，所以每次打开都会重新按当前话定位。
        val chapterListState = rememberLazyListState(
            initialFirstVisibleItemIndex = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)),
        )
        ModalBottomSheet(onDismissRequest = { showChapters = false }, sheetState = sheetState) {
            // 预加载下一话开关：放在目录顶部
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("预加载下一话", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "当前章节滚到后段时，后台提前请求下一话",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = preloadNext, onCheckedChange = onPreloadNextChange)
            }
            HorizontalDivider()
            LazyColumn(state = chapterListState, contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(chapters) { index, chapter ->
                    val isCurrent = index == chapterIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showChapters = false
                                onChapterSelected(index)
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}/${chapters.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            chapter.title.ifEmpty { "第 ${index + 1} 话" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        if (isCurrent) {
                            Text(
                                "当前",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 复制图片地址到剪贴板（排查问题 / 外部打开） */
private fun copyAddress(context: android.content.Context, url: String) {
    runCatching {
        val manager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        manager.setPrimaryClip(android.content.ClipData.newPlainText("image-url", url))
        android.widget.Toast.makeText(context, "已复制图片地址", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** 按清晰度取地址：原图优先 fullUrl，缩略图优先 url */
internal fun Detail.preferQuality(quality: ImageQuality): String = when (quality) {
    ImageQuality.ORIGINAL -> fullUrl?.takeIf { it.isNotBlank() } ?: url
    ImageQuality.PREVIEW -> url.ifEmpty { fullUrl.orEmpty() }
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
            model = ImageRequest.Builder(LocalContext.current).data(model).size(coil.size.Size.ORIGINAL).build(),
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
