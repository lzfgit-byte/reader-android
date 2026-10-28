package com.ghs.mobile.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.Pagination
import com.ghs.mobile.web.ConfigStore
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest

/**
 * 站点选择抽屉：标题 + 搜索 + 两列站点卡片，当前项打勾。
 *
 * 对应"右上角点站点名 → 选站点"的交互。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SitePickerSheet(
    sites: List<ConfigStore.StoredConfig>,
    currentKey: String?,
    onPick: (String) -> Unit,
    /** 长按拖动后的新顺序（按 key 排列），由调用方落库 */
    onReorder: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var keyword by remember { mutableStateOf("") }
    // 拖动时用本地顺序立即反馈，松手才回调落库；外部列表被重新读取时，非拖动状态才覆盖本地顺序
    val order = remember { mutableStateListOf<ConfigStore.StoredConfig>().apply { addAll(sites) } }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragPointer by remember { mutableStateOf(Offset.Zero) }
    var autoScroll by remember { mutableStateOf(0) }
    // 每张卡片在窗口里的位置：拖动时靠它判断手指落在哪个格子上
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var gridBounds by remember { mutableStateOf(Rect.Zero) }
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    val edgeSlopPx = with(LocalDensity.current) { 72.dp.toPx() }

    LaunchedEffect(sites) {
        if (dragKey == null) {
            order.clear()
            order.addAll(sites)
        }
    }

    /** 把正在拖的卡片移到手指所在的格子（同一格不重复移动，避免抖动） */
    fun moveDraggedTo(pointer: Offset) {
        val key = dragKey ?: return
        val target = bounds.entries.firstOrNull { it.value.contains(pointer) }?.key ?: return
        if (target == key) return
        val from = order.indexOfFirst { it.key == key }
        val to = order.indexOfFirst { it.key == target }
        if (from < 0 || to < 0) return
        order.add(to, order.removeAt(from))
    }

    // 拖到列表上下边缘时自动滚动：20 个站点一屏放不下，不自动滚就只能拖到当前可见的那一行
    LaunchedEffect(autoScroll, dragKey) {
        if (dragKey == null) return@LaunchedEffect
        while (autoScroll != 0) {
            gridState.scrollBy(autoScroll * edgeSlopPx * 0.35f)
            withFrameNanos { }
            moveDraggedTo(dragPointer)
        }
    }

    val filtered = if (keyword.isBlank()) {
        order.toList()
    } else {
        order.filter {
            it.key.contains(keyword, ignoreCase = true) || siteName(it).contains(keyword, ignoreCase = true)
        }
    }
    val currentName = sites.firstOrNull { it.key == currentKey }?.let { siteName(it) } ?: "未选择"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("选择站点", style = MaterialTheme.typography.titleLarge)
            Text(
                "共 ${sites.size} 个站点 · 当前 $currentName" +
                    if (keyword.isBlank()) " · 长按卡片拖动排序" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("搜索站点") },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .padding(top = 12.dp)
                    .onGloballyPositioned { gridBounds = it.boundsInWindow() },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.key }) { site ->
                    val selected = site.key == currentKey
                    val dragging = dragKey == site.key
                    val itemShape = RoundedCornerShape(10.dp)
                    // "拿起来"的程度做成 0→1 的动画量：底色、阴影、缩放一起过渡，
                    // 之前是二值切换，按下/松手那一下会硬跳一下（生硬）
                    val lift by animateFloatAsState(
                        targetValue = if (dragging) 1f else 0f,
                        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                        label = "site-lift",
                    )
                    val scheme = MaterialTheme.colorScheme
                    // 抬起态要"实心 + 阴影"：卡片平时是透明底的，只加阴影/放大挡不住阴影层，
                    // 会透出一块白板。所以按状态换底色与描边，让抬起的那张看起来是张真卡片。
                    val restingColor = if (selected) scheme.primary.copy(alpha = 0.08f) else Color.Transparent
                    val containerColor = lerp(restingColor, scheme.surfaceContainerHighest, lift)
                    val borderColor = lerp(
                        if (selected) scheme.primary else scheme.outlineVariant,
                        scheme.primary,
                        lift,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 换位时让卡片滑动过去，而不是瞬移
                            .animateItem(placementSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing))
                            // 浮到最上层
                            .zIndex(if (dragging) 1f else 0f)
                            // 阴影与缩放放在同一个 graphicsLayer 里：分成两层时阴影按未缩放的
                            // 边界绘制，会被裁成一条贴在卡片边上的白边
                            .graphicsLayer {
                                val scale = 1f + 0.03f * lift
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = 8.dp.toPx() * lift
                                shape = itemShape
                            }
                            // 位置要按窗口坐标记：抽屉在独立的 Dialog 窗口里，局部坐标对不上手指
                            .onGloballyPositioned { bounds[site.key] = it.boundsInWindow() }
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = borderColor,
                                shape = itemShape,
                            )
                            .background(containerColor, itemShape)
                            // 长按拖动排序：只在不搜索时启用——搜索结果只是列表的子集，拖它无法表达整体顺序
                            .pointerInput(site.key, keyword.isBlank()) {
                                if (keyword.isNotBlank()) return@pointerInput
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { local ->
                                        val box = bounds[site.key]
                                        if (box != null) {
                                            dragKey = site.key
                                            dragPointer = box.topLeft + local
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (dragKey == site.key) {
                                            dragPointer += amount
                                            moveDraggedTo(dragPointer)
                                            autoScroll = when {
                                                gridBounds.height <= 0f -> 0
                                                dragPointer.y < gridBounds.top + edgeSlopPx -> -1
                                                dragPointer.y > gridBounds.bottom - edgeSlopPx -> 1
                                                else -> 0
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        val changed = dragKey != null &&
                                            order.map { it.key } != sites.map { it.key }
                                        dragKey = null
                                        autoScroll = 0
                                        if (changed) onReorder(order.map { it.key })
                                    },
                                    onDragCancel = {
                                        dragKey = null
                                        autoScroll = 0
                                    },
                                )
                            }
                            .clickable { onPick(site.key) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 站点图标：优先配置里的 favicon；没配/取不到/解不开时才画首字色块。
                        // 色块不能垫在图标下面——图标（ico/png/svg）常有透明背景，会透出字母。
                        val faviconModel = rememberSiteFaviconModel(site)
                        if (faviconModel == null) {
                            SiteLetter(site)
                        } else {
                            SubcomposeAsyncImage(
                                model = faviconModel,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                                loading = { SiteLetter(site) },
                                error = { SiteLetter(site) },
                            )
                        }
                        Text(
                            siteName(site),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 10.dp),
                        )
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "当前",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 页码抽屉：右上角点页码 → 选页。
 *
 * 分页条从列表底部移到抽屉，列表区不再被挤压。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagePickerSheet(
    pages: List<Pagination>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("选择页码", style = MaterialTheme.typography.titleLarge)
            Text(
                "共 ${pages.size} 页",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(pages) { page ->
                    val isCurrent = page.isCurrent
                    Surface(
                        onClick = { if (page.url.isNotBlank()) onPick(page.url) },
                        enabled = page.url.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.aspectRatio(2.2f),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                page.title.ifEmpty { "·" },
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 标签抽屉：首页顶栏「标签」图标 → 选标签。
 *
 * 标签入口不再占一个底栏 Tab：它属于首页的一次「换一批内容」，放在顶栏更贴近
 * 分页/筛选这两个同类动作。`url` 为空的项是配置给的占位（见 ui-implementation §5），
 * 渲染但不可点；当前标签高亮，并给出「全部」用于回到首页全量列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagPickerSheet(
    tags: List<com.ghs.mobile.core.model.Tag>,
    /** 当前列表来自哪个标签；null 表示首页全量列表 */
    currentTitle: String?,
    onPickHome: () -> Unit,
    onPick: (com.ghs.mobile.core.model.Tag) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clickableCount = tags.count { it.url.isNotBlank() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("选择标签", style = MaterialTheme.typography.titleLarge)
            Text(
                if (currentTitle == null) "共 $clickableCount 个标签" else "当前：$currentTitle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 只在「已经按标签筛过」时给回去的入口，避免首页上多一个无意义的同义项
                if (currentTitle != null) {
                    item(key = "home") {
                        Surface(
                            onClick = onPickHome,
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                        ) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text("全部", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                // 不按「标题#地址」做 key：配置可以返回多个空地址占位项（同名、同空地址），
                // 那样 key 会重复，LazyGrid 直接抛 "Key was already used" 崩掉
                itemsIndexed(tags, key = { index, _ -> index }) { index, tag ->
                    val placeholder = tag.url.isBlank()
                    val selected = !placeholder && tag.title == currentTitle
                    Surface(
                        onClick = { if (!placeholder) onPick(tag) },
                        enabled = !placeholder,
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            selected -> MaterialTheme.colorScheme.primary
                            placeholder -> Color.Transparent
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        border = if (placeholder) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                tag.title.ifEmpty { "·" },
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    selected -> MaterialTheme.colorScheme.onPrimary
                                    placeholder -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 站点显示名：优先配置里的 name，缺失时用 key */
private fun siteName(store: ConfigStore.StoredConfig): String {
    val name = Regex("""\bname\s*:\s*['"]([^'"]+)['"]""").find(store.source)?.groupValues?.get(1)
    return name?.takeIf { it.isNotBlank() } ?: store.key
}

/** 站点图标：读取配置里的 favicon（规范 §3 的字段），空串表示没有 */
internal fun siteFavicon(store: ConfigStore.StoredConfig): String =
    Regex("""\bfavicon\s*:\s*['"]([^'"]+)['"]""")
        .find(store.source)?.groupValues?.get(1)?.trim().orEmpty()

/** 站点首页 origin：图标请求拿它当 Referer（站点常按 Referer 拦静态资源） */
private fun siteOrigin(store: ConfigStore.StoredConfig): String? =
    Regex("""\bhomeUrl\s*:\s*['"]([^'"]+)['"]""")
        .find(store.source)?.groupValues?.get(1)?.trim()
        ?.let { ImageReferer.originOf(it) }

/**
 * 站点图标的 Coil 请求。
 *
 * Referer 用**这个站点自己的**首页地址，而不是当前站点的：站点选择器里会同时加载
 * 十几个站点的图标，用当前站点的 Referer 会被目标站点的防盗链拦掉。
 */
@Composable
internal fun rememberSiteFaviconModel(store: ConfigStore.StoredConfig): ImageRequest? {
    val url = siteFavicon(store)
    if (url.isEmpty()) return null
    val context = LocalContext.current
    val referer = siteOrigin(store)
    return remember(url, referer, context) {
        ImageRequest.Builder(context)
            .data(url)
            // 图标是装饰：取不到就画首字色块。别让它的 403 触发图片兜底里的 WebView 挑战流程，
            // 否则只是打开「选择站点」，就会给某个没打开的站点弹一次人机校验。
            .setParameter(SkipImageFallbackKey, true)
            .apply { if (!referer.isNullOrBlank()) setHeader("Referer", referer) }
            .build()
    }
}

private fun siteColor(key: String): Color {
    val palette = listOf(Color(0xFF5B7F95), Color(0xFF8A6E9B), Color(0xFF9B7E6E), Color(0xFF6E9B88))
    val hash = key.hashCode()
    return palette[(if (hash < 0) -hash else hash) % palette.size]
}

/** 站点首字色块：没有配图标、或图标取不到/解不开时的占位 */
@Composable
private fun SiteLetter(site: ConfigStore.StoredConfig) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(siteColor(site.key), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            siteName(site).take(1),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
