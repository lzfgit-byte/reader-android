package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CardDefaults
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext

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
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            // 图片贴边铺满：核心是让封面尽量大
            Box {
                Cover(item, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                // 配置返回的 tag 悬浮在封面左上角
                if (item.tags.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item.tags.take(3).forEach { tag ->
                            Text(
                                tag.title,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .background(Color(0xB3000000), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 0.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1A1A1A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).basicMarquee(),
                )
                StarToggle(collected = collected, onToggle = onToggleCollect)
            }
            // 首页/收藏不显示进度副标题（只在历史页用传进来的时间）
            if (subtitleOverride != null) {
                Text(
                    subtitleOverride,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
                )
            }
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
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(112.dp)) {
            Cover(item, Modifier.width(150.dp).fillMaxSize())
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1A1A1A),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
                if (subtitleOverride != null) {
                    Text(
                        subtitleOverride,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

/** 封面：有地址就真实加载，失败时给出「重新加载」 */
@Composable
private fun Cover(item: Item, modifier: Modifier) {
    val cover = item.coverImg
    if (cover.isEmpty()) {
        CoverPlaceholder(item.title, modifier = modifier)
        return
    }
    // 重试计数作为 key：重建组件才会重新发起请求（同一 model 不会自动重试）
    var retryCount by remember(cover) { mutableIntStateOf(0) }
    androidx.compose.runtime.key(retryCount) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(cover)
                .setParameter("retry", retryCount, null)
                .build(),
            contentDescription = item.title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            loading = { Box(modifier) { CoverPlaceholder(item.title, modifier = Modifier.fillMaxSize()) } },
            error = {
                Box(modifier) {
                    CoverPlaceholder(item.title, modifier = Modifier.fillMaxSize())
                    TextButton(
                        onClick = { retryCount += 1 },
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text("重新加载", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
        )
    }
}

@Composable
private fun StarToggle(collected: Boolean, onToggle: () -> Unit) {
    // 点一下弹一下：收藏放大回弹，取消缩小回弹
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(collected) {
        if (collected) {
            bounce.animateTo(1.3f, androidx.compose.animation.core.tween(120))
            bounce.animateTo(1f, androidx.compose.animation.core.tween(180))
        } else {
            bounce.animateTo(0.8f, androidx.compose.animation.core.tween(90))
            bounce.animateTo(1f, androidx.compose.animation.core.tween(140))
        }
    }
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (collected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "starTint",
    )
    IconButton(
        onClick = onToggle,
        modifier = Modifier
            .padding(2.dp)
            .graphicsLayer {
                scaleX = bounce.value
                scaleY = bounce.value
            },
    ) {
        Icon(
            imageVector = Icons.Default.Star,
            contentDescription = if (collected) "取消收藏" else "收藏",
            tint = tint,
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
