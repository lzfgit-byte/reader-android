package com.ghs.mobile.ui

import com.ghs.mobile.core.model.Analysis
import com.ghs.mobile.core.model.AnalysisDetail

/**
 * 剧集在播放器里的上下文：让播放器能直接换集 / 换线路，而不必退回列表。
 *
 * @param analysis 全部分段（线路）
 * @param lineUrl 当前线路地址
 * @param episodes 当前线路下的集
 * @param currentIndex 当前集下标
 */
data class SeriesPlayback(
    val analysis: List<Analysis>,
    val lineUrl: String,
    val episodes: List<AnalysisDetail>,
    val currentIndex: Int,
) {
    val currentLine: Analysis? get() = analysis.firstOrNull { it.url == lineUrl }
    val currentEpisode: AnalysisDetail? get() = episodes.getOrNull(currentIndex)
}
