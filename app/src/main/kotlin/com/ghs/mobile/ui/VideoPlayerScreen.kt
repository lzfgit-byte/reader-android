package com.ghs.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ghs.mobile.GhsApp
import com.ghs.mobile.core.model.AnalysisDetail
import com.ghs.mobile.core.model.Detail
import kotlinx.coroutines.delay

/** 把毫秒格式化成 `12:34` / `1:02:03` */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 视频播放器：Media3（ExoPlayer）+ HLS，控制条用官方 `media3-ui` 的 PlayerView。
 *
 * - 进入即播；清晰度、线路、选集都能就地切换
 * - 手势：单击显隐控制条，双击右半区快进 10s、左半区后退 10s
 * - 全屏（横屏 + 隐藏系统栏）、锁屏（屏蔽手势防误触）
 * - 续看：有历史进度时提示「上次看到 mm:ss」，点「快进」才跳
 *
 * 换集/换线路时按当前地址重置全部播放态，并**把新播放器塞回 PlayerView**
 * （`AndroidView` 的 factory 只跑一次，不 update 会一直挂着已 release 的旧实例，
 * 表现为"切换剧集后画面冻住"）。
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
    onPlayEpisode: (lineUrl: String, episodeIndex: Int) -> Unit = { _, _ -> },
    loadEpisodes: suspend (lineUrl: String) -> List<AnalysisDetail> = { emptyList() },
    onProgress: (sourceUrl: String, positionMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()

    var index by remember(startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0)))
    }
    val currentUrl = sources.getOrNull(index)?.url.orEmpty()

    // ---- 全部按当前地址重置：换集/换线路后不能沿用上一集的状态 ----
    var error by remember(currentUrl) { mutableStateOf<String?>(null) }
    var menuOpen by remember(currentUrl) { mutableStateOf(false) }
    var sheet by remember(currentUrl) { mutableStateOf(PlayerSheet.NONE) }
    var locked by remember(currentUrl) { mutableStateOf(false) }
    var fullscreen by remember(currentUrl) { mutableStateOf(false) }
    var controllerVisible by remember(currentUrl) { mutableStateOf(true) }
    var resumeMs by remember(currentUrl) { mutableLongStateOf(startPositionMs) }
    var pendingResume by remember(currentUrl) {
        mutableLongStateOf(resumePositionMs.takeIf { it > 5_000 } ?: 0L)
    }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

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

    // 全屏：横屏 + 隐藏系统栏
    LaunchedEffect(fullscreen) {
        activity?.requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    // 离开播放器时恢复竖屏与系统栏
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
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
                    playerViewRef = this
                }
            },
            update = { view ->
                // 换集后必须把新播放器交给 PlayerView，否则画面停留在旧实例
                view.player = player
                playerViewRef = view
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层：单击显隐控制条，双击左右分别后退/快进 10s；锁屏时全部忽略
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = {
                            if (!locked) {
                                controllerVisible = !controllerVisible
                                playerViewRef?.let { view ->
                                    if (controllerVisible) view.showController() else view.hideController()
                                }
                            }
                        },
                        onDoubleTap = { offset ->
                            if (!locked) {
                                val width = size.width
                                when {
                                    offset.x > width * 0.55f -> {
                                        val duration = player.duration
                                        val limit = if (duration > 0) duration else Long.MAX_VALUE
                                        player.seekTo((player.currentPosition + 10_000).coerceAtMost(limit))
                                    }
                                    offset.x < width * 0.45f -> {
                                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                                    }
                                }
                                playerViewRef?.showController()
                            }
                        },
                    )
                },
        )

        if (!locked) {
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
                        val label = listOf(
                            ctx.currentLine?.title.orEmpty(),
                            ctx.currentEpisode?.title.orEmpty(),
                        ).filter { it.isNotBlank() }.joinToString(" · ")
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
                    TextButton(onClick = { sheet = PlayerSheet.SWITCHER }) { Text("线路 / 选集") }
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
                                        Text((source.quality.ifEmpty { "默认" }) + if (i == index) " · 当前" else "")
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
        } else {
            IconButton(
                onClick = {
                    locked = false
                    playerViewRef?.showController()
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(8.dp)
                    .size(40.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                Icon(Icons.Default.Lock, contentDescription = "解锁", tint = Color.White)
            }
        }

        // 锁定：左侧边缘、垂直居中（主流播放器习惯，避免误触前进度条）
        if (!locked) {
            IconButton(
                onClick = {
                    locked = true
                    playerViewRef?.hideController()
                },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                Icon(Icons.Default.Lock, contentDescription = "锁定", tint = Color.White)
            }
        }

        // 全屏：右下角（播放控制区，与清晰度同类）
        if (!locked) {
            TextButton(
                onClick = { fullscreen = !fullscreen },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 56.dp),
            ) {
                Text(
                    if (fullscreen) "退出全屏" else "全屏",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        // 续看提示
        if (pendingResume > 0 && !locked) {
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
                    TextButton(onClick = {
                        player.seekTo(pendingResume)
                        pendingResume = 0L
                        playerViewRef?.showController()
                    }) { Text("快进") }
                    TextButton(onClick = {
                        player.seekTo(0)
                        pendingResume = 0L
                    }) { Text("从头") }
                }
            }
        }

        if (error != null && !locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无法播放", color = Color(0xFFE6E6EA), style = MaterialTheme.typography.titleSmall)
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
                    TextButton(onClick = { sheet = PlayerSheet.SWITCHER }) { Text("换线路") }
                }
            }
        }
    }

    // 线路 Tab → 该线路剧集（与剧集详情页同一组件）
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
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                )
                LineEpisodeTabs(
                    analysis = series.analysis,
                    initialLineUrl = series.lineUrl,
                    loadEpisodes = loadEpisodes,
                    currentLineUrl = series.lineUrl,
                    currentEpisodeIndex = series.currentIndex,
                    onPick = { lineUrl, episodeIndex ->
                        sheet = PlayerSheet.NONE
                        if (lineUrl == series.lineUrl) {
                            onSelectEpisode(episodeIndex)
                        } else {
                            onPlayEpisode(lineUrl, episodeIndex)
                        }
                    },
                    maxGridHeight = 320,
                )
            }
        }
    }
}

private enum class PlayerSheet { NONE, SWITCHER }
