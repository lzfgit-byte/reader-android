package com.ghs.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SitePickerSheet(
    sites: List<ConfigStore.StoredConfig>,
    currentKey: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var keyword by remember { mutableStateOf("") }
    val filtered = remember(sites, keyword) {
        if (keyword.isBlank()) sites else sites.filter {
            it.key.contains(keyword, ignoreCase = true) || siteName(it).contains(keyword, ignoreCase = true)
        }
    }
    val currentName = sites.firstOrNull { it.key == currentKey }?.let { siteName(it) } ?: "未选择"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("选择站点", style = MaterialTheme.typography.titleLarge)
            Text(
                "共 ${sites.size} 个站点 · 当前 $currentName",
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
                modifier = Modifier.fillMaxWidth().height(420.dp).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filtered, key = { it.key }) { site ->
                    val selected = site.key == currentKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                width = if (selected) 1.5.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(10.dp),
                            )
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
                                RoundedCornerShape(10.dp),
                            )
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
