package com.ghs.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn

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

/** 双击分区的边界：左右各 40% 是快退/快进，中间 20% 是播放暂停 */
private const val EDGE_TAP_THRESHOLD = 0.4f

/** 连击累加窗口：900ms 内同方向连续双击，每次再进 10s */
private const val SMART_SEEK_WINDOW_MS = 900L

/** 每步快进/快退毫秒数 */
private const val SEEK_STEP_MS = 10_000L

/** 控制层自动隐藏时间 */
private const val CONTROLS_HIDE_MS = 3_500L

/**
 * 视频播放器：Media3（ExoPlayer）+ HLS，控制层自绘（**不用 PlayerView 自带控制器**）。
 *
 * 布局与手势对齐主流播放器：
 * - 顶栏：返回、标题、「线路 · 第 N 集」、线路/选集
 * - 底栏：进度条 + 时间，以及 上一集 / 播放暂停 / 下一集 / 清晰度 / 全屏
 * - 左侧边缘：锁屏；锁后单击只切换锁按钮显隐
 * - 手势：单击显隐控制层（3.5s 自动隐藏）；双击左 40% 快退、右 40% 快进、中间 20% 播放暂停；
 *   900ms 内连续双击同方向按 10s 累加
 *
 * 换集/换线路时按当前地址重置全部播放态，并**把新播放器塞回 PlayerView**
 * （`AndroidView` 的 factory 只跑一次，不 update 会一直挂着已 release 的旧实例）。
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
    /** 传入时底栏显示上一集/下一集 */
    onPrevEpisode: (() -> Unit)? = null,
    onNextEpisode: (() -> Unit)? = null,
    onSelectEpisode: (Int) -> Unit = {},
    onPlayEpisode: (lineUrl: String, episodeIndex: Int) -> Unit = { _, _ -> },
    loadEpisodes: suspend (lineUrl: String) -> List<AnalysisDetail> = { emptyList() },
    onProgress: (sourceUrl: String, positionMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val activity = context.findActivity()

    var index by remember(startIndex) {
        mutableIntStateOf(startIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0)))
    }
    val currentUrl = sources.getOrNull(index)?.url.orEmpty()

    var error by remember(currentUrl) { mutableStateOf<String?>(null) }
    var menuOpen by remember(currentUrl) { mutableStateOf(false) }
    var sheet by remember(currentUrl) { mutableStateOf(PlayerSheet.NONE) }
    var locked by remember(currentUrl) { mutableStateOf(false) }
    var lockButtonVisible by remember(currentUrl) { mutableStateOf(true) }
    var fullscreen by remember(currentUrl) { mutableStateOf(false) }
    var controlsVisible by remember(currentUrl) { mutableStateOf(true) }
    var resumeMs by remember(currentUrl) { mutableLongStateOf(startPositionMs) }
    var pendingResume by remember(currentUrl) {
        mutableLongStateOf(resumePositionMs.takeIf { it > 5_000 } ?: 0L)
    }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    // 长按倍速是否生效中
    var boostActive by remember(currentUrl) { mutableStateOf(false) }
    // 评论面板：显示当前视频源自带的评论
    var commentsOpen by remember(currentUrl) { mutableStateOf(false) }
    val currentComments = sources.getOrNull(index)?.comments.orEmpty()

    // 交给系统播放器 / 浏览器打开：mp4 直链在应用内解不了时的兜底
    fun openExternally() {
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(currentUrl),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // 播放进度镜像：给进度条与时间显示用
    var positionMs by remember(currentUrl) { mutableLongStateOf(0L) }
    var durationMs by remember(currentUrl) { mutableLongStateOf(0L) }
    var playing by remember(currentUrl) { mutableStateOf(false) }
    var buffering by remember(currentUrl) { mutableStateOf(true) }
    var dragging by remember(currentUrl) { mutableStateOf(false) }
    var dragPosition by remember(currentUrl) { mutableLongStateOf(0L) }

    // 硬件返回键：交给播放器自己的返回逻辑，避免直接退出应用
    androidx.activity.compose.BackHandler {
        if (locked) {
            locked = false
        } else {
            onBack()
        }
    }

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
                        com.ghs.mobile.core.engine.net.UserAgents.DESKTOP_CHROME,
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
                // mp4 直链在应用内解不了时交给系统播放器，避免只剩一张错误页
                val path = currentUrl.substringBefore("?").lowercase()
                if (path.endsWith(".mp4")) openExternally()
            }
        }
        player.addListener(listener)
        onDispose {
            if (pendingResume == 0L && player.currentPosition > 0) {
                runCatching { onProgress(currentUrl, player.currentPosition, player.duration.coerceAtLeast(0)) }
            }
            player.removeListener(listener)
            player.release()
        }
    }

    // 每 500ms 刷新进度条，同时每 5s 回写一次进度
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            delay(500)
            tick++
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0)
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (tick % 10 == 0 && pendingResume == 0L) {
                onProgress(currentUrl, player.currentPosition, player.duration.coerceAtLeast(0))
            }
        }
    }

    // 控制层自动隐藏
    LaunchedEffect(controlsVisible, playing) {
        if (controlsVisible && playing && pendingResume == 0L) {
            delay(CONTROLS_HIDE_MS)
            controlsVisible = false
        }
    }

    LaunchedEffect(pendingResume) {
        if (pendingResume > 0) {
            delay(12_000)
            pendingResume = 0L
            controlsVisible = false
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
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /** 快进/快退：900ms 内同方向连续双击按步长累加 */
    fun smartSeek(direction: Int) {
        val duration = player.duration.takeIf { it > 0 } ?: durationMs
        val target = (player.currentPosition + SEEK_STEP_MS * direction)
            .coerceAtLeast(0)
            .let { if (duration > 0) it.coerceAtMost(duration) else it }
        player.seekTo(target)
        controlsVisible = true
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
                    useController = false // 控制层由我们自己画
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
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

        // 手势层
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(locked, currentUrl) {
                    var lastSeekAt = 0L
                    var lastSeekDir = 0
                    detectTapGestures(
                        onTap = {
                            if (locked) {
                                lockButtonVisible = !lockButtonVisible
                            } else {
                                controlsVisible = !controlsVisible
                            }
                        },
                        onDoubleTap = { offset ->
                            if (!locked) {
                                val width = size.width
                                val dir = when {
                                    offset.x < width * EDGE_TAP_THRESHOLD -> -1
                                    offset.x > width * (1f - EDGE_TAP_THRESHOLD) -> 1
                                    else -> 0
                                }
                                if (dir == 0) {
                                    if (player.isPlaying) player.pause() else player.play()
                                    controlsVisible = true
                                } else {
                                    val now = System.currentTimeMillis()
                                    val continued = dir == lastSeekDir && now - lastSeekAt <= SMART_SEEK_WINDOW_MS
                                    lastSeekDir = dir
                                    lastSeekAt = now
                                    smartSeek(dir * if (continued) 2 else 1)
                                }
                            }
                        },
                        // 长按 2x 倍速：按住生效，松手恢复
                        onPress = {
                            if (locked) return@detectTapGestures
                            val boostJob = scope.launch {
                                delay(400)
                                player.setPlaybackSpeed(2f)
                                boostActive = true
                            }
                            tryAwaitRelease()
                            boostJob.cancel()
                            if (boostActive) {
                                player.setPlaybackSpeed(1f)
                                boostActive = false
                            }
                        },
                    )
                }
                // 上下滑：左半边调亮度、右半边调音量
                .pointerInput(locked, currentUrl) {
                    var startX = 0f
                    var startBrightness = 0.5f
                    var startVolume = 0
                    val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                    val maxVolume = audio?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 15
                    startVolume = audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0

                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            if (locked) return@detectVerticalDragGestures
                            startX = offset.x
                            val current = activity?.window?.attributes?.screenBrightness ?: -1f
                            startBrightness = if (current < 0f) 0.5f else current
                            startVolume = audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: startVolume
                        },
                        onVerticalDrag = { change, dragAmount ->
                            if (locked) return@detectVerticalDragGestures
                            change.consume()
                            // 向上滑为正 → 亮度/音量增大
                            val ratio = -dragAmount / size.height
                            if (startX < size.width / 2f) {
                                val next = (startBrightness + ratio).coerceIn(0.02f, 1f)
                                startBrightness = next
                                activity?.window?.let { window ->
                                    val attrs = window.attributes
                                    attrs.screenBrightness = next
                                    window.attributes = attrs
                                }
                            } else {
                                val next = (startVolume + ratio * maxVolume * 2).toInt()
                                    .coerceIn(0, maxVolume)
                                if (next != startVolume) {
                                    startVolume = next
                                    runCatching {
                                        audio?.setStreamVolume(
                                            android.media.AudioManager.STREAM_MUSIC,
                                            next,
                                            0,
                                        )
                                    }
                                }
                            }
                        },
                    )
                },
        )

        // 缓冲圈
        if (buffering && error == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(44.dp),
                color = Color(0xFF4DD0E1),
            )
        }

        // 锁按钮：左侧边缘垂直居中
        if (!locked) {
            IconButton(
                onClick = { locked = true; controlsVisible = false },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                LockIcon(locked = false, tint = Color.White)
            }
        } else if (lockButtonVisible) {
            IconButton(
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .background(Color(0x66000000), CircleShape),
            ) {
                LockIcon(locked = true, tint = Color.White)
            }
        }

        if (controlsVisible && !locked) {
            // 顶栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                    .padding(horizontal = 4.dp, vertical = 4.dp),
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
                        modifier = Modifier.basicMarquee(),
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
            }

            // 底栏：进度条 + 控制行
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xCC000000))))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatDuration(if (dragging) dragPosition else positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Slider(
                        value = if (durationMs > 0) {
                            ((if (dragging) dragPosition else positionMs).toFloat() / durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        onValueChange = { fraction ->
                            if (durationMs > 0) {
                                dragging = true
                                dragPosition = (durationMs * fraction).toLong()
                            }
                        },
                        onValueChangeFinished = {
                            if (durationMs > 0) player.seekTo(dragPosition)
                            dragging = false
                            controlsVisible = true
                        },
                        thumb = {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(Color.White, CircleShape),
                            )
                        },
                        track = { state ->
                            val fraction = ((if (dragging) dragPosition else positionMs).toFloat() /
                                durationMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp)),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction)
                                        .height(3.dp)
                                        .background(Color(0xFF7C4DFF), RoundedCornerShape(2.dp)),
                                )
                            }
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    Text(
                        formatDuration(durationMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onPrevEpisode != null) {
                            TextButton(onClick = { onPrevEpisode() }) { Text("上一集") }
                        }
                        IconButton(onClick = { if (playing) player.pause() else player.play() }) {
                            if (playing) {
                                PauseIcon(tint = Color.White)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = "播放", tint = Color.White)
                            }
                        }
                        if (onNextEpisode != null) {
                            TextButton(onClick = { onNextEpisode() }) { Text("下一集") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentComments.isNotEmpty()) {
                            TextButton(onClick = { commentsOpen = true }) {
                                Text(
                                    "评论 ${currentComments.size}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        if (sources.size > 1) {
                            Box {
                                TextButton(onClick = { menuOpen = true }) {
                                    Text(
                                        sources.getOrNull(index)?.quality?.ifEmpty { "清晰度" } ?: "清晰度",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    sources.forEachIndexed { i, source ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    (source.quality.ifEmpty { "默认" }) +
                                                        if (i == index) " · 当前" else "",
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
                        IconButton(onClick = { fullscreen = !fullscreen }) {
                            FullscreenIcon(expanded = fullscreen, tint = Color.White)
                        }
                    }
                }
            }
        }

        // 续看提示
        if (pendingResume > 0 && !locked) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp, start = 16.dp, end = 16.dp),
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
                        controlsVisible = true
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
                    Row {
                        TextButton(onClick = { sheet = PlayerSheet.SWITCHER }) { Text("换线路") }
                        TextButton(onClick = { openExternally() }) { Text("用外部播放器打开") }
                    }
                }
            }
        }
    }

    // 评论：视频源自带的评论列表
    if (commentsOpen && currentComments.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { commentsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text("评论 ${currentComments.size}", style = MaterialTheme.typography.titleSmall)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    currentComments.forEach { comment ->
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            if (comment.datetime.isNotBlank()) {
                                Text(
                                    comment.datetime,
                                    color = Color(0xFF9E9E9E),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                            Text(comment.comment, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
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
                    maxGridHeight = 460,
                )
            }
        }
    }
}

/** 暂停图标（两条竖线），不依赖 material-icons-extended */
@Composable
private fun PauseIcon(tint: Color, boxSize: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(modifier = Modifier.size(boxSize)) {
        val w = size.width
        val barWidth = w * 0.22f
        val top = w * 0.12f
        val bottom = w * 0.88f
        drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, top), size = androidx.compose.ui.geometry.Size(barWidth, bottom - top))
        drawRect(tint, topLeft = androidx.compose.ui.geometry.Offset(w * 0.56f, top), size = androidx.compose.ui.geometry.Size(barWidth, bottom - top))
    }
}

/** 全屏 / 退出全屏图标：四个角括号 */
@Composable
private fun FullscreenIcon(expanded: Boolean, tint: Color, boxSize: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(modifier = Modifier.size(boxSize)) {
        val w = size.width
        val inset = w * if (expanded) 0.32f else 0.02f
        val arm = w * 0.32f
        val stroke = w * 0.12f
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = w - inset
        fun point(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
        drawLine(tint, point(left, top + arm), point(left, top), strokeWidth = stroke)
        drawLine(tint, point(left, top), point(left + arm, top), strokeWidth = stroke)
        drawLine(tint, point(right - arm, top), point(right, top), strokeWidth = stroke)
        drawLine(tint, point(right, top), point(right, top + arm), strokeWidth = stroke)
        drawLine(tint, point(left, bottom - arm), point(left, bottom), strokeWidth = stroke)
        drawLine(tint, point(left, bottom), point(left + arm, bottom), strokeWidth = stroke)
        drawLine(tint, point(right - arm, bottom), point(right, bottom), strokeWidth = stroke)
        drawLine(tint, point(right, bottom), point(right, bottom - arm), strokeWidth = stroke)
    }
}

private enum class PlayerSheet { NONE, SWITCHER }

/**
 * 锁图标：闭合 / 打开两种形态（开锁时锁梁绕锁体顶部倾斜）。
 *
 * `material-icons-extended` 里才有 `LockOpen`，为它引入整套图标库不划算，这里自绘。
 */
@Composable
private fun LockIcon(locked: Boolean, tint: Color, boxSize: androidx.compose.ui.unit.Dp = 22.dp) {
    Canvas(modifier = Modifier.size(boxSize)) {
        val w = size.width
        val stroke = w * 0.10f
        val bodyTop = w * 0.46f

        rotate(
            degrees = if (locked) 0f else 32f,
            pivot = androidx.compose.ui.geometry.Offset(w * 0.50f, bodyTop),
        ) {
            val radius = w * 0.17f
            val cx = w * 0.50f
            val cy = bodyTop - radius
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(cx - radius, cy - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = stroke,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                ),
            )
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(cx - radius, cy),
                end = androidx.compose.ui.geometry.Offset(cx - radius, bodyTop + w * 0.02f),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(cx + radius, cy),
                end = androidx.compose.ui.geometry.Offset(cx + radius, bodyTop + w * 0.02f),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }

        drawRoundRect(
            color = tint,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, bodyTop),
            size = androidx.compose.ui.geometry.Size(w * 0.56f, w * 0.42f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.09f),
        )
    }
}

