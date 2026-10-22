package com.ghs.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.Analysis
import com.ghs.mobile.core.model.AnalysisDetail

/**
 * 剧集详情：直接摊成「线路 Tab → 剧集网格」。
 *
 * 线路由 `getDetailInfo` 的 `analysis[]` 给出；每切到一个 Tab 再按需加载该线路的剧集
 * （`getAnalysisDetail`），避免一进详情把所有线路都请求一遍。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    title: String,
    analysis: List<Analysis>,
    loadEpisodes: suspend (lineUrl: String) -> List<AnalysisDetail>,
    onPlay: (lineUrl: String, episodeIndex: Int) -> Unit,
    onBack: () -> Unit,
    currentLineUrl: String? = null,
    currentEpisodeIndex: Int = -1,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    if (analysis.isEmpty()) "暂无线路" else "${analysis.size} 条线路 · 切换线路查看剧集",
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                )
                LineEpisodeTabs(
                    analysis = analysis,
                    initialLineUrl = currentLineUrl,
                    loadEpisodes = loadEpisodes,
                    onPick = { lineUrl, index -> onPlay(lineUrl, index) },
                    currentLineUrl = currentLineUrl,
                    currentEpisodeIndex = currentEpisodeIndex,
                    maxGridHeight = 520,
                )
            }
        }
    }
}
