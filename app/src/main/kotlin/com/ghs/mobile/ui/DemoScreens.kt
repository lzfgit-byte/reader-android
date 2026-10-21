package com.ghs.mobile.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
    data class ComicReader(val item: Item, val pages: kotlin.collections.List<com.ghs.mobile.core.model.Detail>, val startIndex: Int) : Screen
    data class VideoPlayer(val item: Item, val url: String, val startMs: Long) : Screen
    data class SearchResults(val keyword: String, val page: Page) : Screen
    data class Failed(val message: String) : Screen
}

/** 底部导航 */
enum class DemoTab(val label: String) { HOME("首页"), FAVORITES("收藏"), HISTORY("历史") }

/** 演示配置的 key（收藏/历史按配置分组的键） */
private const val CONFIG_KEY = "demo"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoApp(onOpenProbe: () -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    val app = GhsApp.instance
    val repository = app.repository
    // 会话（WebView 引擎）必须在后台线程创建：引擎内部会等待页面就绪，主线程等待会死锁
    var session by remember { mutableStateOf<ReaderSession?>(null) }
    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    // 记住上一次解析好的列表：返回时直接回填，避免回到 Loading 又无处可去
    var lastPage by remember { mutableStateOf<Page?>(null) }
    var progressByUrl by remember { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    var text by remember { mutableStateOf<TextContent?>(null) }
    var textUrl by remember { mutableStateOf<String?>(null) }
    var textLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(DemoTab.HOME) }
    // 当前配置：来自 files/configs（浏览器里编辑的那份）
    var configKey by remember { mutableStateOf<String?>(null) }
    var configs by remember { mutableStateOf<kotlin.collections.List<ConfigStore.StoredConfig>>(emptyList()) }
    var showConfigMenu by remember { mutableStateOf(false) }
    // 详情解析失败的原因：info == null 既可能是"还在解析"，也可能是"失败了"
    var detailError by remember { mutableStateOf<String?>(null) }
    // 网页端改过配置后由服务端通知：在列表页立即重载，阅读中则等回到列表
    var reloadRequest by remember { mutableStateOf(0) }
    var pendingReload by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchKeyword by remember { mutableStateOf("") }
    var searchError by remember { mutableStateOf<String?>(null) }
    // 列表形态：双列卡片 / 单列行卡片（参考常见阅读客户端）
    var layoutMode by remember { mutableStateOf(ItemCardLayout.GRID) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    var collected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var favoriteItems by remember { mutableStateOf<kotlin.collections.List<Item>>(emptyList()) }
    var historyEntries by remember { mutableStateOf<kotlin.collections.List<HistoryEntry>>(emptyList()) }

    fun refreshLibrary() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val collects = repository.collects(CONFIG_KEY)
                val favorites = collects.mapNotNull { record -> ItemCodec.decode(record.url, record.payload) }
                val history = repository.history(CONFIG_KEY, 100).mapNotNull { record ->
                    ItemCodec.decode(record.url, record.payload)?.let { item -> HistoryEntry(item, record.createTime) }
                }
                Triple(collects.map { it.url }.toSet(), favorites, history)
            }
            collected = snapshot.first
            favoriteItems = snapshot.second
            historyEntries = snapshot.third
        }
    }

    LaunchedEffect(Unit) { refreshLibrary() }

    // 切到收藏/历史时重新读一次：数据可能在别处（阅读、收藏）被改过
    LaunchedEffect(tab) { if (tab != DemoTab.HOME) refreshLibrary() }

    fun toggleCollect(item: Item) {
        scope.launch {
            withContext(Dispatchers.IO) {
                if (repository.isCollected(CONFIG_KEY, item.jumpUrl)) {
                    repository.uncollect(CONFIG_KEY, item.jumpUrl)
                } else {
                    repository.collect(CONFIG_KEY, item.jumpUrl, ItemCodec.encode(item))
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

    /** 滑到底部时取下一页：按 pagination 里当前页之后的第一条链接 */
    fun loadMore() {
        if (loadingMore || noMore) return
        val current = screen as? Screen.List ?: return
        val nextUrl = nextPageUrl(current.page) ?: run {
            noMore = true
            return
        }
        val active = session ?: return
        loadingMore = true
        scope.launch {
            val next = runCatching { withContext(Dispatchers.IO) { active.pageFrom(nextUrl) } }.getOrNull()
            if (next != null && next.items.isNotEmpty()) {
                val known = current.page.items.map { it.jumpUrl }.toSet()
                val merged = current.page.copy(
                    items = current.page.items + next.items.filterNot { it.jumpUrl in known },
                    pagination = next.pagination,
                )
                lastPage = merged
                screen = Screen.List(merged)
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
                ?: all.firstOrNull()
            all to picked
        }
        configs = loaded
        configKey = selected?.key
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
        screen = Screen.Loading
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
        runCatching { withContext(Dispatchers.IO) { created.page() } }
            .onSuccess {
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
            list.page.items.mapNotNull { item -> repository.progress(item.jumpUrl)?.let { item.jumpUrl to it } }.toMap()
        }
    }

    // 打开详情：解析 + 记录进度
    val detailItem = (screen as? Screen.Detail)?.item
    LaunchedEffect(detailItem) {
        val item = detailItem ?: return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        screen = Screen.Detail(item, null)
        detailError = null
        text = null
        textUrl = null
        val outcome = runCatching { withContext(Dispatchers.IO) { active.detail(item) } }
        val info = outcome.getOrNull()
        // 失败必须能看见：否则 info == null 会被界面当成"还在解析"，永远转圈
        detailError = outcome.exceptionOrNull()?.let { it.message ?: it.toString() }
        screen = Screen.Detail(item, info)
        if (info != null) {
            withContext(Dispatchers.IO) { rememberOpened(repository, item, info) }
        }
        if (info?.detailType == DetailType.TEXT) {
            textUrl = info.details.firstOrNull()?.url ?: item.jumpUrl
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
                        itemUrl = item.jumpUrl,
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

    // 阅读器自带顶栏：直接整屏渲染，避免与外层 Scaffold 叠成双顶栏
    when (val reader = screen) {
        is Screen.ComicReader -> {
            var chapters by remember(reader.item.jumpUrl) {
                mutableStateOf<kotlin.collections.List<CContent>>(emptyList())
            }
            LaunchedEffect(reader.item.jumpUrl) {
                val active = session ?: return@LaunchedEffect
                chapters = runCatching {
                    withContext(Dispatchers.IO) { active.contents(reader.item.jumpUrl) }
                }.getOrDefault(emptyList())
            }
            ComicReaderScreen(
                title = reader.item.title,
                pages = reader.pages,
                chapters = chapters,
                startIndex = reader.startIndex,
                onProgress = { index ->
                    scope.launch {
                        repository.saveProgress(
                            ReadingProgress(
                                itemUrl = reader.item.jumpUrl,
                                kind = ReaderKind.IMAGE,
                                chapterUrl = reader.pages.getOrNull(index)?.url.orEmpty(),
                                chapterIndex = 0,
                                chapterCount = reader.pages.size,
                                chapterOffset = index,
                                percent = ((index + 1) * 100.0 / reader.pages.size.coerceAtLeast(1)),
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onBack = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading },
            )
            return
        }

        is Screen.VideoPlayer -> {
            VideoPlayerScreen(
                title = reader.item.title,
                url = reader.url,
                startPositionMs = reader.startMs,
                onProgress = { position, duration ->
                    scope.launch {
                        repository.saveProgress(
                            ReadingProgress(
                                itemUrl = reader.item.jumpUrl,
                                kind = ReaderKind.VIDEO,
                                chapterUrl = reader.url,
                                positionMs = position,
                                durationMs = duration,
                                percent = if (duration > 0) position * 100.0 / duration else 0.0,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
                onBack = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading },
            )
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (tab) {
                            DemoTab.HOME -> titleFor(screen)
                            DemoTab.FAVORITES -> "收藏 · ${favoriteItems.size}"
                            DemoTab.HISTORY -> "历史 · ${historyEntries.size}"
                        },
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = {
                            showConfigMenu = true
                            // 每次打开都重新读：网页端可能刚新建/删除过配置
                            scope.launch {
                                configs = withContext(Dispatchers.IO) { app.configs.list() }
                            }
                        }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "切换配置")
                        }
                        DropdownMenu(expanded = showConfigMenu, onDismissRequest = { showConfigMenu = false }) {
                            if (configs.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("没有可用配置") },
                                    onClick = { showConfigMenu = false },
                                )
                            }
                            configs.forEach { config ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (config.enabled) "" else "（停用）") + config.key,
                                        )
                                    },
                                    onClick = {
                                        showConfigMenu = false
                                        configKey = config.key
                                        tab = DemoTab.HOME
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                repository.putSetting(
                                                    SettingScope.PREFS,
                                                    "currentConfig",
                                                    config.key,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        layoutMode = if (layoutMode == ItemCardLayout.GRID) {
                            ItemCardLayout.LIST
                        } else {
                            ItemCardLayout.GRID
                        }
                    }) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = if (layoutMode == ItemCardLayout.GRID) "切换单列" else "切换双列",
                        )
                    }
                    if (tab == DemoTab.HOME && (screen is Screen.List || screen is Screen.SearchResults)) {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                    IconButton(onClick = onOpenProbe) {
                        Icon(Icons.Default.Info, contentDescription = "引擎自检")
                    }
                },
                navigationIcon = {
                    if (tab == DemoTab.HOME && (screen is Screen.Detail || screen is Screen.SearchResults)) {
                        IconButton(onClick = { screen = lastPage?.let { Screen.List(it) } ?: Screen.Loading }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
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
            if (tab == DemoTab.FAVORITES) {
                FavoritesScreen(
                    items = favoriteItems,
                    progress = progressByUrl,
                    collected = collected,
                    layout = layoutMode,
                    onOpen = { item -> screen = Screen.Detail(item, null); tab = DemoTab.HOME },
                    onToggleCollect = { item -> toggleCollect(item) },
                )
                return@Surface
            }
            if (tab == DemoTab.HISTORY) {
                HistoryScreen(
                    entries = historyEntries,
                    layout = layoutMode,
                    onOpen = { item -> screen = Screen.Detail(item, null); tab = DemoTab.HOME },
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
                        loadingMore = loadingMore,
                        noMore = noMore,
                        onOpen = { item -> screen = Screen.Detail(item, null) },
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
                        loadingMore = loadingMore,
                        noMore = noMore,
                        onOpen = { item -> screen = Screen.Detail(item, null) },
                        onToggleCollect = { item -> toggleCollect(item) },
                        onLoadMore = { loadMore() },
                    )
                }

                is Screen.Detail -> DetailScreen(
                    item = current.item,
                    info = current.info,
                    error = detailError,
                    resumeIndex = progressByUrl[current.item.jumpUrl]?.chapterOffset ?: 0,
                    onPlayVideo = { url -> screen = Screen.VideoPlayer(current.item, url, 0L) },
                    onOpenComicPage = { index ->
                        val info = current.info
                        if (info != null && info.details.isNotEmpty()) {
                            screen = Screen.ComicReader(current.item, info.details, index)
                        }
                    },
                )

                // 阅读器在上面已整屏返回，这里只是为了让 when 穷尽
                is Screen.ComicReader -> Unit
                is Screen.VideoPlayer -> Unit
            }
        }
    }
}

private suspend fun rememberOpened(repository: LocalRepository, item: Item, info: DetailInfo) {
    val kind = when (info.detailType) {
        DetailType.TEXT -> ReaderKind.TEXT
        DetailType.COMIC -> ReaderKind.IMAGE
        DetailType.MP4, DetailType.M3U8 -> ReaderKind.VIDEO
        else -> ReaderKind.IMAGE
    }
    repository.saveProgress(
        ReadingProgress(
            itemUrl = item.jumpUrl,
            kind = kind,
            chapterUrl = info.details.firstOrNull()?.url ?: item.jumpUrl,
            chapterIndex = 0,
            chapterCount = info.details.size,
            chapterOffset = 0,
            percent = 0.0,
            updatedAt = System.currentTimeMillis(),
        ),
    )
    repository.recordView(CONFIG_KEY, item.jumpUrl, ItemCodec.encode(item))
    repository.trimHistory(100)
    repository.putSetting(SettingScope.DEVICE, "last_opened", item.jumpUrl)
}

private fun titleFor(screen: Screen): String = when (screen) {
    Screen.Loading -> "演示来源"
    is Screen.Failed -> "演示来源"
    is Screen.List -> "演示列表 · ${screen.page.items.size} 项"
    is Screen.Detail -> screen.item.title.ifEmpty { "详情" }
    is Screen.SearchResults -> "搜索：${screen.keyword}"
    is Screen.VideoPlayer -> screen.item.title.ifEmpty { "播放" }
    is Screen.ComicReader -> screen.item.title.ifEmpty { "阅读" }
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
) {
    val columns = if (layout == ItemCardLayout.LIST) 1 else 2
    val gridState = rememberLazyGridState()

    // 滚到接近底部就预取下一页（留 4 个的余量，避免到底才转圈）
    LaunchedEffect(gridState, page.items.size, noMore) {
        if (noMore) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (last >= page.items.size - 4) onLoadMore()
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(page.items, key = { it.jumpUrl }) { item ->
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

@Composable
private fun DetailScreen(
    item: Item,
    info: DetailInfo?,
    error: String?,
    resumeIndex: Int,
    onOpenComicPage: (Int) -> Unit,
    onPlayVideo: (String) -> Unit,
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
        DetailType.MP4, DetailType.M3U8 -> VideoDetail(info, onPlayVideo)
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
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
private fun VideoDetail(info: DetailInfo, onPlay: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
            Card(onClick = { if (playable) onPlay(source.url) }) {
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
