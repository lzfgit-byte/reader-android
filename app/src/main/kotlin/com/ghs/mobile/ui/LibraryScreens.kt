package com.ghs.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

/** 历史条目：条目 + 记录时间 */
data class HistoryEntry(val item: Item, val createTime: Long)

/** 收藏页：与首页同样的卡片（单/双列随首页设置） */
@Composable
fun FavoritesScreen(
    items: List<Item>,
    progress: Map<String, ReadingProgress>,
    collected: Set<String>,
    layout: ItemCardLayout,
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
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.jumpUrl }) { item ->
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
    layout: ItemCardLayout,
    onOpen: (Item) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyHint("还没有浏览记录 · 打开任意条目后会记录在这里")
        return
    }
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    val columns = if (layout == ItemCardLayout.LIST) 1 else 2

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.item.jumpUrl }) { entry ->
            ItemCard(
                item = entry.item,
                progress = null,
                collected = false,
                layout = layout,
                onClick = { onOpen(entry.item) },
                onToggleCollect = {},
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
