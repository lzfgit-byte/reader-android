package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.ItemType
import com.ghs.mobile.core.model.ReaderKind
import com.ghs.mobile.core.model.ReadingProgress

/** 条目卡片：封面占位 + 标题 + 标签 + 进度/类型 + 收藏按钮 */
@Composable
fun ItemCard(
    item: Item,
    progress: ReadingProgress?,
    collected: Boolean,
    onClick: () -> Unit,
    onToggleCollect: () -> Unit,
) {
    Card(onClick = onClick) {
        Column {
            Box {
                CoverPlaceholder(title = item.title)
                IconButton(
                    onClick = onToggleCollect,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                ) {
                    Icon(
                        imageVector = if (collected) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (collected) "取消收藏" else "收藏",
                        tint = if (collected) MaterialTheme.colorScheme.error else Color.White,
                    )
                }
            }
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
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
                Text(
                    progress?.let { describeProgress(it) } ?: readableType(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun CoverPlaceholder(title: String) {
    val tint = remember(title) {
        val palette = listOf(Color(0xFF5B7F95), Color(0xFF8A6E9B), Color(0xFF9B7E6E), Color(0xFF6E9B88))
        palette[title.hashCode().let { if (it < 0) -it else it } % palette.size]
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .background(Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.take(1),
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold,
        )
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

    ReaderKind.VIDEO -> "上次看到 ${(progress.percent).toInt()}%"
}

/** 空态占位（收藏/历史为空时） */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
