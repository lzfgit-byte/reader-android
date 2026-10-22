package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ghs.mobile.GhsApp
import com.ghs.mobile.core.model.Detail
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow

/** 把毫秒格式化成 `12:34` / `1:02:03` */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 视频播放器：Media3（ExoPlayer）+ HLS，控制条用官方 `media3-ui` 的 PlayerView。
 *
 * - 进入即播：地址由详情/剧集解析阶段拿到，这里不再有中间列表页
 * - 多清晰度：顶栏菜单就地切换，保留当前播放位置
 * - 剧集：顶栏「选集」「线路」可直接切换，不必退回列表
 * - 续看：有历史进度时先提示"上次看到 mm:ss"，用户点「快进」才跳转
 * - 每 5 秒与退出时回写进度；失败显示错误码与地址
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    title: String,
    sources: List<Detail>,
    startIndex: Int,
    startPositionMs: Long,
    /** 资源站点 origin：很多 CDN 校验 Referer，缺了会返回 403 */
    referer: String? = null,
    /** 上次看到的位置；> 0 时先显示"继续观看"提示，由用户决定是否快进 */
    resumePositionMs: Long = 0L,
    /** 剧集上下文；null 表示这不是剧集（或没有分段信息） */
    series: SeriesPlayback? = null,
    onSelectEpisode: (Int) -> Unit = {},
    onSelectLine: (String) -> Unit = {},
    onProgress: (sourceUrl: String, positionMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0))) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(PlayerSheet.NONE) }
    // 以「线路」为主 Tab：切换线路后自动停在同一集
    var sheetTab by remember { mutableStateOf(SHEET_TAB_LINES) }

    // 有历史进度时先不自动跳，等用户点「快进」
    var pendingResume by remember { mutableStateOf(resumePositionMs.takeIf { it > 5_000 } ?: 0L) }
    var resumeApplied by remember { mutableStateOf(false) }
    // 切清晰度时保留播放位置
    var resumeMs by remember { mutableStateOf(startPositionMs) }

    val currentUrl = sources.getOrNull(index)?.url.orEmpty()

    val player = remember(currentUrl) {
        // 视频 CDN 常拦截代理出口 IP（直连 200、走代理 403）：403 时直连重试一次
        val baseClient = GhsApp.instance.proxiedClient()
        val directClient = baseClient.newBuilder()
            .proxy(java.net.Proxy.NO_PROXY)
            .build()
        val mediaClient = baseClient.newBuilder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 403) {
                    response.close()
                    directClient.newCall(chain.request()).execute()
                } else {
                    response
                }
            }
            .build()
        val httpFactory = OkHttpDataSource.Factory(mediaClient)
            .setDefaultRequestProperties(
                buildMap {
                    referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
                    put(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
                    )
                },
            )
        val dataSource = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(MediaItem.fromUri(currentUrl)))
            prepare()
            // 有待确认的续看位置时从 0 开始；用户点「快进」再跳
            if (resumeMs > 0 && pendingResume == 0L) seekTo(resumeMs)
            playWhenReady = true
        }
    }

    DisposableEffect(player, currentUrl) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playerError: PlaybackException) {
                error = playerError.errorCodeName + " · " + (playerError.message ?: "")
            }
        }
        player.addListener(listener)
        onDispose {
            // 续看提示还没处理时不要回写：播放器停在 0，会把上次位置冲掉
            if (pendingResume == 0L && player.currentPosition > 0) {
                runCatching { onProgress(currentUrl, player.currentPosition, player.duration.coerceAtLeast(0)) }
            }
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            // 续看提示还没处理时不要回写：播放器停在 0，会把上次位置冲掉
            if (pendingResume == 0L) {
                onProgress(currentUrl, player.currentPosition, player.duration.coerceAtLeast(0))
            }
        }
    }

    // 提示 12 秒后自动消失（用户没点就从头播）
    LaunchedEffect(pendingResume) {
        if (pendingResume > 0) {
            delay(12_000)
            pendingResume = 0L
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 顶栏：返回 + 标题 + 线路 + 选集 + 清晰度
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                series?.let { ctx ->
                    val line = ctx.currentLine?.title.orEmpty()
                    val episode = ctx.currentEpisode?.title.orEmpty()
                    val label = listOf(line, episode).filter { it.isNotBlank() }.joinToString(" · ")
                    if (label.isNotBlank()) {
                        Text(
                            label,
                            color = Color(0xFFBDBDBD),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (series != null) {
                TextButton(onClick = {
                    sheetTab = SHEET_TAB_LINES
                    sheet = PlayerSheet.SWITCHER
                }) { Text("线路 / 选集") }
            }
            if (sources.size > 1) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.List, contentDescription = "清晰度", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        sources.forEachIndexed { i, source ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        (source.quality.ifEmpty { "默认" }) + if (i == index) " · 当前" else "",
                                    )
                                },
                                onClick = {
                                    menuOpen = false
                                    if (i != index) {
                                        resumeMs = player.currentPosition
                                        error = null
                                        index = i
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        // 续看提示
        if (pendingResume > 0) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp, start = 16.dp, end = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xE6222226)),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "上次看到 ${formatDuration(pendingResume)}",
                        color = Color(0xFFE6E6EA),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            player.seekTo(pendingResume)
                            resumeApplied = true
                            pendingResume = 0L
                        },
                    ) { Text("快进") }
                    TextButton(
                        onClick = {
                            player.seekTo(0)
                            pendingResume = 0L
                        },
                    ) { Text("从头") }
                }
            }
        }

        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "无法播放",
                        color = Color(0xFFE6E6EA),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        error.orEmpty(),
                        color = Color(0xFFFF8A80),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        currentUrl,
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = {
                        sheetTab = SHEET_TAB_LINES
                        sheet = PlayerSheet.SWITCHER
                    }) { Text("换线路") }
                }
            }
        }
    }

    // 线路 / 选集抽屉
    // 线路 / 选集合并成一个抽屉：Tab 切换，换线路时保留当前集
    if (sheet != PlayerSheet.NONE && series != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = PlayerSheet.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
                TabRow(selectedTabIndex = sheetTab) {
                    Tab(
                        selected = sheetTab == SHEET_TAB_LINES,
                        onClick = { sheetTab = SHEET_TAB_LINES },
                        text = { Text("线路 · ${series.analysis.size}") },
                    )
                    Tab(
                        selected = sheetTab == SHEET_TAB_EPISODES,
                        onClick = { sheetTab = SHEET_TAB_EPISODES },
                        text = { Text("选集 · ${series.episodes.size}") },
                    )
                }

                if (sheetTab == SHEET_TAB_LINES) {
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        items(series.analysis) { line ->
                            val current = line.url == series.lineUrl
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        sheet = PlayerSheet.NONE
                                        if (!current) onSelectLine(line.url)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    line.title.ifEmpty { line.url },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (current) {
                                    Text(
                                        "当前",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    Text(
                                        if (line.total > 0) "${line.total} 集" else "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    ) {
                        itemsIndexed(series.episodes) { i, episode ->
                            val current = i == series.currentIndex
                            Card(
                                onClick = {
                                    sheet = PlayerSheet.NONE
                                    if (!current) onSelectEpisode(i)
                                },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (current) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        episode.title.ifEmpty { "${i + 1}" },
                                        style = MaterialTheme.typography.titleSmall,
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
    }
}

/** 抽屉里的两个 Tab 下标 */
private const val SHEET_TAB_LINES = 0
private const val SHEET_TAB_EPISODES = 1

private enum class PlayerSheet { NONE, SWITCHER }
