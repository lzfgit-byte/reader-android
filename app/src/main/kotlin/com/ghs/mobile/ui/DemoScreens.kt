package com.ghs.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ghs.mobile.GhsApp
import com.ghs.mobile.core.data.LocalRepository
import com.ghs.mobile.core.data.SettingScope
import com.ghs.mobile.core.model.BlockType
import com.ghs.mobile.core.model.CContent
import com.ghs.mobile.core.model.Detail
import com.ghs.mobile.core.model.DetailInfo
import com.ghs.mobile.core.model.DetailType
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.model.Page
import com.ghs.mobile.core.model.ReaderKind
import com.ghs.mobile.core.model.ReadingProgress
import com.ghs.mobile.core.model.TextContent
import com.ghs.mobile.session.ReaderSession
import com.ghs.mobile.web.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import com.ghs.mobile.demo.ItemCodec
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import com.ghs.mobile.core.model.Analysis
import com.ghs.mobile.core.model.AnalysisDetail
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FloatingActionButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import coil.imageLoader

/**
 * 演示界面：列表 → 详情（图集 / 文本 / 视频）。
 *
 * 数据全部来自 `assets/demo/`，但解析链路与真实配置一致（脚本 → $ → 领域模型），
 * 进度落本地仓库。
 */
private sealed interface Screen {
    data object Loading : Screen
    data class List(val page: Page) : Screen
    data class Detail(val item: Item, val info: DetailInfo?) : Screen
    data class ComicReader(
        val item: Item,
        val pages: kotlin.collections.List<com.ghs.mobile.core.model.Detail>,
        val startIndex: Int,
        /** 目录（getContents）；空表示单组图 */
        val chapters: kotlin.collections.List<CContent> = emptyList(),
        val chapterIndex: Int = 0,
        /** 目录里的「预加载下一话」开关 */
        val preloadNext: Boolean = false,
    ) : Screen
    /** 单图阅读：与连续滚动完全独立的模式，一次一张、左右翻页 */
    data class SinglePage(
        val item: Item,
        val pages: kotlin.collections.List<com.ghs.mobile.core.model.Detail>,
        val startIndex: Int,
        val chapterIndex: Int = 0,
        val chapters: kotlin.collections.List<CContent> = emptyList(),
        val preloadNext: Boolean = false,
        /** 是否从漫画阅读双击进入：是则关闭回漫画阅读，否则回列表 */
        val fromComic: Boolean = false,
    ) : Screen
    data class Series(val item: Item, val info: DetailInfo) : Screen
    data class VideoPlayer(
        val item: Item,
        val sources: kotlin.collections.List<com.ghs.mobile.core.model.Detail>,
        val startIndex: Int,
        val startMs: Long,
        val referer: String? = null,
        /** 上次看到的位置：进播放器后提示「快进」而不是直接跳 */
        val resumePositionMs: Long = 0L,
        /** 剧集上下文：播放器里可直接换集 / 换线路 */
        val series: SeriesPlayback? = null,
        /** 从剧集进入时带上详情，返回键回到剧集页而不是列表 */
        val backToSeries: DetailInfo? = null,
    ) : Screen
    data class SearchResults(val keyword: String, val page: Page) : Screen
    data class Failed(val message: String) : Screen
}

/** 底部导航 */
enum class DemoTab(val label: String) { HOME("首页"), FAVORITES("收藏"), HISTORY("历史"), TAGS("标签") }

/** 演示配置的 key（收藏/历史按配置分组的键） */
private const val CONFIG_KEY = "demo"

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DemoApp(
    onOpenProbe: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    /** 左侧抽屉里直接展开的设置内容（由外层提供，避免二级菜单） */
    drawerSettings: @Composable () -> Unit = {},
) {
    val app = GhsApp.instance
    val repository = app.repository
    // 会话（WebView 引擎）必须在后台线程创建：引擎内部会等待页面就绪，主线程等待会死锁
    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    // 记住上一次解析好的列表：返回时直接回填，避免回到 Loading 又无处可去
    var lastPage by remember { mutableStateOf<Page?>(null) }
    // 从哪个界面点进条目的：返回要回到那一页（尤其是搜索结果，别清成普通列表）
    var returnScreen by remember { mutableStateOf<Screen?>(null) }
    var progressByUrl by remember { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    // 从哪个 tab 点进来的：从收藏/历史进来的，退出播放器要回到收藏/历史，而不是首页
    var returnTab by remember { mutableStateOf(DemoTab.HOME) }
    var text by remember { mutableStateOf<TextContent?>(null) }
    var textUrl by remember { mutableStateOf<String?>(null) }
    var textLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(DemoTab.HOME) }
    // 收藏 / 历史的滚动位置提到这里：进播放器或阅读器时整屏 return 会把列表整棵销毁，
    // 滚动状态只留在页面内部就再也回不来了（返回时列表跳回顶部）
    val favoritesGridState = rememberLazyGridState()
    val historyGridState = rememberLazyGridState()
    // 首页列表同理：看视频/详情时列表被销毁，返回必须回到原来的位置
    val homeGridState = rememberLazyGridState()
    // 当前配置：来自 files/configs（浏览器里编辑的那份）
    var configKey by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<kotlin.collections.List<ConfigStore.StoredConfig>>(emptyList()) }
    var showConfigMenu by remember { mutableStateOf(false) }
    var showSiteSheet by remember { mutableStateOf(false) }
    // 左侧菜单（设置 / 引擎自检）
    var drawerOpen by remember { mutableStateOf(false) }
    // 系统返回键：按「阅读器/播放器 → 列表 → 首页 → 抽屉 → 退出」逐级回退
    // 具体处理放在 parseTarget / opening 声明之后（解析中按返回要能取消这次解析）
    // 配置日志（LogMsgUtil / console.log）→ 应用内「日志云」
    var logEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    DisposableEffect(Unit) {
        val listener: (List<String>) -> Unit = { entries ->
            logEntries = entries
            // 同时打到 logcat：adb logcat -s GHS-JS 可看配置脚本输出
            entries.lastOrNull()?.let { android.util.Log.i("GHS-JS", it) }
        }
        com.ghs.mobile.core.engine.script.LogHub.addListener(listener)
        onDispose { com.ghs.mobile.core.engine.script.LogHub.removeListener(listener) }
    }
    var logSheetOpen by remember { mutableStateOf(false) }
    // 清除缓存确认框
    var showClearCache by remember { mutableStateOf(false) }
    var showPageSheet by remember { mutableStateOf(false) }
    // 筛选/排序面板：配置返回的 urlReplace 分组
    var showFilterSheet by remember { mutableStateOf(false) }
    var filterLoading by remember { mutableStateOf(false) }
    // 首页刷新中：FAB 转圈 + 列表上加遮罩
    var refreshing by remember { mutableStateOf(false) }
    // 详情解析失败的原因：info == null 既可能是"还在解析"，也可能是"失败了"
    var detailError by remember { mutableStateOf<String?>(null) }
    // 详情解析任务：允许手动取消，并统计已用时间
    var detailJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var detailElapsedSec by remember { mutableStateOf(0) }
    // 用户手动取消：不再展示任何错误页，直接回到列表
    // 用户手动取消：不再展示任何错误页，直接回到列表
    var detailCancelled by remember { mutableStateOf(false) }
    var parseTarget by remember { mutableStateOf<Item?>(null) }
    // 打开条目的整体等待态：解析 + 目录 + 图片全流程都显示 loading
    var opening by remember { mutableStateOf(false) }

    // 只要当前不是「列表页」就说明有上一级可回。这里不能只列具体 Screen 类型：
    // 新增的界面（如分段/线路选集的详情态）一旦漏掉，按返回就会被系统当成退出应用
    val canGoBack = screen !is Screen.List ||
        tab != DemoTab.HOME || drawerOpen || opening
    androidx.activity.compose.BackHandler(enabled = canGoBack) {
        when {
            drawerOpen -> drawerOpen = false
            // 解析中：返回就是取消这次打开，别把用户甩回首页
            opening -> {
                detailCancelled = true
                session?.cancelPending()
                detailJob?.cancel()
                detailJob = null
                detailError = null
                parseTarget = null
                opening = false
                detailElapsedSec = 0
            }
            // 单图阅读：退回漫画阅读（双击进来的位置）
            screen is Screen.SinglePage -> {
                val single = screen as Screen.SinglePage
                screen = if (single.fromComic) {
                    Screen.ComicReader(
                        item = single.item,
                        pages = single.pages,
                        startIndex = single.startIndex,
                        chapters = single.chapters,
                        chapterIndex = single.chapterIndex,
                        preloadNext = single.preloadNext,
                    )
                } else {
                    lastPage?.let { Screen.List(it) } ?: Screen.Loading
                }
            }
            // 详情 / 播放 / 漫画：回到点进来的那个界面（搜索结果要保留搜索态）
            screen is Screen.ComicReader || screen is Screen.VideoPlayer || screen is Screen.Detail -> {
                screen = returnScreen ?: lastPage?.let { Screen.List(it) } ?: Screen.Loading
                tab = returnTab
            }
            tab != DemoTab.HOME -> tab = DemoTab.HOME
            // 兜底：任何未在上面显式处理的非列表界面都退回列表，绝不把返回键放给系统
            screen !is Screen.List -> {
                screen = returnScreen ?: lastPage?.let { Screen.List(it) } ?: Screen.Loading
                tab = returnTab
            }
        }
    }
    // 网页端改过配置后由服务端通知：在列表页立即重载，阅读中则等回到列表
    var reloadRequest by remember { mutableStateOf(0) }
    // 当前列表地址：刷新按钮用它重新取数据
    var currentListUrl by remember { mutableStateOf<String?>(null) }
    var pendingReload by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var searchError by remember { mutableStateOf<String?>(null) }
    // 列表形态：双列卡片 / 单列行卡片（参考常见阅读客户端）
    var layoutMode by remember { mutableStateOf(ItemCardLayout.GRID) }
    // 图片清晰度：默认原图（fullUrl），可切到展示图（url）
    var imageQuality by remember { mutableStateOf(ImageQuality.ORIGINAL) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    // 从标签进入的列表：标题显示标签名；null 表示首页
    var activeTagTitle by remember { mutableStateOf<String?>(null) }
    var collected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favoriteItems by remember { mutableStateOf<kotlin.collections.List<Item>>(emptyList()) }
    var historyEntries by remember { mutableStateOf<kotlin.collections.List<HistoryEntry>>(emptyList()) }
    // 当前站点 key：收藏/历史都按站点隔离（历史只显示当前站点）
    val siteKey = configKey ?: CONFIG_KEY


    fun refreshLibrary() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val collects = repository.collects(siteKey)
                // 库里存的是相对地址：读出来时用当前 homeUrl 补回域名
                val home = session?.homeUrl
                val favorites = collects.mapNotNull { record ->
                    ItemCodec.decode(record.url, record.payload)?.let { it.copy(jumpUrl = absUrl(it.jumpUrl, home)) }
                }
                val history = repository.history(siteKey, 100).mapNotNull { record ->
                    ItemCodec.decode(record.url, record.payload)?.let { item ->
                        HistoryEntry(item.copy(jumpUrl = absUrl(item.jumpUrl, home)), record.createTime)
                    }
                }
                // 库里存相对地址、界面上的 item.jumpUrl 是绝对地址：统一成绝对地址，
                // 否则「是否已收藏」永远为 false，星标点了不变、取消也删不掉
                Triple(collects.map { absUrl(it.url, home) }.toSet(), favorites, history)
            }
            collected = snapshot.first
            favoriteItems = snapshot.second
            historyEntries = snapshot.third
        }
    }

    // 一次性把旧版本写在 demo 名下的历史迁到当前站点
    LaunchedEffect(siteKey) {
        if (siteKey == "demo") return@LaunchedEffect
        val done = withContext(Dispatchers.IO) { repository.settings(SettingScope.DEVICE)["history_migrated"] }
        if (done == "1") return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching { repository.migrateLegacyHistory(to = siteKey) }
            repository.putSetting(SettingScope.DEVICE, "history_migrated", "1")
        }
        refreshLibrary()
    }
    LaunchedEffect(Unit, siteKey) { refreshLibrary() }

    // 切到收藏/历史时重新读一次：数据可能在别处（阅读、收藏）被改过
    LaunchedEffect(tab) { if (tab != DemoTab.HOME) refreshLibrary() }

    fun toggleCollect(item: Item) {
        scope.launch {
            withContext(Dispatchers.IO) {
                // 新数据存相对地址（换域名仍可用）；但历史/导入的旧数据可能是绝对地址。
                // 两种都要认，否则「取消收藏」删不到旧那条，星标会一直亮着。
                val rel = relUrl(item.jumpUrl, session?.homeUrl)
                val abs = item.jumpUrl
                val hasRel = repository.isCollected(siteKey, rel)
                val hasAbs = !hasRel && repository.isCollected(siteKey, abs)
                if (hasRel || hasAbs) {
                    if (hasRel) repository.uncollect(siteKey, rel)
                    if (hasAbs) repository.uncollect(siteKey, abs)
                } else {
                    repository.collect(siteKey, rel, ItemCodec.encode(item))
                }
            }
            refreshLibrary()
        }
    }

    /** 搜索：adapterSearchUrl 取地址 → 取页 → 按普通列表解析 */
    fun runSearch(keyword: String) {
        val active = session ?: return
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { active.search(trimmed) } }
                .onSuccess { matched ->
                    if (matched == null) {
                        searchError = "该配置不支持搜索（adapterSearchUrl 返回空串）"
                    } else {
                        searchError = null
                        searchOpen = false
                        screen = Screen.SearchResults(trimmed, matched.second)
                    }
                }
                .onFailure { searchError = it.message ?: it.toString() }
        }
    }

    /** 当前站点显示名：优先配置里的 name，缺失时退回 key */
    fun siteLabel(): String {
        val current = configs.firstOrNull { it.key == configKey }
        val name = current?.source?.let { source ->
            Regex("\\bname\\s*:\\s*['\"]([^'\"]+)['\"]").find(source)?.groupValues?.get(1)
        }
        return name?.takeIf { it.isNotBlank() } ?: configKey ?: "站点"
    }

    /**
     * 打开某个地址的列表：标签点击与分页跳转共用。
     *
     * 与"加载更多"的区别是**替换**当前列表而不是追加，并重置到底状态。
     */
    fun openFromUrl(url: String, title: String?) {
        val active = session ?: return
        if (refreshing) return
        refreshing = true
        // 先切回首页再看 loading：遮罩盖在旧列表上，视觉上比"停在标签页等"更连贯
        tab = DemoTab.HOME
        scope.launch {
            val page = runCatching { withContext(Dispatchers.IO) { active.pageFrom(url) } }.getOrNull()
            if (page != null) {
                noMore = false
                currentListUrl = url
                activeTagTitle = title
                lastPage = page
                screen = Screen.List(page)
            }
            refreshing = false
        }
    }

    /** 首页刷新：用当前列表地址重新取数据，期间显示 loading */
    fun refreshCurrent() {
        if (refreshing) return
        val active = session ?: return
        val url = currentListUrl ?: active.homeUrl
        refreshing = true
        // 手动刷新：作废 HTML 缓存（图片缓存保留）
        ImageCache.clearHtml()
        scope.launch {
            val page = runCatching { withContext(Dispatchers.IO) { active.pageFrom(url) } }.getOrNull()
            if (page != null) {
                noMore = false
                currentListUrl = url
                lastPage = page
                screen = Screen.List(page)
            }
            refreshing = false
        }
    }
    /**
     * 应用筛选/排序：把新选择交给配置的 `adapterLoadUrl` 得到最终地址，再重新取页。
     *
     * 只替换被点的那一组，其余组保持原样（配置自己看 current 标记）。
     */
    fun applyFilter(schema: String, value: String) {
        val active = session ?: return
        val currentPage = (screen as? Screen.List)?.page ?: return
        val updated = currentPage.urlReplace.map { group ->
            if (group.schema != schema) {
                group
            } else {
                group.copy(
                    urlAppend = group.urlAppend.map { entry ->
                        entry.copy(current = entry.value == value)
                    },
                )
            }
        }
        val baseUrl = currentListUrl ?: active.homeUrl
        filterLoading = true
        scope.launch {
            val target = withContext(Dispatchers.IO) { active.loadUrl(baseUrl, updated) }
            val page = runCatching { withContext(Dispatchers.IO) { active.pageFrom(target) } }.getOrNull()
            if (page != null) {
                noMore = false
                currentListUrl = target
                lastPage = page
                screen = Screen.List(page)
            }
            filterLoading = false
            showFilterSheet = false
        }
    }

    /** 滑到底部时取下一页：按 pagination 里当前页之后的第一条链接 */
    fun loadMore() {
        if (loadingMore || noMore) return
        // 搜索结果同样带分页：不要只拉第一页就停（固定 24 条那种表现）
        val listScreen = screen as? Screen.List
        val searchScreen = screen as? Screen.SearchResults
        val page = listScreen?.page ?: searchScreen?.page ?: return
        val nextUrl = nextPageUrl(page) ?: run {
            noMore = true
            return
        }
        val active = session ?: return
        loadingMore = true
        scope.launch {
            val next = runCatching { withContext(Dispatchers.IO) { active.pageFrom(nextUrl) } }.getOrNull()
            if (next != null && next.items.isNotEmpty()) {
                val known = page.items.map { it.jumpUrl }.toSet()
                val merged = page.copy(
                    items = page.items + next.items.filterNot { it.jumpUrl in known },
                    pagination = next.pagination,
                )
                if (listScreen != null) {
                    lastPage = merged
                    screen = Screen.List(merged)
                } else if (searchScreen != null) {
                    screen = Screen.SearchResults(searchScreen.keyword, merged)
                }
            } else {
                noMore = true
            }
            loadingMore = false
        }
    }

    LaunchedEffect(Unit) {
        val (loaded, selected) = withContext(Dispatchers.IO) {
            app.configs.seedIfEmpty()
            val all = app.configs.list()
            val savedKey = repository.settings(SettingScope.PREFS)["currentConfig"]
            val picked = all.firstOrNull { it.key == savedKey && it.enabled }
                ?: all.firstOrNull { it.enabled }
            // 全部停用时不选中任何站点：界面走「无可用站点」，不偷偷启用一个停用配置
            all to picked
        }
        configs = loaded
        configKey = selected?.key
        // 恢复上次选择的图片清晰度
        imageQuality = when (repository.settings(SettingScope.PREFS)["imageQuality"]) {
            "preview" -> ImageQuality.PREVIEW
            else -> ImageQuality.ORIGINAL
        }
    }

    // 网页端保存/新建/删除后，服务端会发通知：列表页立刻重载
    LaunchedEffect(Unit) {
        app.webAdmin.reloadSignal.collect { signal ->
            if (signal == 0) return@collect
            if (screen is Screen.List) reloadRequest += 1 else pendingReload = true
        }
    }

    // 阅读中先不动：等回到列表再重载，避免把正在看的页面拆掉
    LaunchedEffect(screen) {
        if (screen is Screen.List && pendingReload) {
            pendingReload = false
            reloadRequest += 1
        }
    }

    // 配置切换或收到重载通知：关掉旧会话再按配置重建（引擎较重，不能并行两份）
    LaunchedEffect(configKey, reloadRequest) {
        val key = configKey ?: return@LaunchedEffect
        // 切站点：把「从哪来回哪去」的缓存一起清掉，否则返回会回到上一个站点的列表
        returnScreen = null
        screen = Screen.Loading
        // 切换站点 / 配置重载：作废 HTML 缓存，避免看到上一个站点的页面
        ImageCache.clearHtml()
        session?.close()
        session = null

        val created = runCatching {
            withContext(Dispatchers.IO) {
                val source = app.configs.get(key)?.source
                    ?: app.assets.open("demo/config.js").bufferedReader().use { it.readText() }
                ReaderSession(app, source, app.proxy.rules.value)
            }
        }.getOrElse {
            screen = Screen.Failed(it.message ?: it.toString())
            return@LaunchedEffect
        }

        session = created
        // 进度按相对地址存：记录当前站点，供 ProgressKeys 剥/拼域名
        ProgressKeys.homeUrl = created.homeUrl
        // 调试台（F25）：把求值器挂到当前会话上
        com.ghs.mobile.web.DebugHub.evaluator = { code -> created.eval(code) }
        // 图片解码（F05）：图集打散图交给当前配置的 adapterImageCode 还原
        com.ghs.mobile.ui.ImageDecodeBridge.decoder = { url, bytes, extra ->
            created.decodeImage(url, bytes, extra)
        }
        runCatching { withContext(Dispatchers.IO) { created.page() } }
            .onSuccess {
                currentListUrl = created.homeUrl
                lastPage = it
                screen = Screen.List(it)
            }
            .onFailure { screen = Screen.Failed(it.message ?: it.toString()) }
    }

    DisposableEffect(Unit) {
        onDispose { session?.close() }
    }

    LaunchedEffect(screen) {
        val list = screen as? Screen.List ?: return@LaunchedEffect
        progressByUrl = withContext(Dispatchers.IO) {
            list.page.items.mapNotNull { item -> repository.progress(ProgressKeys.key(item.jumpUrl))?.let { item.jumpUrl to it } }.toMap()
        }
    }

    // 打开详情：不切页面，首页原地转圈（可取消）
    LaunchedEffect(parseTarget) {
        val item = parseTarget ?: return@LaunchedEffect
        detailJob?.cancel()
        detailJob?.cancel()
        detailCancelled = false
        detailElapsedSec = 0
        opening = true
        detailJob = scope.launch {
            // 任何出口都先收起 loading：解析、目录、图片取完才真正跳转
            fun finish() {
                opening = false
                parseTarget = null
            }
            val active = session ?: run { finish(); return@launch }
            detailError = null
            text = null
            textUrl = null
            val outcome = runCatching { withContext(Dispatchers.IO) { active.detail(item) } }
            // 用户点了取消：静默结束，不进入错误页
            if (detailCancelled) {
                finish()
                return@launch
            }
            val info = outcome.getOrNull()
            detailError = outcome.exceptionOrNull()?.let { it.message ?: it.toString() }
            if (info != null) {
            withContext(Dispatchers.IO) { rememberOpened(repository, siteKey, item, info, session?.homeUrl) }
            }

            // 视频：解析出地址后直接播放
            if (info != null && (info.detailType == DetailType.MP4 || info.detailType == DetailType.M3U8)) {
                val saved = withContext(Dispatchers.IO) { repository.progress(ProgressKeys.key(item.jumpUrl)) }
                screen = Screen.VideoPlayer(
                    item = item,
                    sources = info.details,
                    startIndex = 0,
                    // 默认从 0 开始播；历史进度只作为「继续观看」提示（2 秒内可点）
                    startMs = 0L,
                    resumePositionMs = saved?.positionMs ?: 0L,
                )
                finish()
                return@launch
            }
            // 剧集：线路 Tab → 集 → 播放
            if (info != null && info.detailType == DetailType.SERIES) {
                screen = Screen.Series(item, info)
                finish()
                return@launch
            }
            // 图片：直接进阅读器，从上次的页续看
            // 图片（非 comic）：直接进单图阅读，从上次的页续看
            if (info != null && info.detailType == DetailType.IMAGE && info.details.isNotEmpty()) {
                val saved = withContext(Dispatchers.IO) { repository.progress(ProgressKeys.key(item.jumpUrl)) }
                screen = Screen.SinglePage(
                    item = item,
                    pages = info.details,
                    startIndex = saved?.chapterOffset ?: 0,
                )
                finish()
                return@launch
            }
            // 图集（有目录）：先取目录，再按章节取图片；单章时直接进阅读器
            if (info != null && info.detailType == DetailType.COMIC && info.details.isNotEmpty()) {
                val chapterUrl = info.details.first().url
                val chapters = runCatching { withContext(Dispatchers.IO) { active.comicChapters(chapterUrl) } }
                    .getOrDefault(emptyList())
                val saved = withContext(Dispatchers.IO) { repository.progress(ProgressKeys.key(item.jumpUrl)) }
                // 续看要回到「上次那一话」：只记页码、不记话数会永远从第一话开始
                val startChapter = (saved?.chapterIndex ?: 0)
                    .coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
                // 18C 这类：目录返回「开始阅读」一条，url 指到真正的图页
                val target = chapters.getOrNull(startChapter)?.url?.takeIf { it.isNotBlank() }
                    ?: chapters.firstOrNull { it.url.isNotBlank() }?.url
                    ?: chapterUrl
                val images = runCatching { withContext(Dispatchers.IO) { active.comicPages(target) } }
                    .getOrDefault(emptyList())
                if (images.isNotEmpty()) {
                    val sameChapter = saved != null && saved.chapterIndex == startChapter
                    screen = Screen.ComicReader(
                        item = item,
                        pages = images.map { com.ghs.mobile.core.model.Detail(url = it.url) },
                        startIndex = if (sameChapter) saved?.chapterOffset ?: 0 else 0,
                        chapters = chapters,
                        chapterIndex = chapters.indexOfFirst { it.url == target }.coerceAtLeast(0),
                    )
                    finish()
                    return@launch
                }
            }
            if (info?.detailType == DetailType.TEXT) {
                textUrl = info.details.firstOrNull()?.url ?: item.jumpUrl
            }
            // 用户已经取消：静默回列表，不落到详情/失败页
            if (detailCancelled) {
                finish()
                return@launch
            }
            // 其它情况（文本 / 解析失败）落到详情页展示
            screen = Screen.Detail(item, info)
            finish()
        }
    }

    // 解析计时：只在解析中累加
    LaunchedEffect(parseTarget) {
        if (parseTarget == null) return@LaunchedEffect
        while (true) {
            delay(1_000)
            if (parseTarget == null) break
            detailElapsedSec += 1
        }
    }
    // 文本章节：按当前章节 URL 加载，并回写进度
    LaunchedEffect(textUrl) {
        val url = textUrl ?: return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        val item = (screen as? Screen.Detail)?.item
        textLoading = true
        val loaded = runCatching { withContext(Dispatchers.IO) { active.textContent(url) } }.getOrNull()
        text = loaded
        textLoading = false
        if (item != null && loaded != null) {
            withContext(Dispatchers.IO) {
                val chapterIndex = Regex("page=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: 0
                repository.saveProgress(
                    ReadingProgress(
                        itemUrl = ProgressKeys.key(item.jumpUrl),
                        kind = ReaderKind.TEXT,
                        chapterUrl = url,
                        chapterIndex = chapterIndex,
                        chapterCount = 0,
                        chapterOffset = 0,
                        percent = 0.0,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        }
    }

    /**
     * 解析某一集并进入播放器。
     *
     * 剧集的「选集」「换线路」以及首次续看都复用这里，避免三处各写一遍解析逻辑。
     */
    fun playSeriesEpisode(
        item: Item,
        info: DetailInfo?,
        analysis: kotlin.collections.List<Analysis>,
        line: Analysis,
        episodes: kotlin.collections.List<AnalysisDetail>,
        episodeIndex: Int,
        startMs: Long,
        resumeMs: Long,
    ) {
        val episode = episodes.getOrNull(episodeIndex) ?: return
        scope.launch {
            val video = runCatching {
                withContext(Dispatchers.IO) { session?.episodeVideo(episode) }
            }.getOrNull()
            if (video != null && video.url.isNotEmpty()) {
                screen = Screen.VideoPlayer(
                    item = item,
                    sources = listOf(
                        com.ghs.mobile.core.model.Detail(
                            url = video.url,
                            quality = video.name.ifEmpty { episode.title },
                            title = video.name,
                        ),
                    ),
                    startIndex = 0,
                    startMs = startMs,
                    referer = deviceReferer(session, item),
                    resumePositionMs = resumeMs,
                    series = SeriesPlayback(
                        analysis = analysis,
                        lineUrl = line.url,
                        episodes = episodes,
                        currentIndex = episodeIndex,
                    ),
                )
            }
        }
    }

    // 阅读器自带顶栏：直接整屏渲染，避免与外层 Scaffold 叠成双顶栏
    when (val reader = screen) {
        is Screen.ComicReader -> {
            // 章节切换：取新章节的图片，保留目录与「预加载下一话」开关
            fun openChapter(index: Int) {
                val chapter = reader.chapters.getOrNull(index) ?: return
                val active = session ?: return
                scope.launch {
                    val images = runCatching {
                        withContext(Dispatchers.IO) { active.comicPages(chapter.url) }
                    }.getOrDefault(emptyList())
                    if (images.isEmpty()) return@launch
                    screen = reader.copy(
                        pages = images.map { com.ghs.mobile.core.model.Detail(url = it.url) },
                        startIndex = 0,
                        chapterIndex = index,
                    )
                }
            }

            ComicReaderScreen(
                title = reader.item.title,
                pages = reader.pages,
                chapters = reader.chapters,
                startIndex = reader.startIndex,
                quality = imageQuality,

                onQualityChange = { next ->
                    imageQuality = next
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            repository.putSetting(SettingScope.PREFS, "imageQuality", next.name.lowercase())
                        }
                    }
                },
                onProgress = { index ->
                    scope.launch {
                        repository.saveProgress(
                            ReadingProgress(
                                itemUrl = ProgressKeys.key(reader.item.jumpUrl),
                                kind = ReaderKind.IMAGE,
                                chapterUrl = reader.chapters.getOrNull(reader.chapterIndex)?.url.orEmpty(),
                                chapterIndex = reader.chapterIndex,
                                chapterCount = reader.chapters.size.coerceAtLeast(1),
                                chapterOffset = index,
                                percent = ((index + 1) * 100.0 / reader.pages.size.coerceAtLeast(1)),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onBack = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading },
                chapterIndex = reader.chapterIndex,
                preloadNext = reader.preloadNext,
                onPreloadNextChange = { enabled -> screen = reader.copy(preloadNext = enabled) },
                onChapterSelected = { index -> openChapter(index) },
                onNextChapter = { openChapter(reader.chapterIndex + 1) },
                onOpenSinglePage = { index ->
                    screen = Screen.SinglePage(
                        item = reader.item,
                        pages = reader.pages,
                        startIndex = index,
                        chapterIndex = reader.chapterIndex,
                        chapters = reader.chapters,
                        preloadNext = reader.preloadNext,
                        fromComic = true,
                    )
                },
            )
            return
        }

        is Screen.SinglePage -> {
            SinglePageReaderScreen(
                title = reader.item.title,
                pages = reader.pages,
                startIndex = reader.startIndex,
                onProgress = { index ->
                    scope.launch {
                        repository.saveProgress(
                            ReadingProgress(
                                itemUrl = ProgressKeys.key(reader.item.jumpUrl),
                                kind = ReaderKind.IMAGE,
                                chapterUrl = reader.chapters.getOrNull(reader.chapterIndex)?.url.orEmpty(),
                                chapterIndex = reader.chapterIndex,
                                chapterCount = reader.chapters.size.coerceAtLeast(1),
                                chapterOffset = index,
                                percent = ((index + 1) * 100.0 / reader.pages.size.coerceAtLeast(1)),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                // 关闭：来自漫画阅读就回去；否则回列表（单图阅读是独立组件）
                onClose = {
                    if (reader.fromComic) {
                        screen = Screen.ComicReader(
                            item = reader.item,
                            pages = reader.pages,
                            startIndex = reader.startIndex,
                            chapters = reader.chapters,
                            chapterIndex = reader.chapterIndex,
                            preloadNext = reader.preloadNext,
                        )
                    } else {
                        screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading
                    }
                },
            )
            return
        }

        is Screen.Series -> {
            var selected by remember(reader.item.jumpUrl) { mutableStateOf<Analysis?>(null) }
            var episodes by remember(reader.item.jumpUrl) {
                mutableStateOf<kotlin.collections.List<AnalysisDetail>>(emptyList())
            }
            var seriesLoading by remember { mutableStateOf(false) }
            var seriesError by remember { mutableStateOf<String?>(null) }

            // 上次看过：直接进那一集，并带上「上次看到 mm:ss」的续看提示
            var restored by remember(reader.item.jumpUrl) { mutableStateOf(false) }
            LaunchedEffect(reader.item.jumpUrl) {
                if (restored) return@LaunchedEffect
                restored = true
                val saved = withContext(Dispatchers.IO) { repository.progress(ProgressKeys.key(reader.item.jumpUrl)) }
                if (saved == null) return@LaunchedEffect
                if (saved.chapterUrl.isBlank()) return@LaunchedEffect
                val line = reader.info.analysis.firstOrNull { it.url == saved.chapterUrl }
                    ?: return@LaunchedEffect
                val active = session ?: return@LaunchedEffect
                seriesLoading = true
                val list = runCatching { withContext(Dispatchers.IO) { active.episodes(line) } }
                    .getOrDefault(emptyList())
                seriesLoading = false
                if (list.isEmpty()) return@LaunchedEffect
                playSeriesEpisode(
                    item = reader.item,
                    info = reader.info,
                    analysis = reader.info.analysis,
                    line = line,
                    episodes = list,
                    episodeIndex = saved.chapterIndex.coerceIn(0, list.size - 1),
                    startMs = 0L,
                    resumeMs = saved.positionMs,
                )
            }

            // 选中分段 → 取该分段下的每一集
            LaunchedEffect(selected) {
                val part = selected ?: return@LaunchedEffect
                val active = session ?: return@LaunchedEffect
                seriesLoading = true
                seriesError = null
                val result = runCatching { withContext(Dispatchers.IO) { active.episodes(part) } }
                episodes = result.getOrDefault(emptyList())
                seriesError = result.exceptionOrNull()?.message
                seriesLoading = false
            }

            SeriesScreen(
                title = reader.item.title,
                analysis = reader.info.analysis,
                loadEpisodes = { lineUrl ->
                    val line = reader.info.analysis.firstOrNull { it.url == lineUrl }
                    if (line == null) emptyList() else withContext(Dispatchers.IO) {
                        runCatching { session?.episodes(line) }.getOrNull().orEmpty()
                    }
                },
                onPlay = { lineUrl, episodeIndex ->
                    val line = reader.info.analysis.firstOrNull { it.url == lineUrl }
                    if (line != null && session != null) {
                        scope.launch {
                            val list = withContext(Dispatchers.IO) {
                                runCatching { session?.episodes(line) }.getOrNull().orEmpty()
                            }
                            if (list.isNotEmpty()) {
                                playSeriesEpisode(
                                    item = reader.item,
                                    info = reader.info,
                                    analysis = reader.info.analysis,
                                    line = line,
                                    episodes = list,
                                    episodeIndex = episodeIndex.coerceIn(0, list.size - 1),
                                    startMs = 0L,
                                    resumeMs = 0L,
                                )
                            }
                        }
                    }
                },
                onBack = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading },
            )
            return
        }

        is Screen.VideoPlayer -> {
            VideoPlayerScreen(
                title = reader.item.title,
                sources = reader.sources,
                startIndex = reader.startIndex,
                startPositionMs = reader.startMs,
                referer = reader.referer,
                resumePositionMs = reader.resumePositionMs,
                series = reader.series,
                onPrevEpisode = reader.series?.takeIf { ctx -> ctx.currentIndex > 0 }?.let { ctx ->
                    {
                        playSeriesEpisode(
                            item = reader.item,
                            info = reader.backToSeries,
                            analysis = ctx.analysis,
                            line = ctx.currentLine ?: ctx.analysis.first(),
                            episodes = ctx.episodes,
                            episodeIndex = ctx.currentIndex - 1,
                            startMs = 0L,
                            resumeMs = 0L,
                        )
                    }
                },
                onNextEpisode = reader.series?.takeIf { ctx -> ctx.currentIndex < ctx.episodes.size - 1 }?.let { ctx ->
                    {
                        playSeriesEpisode(
                            item = reader.item,
                            info = reader.backToSeries,
                            analysis = ctx.analysis,
                            line = ctx.currentLine ?: ctx.analysis.first(),
                            episodes = ctx.episodes,
                            episodeIndex = ctx.currentIndex + 1,
                            startMs = 0L,
                            resumeMs = 0L,
                        )
                    }
                },
                onSelectEpisode = { nextIndex ->
                    val ctx = reader.series
                    if (ctx != null) {
                        playSeriesEpisode(
                            item = reader.item,
                            info = reader.backToSeries,
                            analysis = ctx.analysis,
                            line = ctx.currentLine ?: ctx.analysis.first(),
                            episodes = ctx.episodes,
                            episodeIndex = nextIndex,
                            startMs = 0L,
                            resumeMs = 0L,
                        )
                    }
                },
                loadEpisodes = { lineUrl ->
                    val line = reader.series?.analysis?.firstOrNull { it.url == lineUrl }
                    if (line == null) emptyList() else withContext(Dispatchers.IO) {
                        runCatching { session?.episodes(line) }.getOrNull().orEmpty()
                    }
                },
                onPlayEpisode = { lineUrl, nextIndex ->
                    val ctx = reader.series
                    val line = ctx?.analysis?.firstOrNull { it.url == lineUrl }
                    if (ctx != null && line != null) {
                        scope.launch {
                            val list = runCatching {
                                withContext(Dispatchers.IO) { session?.episodes(line) }
                            }.getOrNull().orEmpty()
                            if (list.isNotEmpty()) {
                                playSeriesEpisode(
                                    item = reader.item,
                                    info = reader.backToSeries,
                                    analysis = ctx.analysis,
                                    line = line,
                                    episodes = list,
                                    episodeIndex = nextIndex.coerceIn(0, list.size - 1),
                                    startMs = 0L,
                                    resumeMs = 0L,
                                )
                            }
                        }
                    }
                },
                onProgress = { _, position, duration ->
                    val ctx = reader.series
                    scope.launch {
                        repository.saveProgress(
                            ReadingProgress(
                                itemUrl = ProgressKeys.key(reader.item.jumpUrl),
                                kind = ReaderKind.VIDEO,
                                // 剧集：把「线路 + 第几集」也记下来，下次直接续上
                                chapterUrl = ctx?.lineUrl ?: reader.sources.getOrNull(reader.startIndex)?.url.orEmpty(),
                                chapterIndex = ctx?.currentIndex ?: 0,
                                chapterCount = ctx?.episodes?.size ?: ctx?.analysis?.size ?: 0,
                                positionMs = position,
                                durationMs = duration,
                                percent = if (duration > 0) position * 100.0 / duration else 0.0,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onBack = {
                    val back = reader.backToSeries
                    screen = when {
                        back != null -> Screen.Series(reader.item, back)
                        returnScreen != null -> returnScreen!!
                        else -> lastPage?.let { Screen.List(it) } ?: Screen.Loading
                    }
                    // 退出播放器回到「点进来的那个 tab」：收藏进来就回收藏
                    tab = returnTab
                },
            )
            // 整屏播放器必须 return：否则外层 Scaffold 会把列表画在视频上（只剩声音）
            return
        }
        is Screen.Detail -> if (reader.info?.detailType == DetailType.TEXT) {
            TextReaderScreen(
                itemTitle = reader.item.title,
                content = text,
                loading = textLoading,
                onNavigate = { url -> if (url.isNotEmpty()) textUrl = url },
                onBack = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading },
            )
            return
        }

        else -> Unit
    }

    // 调试台（F25）：把当前状态上报给内置 Web 服务
    LaunchedEffect(screen, tab, configKey) {
        val list = (screen as? Screen.List)?.page
        val stateName = when (screen) {
            is Screen.Loading -> "Loading"
            is Screen.List -> "List"
            is Screen.Detail -> "Detail"
            is Screen.ComicReader -> "ComicReader"
            is Screen.SinglePage -> "SinglePage"
            is Screen.VideoPlayer -> "VideoPlayer"
            is Screen.Series -> "Series"
            is Screen.SearchResults -> "SearchResults"
            is Screen.Failed -> "Failed"
        }
        val json = org.json.JSONObject()
            .put("ready", true)
            .put("configKey", configKey ?: CONFIG_KEY)
            .put("tab", tab.name)
            .put("screen", stateName)
            .put("url", currentListUrl ?: session?.homeUrl ?: "")
            .put("items", list?.items?.size ?: 0)
            .put("tags", list?.tags?.size ?: 0)
            .put("filterGroups", list?.urlReplace?.size ?: 0)
            .put("detailError", detailError ?: "")
            .put("logCount", logEntries.size)
            .put("updatedAt", System.currentTimeMillis())
        com.ghs.mobile.web.DebugHub.publish(json.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 详情页可以从收藏/历史里打开：标题跟着内容走，不然会顶着「收藏」两个字看详情
                    Text(
                        if (screen is Screen.Detail) {
                            titleFor(screen)
                        } else {
                            when (tab) {
                                DemoTab.HOME -> ""
                                DemoTab.FAVORITES -> "收藏"
                                DemoTab.HISTORY -> "历史"
                                DemoTab.TAGS -> "标签"
                            }
                        },
                    )
                },
                actions = {
                    // 站点名：点开抽屉切换站点（对应参考的"选择站点"）
                    TextButton(onClick = {
                        showSiteSheet = true
                        scope.launch { configs = withContext(Dispatchers.IO) { app.configs.list() } }
                    }) {
                        val favicon = configs.firstOrNull { it.key == configKey }?.let { siteFavicon(it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!favicon.isNullOrBlank()) {
                                AsyncImage(
                                    model = favicon,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(siteLabel())
                        }
                    }

                    // 筛选：仅当配置返回了 urlReplace 分组时出现
                    val filterGroups = (screen as? Screen.List)?.page?.urlReplace.orEmpty()
                    if (filterGroups.isNotEmpty()) {
                        TextButton(onClick = { showFilterSheet = true }) {
                            Text(if (filterLoading) "筛选中…" else "筛选")
                        }
                    }
                    // 页码：右上角，点开抽屉选页
                    if (tab == DemoTab.HOME && (screen is Screen.List || screen is Screen.SearchResults)) {
                        val pages = ((screen as? Screen.List)?.page
                            ?: (screen as? Screen.SearchResults)?.page)?.pagination.orEmpty()
                        if (pages.isNotEmpty()) {
                            TextButton(onClick = { showPageSheet = true }) {
                                Text(pages.firstOrNull { it.isCurrent }?.title?.ifEmpty { "1" } ?: "1")
                            }
                        }
                    }
                    // 布局切换：单列 / 双列
                    TextButton(onClick = {
                        layoutMode = if (layoutMode == ItemCardLayout.GRID) {
                            ItemCardLayout.LIST
                        } else {
                            ItemCardLayout.GRID
                        }
                    }) {
                        Text(if (layoutMode == ItemCardLayout.GRID) "双列" else "单列")
                    }
                    if (tab == DemoTab.HOME && (screen is Screen.List || screen is Screen.SearchResults)) {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                },
                navigationIcon = {
                    // 详情 / 搜索结果只显示「返回」；其余页面只显示「菜单」——两者不叠加
                    val showBack = screen is Screen.Detail ||
                        (tab == DemoTab.HOME && screen is Screen.SearchResults)
                    if (showBack) {
                        IconButton(onClick = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    } else {
                        IconButton(onClick = { drawerOpen = true }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    }
                },
            )
        },
        // 首页刷新按钮放在 Scaffold 槽里，保证在内容之上可点
        floatingActionButton = {
            if (tab == DemoTab.HOME && screen is Screen.List) {
                FloatingActionButton(
                    onClick = { refreshCurrent() },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                DemoTab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {
                            Icon(
                                when (entry) {
                                    DemoTab.HOME -> Icons.Default.Home
                                    DemoTab.FAVORITES -> Icons.Default.Favorite
                                    DemoTab.HISTORY -> Icons.Default.List
                                    DemoTab.TAGS -> Icons.Default.Place
                                },
                                contentDescription = entry.label,
                            )
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 收藏 / 历史：只在当前 tab 上原地打开，不切回首页
            // （切 tab 会把收藏页整棵销毁 → 先看见首页、返回后滚动位置也没了）
            val libraryOnTop = tab != DemoTab.HOME && screen !is Screen.Detail
            if (tab == DemoTab.FAVORITES && libraryOnTop) {
                FavoritesScreen(
                    items = favoriteItems,
                    progress = progressByUrl,
                    collected = collected,
                    layout = layoutMode,
                    gridState = favoritesGridState,
                    onOpen = { item ->
                        returnTab = DemoTab.FAVORITES
                        returnScreen = null
                        parseTarget = item
                    },
                    onToggleCollect = { item -> toggleCollect(item) },
                )
                return@Surface
            }
            if (tab == DemoTab.HISTORY && libraryOnTop) {
                HistoryScreen(
                    entries = historyEntries,
                    collected = collected,
                    progress = progressByUrl,
                    layout = layoutMode,
                    gridState = historyGridState,
                    onOpen = { item ->
                        returnTab = DemoTab.HISTORY
                        returnScreen = null
                        parseTarget = item
                    },
                    onToggleCollect = { item -> toggleCollect(item) },
                )
                return@Surface
            }

            if (tab == DemoTab.TAGS && libraryOnTop) {
                TagsScreen(
                    tags = lastPage?.tags.orEmpty(),
                    onOpen = { tag -> if (tag.url.isNotBlank()) openFromUrl(tag.url, tag.title) },
                )
                return@Surface
            }

            when (val current = screen) {
                Screen.Loading -> Centered {
                    CircularProgressIndicator()
                    Text("正在解析演示配置…", modifier = Modifier.padding(top = 12.dp))
                }

                is Screen.Failed -> Centered {
                    Text("解析失败", style = MaterialTheme.typography.titleMedium)
                    Text(current.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

                    // 失败原因常常是"配的地址不是 HTTP 代理"，把判定线索直接摆出来
                    val rules = app.proxy.rules.value
                    val endpoint = rules.endpoint
                    if (rules.enabled && endpoint != null) {
                        Text(
                            "已启用代理 ${endpoint.authority}（白名单 ${rules.whitelist.size} 条）\n" +
                                "若错误为 unexpected end of stream / CONNECT 失败，\n" +
                                "说明该地址不是 HTTP 代理，或目标域名未放行。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
                        )
                    }
                    Button(
                        onClick = {
                            screen = Screen.Loading
                            reloadRequest += 1
                        },
                        modifier = Modifier.padding(top = 20.dp),
                    ) {
                        Text("重试")
                    }
                    // 失败页要能换站点：否则某个站点挂了就彻底出不去
                    if (configs.size > 1) {
                        OutlinedButton(
                            onClick = {
                                // 只换到启用中的站点，别把停用的那批又放进来
                                val next = configs.firstOrNull { it.key != configKey && it.enabled }
                                if (next != null) {
                                    configKey = next.key
                                    screen = Screen.Loading
                                    reloadRequest += 1
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            repository.putSetting(SettingScope.PREFS, "currentConfig", next.key)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(top = 10.dp),
                        ) {
                            Text("换个站点")
                        }
                    }
                }

                is Screen.SearchResults -> Column(Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "「${current.keyword}」的结果 · ${current.page.items.size} 项",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        TextButton(onClick = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading }) {
                            Text("清除")
                        }
                    }
                    ListScreen(
                        page = current.page,
                        progress = progressByUrl,
                        collected = collected,
                        layout = layoutMode,
                        gridState = homeGridState,
                        loadingMore = loadingMore,
                        noMore = noMore,
                        onOpen = { item ->
                            // 从搜索结果进来：返回回到这一页（保留搜索词与分页）
                            returnScreen = Screen.SearchResults(current.keyword, current.page)
                            returnScreen = Screen.SearchResults(current.keyword, current.page)
                            parseTarget = item
                        },
                        onToggleCollect = { item -> toggleCollect(item) },
                        onLoadMore = { loadMore() },
                    )
                }

                is Screen.List -> Column(Modifier.fillMaxSize()) {
                    if (searchOpen) {
                        DemoSearchBar(
                            keyword = searchKeyword,
                            error = searchError,
                            onKeywordChange = { searchKeyword = it },
                            onSubmit = { runSearch(searchKeyword) },
                        )
                    }
                    ListScreen(
                        page = current.page,
                        progress = progressByUrl,
                        collected = collected,
                        layout = layoutMode,
                        gridState = homeGridState,
                        loadingMore = loadingMore,
                        noMore = noMore,
                        onOpen = { item ->
                            returnScreen = Screen.List(current.page)
                            returnTab = DemoTab.HOME
                            returnScreen = Screen.List(current.page)
                            parseTarget = item
                        },
                        onToggleCollect = { item -> toggleCollect(item) },
                        onLoadMore = { loadMore() },
                    )
                }

                is Screen.Detail -> DetailScreen(
                    item = current.item,
                    info = current.info,
                    error = detailError,
                    resumeIndex = progressByUrl[current.item.jumpUrl]?.chapterOffset ?: 0,
                    onOpenComicPage = { index ->
                        val info = current.info
                        if (info != null && info.details.isNotEmpty()) {
                            screen = Screen.ComicReader(current.item, info.details, index)
                        }
                    },
                )

                // 阅读器在上面已整屏返回，这里只是为了让 when 穷尽
                is Screen.ComicReader, is Screen.SinglePage -> Unit
                is Screen.VideoPlayer -> Unit
                is Screen.Series -> Unit
            }
        }

        // 加载中（刷新 / 标签跳转 / 页码跳转）：内容上方盖遮罩 + 转圈
        if (refreshing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "正在加载…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }


    // 日志入口在左侧抽屉里：点开看配置脚本输出的日志
    val latestLog = logEntries.lastOrNull()
    // 清除缓存：确认框里展示分项占用
    if (showClearCache) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearCache = false },
            title = { Text("清除缓存") },
            text = {
                Text(
                    "当前占用 " + ImageCache.format(ImageCache.sizeBytes()) +
                        "\n图片字节 " + ImageCache.format(ImageCache.rawSizeBytes()) +
                        "\n解码后图片 " + ImageCache.format(ImageCache.bitmapSizeBytes()) +
                        "\n网页 HTML " + ImageCache.format(ImageCache.htmlSizeBytes()),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        ImageCache.clearAll()
                        app.imageLoader.memoryCache?.clear()
                        app.imageLoader.diskCache?.clear()
                        showClearCache = false
                    },
                ) { Text("清除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearCache = false }) { Text("取消") }
            },
        )
    }

    if (logSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { logSheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("引擎日志 · ${logEntries.size}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { com.ghs.mobile.core.engine.script.LogHub.clear() }) { Text("清空") }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(logEntries.reversed()) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }

    // 解析详情弹窗：显示已用时间，可手动取消
    val parsingDetail = opening
    if (parsingDetail) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                tonalElevation = 6.dp,
                modifier = Modifier.width(320.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(40.dp))
                    Text(
                        "正在解析详情…",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                    parseTarget?.let { item ->
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Text(
                        "已用 ${detailElapsedSec}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(
                        onClick = {
                            // 手动取消：先标记，保证后续不会落到失败页
                            detailCancelled = true
                            session?.cancelPending()
                            detailJob?.cancel()
                            detailJob = null
                            detailError = null
                            parseTarget = null
                            // 必须同时收起 loading：否则弹窗会一直停在「正在解析详情…」
                            opening = false
                            detailElapsedSec = 0
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    ) { Text("取消") }
                }
            }
        }
    }

    // 左侧抽屉：直接展开设置内容（不做二级菜单），底部是引擎自检
    AnimatedVisibility(
        visible = drawerOpen,
        enter = fadeIn() + slideInHorizontally { -it / 2 },
        exit = fadeOut() + slideOutHorizontally { -it / 2 },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable { drawerOpen = false },
            )
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.88f),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 24.dp)
                        // 整块一起滚动：引擎日志 / 自检 / 清缓存也跟随滚动，不再钉在底部
                        .verticalScroll(rememberScrollState()),
                ) {
                    drawerSettings()
                    NavigationDrawerItem(
                        label = {
                            Text(
                                if (logEntries.isEmpty()) "引擎日志"
                                else "引擎日志 · ${logEntries.size}",
                            )
                        },
                        selected = false,
                        onClick = {
                            drawerOpen = false
                            logSheetOpen = true
                        },
                        icon = { Icon(Icons.Default.List, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("引擎自检") },
                        selected = false,
                        onClick = {
                            drawerOpen = false
                            onOpenProbe()
                        },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("网页浏览（可选代理/直连）") },
                        selected = false,
                        onClick = {
                            drawerOpen = false
                            onOpenBrowser()
                        },
                        icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("清除缓存 · ${ImageCache.format(ImageCache.sizeBytes())}") },
                        selected = false,
                        onClick = {
                            drawerOpen = false
                            showClearCache = true
                        },
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("清空本站历史") },
                        selected = false,
                        onClick = {
                            drawerOpen = false
                            scope.launch {
                                withContext(Dispatchers.IO) { repository.clearHistory(siteKey) }
                                refreshLibrary()
                            }
                        },
                        icon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }

    // 筛选/排序面板：每个 schema 一组，点一项即重新取页
    if (showFilterSheet) {
        val groups = (screen as? Screen.List)?.page?.urlReplace.orEmpty()
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                Text("筛选与排序", style = MaterialTheme.typography.titleSmall)
                groups.forEach { group ->
                    if (group.schema.isNotBlank()) {
                        Text(
                            group.schema,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                        )
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.urlAppend.forEach { entry ->
                            FilterChip(
                                selected = entry.current,
                                onClick = { applyFilter(group.schema, entry.value) },
                                label = { Text(entry.title.ifEmpty { entry.value }) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 站点抽屉：标题 + 搜索 + 两列站点卡片，当前打勾
    if (showSiteSheet) {
        SitePickerSheet(
            // 只列启用的站点；当前站点即使被停用也保留，否则抽屉里连自己都找不到
            sites = configs.filter { it.enabled || it.key == configKey },
            currentKey = configKey,
            onPick = { key ->
                showSiteSheet = false
                configKey = key
                tab = DemoTab.HOME
                scope.launch {
                    withContext(Dispatchers.IO) {
                        repository.putSetting(SettingScope.PREFS, "currentConfig", key)
                    }
                }
            },
            onDismiss = { showSiteSheet = false },
        )
    }

    // 页码抽屉：右上角点页码打开（列表与搜索结果共用）
    if (showPageSheet) {
        val pages = ((screen as? Screen.List)?.page
            ?: (screen as? Screen.SearchResults)?.page)?.pagination.orEmpty()
        PagePickerSheet(
            pages = pages,
            onPick = { url ->
                showPageSheet = false
                val search = screen as? Screen.SearchResults
                if (search != null) {
                    // 搜索结果翻页：保持搜索状态，不要跳回普通列表
                    val active = session
                    if (active != null) {
                        scope.launch {
                            val page = runCatching {
                                withContext(Dispatchers.IO) { active.pageFrom(url) }
                            }.getOrNull()
                            if (page != null) {
                                noMore = false
                                screen = Screen.SearchResults(search.keyword, page)
                            }
                        }
                    }
                } else {
                    openFromUrl(url, null)
                }
            },
            onDismiss = { showPageSheet = false },
        )
    }
}

private suspend fun rememberOpened(
    repository: LocalRepository,
    configKey: String,
    item: Item,
    info: DetailInfo,
    homeUrl: String?,
) {
    val kind = when (info.detailType) {
        DetailType.TEXT -> ReaderKind.TEXT
        DetailType.COMIC -> ReaderKind.IMAGE
        DetailType.MP4, DetailType.M3U8 -> ReaderKind.VIDEO
        else -> ReaderKind.IMAGE
    }
    val existing = repository.progress(ProgressKeys.key(item.jumpUrl))
    if (existing == null) {
        repository.saveProgress(
            ReadingProgress(
                itemUrl = ProgressKeys.key(item.jumpUrl),
                kind = kind,
                chapterUrl = info.details.firstOrNull()?.url ?: item.jumpUrl,
                chapterIndex = 0,
                chapterCount = info.details.size,
                chapterOffset = 0,
                percent = 0.0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    } else {
        // 已有进度（剧集的「线路 + 第几集 + 播放位置」）时只刷新访问时间：
        // 用空白记录覆盖会把续看信息冲掉，表现为"明明看过却从头开始"
        repository.saveProgress(existing.copy(updatedAt = System.currentTimeMillis()))
    }
    val key = relUrl(item.jumpUrl, homeUrl)
    // 打开即计数：收藏里的 count 表示「同一地址看过多少次」，用于收藏排序
    if (repository.isCollected(configKey, key)) {
        repository.collect(configKey, key, ItemCodec.encode(item))
    }
    repository.recordView(configKey, key, ItemCodec.encode(item))
    repository.putSetting(SettingScope.DEVICE, "last_opened", item.jumpUrl)
}

private fun titleFor(screen: Screen, tagTitle: String? = null): String = when (screen) {
    Screen.Loading -> ""
    is Screen.Failed -> "加载失败"
    is Screen.List -> tagTitle ?: ""
    is Screen.Detail -> screen.item.title.ifEmpty { "详情" }
    is Screen.SearchResults -> "搜索：${screen.keyword}"
    is Screen.VideoPlayer -> screen.item.title.ifEmpty { "播放" }
    is Screen.Series -> screen.item.title.ifEmpty { "剧集" }
    is Screen.ComicReader -> screen.item.title.ifEmpty { "阅读" }
    is Screen.SinglePage -> screen.item.title.ifEmpty { "阅读" }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

// ── 列表 ────────────────────────────────────────────────────────────────────

@Composable
private fun ListScreen(
    page: Page,
    progress: Map<String, ReadingProgress>,
    collected: Set<String>,
    layout: ItemCardLayout,
    loadingMore: Boolean,
    noMore: Boolean,
    onOpen: (Item) -> Unit,
    onToggleCollect: (Item) -> Unit,
    onLoadMore: () -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val columns = if (layout == ItemCardLayout.LIST) 1 else 2

    // 滚到接近底部就预取下一页（留 4 个的余量，避免到底才转圈）
    LaunchedEffect(gridState, page.items.size, noMore) {
        if (noMore) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= page.items.size - 4) onLoadMore()
            }
    }

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
        // key 用「下标 + 地址」：同一页里偶尔出现重复条目，纯地址会撞 key 直接崩
        itemsIndexed(page.items, key = { index, item -> "${index}#${item.jumpUrl}" }) { _, item ->
                ItemCard(
                    item = item,
                    progress = progress[item.jumpUrl],
                    collected = item.jumpUrl in collected,
                    layout = layout,
                    onClick = { onOpen(item) },
                    onToggleCollect = { onToggleCollect(item) },
                )
            }
            item {
                LoadMoreHint(loading = loadingMore, noMore = noMore)
            }
        }
    }
}

@Composable
private fun DetailScreen(
    item: Item,
    info: DetailInfo?,
    error: String?,
    resumeIndex: Int,
    onOpenComicPage: (Int) -> Unit,
) {
    if (info == null) {
        Centered {
            if (error == null) {
                CircularProgressIndicator()
                Text("正在解析详情…", modifier = Modifier.padding(top = 12.dp))
            } else {
                Text("解析详情失败", style = MaterialTheme.typography.titleMedium)
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
                )
                Text(
                    "常见原因：网络/代理不可达、目标页面需要登录、配置里的选择器不匹配。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
                )
            }
        }
        return
    }

    when (info.detailType) {
        DetailType.COMIC -> ComicDetail(info, resumeIndex, onOpenComicPage)
        DetailType.MP4, DetailType.M3U8 -> VideoDetail(info)
        else -> Centered { Text("暂不支持的类型：${info.detailType}") }
    }
}

@Composable
private fun ComicDetail(info: DetailInfo, resumeIndex: Int, onOpenPage: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (resumeIndex > 0) {
            Button(
                onClick = { onOpenPage(resumeIndex) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("继续阅读 · 第 ${resumeIndex + 1} 页")
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(info.details) { index, page ->
                Card(onClick = { onOpenPage(index) }) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                page.title.ifEmpty { "第 ${index + 1} 页" },
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            page.preferredUrl,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextDetail(text: TextContent?) {
    if (text == null) {
        Centered { CircularProgressIndicator() }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(text.title.ifEmpty { "正文" }, style = MaterialTheme.typography.titleLarge)
        }
        items(text.blocks) { block ->
            when (block.type) {
                BlockType.IMAGE -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "图片块\n${block.src}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BlockType.P -> Text(block.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun VideoDetail(info: DetailInfo) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("选择清晰度开始播放", style = MaterialTheme.typography.bodyMedium)
        }
        info.details.forEach { source ->
            val playable = source.url.isNotEmpty()
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(source.quality.ifEmpty { "默认" }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        source.type?.name ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 演示用搜索栏：输入关键字 -> adapterSearchUrl -> 取页 -> 复用列表解析 */
@Composable
private fun DemoSearchBar(
    keyword: String,
    error: String?,
    onKeywordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("搜索关键字") },
            )
            TextButton(onClick = onSubmit) { Text("搜索") }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 下一页地址：当前页之后的第一条非空链接（没有则说明到底了） */
private fun nextPageUrl(page: Page): String? {
    val currentIndex = page.pagination.indexOfFirst { it.isCurrent }
    val candidates = if (currentIndex >= 0) page.pagination.drop(currentIndex + 1) else page.pagination
    return candidates.firstOrNull { it.url.isNotBlank() }?.url
}

/** 播放器/图片下载用的 Referer：取站点 origin，CDN 常校验它 */
private fun deviceReferer(session: ReaderSession?, item: Item): String? {
    val base = session?.homeUrl?.takeIf { it.isNotBlank() } ?: item.jumpUrl
    return runCatching {
        val uri = java.net.URI(base)
        "${uri.scheme}://${uri.host}/"
    }.getOrNull()
}
/**
 * 把绝对地址转成「相对 homeUrl」的地址（保留 path/query/fragment）。
 *
 * 收藏与历史只存这个相对地址：站点换域名时不需要迁移数据，打开时用当前 homeUrl 拼回来。
 * 与当前站点不同源（比如外部链接）的原样保留。
 */
private fun relUrl(url: String, homeUrl: String?): String = runCatching {
    if (homeUrl.isNullOrBlank() || !url.startsWith("http")) return@runCatching url
    val base = java.net.URI(homeUrl)
    val target = java.net.URI(url)
    if (!target.host.equals(base.host, ignoreCase = true)) return@runCatching url
    buildString {
        append(target.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
        target.rawQuery?.let { append('?').append(it) }
        target.rawFragment?.let { append('#').append(it) }
    }
}.getOrDefault(url)

/** 相对地址 → 绝对地址（用当前 homeUrl 的协议与主机） */
private fun absUrl(url: String, homeUrl: String?): String = runCatching {
    if (!url.startsWith("/") || homeUrl.isNullOrBlank()) return@runCatching url
    val base = java.net.URI(homeUrl)
    "${base.scheme}://${base.rawAuthority}$url"
}.getOrDefault(url)
/**
 * 进度也用相对地址做 key（与收藏/历史一致）。
 *
 * 当前站点的 homeUrl 在会话创建时写入，关闭时清空；读写进度都过这里，
 * 站点换域名后还能续上原来的位置。
 */
object ProgressKeys {
    @Volatile
    var homeUrl: String? = null

    fun key(url: String): String = relUrl(url, homeUrl)
}
