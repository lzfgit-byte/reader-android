package com.ghs.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.ReadingProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史条目：条目 + 记录时间 */
data class HistoryEntry(val item: Item, val createTime: Long)

/** 收藏页：与列表同样的卡片，可直接取消收藏 */
@Composable
fun FavoritesScreen(
    items: List<Item>,
    progress: Map<String, ReadingProgress>,
    collected: Set<String>,
    onOpen: (Item) -> Unit,
    onToggleCollect: (Item) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyHint("还没有收藏 · 在列表点 ♡ 即可收藏")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
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
                onClick = { onOpen(item) },
                onToggleCollect = { onToggleCollect(item) },
            )
        }
    }
}

/** 历史页：单列，展示标题、类型与浏览时间 */
@Composable
fun HistoryScreen(
    entries: List<HistoryEntry>,
    onOpen: (Item) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyHint("还没有浏览记录 · 打开任意条目后会记录在这里")
        return
    }
    val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.item.jumpUrl }) { entry ->
            Card(onClick = { onOpen(entry.item) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.item.title.ifEmpty { entry.item.jumpUrl },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${readableType(entry.item)} · ${formatter.format(Date(entry.createTime))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
