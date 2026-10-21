package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.ItemType
import com.ghs.mobile.core.model.ReaderKind
import com.ghs.mobile.core.model.ReadingProgress

/**
 * 条目卡片。
 *
 * 两种排布共用同一个组件：
 * - [ItemCardLayout.GRID]：封面在上、标题/星标在下（多列时使用）
 * - [ItemCardLayout.LIST]：缩略图在左、文字在右（单列时使用）
 */
enum class ItemCardLayout { GRID, LIST }

@Composable
fun ItemCard(
    item: Item,
    progress: ReadingProgress?,
    collected: Boolean,
    layout: ItemCardLayout,
    onClick: () -> Unit,
    onToggleCollect: () -> Unit,
    subtitleOverride: String? = null,
) {
    when (layout) {
        ItemCardLayout.GRID -> GridCard(item, progress, collected, subtitleOverride, onClick, onToggleCollect)
        ItemCardLayout.LIST -> ListCard(item, progress, collected, subtitleOverride, onClick, onToggleCollect)
    }
}

@Composable
private fun GridCard(
    item: Item,
    progress: ReadingProgress?,
    collected: Boolean,
    subtitleOverride: String?,
    onClick: () -> Unit,
    onToggleCollect: () -> Unit,
) {
    Card(onClick = onClick) {
        Column {
            Cover(item, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StarToggle(collected = collected, onToggle = onToggleCollect)
            }
            Text(
                subtitleOverride ?: describe(item, progress),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun ListCard(
    item: Item,
    progress: ReadingProgress?,
    collected: Boolean,
    subtitleOverride: String?,
    onClick: () -> Unit,
    onToggleCollect: () -> Unit,
) {
    Card(onClick = onClick) {
        Row(modifier = Modifier.fillMaxWidth().height(112.dp)) {
            Cover(item, Modifier.width(150.dp).fillMaxSize())
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleOverride ?: describe(item, progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.take(2).forEach { tag ->
                        Text(
                            tag.title,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            StarToggle(collected = collected, onToggle = onToggleCollect)
        }
    }
}

/** 封面：有地址就真实加载，失败/为空退回字母占位 */
@Composable
private fun Cover(item: Item, modifier: Modifier) {
    val cover = item.coverImg
    if (cover.isNotEmpty()) {
        SubcomposeAsyncImage(
            model = cover,
            contentDescription = item.title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            loading = { Box(modifier) { CoverPlaceholder(item.title, modifier = Modifier.fillMaxSize()) } },
            error = { Box(modifier) { CoverPlaceholder(item.title, modifier = Modifier.fillMaxSize()) } },
        )
    } else {
        CoverPlaceholder(item.title, modifier = modifier)
    }
}

@Composable
private fun StarToggle(collected: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, modifier = Modifier.padding(2.dp)) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = if (collected) "取消收藏" else "收藏",
            tint = if (collected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

private fun describe(item: Item, progress: ReadingProgress?): String =
    progress?.let { describeProgress(it) } ?: readableType(item)

@Composable
fun CoverPlaceholder(title: String, modifier: Modifier = Modifier) {
    val palette = listOf(Color(0xFF5B7F95), Color(0xFF8A6E9B), Color(0xFF9B7E6E), Color(0xFF6E9B88))
    val tint = remember(title) {
        val hash = title.hashCode()
        palette[(if (hash < 0) -hash else hash) % palette.size]
    }
    Box(
        modifier = modifier.background(
            Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.45f))),
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (title.isNotEmpty()) {
            Text(
                title.take(1),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White.copy(alpha = 0.9f),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

fun readableType(item: Item): String = when (item.type) {
    ItemType.COMIC -> "图集"
    ItemType.TEXT -> "文本"
    ItemType.VIDEO -> "视频"
    else -> "条目"
}

/**
 * 进度描述：文本按"章"，图集/视频按"页"。
 *
 * 文本的 `chapterCount` 常常未知（配置不返回章节总数），这时不显示分母，
 * 否则会出现"第 2 / 1 章"这种明显错误的文案。
 */
fun describeProgress(progress: ReadingProgress): String = when (progress.kind) {
    ReaderKind.TEXT ->
        if (progress.chapterCount > 0) {
            "读到第 ${progress.chapterIndex + 1} / ${progress.chapterCount} 章"
        } else {
            "读到第 ${progress.chapterIndex + 1} 章"
        }

    ReaderKind.IMAGE ->
        "读到第 ${(progress.chapterOffset + 1).coerceAtMost(progress.chapterCount.coerceAtLeast(1))} / " +
            "${progress.chapterCount.coerceAtLeast(1)} 页"

    ReaderKind.VIDEO -> "上次看到 ${progress.percent.toInt()}%"
}

/** 空态占位（收藏/历史为空时） */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
