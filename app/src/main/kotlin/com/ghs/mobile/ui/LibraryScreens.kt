package com.ghs.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.ReadingProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow

/** 历史条目：条目 + 记录时间 */
data class HistoryEntry(val item: Item, val createTime: Long)

/** 收藏页：与首页同样的卡片（单/双列随首页设置） */
@Composable
fun FavoritesScreen(
    items: List<Item>,
    progress: Map<String, ReadingProgress>,
    collected: Set<String>,
    layout: ItemCardLayout,
    /** 由外层持有：进播放器时本页会被销毁，滚动位置必须活在上一层 */
    gridState: LazyGridState,
    onOpen: (Item) -> Unit,
    onToggleCollect: (Item) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyHint("还没有收藏 · 点卡片上的星标即可收藏")
        return
    }
    val columns = if (layout == ItemCardLayout.LIST) 1 else 2
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { item -> item.jumpUrl }) { item ->
            ItemCard(
                item = item,
                progress = progress[item.jumpUrl],
                collected = item.jumpUrl in collected,
                layout = layout,
                onClick = { onOpen(item) },
                onToggleCollect = { onToggleCollect(item) },
            )
        }
    }
}

/** 历史页：同一套卡片，副标题换成浏览时间 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    /** 历史页也能收藏：与收藏页共用同一套星标逻辑 */
    collected: Set<String>,
    progress: Map<String, ReadingProgress>,
    layout: ItemCardLayout,
    gridState: LazyGridState,
    onOpen: (Item) -> Unit,
    onToggleCollect: (Item) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyHint("还没有浏览记录 · 打开任意条目后会记录在这里")
        return
    }
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val columns = if (layout == ItemCardLayout.LIST) 1 else 2

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { entry -> entry.item.jumpUrl }) { entry ->
            ItemCard(
                item = entry.item,
                progress = progress[entry.item.jumpUrl],
                collected = entry.item.jumpUrl in collected,
                layout = layout,
                onClick = { onOpen(entry.item) },
                onToggleCollect = { onToggleCollect(entry.item) },
                subtitleOverride = "${readableType(entry.item)} · ${formatter.format(Date(entry.createTime))}",
            )
        }
    }
}

/** 底部加载状态提示 */
@Composable
fun LoadMoreHint(loading: Boolean, noMore: Boolean) {
    if (!loading && !noMore) return
    Text(
        if (loading) "正在加载更多…" else "没有更多了",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    )
}
/** 分页条：读取 pagination，当前页高亮，点击跳转 */
@Composable
fun PagerBar(
    pages: List<com.ghs.mobile.core.model.Pagination>,
    onJump: (String) -> Unit,
) {
    if (pages.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(pages, key = { index, page -> "${index}#${page.url}${page.title}" }) { _, page ->
            Text(
                page.title.ifEmpty { "·" },
                style = MaterialTheme.typography.labelLarge,
                color = if (page.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(enabled = page.url.isNotBlank()) { onJump(page.url) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
