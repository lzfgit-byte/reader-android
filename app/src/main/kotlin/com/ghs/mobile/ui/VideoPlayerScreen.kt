package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

/**
 * 视频播放器：Media3（ExoPlayer）+ HLS。
 *
 * - 进入即播：地址由详情解析阶段拿到，这里不再有中间列表页
 * - 多清晰度：顶部菜单就地切换，保留当前播放位置
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
    onProgress: (sourceUrl: String, positionMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0))) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // 切清晰度时保留播放位置
    var resumeMs by remember { mutableStateOf(startPositionMs) }

    val currentUrl = sources.getOrNull(index)?.url.orEmpty()

    val player = remember(index, currentUrl) {
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
        val dataSource = DefaultDataSource.Factory(
            context,
            httpFactory,
        )
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(DefaultMediaSourceFactory(dataSource).createMediaSource(MediaItem.fromUri(currentUrl)))
            prepare()
            if (resumeMs > 0) seekTo(resumeMs)
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
            runCatching { onProgress(currentUrl, player.currentPosition, player.duration.coerceAtLeast(0)) }
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            onProgress(currentUrl, player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sources.getOrNull(index)?.quality?.ifEmpty { title } ?: title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (sources.size > 1) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.List, contentDescription = "清晰度")
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
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )

                if (error != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.75f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "无法播放\n$error\n\n$currentUrl",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE6E6EA),
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }
}
