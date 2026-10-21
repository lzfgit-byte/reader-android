package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * 视频播放器：Media3（ExoPlayer）+ HLS。
 *
 * - 进入时从进度续播（`startPositionMs`）
 * - 每 5 秒与退出时回写进度（位置 / 时长 / 百分比）
 * - 播放失败显示原因，而不是黑屏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    title: String,
    url: String,
    startPositionMs: Long,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            // 加了 media3-exoplayer-hls 后，DefaultMediaSourceFactory 会按扩展名/内容类型识别 m3u8
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            if (startPositionMs > 0) seekTo(startPositionMs)
            playWhenReady = true
        }
    }

    DisposableEffect(url) {
        val listener = object : Player.Listener {
            override fun onPlayerError(playerError: PlaybackException) {
                error = playerError.errorCodeName + " · " + (playerError.message ?: "")
            }
        }
        player.addListener(listener)
        onDispose {
            runCatching { onProgress(player.currentPosition, player.duration.coerceAtLeast(0)) }
            player.removeListener(listener)
            player.release()
        }
    }

    // 定期回写进度
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            onProgress(player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                val exoPlayer = player
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = exoPlayer
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
                            "无法播放\n$error\n\n$url",
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
