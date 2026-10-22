package com.ghs.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghs.mobile.core.model.Analysis
import com.ghs.mobile.core.model.AnalysisDetail

/**
 * 「线路 Tab → 该线路的剧集」选择器。
 *
 * 剧集详情页与播放器抽屉共用同一套交互：
 * - 每个**线路**是一个 Tab，切 Tab 只换下面的剧集列表，不直接播放
 * - 剧集按线路懒加载并缓存，切回来不重复请求
 * - 当前播放的「线路 + 集」会高亮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineEpisodeTabs(
    analysis: List<Analysis>,
    initialLineUrl: String?,
    loadEpisodes: suspend (lineUrl: String) -> List<AnalysisDetail>,
    onPick: (lineUrl: String, episodeIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    currentLineUrl: String? = null,
    currentEpisodeIndex: Int = -1,
    maxGridHeight: Int = 380,
) {
    if (analysis.isEmpty()) {
        Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text("该配置没有返回线路", style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    var tab by remember(analysis, initialLineUrl) {
        mutableIntStateOf(
            analysis.indexOfFirst { it.url == initialLineUrl }.let { if (it >= 0) it else 0 },
        )
    }
    val cache = remember(analysis) { mutableStateMapOf<String, List<AnalysisDetail>>() }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val line = analysis.getOrNull(tab)

    // 切到某条线路时懒加载它的剧集
    LaunchedEffect(line?.url) {
        val url = line?.url ?: return@LaunchedEffect
        if (cache.containsKey(url)) return@LaunchedEffect
        loading = true
        error = null
        runCatching { loadEpisodes(url) }
            .onSuccess { cache[url] = it }
            .onFailure { error = it.message ?: it.toString() }
        loading = false
    }

    val episodes = line?.let { cache[it.url] }.orEmpty()

    Column(modifier.fillMaxWidth()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 12.dp) {
            analysis.forEachIndexed { index, item ->
                val selected = index == tab
                Tab(
                    selected = selected,
                    onClick = { tab = index },
                    text = {
                        Text(
                            item.title.ifEmpty { "线路 ${index + 1}" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                )
            }
        }

        when {
            loading -> Box(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            error != null -> Box(
                modifier = Modifier.fillMaxWidth().height(140.dp).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            episodes.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().height(140.dp),
                contentAlignment = Alignment.Center,
            ) { Text("该线路没有剧集", style = MaterialTheme.typography.bodySmall) }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = maxGridHeight.dp),
            ) {
                itemsIndexed(episodes) { index, episode ->
                    val current = line?.url == currentLineUrl && index == currentEpisodeIndex
                    Card(
                        onClick = { line?.let { onPick(it.url, index) } },
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
                                episode.title.ifEmpty { "${index + 1}" },
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
