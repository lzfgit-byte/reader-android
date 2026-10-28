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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
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

/** 长按多久算“按住加速”（太短会把单击误判成倍速） */
private const val LONG_PRESS_MS = 350L

/** 隐藏态细进度条的高度：固定值，别让它随内容/inset 被拉伸 */
private val HIDDEN_BAR_HEIGHT = 3.dp

/** 播放器里可选的快进/快退倍速 */
private val PLAY_RATE_OPTIONS = listOf(1.5f, 2f, 3f, 4f)

/** 自动重连（重拨）最多试几次；三次仍失败就把决定权交回用户 */
private const val AUTO_REDIAL_MAX = 3

/** 每次重拨前的等待：2s / 4s / 8s，避开"刚断就重连"的抖动窗口 */
private val AUTO_REDIAL_DELAYS_MS = longArrayOf(2_000L, 4_000L, 8_000L)

/**
 * 哪些播放错误值得自动重拨。
 *
 * 只重连**连接/传输**这一类：网络断开、连接超时、HTTP 4xx/5xx、直播落后窗口、IO 未分类。
 * 解析/解码/容器不支持（`ERROR_CODE_PARSING_*`、`ERROR_CODE_DECODING_*`、
 * `ERROR_CODE_DECODER_INIT_FAILED`、`ERROR_CODE_UNSUPPORTED_*`）重连多少次都一样，
 * 直接留给用户（换线路 / 外部播放器）。
 */
private fun isAutoRetryable(error: PlaybackException): Boolean = when (error.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    PlaybackException.ERROR_CODE_TIMEOUT,
    PlaybackException.ERROR_CODE_REMOTE_ERROR,
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
    -> true
    else -> false
}

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
    /** 长按右半侧的快进倍速（播放器内可改） */
    fastRate: Float = 3f,
    /** 长按左半侧的快退倍速（播放器内可改） */
    rewindRate: Float = 3f,
    /** 改倍速：由上层落库，播放器只负责展示与调用 */
    onRatesChange: (fast: Float, rewind: Float) -> Unit = { _, _ -> },
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
    var fullscreen by remember(currentUrl) { mutableStateOf(false) }
    var controlsVisible by remember(currentUrl) { mutableStateOf(true) }
    var resumeMs by remember(currentUrl) { mutableLongStateOf(startPositionMs) }
    var pendingResume by remember(currentUrl) {
        mutableLongStateOf(resumePositionMs.takeIf { it > 5_000 } ?: 0L)
    }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    // 本次是否已经播到结尾：是则进度归零，不再把结尾位置写回历史
    var reachedEnd by remember(currentUrl) { mutableStateOf(false) }
    // 长按倍速是否生效中
    var boostActive by remember(currentUrl) { mutableStateOf(false) }
    // 长按方向：true=右侧快进 / false=左侧快退（用于屏幕上的提示）
    var boostForward by remember(currentUrl) { mutableStateOf(true) }
    // 长按快退时先暂停，松手恢复原来的播放状态
    var wasPlayingBeforeRewind by remember(currentUrl) { mutableStateOf(false) }
    // 播放设置弹窗（长按快进/快退倍速）
    var ratesOpen by remember { mutableStateOf(false) }
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
    // 双击中央的瞬时反馈：true=刚切到播放（闪 ▶）/ false=刚暂停（闪 ⏸）/ null=不显示
    var centerFlash by remember(currentUrl) { mutableStateOf<Boolean?>(null) }


    // 硬件返回键：全屏先退全屏，锁屏先解锁，最后才退出播放器
    androidx.activity.compose.BackHandler {
        if (fullscreen) {
            fullscreen = false
        } else if (locked) {
            // 锁定时唯一的退路：解锁并把控制层亮出来
            locked = false
            controlsVisible = true
        } else {
            onBack()
        }
    }

    // 媒体源工厂留一份：自动重连（重拨）要重新 createMediaSource，并把中断位置带进去
    val mediaFactory = remember(currentUrl) {
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
                    referer?.takeIf { it.isNotBlank() }?.let { put("Referer", asciiHeaderValue(it)) }
                    put(
                        "User-Agent",
                        com.ghs.mobile.core.engine.net.UserAgents.UA,
                    )
                },
            )
        val dataSource = DefaultDataSource.Factory(context, httpFactory)
        // 排查媒体 403 的第一手证据：CDN 普遍按 Referer 放行，缺了会被重定向到 403 页
        android.util.Log.i("GHS", "video source referer=${referer ?: "(空)"} url=$currentUrl")
        DefaultMediaSourceFactory(dataSource)
    }

    val player = remember(currentUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaFactory.createMediaSource(MediaItem.fromUri(currentUrl)))
            prepare()
            // 默认从 0 开始；有历史进度时由「继续观看」按钮决定是否跳转
            playWhenReady = true
        }
    }

    // 自动重连（重拨）：网络/HTTP 中断后按 2s / 4s / 8s 各重拨一次，三次仍失败才把错误交给用户。
    // 解码类错误不重拨——重连解决不了格式问题，直接保留手动出口。
    var retryAttempt by remember(currentUrl) { mutableIntStateOf(0) }
    var retryPending by remember(currentUrl) { mutableStateOf(false) }
    var retryIn by remember(currentUrl) { mutableIntStateOf(0) }
    var retryToken by remember(currentUrl) { mutableIntStateOf(0) }
    // 中断位置：重拨后用 setMediaSource(source, startPositionMs) 从那里接着播
    var retryPositionMs by remember(currentUrl) { mutableLongStateOf(0L) }
    // 自动重播提示：播完从头再来时闪一下，几秒后自己消失
    var replayHint by remember(currentUrl) { mutableStateOf(false) }

    /** 立刻重拨：重建媒体源并从 [retryPositionMs] 续播（错误叠层先撤掉，失败会再弹回来） */
    fun redialNow() {
        val pos = retryPositionMs
        retryPending = false
        retryIn = 0
        error = null
        android.util.Log.i(
            "GHS",
            "video redial ${retryAttempt}/$AUTO_REDIAL_MAX at ${pos}ms $currentUrl",
        )
        runCatching {
            player.setMediaSource(mediaFactory.createMediaSource(MediaItem.fromUri(currentUrl)), pos)
            player.prepare()
            player.playWhenReady = true
        }
    }

    DisposableEffect(player, currentUrl) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playerError: PlaybackException) {
                error = playerError.errorCodeName + " · " + (playerError.message ?: "")
                val path = currentUrl.substringBefore("?").lowercase()
                val retryable = isAutoRetryable(playerError)
                if (retryable && retryAttempt < AUTO_REDIAL_MAX) {
                    val delayMs = AUTO_REDIAL_DELAYS_MS[retryAttempt]
                    retryAttempt += 1
                    retryPositionMs = player.currentPosition.coerceAtLeast(0)
                    retryIn = (delayMs / 1000).toInt()
                    retryPending = true
                    retryToken += 1
                    android.util.Log.w(
                        "GHS",
                        "video error ${playerError.errorCodeName} → 自动重连 " +
                            "$retryAttempt/$AUTO_REDIAL_MAX，${delayMs}ms 后从 ${retryPositionMs}ms 重拨 $currentUrl",
                    )
                } else if (!retryable && path.endsWith(".mp4")) {
                    // 重连也解决不了的 mp4（格式/解码不支持）：交给系统播放器，避免只剩一张错误页
                    openExternally()
                }
            }

            // 播放状态必须由播放器回调即时驱动：靠 500ms 轮询镜像会让按钮和图标滞后、连点还会切反
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) playing = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    playing = false
                    // 自动重播：播完立刻从头再放。直播/时长未知的流不重播，避免原地空转
                    if (player.duration > 0) {
                        android.util.Log.i("GHS", "video ended → 自动重播 $currentUrl")
                        replayHint = true
                        // 上一轮"看完"的标记要清掉，否则重播后不再回写进度、也不会再触发下一轮重播
                        reachedEnd = false
                        player.seekTo(0)
                        player.playWhenReady = true
                        player.play()
                    }
                }
            }
        }
        player.addListener(listener)
        onDispose {
            if (pendingResume == 0L && player.currentPosition > 0) {
                runCatching { onProgress(currentUrl, player.currentPosition, player.duration.coerceAtLeast(0)) }
            }
            player.removeListener(listener)
            // 先停再释放：release 抛异常时也不会留下后台播放在响
            runCatching { player.stop() }
            runCatching { player.release() }
        }
    }

    // 倒计时结束就重拨；用户点「立即重连」会把 retryPending 置 false，这里随即退出
    LaunchedEffect(retryToken) {
        while (retryPending) {
            delay(1_000)
            if (!retryPending) break
            retryIn -= 1
            if (retryIn <= 0) {
                redialNow()
                break
            }
        }
    }

    // 「自动重播」提示只亮一小会儿
    LaunchedEffect(replayHint) {
        if (replayHint) {
            delay(2_000)
            replayHint = false
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
            // 真正"恢复"的判据是位置往前走了 10 秒，而不是 playing 变 true：
            // 断网时播放器会短暂报告 playing，按时间判断会把重连计数清零，变成无限重试
            if (retryAttempt > 0 && !retryPending && positionMs > retryPositionMs + 10_000) {
                retryAttempt = 0
            }
            // 播到结尾附近即视为看完：进度写 0，下次进来从头播
            if (!reachedEnd && durationMs > 0 && positionMs >= durationMs - 1_000) {
                reachedEnd = true
                onProgress(currentUrl, 0L, durationMs)
            }
            if (tick % 10 == 0 && pendingResume == 0L && !reachedEnd) {
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

    // 续看提示只停留 5 秒：默认从头播，想续看要在这 5 秒内点
    LaunchedEffect(pendingResume) {
        if (pendingResume > 0) {
            delay(5_000)
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
    // 播放期间不要息屏：长视频/切后台时系统会锁屏，声音还在但画面停
    // 播放期间不要息屏：否则长视频/切后台时系统会锁屏，声音还在但画面停
    DisposableEffect(activity) {
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    // 双击中央的图标只闪 700ms
    LaunchedEffect(centerFlash) {
        if (centerFlash != null) {
            delay(700)
            centerFlash = null
        }
    }

    /** 播放 / 暂停：按播放器的真实意图（playWhenReady）判断，不用 500ms 轮询出来的镜像值 */
    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            reachedEnd = false
            player.seekTo(0)
            player.play()
            centerFlash = true
            return
        }
        if (player.playWhenReady) {
            player.pause()
            centerFlash = false
        } else {
            player.play()
            centerFlash = true
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
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false // 控制层由我们自己画
                    // 遮罩默认色随主题可能是白色：显式设黑，首帧未到时不会白屏
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    playerViewRef = this
                }
            },
            update = { view ->

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
                        // 单击只做一件事：显隐控制层。锁定后同样保留，否则控制层一隐藏就点不到解锁
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            // 锁定 = 全禁：除「点锁解锁」外，任何触摸都不改变播放
                            if (locked) return@detectTapGestures
                            val width = size.width
                            val dir = when {
                                offset.x < width * EDGE_TAP_THRESHOLD -> -1
                                offset.x > width * (1f - EDGE_TAP_THRESHOLD) -> 1
                                else -> 0
                            }
                            if (dir == 0) {
                                // 中间 20%：暂停 / 播放，并在屏幕中央闪一下对应图标
                                togglePlayPause()
                            } else {
                                val now = System.currentTimeMillis()
                                val continued = dir == lastSeekDir && now - lastSeekAt <= SMART_SEEK_WINDOW_MS
                                lastSeekDir = dir
                                lastSeekAt = now
                                smartSeek(dir * if (continued) 2 else 1)
                            }
                        },
                        // 长按倍速：右半侧快进、左半侧快退；按住在放，松手恢复。
                        // 快进用播放速率实现；快退没有负速率，改成"暂停 + 按倍率往回 seek"。
                        onPress = { offset ->
                            if (locked) return@detectTapGestures
                            val forward = offset.x >= size.width / 2f
                            var held = false
                            val holdJob = scope.launch {
                                delay(LONG_PRESS_MS)
                                held = true
                                if (forward) {
                                    boostForward = true
                                    boostActive = true
                                    player.setPlaybackSpeed(fastRate)
                                } else {
                                    boostForward = false
                                    boostActive = true
                                    wasPlayingBeforeRewind = player.playWhenReady
                                    player.pause()
                                    // 每 100ms 往回退 rate×100ms：观感就是"以 rate 倍速倒放"
                                    val stepMs = 100L
                                    while (true) {
                                        val target = (player.currentPosition - (rewindRate * stepMs).toLong())
                                            .coerceAtLeast(0L)
                                        player.seekTo(target)
                                        if (target <= 0L) break
                                        delay(stepMs)
                                    }
                                }
                            }
                            tryAwaitRelease()
                            holdJob.cancel()
                            if (held) {
                                if (forward) {
                                    player.setPlaybackSpeed(1f)
                                } else if (wasPlayingBeforeRewind) {
                                    player.playWhenReady = true
                                    player.play()
                                }
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

        // 控制层隐藏时，底部留一条细进度条：随时知道播到哪，又不挡画面、不接受点击。
        // 用 Canvas 画而不是嵌套 Box：高度写死在 Canvas 上，不会被内层 fillMaxWidth/inset 拉伸
        // （曾经出现过"播完后这条变粗成一条紫色横条"）
        if (!controlsVisible && error == null && durationMs > 0) {
            val played = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HIDDEN_BAR_HEIGHT)
                    .align(Alignment.BottomCenter)
                    // 让开系统手势条：压在它下面会被系统栏裁/染色，看着就像"变粗了"
                    .navigationBarsPadding()
                    .clipToBounds(),
            ) {
                drawRect(Color(0x66000000))
                drawRect(
                    color = Color(0xFF7C4DFF),
                    size = androidx.compose.ui.geometry.Size(size.width * played, size.height),
                )
            }
        }

        // 长按加速提示
        if (boostActive) {
            Text(
                (if (boostForward) "快进 " else "快退 ") +
                    String.format("%.1fx", if (boostForward) fastRate else rewindRate),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // 播完自动从头再来一遍时给一句提示，避免用户以为是"没播完就跳回去"
        if (replayHint) {
            Text(
                "自动重播",
                color = Color(0xFFE6E6EA),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // 锁按钮：控制层的一部分，控制层隐藏时一起隐藏。
        // 锁定后它是屏幕上唯一可点的控件（全禁），所以锁定态额外给一个「已锁定」提示。
        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    // 离屏幕左缘 24dp：手势导航的系统返回热区会吞掉贴着边缘的点击
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        locked = !locked
                        controlsVisible = true
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0x66000000), CircleShape),
                ) {
                    LockIcon(locked = locked, tint = Color.White)
                }
                if (locked) {
                    Text(
                        "已锁定",
                        color = Color(0xFFE6E6EA),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .background(Color(0x88000000), RoundedCornerShape(9.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // 双击中央的即时反馈：闪一下 ▶ / ⏸
        centerFlash?.let { playingNow ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(68.dp)
                    .background(Color(0x88000000), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (playingNow) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    PauseIcon(tint = Color.White, boxSize = 32.dp)
                }
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
                IconButton(onClick = { ratesOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "播放设置",
                        tint = Color.White,
                    )
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
                        // 不要圆点：自绘的 thumb 与轨道不是垂直居中的（看着偏上），干脆去掉
                        thumb = { },
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
                        IconButton(onClick = { togglePlayPause() }) {
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
                        // 重播：回到开头继续播（播完后进度已归零）
                        IconButton(onClick = {
                            reachedEnd = false
                            player.seekTo(0)
                            player.play()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "重播",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { fullscreen = !fullscreen }) {
                            FullscreenIcon(expanded = fullscreen, tint = Color.White)
                        }
                    }
                }
            }
        }

        // 播放设置：长按快进 / 快退的倍速（按住生效，松手恢复）
        if (ratesOpen) {
            AlertDialog(
                onDismissRequest = { ratesOpen = false },
                title = { Text("播放设置") },
                text = {
                    Column {
                        Text("长按右半屏：快进倍速", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PLAY_RATE_OPTIONS.forEach { rate ->
                                FilterChip(
                                    selected = fastRate == rate,
                                    onClick = { onRatesChange(rate, rewindRate) },
                                    label = { Text(String.format("%.1fx", rate)) },
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("长按左半屏：快退倍速", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            PLAY_RATE_OPTIONS.forEach { rate ->
                                FilterChip(
                                    selected = rewindRate == rate,
                                    onClick = { onRatesChange(fastRate, rate) },
                                    label = { Text(String.format("%.1fx", rate)) },
                                )
                            }
                        }
                        Text(
                            "按住不放生效，松手恢复原来的播放速度。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { ratesOpen = false }) { Text("完成") }
                },
            )
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
                    Text(
                        when {
                            retryPending -> "播放中断 · 正在自动重连（第 $retryAttempt/$AUTO_REDIAL_MAX 次）"
                            retryAttempt >= AUTO_REDIAL_MAX -> "自动重连 $AUTO_REDIAL_MAX 次仍失败"
                            else -> "无法播放"
                        },
                        color = Color(0xFFE6E6EA),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (retryPending) {
                        Text(
                            "$retryIn 秒后自动重拨…",
                            color = Color(0xFFFFD180),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
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
                        if (retryPending) {
                            // 不等倒计时：立刻重拨（次数已计过）
                            TextButton(onClick = { redialNow() }) { Text("立即重连") }
                        } else {
                            // 手动重连：计数归零重新开始，位置取当前进度
                            TextButton(onClick = {
                                retryAttempt = 1
                                retryPositionMs = player.currentPosition.coerceAtLeast(0)
                                redialNow()
                            }) { Text("重连") }
                        }
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


/**
 * 能安全放进 HTTP 头的 URL。
 *
 * OkHttp 的 `Headers.checkValue` 只接受 `0x20..0x7e`：详情地址里带中文时（例如
 * `https://avjoy.me/video/103701/國產av-…`），`Request.Builder.header("Referer", …)` 会直接抛
 * `IllegalArgumentException: Unexpected char 0x570b`，请求根本发不出去——表现是"一进播放器就报
 * 播放错误、三次重拨全失败"。所以头值统一过一遍百分号编码。
 */
internal fun asciiHeaderValue(raw: String): String = runCatching {
    java.net.URI(raw).toASCIIString()
}.getOrElse {
    buildString {
        raw.forEach { ch ->
            if (ch.code in 0x20..0x7e) {
                append(ch)
            } else {
                ch.toString().toByteArray(Charsets.UTF_8).forEach { byte ->
                    append('%').append(String.format("%02X", byte.toInt() and 0xFF))
                }
            }
        }
    }
}
