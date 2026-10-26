# 变更日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

**本项目约定（见 `AGENTS.md` §3）：功能的新增、删除、改名、范围变更，必须同时更新
`docs/01-product/design.md` 与 `docs/01-product/feature-registry.md`，并在本文件记录。**

分类：`Added` 新增 / `Changed` 变更 / `Removed` 删除 / `Deprecated` 弃用 / `Fixed` 修复 / `Docs` 文档。

---

## [Unreleased]

### Docs

- **技能补第 20 条坑：目录被拆成多段（单话 / 单行本 / 番外），别只抓第一个数组**（`.dsh/skills/ghs-site-to-config/`）：书目页把目录拆成多个 `<div x-data='{ … chapters: [...] … }'>`，每段标题紧跟在属性后面的 `data-flux-subheading` 里；只 `indexOf('chapters: [')` 取第一个就只拿到一段（番外全丢），全拼上又会混进「第N卷」整卷合集。正确做法是遍历所有数组、按"数组之后最近的段标题"归类，再按站点语义取舍排序（本例：单话 + 番外，跳过单行本，番外排最后）。

- **技能补第 18、19 条坑：语言前缀把绝对地址正则打空 / lozad 的 1x1 占位封面**（`.dsh/skills/ghs-site-to-config/`）：① 站点把首页从 `/` 换成 `/cn` 后，所有链接都带上语言前缀（`https://site.com/cn/comics/1128`），`getItems` 里 `^https?://site\.com/comics/\d+$` 这种精确正则一条都匹配不上，整页变 0 条（`tags` 还正常，最容易误判成选择器坏了）；正确做法是正则留可选前缀、或只判 path，且配置里手工拼的地址一律用 `this.homeUrl` 算。② 列表用 lozad 懒加载时，非首屏卡片的 `src` 是 `data:image/png;base64,…` 的 1x1 透明占位、真地址在 `data-src`，只读 `src` 就会「第一张有封面、其余全灰」；要 `data-src || src` 并丢掉 `data:` 开头的值。`SKILL.md` §1 侦察清单与 §3 `homeUrl` 约束已加指针。

- **技能补第 17 条坑：配置里调用 `getHtml` 拿到的还是相对地址**（`.dsh/skills/ghs-site-to-config/`）：站点给的 `href` 都是站内相对路径，引擎只在条目入库时补全 `jumpUrl`；配置自己在 `getDetailInfo` / `getContents` / `getComicImages` 里再取页时拿到的是原样字符串，OkHttp 直接抛 `Expected URL scheme 'http' or 'https'`，表现为「列表正常、一点条目就失败」。现在 `SKILL.md` §3 硬约束要求 `jumpUrl` / 分页 `url` / 目录 `url` 一律在 `getItems` 里拼成绝对地址，坑列表也记下了这条的判据（先看 `jumpUrl` 是不是以 `/` 开头，别急着查选择器）。

- **技能补第 16 条坑：同一站点里搜索页与列表页是两个列表块**（`.dsh/skills/ghs-site-to-config/`）：某站点的搜索结果单独渲染成 `#list_videos_videos_list_search_result_*`，页码参数是 `from_videos+from_albums:N`；分类页/标签页才是 `#list_videos_common_videos_list_*` + `from:N`。分页选择器只写后者时，搜索结果页分页全空（列表有 24 条、但没有下一页）。一并记下三个连带事实：搜索页翻页没有路由（`/search/<kw>/2/` 返回 404，`?page=` 被忽略，只有 `?from_videos=N` 有效）、末页的"下一页"是 `--disabled` 的假链接（值比总页数大 1，顺着用就是 404）、canonical 在该页是解码后的中文（要重新编码，但不能整串 `encodeURI`，否则 `%E5` 变 `%25E5`）。`SKILL.md` §1 侦察清单同步要求「搜索页/筛选页分别抓一次、分别验证」。

- **新增项目级技能 `ghs-site-to-config`**（`.dsh/skills/ghs-site-to-config/`）：把「一个站点」做成动态配置的完整流程——先侦察取证（含需要代理时的出口核对），再写列表 / 分页 / 排序 / 详情选择器，最后在设备上验证条目与媒体，交付时逐项标明已验证与未验证。附 `references/pitfalls.md`（15 条实战坑：Cloudflare 挑战、懒加载封面、AJAX 假分页、`127.0.0.1` 代理陷阱、调试接口只吃单行等）与 inline / manifest 两套模板。配套把 `AGENTS.md` §1 增加 `.dsh/skills/` 目录行与补充规则 6、§7 放开唯一例外（`SKILL.md` 与 `references/*.md`），`docs/README.md` 与 `docs/00-process/document-policy.md` 登记该目录定位——技能只承载**操作流程**，配置格式仍以 `docs/03-api/config-spec.md` 为 SSOT。

- **人机校验复盘按新证据重写（F29）**：13:55–15:57 的连续抓包把原来的「窗口白屏」结论改写成 4 个可修缺陷——① 不带 CF 标记的拦截页被判成「校验通过」（开窗后 2.5 s 自动放行，重放请求仍被挡）；② `403` 两连击把正在进行的校验窗掐死（挑战页自身就是 403，首次加载即计 1，第二次 403 就关窗）；③ 闸门 5 分钟到点判死后没人接收迟到的成功（两次都是超时 300.0 s、页面在 1.4–2.0 s 后才完成）；④ 兜底 WebView 在 `documentElement` 为 null 时抛错。详见 `docs/04-engineering/postmortem-2026-10-25-challenge-loop.md` §5。

- **新增复盘文档**（`docs/04-engineering/postmortem-2026-10-25-challenge-loop.md`）：记录人机校验死循环的现象、三条根因（窗口 `loadUrl` 被注释吞掉 / 验证期间反复重载 / 挑战页被当正文缓存）、取证日志与截图口径、已修复清单，以及仍未解决的部分（Challenge 页在应用内仍白屏）与下一步验证假设。

- **人机校验窗口改用设备真实标识（F29）**：校验窗口不再套桌面 UA（原先 `navigator.userAgent` 声称 Windows 而 `userAgentData` 仍是 Android，Turnstile 判为伪造、永远不过），也不再复用取页窗口与桌面 Client Hints；取页侧仍用桌面 UA，凭证靠共享 Cookie 传递。

- **新增 §8「验证与取证纪律」**（`AGENTS.md`）：调试与验证是代理自己的工作，**用户只做最终结果校验**。日志、截图、接口返回一律自行获取（`adb logcat` / `adb shell screencap` / 内置 Web 服务调试接口 / 应用内日志云），不得要求用户粘贴日志或"帮忙看一下 logcat"；设备不可达时先自救再说明卡点；"已修复/已验证"的结论必须附自取证据，没证据只能写"待验证"；自己没验证到的部分必须明说。同时补入 DoD 第 6 条。

### Changed

- **外观 / Web 管理 / 阅读拆成独立二级页，抽屉只当导航（F28）**：这三组设置原来以表单形式内嵌在左侧抽屉里（和引擎日志、清缓存混在一起往下滚），现在一项一类一页：抽屉里点「外观」「Web 管理」「阅读」各进一个二级页，返回键退回主界面。抽出台阶外壳 `SettingsPage`（顶栏返回 + 可滚动内容），旧 `SettingsScreen` / `SettingsContent` 删除；`DemoApp` 的 `drawerSettings` 复合参数与从没人用的 `Page.SETTINGS` 一并删掉。规范补 §4.5：抽屉只放入口、不得内嵌输入控件/开关/chip。实测四页（外观 / Web 管理 / 网络代理 / 阅读）都能打开，外观页切「深色」立刻变暗、返回后恢复。

- **网络代理改成独立页面，开关与代理列表即时生效（F28）**：代理设置从「设置 / 抽屉里的内嵌表单」拆成独立页（抽屉 → 网络代理）。**启用开关**、**代理列表的选中 / 删除 / 新增**一拨即生效——不用点「应用」，也不用重启；「应用」只负责保存**白名单与强制代理名单**这两个自由文本（边打边存会写很多次库）。规则改成按请求实时读取（`ReaderSession` / `ReaderHost` / `WebViewScriptEngine` / `HttpScriptHost` 全部走 provider，不再把 `ProxyRules` 快照进会话），所以改完立刻作用于已打开的会话：取页、图片、视频、WebView 兜底四条路径一起换；变更后按设计文档 §8.13 清 HTML/图片缓存并重新套用 WebView 代理。顺带修掉两个旧毛病：① 拨开关时会把输入框里的**未保存草稿**一并写库；② 关掉代理后旧会话仍按旧规则取页。补了两条单测锁住「selector 跟随实时规则」。

- **漫画阅读预取窗口从 3 页扩到 12 页，且不再依赖视窗（F05）**：原来只预取当前页后 3 页，而且 `LazyColumn` 只组合视窗内的 item——"没滚到就不加载"，快速下滑时会先看到占位块。现在从当前页起按从上到下的顺序把后面 12 页主动入队（`PREFETCH_AHEAD`），已在位图缓存里的页跳过；挂载与解码仍按页顺序推进，内存不会一次被几十页撑爆。

### Fixed

- **站点图标：不带自己的 Referer、透明图标上浮出首字色块（F01）**：两件事。① 图标请求原先只吃到「当前站点」的 Referer——站点选择器里同时加载十几个站点的图标，等于拿着 A 站的 Referer 去拉 B 站的图标，被防盗链拦了也说不清；现在每个图标用它**自己站点**的 `homeUrl` 当 Referer（新增 `rememberSiteFaviconModel`，顶栏「来源切换」与选择器共用）。② 上一版为了让失败时不退成空方块，把首字色块垫在图标下面，可 ico/png/svg 常有透明背景，字母会从图标里透出来——`91md` 的图标上就浮着一个白色 9。现在改用 `SubcomposeAsyncImage` 的 `loading` / `error` 槽：只有没加载出来时才画色块，成功时只有图标本身。实测（emulator-5554，release 包）：本地起探针服务器（`10.0.2.2:18999`，记录请求头）→ 图标请求到达时是 `referer: https://reftest.example/`（该站点自己的 homeUrl，不是当前站点的）；91md 的图标放大后只剩图标本身，9 与色块都不见了。

- **图床缺 Referer：封面与页图整片 403（F02/F05/F01）**：mycomic 的书封与章节页图都在 `biccam.com`，不带 Referer 一律 403——同一张 `https://biccam.com/comics/1128-6f942d.jpg`，裸请求 403，带 `Referer: https://mycomic.com/` 就是 200 `image/jpeg`（与 UA 无关，桌面/移动/无 UA 都试过），表现就是「列表、目录、章节全都正常，只剩图是空的」。现在图片请求按浏览器的规则补 Referer：取**当前站点的首页地址**（`homeUrl` 的 origin，如 `https://mycomic.com/`），OkHttp 图片请求与 `adapterImageCode` 解图请求都带上；WebView 兜底那条路带上没用——Android WebView 对程序化 `loadUrl` 只发自己算的 Referer，额外传入的会被丢掉（实测服务端收到空），代码里留了注释说明。`ImageSaver` 与播放器原本就按站点 origin 传 Referer，未改。放在应用侧而不是写进配置的原因：图床认的是「你从哪个站点来」，按首页地址就能推导，任何站点都不用逐个声明；`behavior.headers`（配置里写请求头）规范里有、引擎其实没实现，已在 `config-spec.md` §3.3 标注清楚。实测（emulator-5554，release 包）：切到 MYCOMIC 后 36 条书目封面全部出图，进《ONE PIECE》第00話 `1/23` 与 `2/23` 两页连续正常渲染；回切 18C / HWD 封面照旧、站点图标也都正常，无回归。

- **图片的 WebView 兜底与 `data:` 图标其实都没生效（F01/F05）**：`ImageLoader.Builder.components {}` 在 Coil 2 里是**整体替换**组件注册表而不是追加（实现就是 `components(ComponentRegistry.Builder().apply(builder).build())`），连续三次调用让前两次注册的东西被后一次吃掉——`withWebViewFallback` 的拦截器、`withImageDecoding` 的解图取值器、还有这次新加的 svg 解码器只有一个能留下，留下的永远是最后注册的那个。现在四组组件写在同一个 `components {}` 里（svg 解码器 / `data:` 取值器 / 解图取值器 / WebView 兜底拦截器），并删掉两个「往 builder 上挂组件」的扩展函数，避免下一个人再踩同一脚。取证：修复前 svg 图标报的是 `BitmapFactory returned a null bitmap`（说明走的是内置解码器，svg 解码器根本不在链上），修复后同一 URL 记 `Successful (DISK)`；`rman8.com/favicon.ico`（站点本身 404）修复后开始出现 `image http error(HTTP 404) → webview` 行，说明兜底拦截器重新回到了链上。顺带补上 `DataUriFetcher`：配置里 `favicon: 'data:image/png;base64,...'` 以前直接报 `Unable to create a fetcher that supports: data:...`（Coil 2 没有内置 `data:` 取值器），图标位置永远空着；修复后记 `Successful (MEMORY)`。

- **二级页面按返回键直接退出应用（F28 等）**：网络代理是从主界面打开的独立页，但返回键没人接管，按返回就把整个应用退掉了（引擎自检、设置、网页浏览是同一个坑，只是没被撞上）。现在 `AppRoot` 用一个 `BackHandler` 接管所有非主界面页：返回 = 回主界面；内容页（详情 / 阅读器 / 单图 / 播放器 / 搜索结果 / 解析中）继续由 `DemoApp` 的分层逻辑处理，抽屉与 Sheet 优先消费返回。同时把这条写成**强制规范**：`ui-implementation.md` 新增 §4.4「返回键」——返回键永远只退一层，只有主界面才允许退出应用；新增二级页面必须同时接返回键，并列入 §12 验收清单。

- **漫画目录打开后从第一话开始，不定位当前话（F05）**：目录列表每次都从 `1/N` 开始渲染，几十上百话的作品要自己一路翻到当前这一话。现在打开目录直接停在当前话（列表状态随 sheet 重建，每次打开都按当前话定位）。实测《秘密教学》325 话：从第 149 话打开目录，首行即 `154/335 第149话… 当前`；换到第 151 话再打开，首行变成 `156/335 第151话… 当前`。

- **顶栏元素间距过大（F02）**：顶部动作原来是四个 `TextButton`，Material 给的 58–64dp 最小宽度把每个词都撑成一整格，一行并排在 393dp 宽的屏上留下 45–52dp 的可见空隙——看着像哪儿都不挨着。现在全部换成**宽度随内容**的紧凑按钮（水平内边距 6dp、高度仍保留 48dp 触控下限），并重排顶栏：左侧只留一个主体元素（列表页＝来源切换，库页在来源后跟一个次要 Tab 名，详情页＝内容标题），右侧紧凑排列动作，中间留弹性空白。同一台 1080×2344 实测：`筛选↔1` 52.8→15.6dp、`1↔双列` 50.7→16.0dp、`双列↔搜索` 45.4→25.5dp（搜索是 48dp 图标按钮，视觉留白另计）；`来源↔筛选` 由 64.4dp 变成中间那处弹性留白（120.4dp），不再是夹在两个控件之间的缝隙。规则已写进 `docs/02-architecture/ui-implementation.md` §2.3 与 §4.1。

- **标签跳转 / 分页切换后首页列表不回到顶部（F02/F03）**：上一轮为了「返回保持滚动位置」把首页网格的滚动状态提到了外层，结果这份状态在换页时也被沿用——从标签页点标签、或从右上角选页跳转，新列表仍停在旧偏移量上（可能直接落在第 3 屏，还以为没加载出来）。现在把「换页」与「返回」分开：标签跳转、页码跳转、下拉刷新、筛选/排序、换站点、发起新搜索都回到顶部；从详情/播放器/阅读器返回不换页，位置照旧保留。实现上用一次性「回顶待办」（`listScrollPending`），消费一次即清零，避免列表每次重新进入组合（返回上一级、切 tab 回来）都再回一次顶。

- **加了缩放后单图右滑翻页失效（F12）**：缩放手势用 `detectTransformGestures` 常驻接管，1x 下单指横滑也被它消费，Pager 收不到拖动。改为只接管「双指」与「已放大后的单指平移」，1x 下单指横滑放行给 Pager。

- **单图阅读的进度不回写漫画阅读器（F12）**：关闭单图时用进入时的旧 `startIndex` 重建 `Screen.ComicReader`，外面永远停在第一张（单图看到第 6 张、外层仍显示 1/20）。现在记住单图里最后停留的页码并在关闭时作为 `startIndex`。

- **预加载下一话会直接跳话（F12）**：读到本话 60% 时实现里直接调 `onChapterSelected(chapterIndex + 1)`，等于"预加载一触发就切到下一话"，用户还在看当前话就被带走。现在只预取下一话图片进缓存（新增 `preloadChapterPages` 回调 + Coil 预取），**不切话**，切话仍只由用户操作触发。

- **灵动岛位置偏低（UI）**：`Popup` 的 `offset` 是像素，写死 `120` 在长屏机型上落在标题栏上。改为按内容区顶部 6dp 定位，贴合状态栏下方。

### Added

- **站点图标支持矢量图 svg（F01）**：`favicon` 以前只能放位图——Coil 核心不带 svg 解码器，配置里写了 svg，那块图标就是空的（同一 CDN 的 png 能正常显示，说明不是网络问题）。现在挂上 `coil-svg` 的 `SvgDecoder`：svg 按 `viewBox`（缺失时退回 `width`/`height`）解码成位图，站点选择器与顶栏「来源切换」两处一起生效；同时把「图标取不到 / 解不开」与「没配图标」统一按无图标处理——站点卡片退回首字色块，不再留一个空方块（做法是把色块画在底层、图标画在上面，不用状态分支：`AsyncImagePainter` 必须先被 `onDraw` 才会推进加载，用 `painter.state` 在外层分支会让它永远停在 Loading，这条也写进了 `ui-implementation.md` §5）。实测（模拟器 emulator-5554，release 包）：同一 CDN 上 `android.svg` 与 `1f600.png` 两份对照配置，前者由空白变为正常显示（黑色安卓机器人图标），把它设为当前站点后顶栏「来源切换」同样显示该图标。规范写进 `config-spec.md` §3.1 的 `favicon` 行（v1.11）。

- **单图阅读器支持缩放（F12）**：双指捏合 1x~5x、拖动平移（限制在画面内）、双击在 1x 与 2.5x 间切换（双击点作为放大中心），放大时暂停左右翻页手势；每张图各自记忆缩放与位移。

- **人机校验窗口一直是空白页（F29）**：`ChallengeActivity` 里 `view.loadUrl(url, pageHeaders)` 整行被并进了上一行的 `//` 注释，编译照样通过，但窗口**从未加载过任何页面**——用户看到的是白底加「取消 / 我已完成」，没有可点的验证控件，于是原请求超时后又弹一次，表现为「人机校验一直在循环、点了还重新验证」。现已恢复加载，并让窗口复用**正在取页的那个 WebView**（验证与请求在同一个页面上下文里完成），关闭窗口只摘不销毁。

- **验证页被反复重载，验证进度作废（F29）**：兜底取页遇到验证页时每 2.5s 重载一次、等待流程里每 10s 又重载一次。Cloudflare 挑战页的组件状态挂在文档上，重载后 `[Cloudflare Turnstile] Cannot find Widget` 报错、验证从头再来。现在**只探测不重载**：需要人点选的托管挑战直接交给用户窗口；只有会自己算完的（工作量验证）才在原地等，通过后用同一文档取正文。

- **验证页被当成正文缓存（F04 / F29）**：挑战页标题会按浏览器语言本地化（中文环境是「请稍候…」而不是 "Just a moment..."），原标题表只收英文，于是 403 挑战页被判定为正文、写进 HTML 缓存，首页直接显示「没有更多了」。现在标题表补中英文变体，并新增结构标记（`_cf_chl_opt` / `cf-chl-` / `challenge-platform` / `cf-browser-verification`）判据；非 2xx 状态码集合补上 492。

- **取页无上限等待（F04）**：页面卡在验证脚本里时 `awaitLoaded` 没有上限，界面会一直转圈。现在页面就绪最多等 45s，到点按当前 DOM 处理。

- **工作量验证站点详情打不开（F04 / F29）**：站点在详情页返回 `HTTP 492` + 「安全防御 - 工作量验证」挑战页。此前只对 403/429/503 走 WebView 兜底，且只按状态码判断，492 直接被当成普通失败抛出。现在非 2xx 会读一次正文，命中挑战标题（`工作量验证` / `安全防御`）同样走兜底；验证脚本会占满渲染线程，因此新增「短超时探标题 → 仍是验证页则重载 → 上限 180s」的等待流程，取回仍是验证页则按 2.5s 间隔重试，最多 3 次。

- **WebView 取回的整页 HTML 被 JSON 转义（F04）**：`evaluateJavascript` 的回传值会把 `<` `>` `&` `'` 转成 `\u003C` 等转义，原 `normalize` 只处理引号与换行，交给 jsoup 的 HTML 变成 `\u003Chtml...`，所有选择器都取不到节点（表现：线路为空、图文详情为空）。现改用 `JSONTokener` 完整还原。

- **网页浏览的 WebView 从不销毁（F14）**：`AndroidView` 只把 View 从视图树摘下，WebView 的原生资源与渲染器不会释放，每进一次「网页浏览」就多留一份实例直到进程结束。现在离开页面即 `destroy()`。

- **「显示 webview」关闭后实例挂在已销毁页面上（F29）**：镜像窗口把取页 WebView 摘下挂到自己窗口，`finish()` 时没有摘回，导致 `destroy()` 时它仍有父容器（`WebView.destroy() called while still attached`）。现在窗口关闭时从容器摘回，实例与取页任务继续存活。

- **播放器锁定改成「全禁」（F06）**：锁定的含义是「点一下之后所有播放操作都不生效」。现在锁定后除「点锁图标解锁 / 按返回键解锁」外任何触摸都不改变播放——不再快进快退、不再拖动进度条、不再暂停播放、不再调亮度音量、不再长按倍速；锁定态控制层只显示锁按钮与「已锁定」提示，同样 3.5s 自动隐藏，因此解锁入口始终可达。

- **锁图标常驻（F06）**：锁按钮原先有「未锁定且控制层隐藏」的兜底分支，控制条隐藏后仍画着一个闭锁图标，看起来像「自动上锁」，再点一下又变成开锁图标，看起来像「点击就解锁」。现删除该分支与多余的 `lockButtonVisible` 状态：锁按钮只随控制层显隐，是控制层的一部分；位置从贴边 4dp 改为 24dp，避开手势导航的返回热区。

- **暂停/播放图标不跟随状态（F06）**：播放状态原先只由 500ms 轮询镜像，双击中央暂停后图标滞后、连点还会切反。现由 `Player.Listener`（`onIsPlayingChanged` / `onPlayWhenReadyChanged` / `onPlaybackStateChanged`）即时驱动；双击中央与按钮共用同一个 `togglePlayPause()`（按 `playWhenReady` 判断、播完先回开头），双击中央另加 700ms 的 ▶/⏸ 中央反馈，且不再强制弹出整条控制层。

- **从收藏/历史打开条目：先跳首页、返回丢滚动位置（F07/F08）**：打开条目时把 tab 切到首页，收藏页被整棵销毁；返回时又因 `count + 1` 触发重排，滚动位置既没记住也对不上。现在原 tab 原地打开（浮层盖在原页面上）、滚动状态提升到上一层持有、返回时不再重排，退出播放器/阅读器仍停留在收藏页的同一位置；解析中按返回改为取消这次打开，不再甩回首页。

- **搜索结果固定一页（F09）**：`loadMore` 只处理列表页，搜索结果滑到底不再取；现同时处理搜索结果并合并分页，新搜索时重置「没有更多」。

- **收藏导入未保留次数（F07）**：导入流程仍调用累加式 `collect`，迁移后所有条目的 `count` 相同、按次数排序形同失效。改为读取 JSON 里的 `count`/`createTime` 走 `collectWithCount`，并在 `collects()` 里加代码层倒序兜底。

### Changed

- **WebView 统一为受管窗口（F06 / F29 / F14）**：五处各自 `new WebView` 改为从 `WebWindows` 取窗口（决策见 ADR-0017）。
  - `fetch` 取页兜底从「每次新建、用完销毁」改为**常驻隐藏窗口复用**，省掉每次 1.5–3s 冷启动；同一时刻只跑一个取页任务（`tryLock` 15s）
  - `image` 图片兜底从「每次新建」改为**对象池**（保活 1、封顶 3、空闲 5s 回收）
  - `engine` 脚本引擎、`browser` 网页浏览、`challenge` 人机校验同样走注册表；校验窗口改为每次新建（用完即销毁）
  - 创建/销毁不在锁内做，避免"持锁等主线程建 WebView"与主线程取窗口互相死锁

- **返回层级（F07）**：从收藏/历史点开的条目，退出播放器/详情后回到原 tab；搜索进来仍回搜索结果，列表进来回列表。

- **收藏与历史（F07）**：入库只存相对地址（`path?query`），打开时按当前 `homeUrl` 拼回域名，站点换域名无需迁移

- **Web 管理：启用开关与配置排序（F25）**：`PUT /api/configs/{key}` 改为按请求体的 `enabled` 落库（此前固定沿用旧值，勾选无效）；控制台配置列表支持 ↑/↓ 调整顺序，调用 `POST /api/configs/order`

- **User-Agent（F05/F29）**：所有取页通道统一为桌面 Chrome UA（`UserAgents.DESKTOP_CHROME`），避免站点返回移动版页面导致解析不到条目

- **打开收藏累加观看次数（F07）**：打开已收藏条目时 `count + 1`（未收藏不变），收藏列表据此倒序。

### Added

- **WebView 窗口抽象（WebWindow / WebWindows）**：`:core:script` 新增 `window` 包。
  - `WebWindow`：状态机（IDLE/LOADING/READY/CLOSED）、`load`/`eval`/`attachTo`/`detachFromParent`/`destroy`、页面/错误/进度回调、`__ghsWin` 结果桥
  - `WebWindows`：命名注册表 + 三种复用策略（`SINGLETON` / `PER_TASK` / `POOL(min,max)`）+ 空闲回收线程
  - 新增真机测试 `WebWindowsInstrumentedTest`：单例复用、池化归还复用、销毁后重建（3 条用例通过）

- **强制代理名单（F28）**：与白名单同样写法，命中即走代理且优先于白名单

- **收藏导入 / 导出（F07）**
  - Web 管理界面新增「导出收藏」「导入收藏」：导出下载含 `configKey/url/payload/count/createTime` 的 JSON，导入按 `configKey+url` 覆盖写入
  - 新增接口 `GET /api/favorites/export`、`POST /api/favorites/import`（见 `docs/03-api/web-admin-api.md`）
  - 用于换机与多设备迁移；设计见 `docs/01-product/design.md` §5.7

- **QuickJS 运行时接入（真机主通道）**
  - `:core:engine` 新增 `ScriptEngine` 接口 + `GhsScriptBridge` + `ConfigSource`：句柄表、方法派发、JS prelude、18 个宿主函数、`break` 标记切分，**纯 JVM 可单测**（新增 15 个测试）
  - `:core:script`（Android library）：`QuickJsScriptEngine` 把 `io.github.taoweiji.quickjs:quickjs-android:1.4.6` 适配到 `ScriptEngine`；`ScriptProbe` 提供真机自检（启动耗时 / ES2020 探测 / 选择器取值 / 配置求值与 `this` 绑定）
  - `:app` 引擎探针页新增"脚本运行时 · QuickJS"卡片；APK 13.8MB（含 arm64-v8a / armeabi-v7a / x86 / x86_64 的 `libquickjs*.so`）
  - 选型理由与排除项（`app.cash.quickjs` 无函数注入能力）见 ADR-0015
- **`:app` 壳层 + 可安装 APK（M1 里程碑）**
  - Kotlin + Compose 应用模块（AGP 8.5.2 / Kotlin 2.0.21 / Compose BOM 2024.09.03），`minSdk 26`（与设计 Q4 一致）/ `targetSdk 34`
  - 启动页为「引擎探针」：直接渲染 `PlainTextToBlocks` 与 `InlineConfigScanner` 的**真实输出**，用于证明 `:core:*` 在 Android 运行时可用；不是演示界面
  - `:app:assembleDebug` → `app-debug.apk` 9.2MB（`com.ghs.mobile` 0.1.0，launcher activity 就位）
  - 工具链补齐 Android SDK：cmdline-tools 12.0 + platform-tools 37.0.1 + `platforms;android-34` + `build-tools;34.0.0`（解压在 `.toolchain/`，不入库）；NDK 待 QuickJS 落地时再装
- **脚本运行时 spike（JVM 侧 GraalJS，7 个测试）**
  - `JsConfigRuntime`（仅测试期）：按 `break 标记` 切分配置、求值、绑定 `this`、以**整型句柄 + 方法派发**暴露 `$`
  - 桥接协议与真机 QuickJS 走 JNI 的形态一致（只传基本类型与句柄），结论可直接迁移到 ADR-0003 的实现
  - 验证结论：5/5 份真实配置可求值、7 个必需方法齐全、`this` 绑定成立、静态 `getTags` 条数符合预期（12/22/18/0）、含旧式 `net.request(...).on` 的配置求值不炸
- **GHS Select（Jsoup 桥）+ 配置预检 + 键归一化（新增 42 个单测，累计 59 全绿）**
  - `core/engine/select`：`GhsSelect` / `Selection` —— 覆盖设计 §8.4 的选择器与取值语义：`hasClass`、`toString()`（outerHTML，空集为 `''`）、属性等值、`:eq`/`:first`/`:last`（含中间位置）、逗号与子代选择器、`each`/`find`/`parent`/`children`/`next`/`prev`/`closest`/`filter`；**取值一律返回 `''`，永不返回 null**
  - `core/engine/config`：`InlineConfigScanner` —— 内联脚本的**文本级预检**（抽 key/name/homeUrl 与方法集、检出 7 必需方法缺失、旧式 `net.request(...).on` 警告、1MB 上限），并带两个读取 `tmp/configs` 的真实样本测试（缺失自动跳过）
  - `core/engine/net`：`UrlNormalizer` —— 进度/收藏/历史的键归一化（去 fragment、去 `utm_*`/`from`/`spm`、去尾斜杠、幂等）
- **Gradle 骨架 + 首批 JVM 代码（22 个单测全绿）**
  - `settings.gradle.kts` / `build.gradle.kts` / `gradle.properties`：Kotlin JVM 多模块，`jvmToolchain(21)`
  - `core/model`：领域模型（Item/Tag/Page/Detail/CComic/TextBlock/TextContent/ReadingProgress…）与错误码（`E-CFG-*` / `E-CHALLENGE-*`），含归一化、占位判定、`fullUrl` 回落等语义方法
  - `core/engine`：`PlainTextToBlocks` —— 纯文本＋内嵌 `<img>` 转结构块（实体解码、`<br>` 归一、分段、图片提取与 scheme 校验、2MB 截断）
- **施工开工**：`web/console/`（首个代码目录）
  - `config-api.d.ts`：GHS Script API v1 类型声明，供 Monaco 注入以提供补全/hover/签名/诊断（ADR-0014）
  - `scripts/check-dts.mjs`：**零依赖**一致性检查器，从 `config-spec` §3/§8 与 `rest-contract` §3 解析规范要求，与 d.ts 逐项比对；已通过负向测试（删必需方法即失败）
  - `README.md` / `package.json`（仅 `check:dts`，无依赖）
- 文档体系：`AGENTS.md`、`README.md`、`docs/README.md`、`docs/00-process/document-policy.md`
- `docs/01-product/design.md` v2.1：配置驱动的内容容器定位、底座分层、F01–F26 功能范围、引擎架构、内置服务架构、里程碑
- `docs/01-product/feature-registry.md` v0.4：F01–F26 功能登记（含类别、dropped 追溯）
- `docs/03-api/config-spec.md` v1.1：动态配置规范（含交换文件格式、编辑与同步约定）
- `docs/03-api/web-admin-api.md`：内置 Web 服务 API（三个命名空间、配置 CRUD、校验、导入导出、调试、WebDAV、安全边界）
- `docs/03-api/local-store.md`：**本地仓库规范**（数据分类与同步归属、统一进度模型、导出 bundle 与分层目录、导入/冲突规则、WebDAV 同步流程、保留策略、版本迁移）
- `docs/02-architecture/adr/ADR-0007-local-store-unified-sync.md`：本地仓库为唯一数据源
- `docs/02-architecture/adr/ADR-0008-http-proxy-two-channels.md`：HTTP 代理同时覆盖应用请求与无头 WebView
- `docs/02-architecture/adr/ADR-0009-proxy-whitelist-single-decision.md`：白名单为唯一代理判定点、转发代理进 v1、未配置即直连
- `docs/02-architecture/adr/ADR-0010-challenge-top-activity.md`：人机校验用独立顶层 Activity，校验后带 Cookie 优先 OkHttp 重试
- `docs/02-architecture/adr/ADR-0011-drop-remote-mode.md`：移除远程服务模式（部分取代 ADR-0002）
- `docs/02-architecture/adr/ADR-0012-html-sanitize-to-blocks.md`：正文接受任意 HTML，清洗后转结构块
- `docs/02-architecture/adr/ADR-0014-monaco-editor-intellisense.md`：Web 编辑器选用 Monaco，智能提示由注入的 `config-api.d.ts` 驱动
- `docs/02-architecture/web-console.md`：Web 管理界面实现（三栏布局、双模式编辑、JSON 调试台、同步面板）
- `docs/02-architecture/ui-implementation.md` v1.1：移动端 UI 实现规范
- `docs/03-api/rest-contract.md` v0.3：数据层契约
- ADR-0001 原生 Kotlin + Compose、ADR-0002 引擎内置与双数据源、ADR-0003 脚本运行时、ADR-0004 配置双段式、ADR-0005 Web 管理界面与调试、ADR-0006 WebDAV 同步
- 功能 **F23 内置 Web 管理服务**、**F24 配置编辑器（Web）**、**F25 实时调试台**、**F26 WebDAV 同步**
- 功能 **F27 文本阅读器**：`detailType = text`，默认加载第一章，后续按本地进度续读
- 功能 **F28 网络代理（HTTP）**：一份代理配置同时作用于 OkHttp 通道（取页/图片/视频/WebDAV）与无头 WebView 通道
- 功能 **F29 人机校验交互**：取页命中校验页时在顶层弹出独立窗口，完成后自动继续加载并解析
- 功能 F19 配置编辑器、F20 数据备份与恢复、F21 外观与显示、F22 应用更新检查（前期补齐）

- **宿主能力与列表页端到端**
  - `HttpScriptHost`（:core:script）：OkHttp 取页/请求 + 无头 WebView 执行页面脚本（加载→onPageFinished→求值→销毁，全程可诊断超时）
  - `ConfigEngine`（:core:engine）：把「配置 + HTML」映射为 `Item` / `Pagination` / `Tag`，丢弃缺 `jumpUrl` 的条目、保留占位标签、空 url 分页即禁用
  - instrumented test 增至 8 个：宿主能力 3 项 + 列表页端到端 1 项（本地 HTTP 服务，不访问外网）
- **详情页三条链路（图集 / 文本 / 视频）**
  - `ConfigEngine.detailInfo` / `contents` / `comicImages` / `textContent`：把 `async getDetailInfo` 等方法的返回值映射为 `DetailInfo` / `CContent` / `CComic` / `TextContent`
  - 文本正文：作者 `blocks` 优先，否则 `PlainTextToBlocks` 切分；`fullUrl` 优先、`isCurrent`/`quality`/`info` 全量保留
  - instrumented test 增至 13 个（新增详情 5 项）
- **本地仓库 `:core:data`（Room + KSP）**
  - 六张表按 `local-store.md` §3.2 落地：`app_setting` / `reading_progress` / `collect` / `viewed_history` / `search_history` / `config_unit`
  - `LocalRepository`：进度以 `itemUrl` 唯一且"旧时间戳不覆盖新进度"；设置按 `prefs`/`device` 隔离；收藏累加 `count`；历史 `trimHistory(keep)`；搜索历史按 `(configKey,value)` 去重
  - instrumented test 增至 22 个（新增仓库 9 项）
- **演示界面（列表 / 图集 / 文本 / 视频）**
  - 内置合成配置与样例页面（`assets/demo/`），解析链路与真实配置一致，完全离线
  - 列表两列卡片（封面占位/标题/标签/已读进度）、图集网格、文本块渲染、视频清晰度列表
  - 打开详情即写 `reading_progress` / `viewed_history`，返回列表显示"读到第 N / M 页"
  - 修复：主线程等待脚本结果会死锁（改为后台线程创建会话）；返回详情后的加载态卡死（改为回填缓存列表）；文本正文需取 `.html()` 才能保留换行与内嵌图片
- **阅读器内核**
  - 图集阅读器：横向翻页 + 页码指示 + 翻页回写 `chapterOffset`/`percent`，详情页提供"继续阅读 · 第 N 页"
  - 文本阅读器：块渲染 + 上一章/下一章（`prevUrl`/`nextUrl` 为空即禁用），切章回写 `chapterUrl`/`chapterIndex`
  - 阅读器整屏渲染，消除与列表共用外层 Scaffold 造成的双顶栏
- **图集目录与图片加载**
  - 图集阅读器新增目录（`getContents`）：底部弹层列出章节、当前页高亮、点击跳转
  - 图片改用 Coil 加载（图集页与文本图片块），加载中/失败均有占位，不出现破图
  - 演示配置补 `getContents` 与章节链接；在此之前目录恒为 0 项
- **收藏与历史**
  - 底部导航（首页/收藏/历史）；列表卡片心形按钮收藏/取消，状态来自 Room
  - 收藏页网格（可取消）、历史页单列（标题/类型/时间），均有空态提示
  - `ItemCodec`：条目 ↔ 仓库 `value` 列的 JSON 编解码
  - 修复：历史 payload 存成纯标题导致解码失败（恒 0 条）、切 tab 不重载、文本进度分母错误
- **搜索**
  - 顶栏搜索框 → `adapterSearchUrl(keyword)` → 取页 → `getItems` 解析 → 复用列表 UI
  - 结果页显示"「kw」的结果 · N 项"，可清除返回；不支持搜索时给出明确提示
  - `ConfigEngine.searchUrl()`：按 `adapterSearchUrl` 返回值判定，而非 `searchUrl` 字段
- **设置页**
  - 主题（跟随系统/浅色/深色）、字号（小/标准/大/特大）、阅读器默认模式、历史保留条数
  - `SettingsStore` 用 `StateFlow` 暴露状态并落到 `app_setting(prefs)`；主题与字号在 MaterialTheme 外层应用，全局立即生效
  - 字号缩放统一乘以整套 `Typography`，不逐个写死 sp
- **视频播放器（Media3 + HLS）**
  - ExoPlayer + PlayerView 承载在 Compose 中；清晰度行点击进入播放
  - 按进度续播，每 5 秒与退出时回写 `positionMs`/`durationMs`/`percent`
  - 播放失败显示错误码与地址（演示地址不可达时的预期表现）
- **HTTP 代理（F28）**
  - `ProxyRules` 单一判定点：启用→白名单直连、其余走代理；代理自身地址不自环；支持 `host:port` / `.example.com` / `*.example.com`
  - OkHttp 走 `RulesProxySelector`（逐请求判定），支持可选 Basic 认证；WebView 走 `ProxyController`（启用时按白名单 bypass，未启用显式直连）
  - 代理设置存 `app_setting(device)`（不随导出/同步），设置页新增"网络代理"分组；默认地址 `http://192.168.31.245:4000`
  - 新增 11 个 JVM 单测与 2 个 instrumented 测试（本地代理验证白名单直连/非白名单走代理）
- **Web 控制台 v0.1（F24）**
  - `web/console`：Vue 3 + Vite + Monaco 独立静态工程，可脱离 App 单独运行
  - 三栏界面：配置列表 / 源码编辑 / 校验面板；启用开关、新建、删除、Ctrl+S 保存
  - 智能提示来自注入的 `config-api.d.ts`（补全/hover/签名/诊断与规范同源）
  - 本地静态校验：缺必需方法、`key` 格式、体积上限、`net.request().on(...)` 旧写法
  - API 抽象为 `ConsoleApi`：当前 Mock（localStorage），接内置服务时替换为 HTTP 实现
  - Monaco 与 worker 全部按需 chunk：首屏 68.8kB，总量 9.35MB（见 `web-console.md` v1.5）
- **控制台断点（F24）**
  - 编辑器列改 `minmax(0, …)`，窄窗口下不再被两侧固定宽度挤成 0 宽
  - `<900px` 单栏堆叠，源码模式只读并提示改用桌面浏览器，与 `web-console.md` §2 一致
- **内置 Web 管理服务（F23）**
  - `/console` 提供控制台静态资源（SPA 回落），`/api` 提供配置 CRUD 与静态校验，`/healthz` 探活
  - 鉴权：`/api` 需 Bearer 或 Cookie，`/console/?t=` 换取 Cookie；无凭证 401
  - 配置存 `filesDir/configs/<key>.js` + `index.json`，首次运行播种内置示例
  - 设置页新增「Web 管理」（开关/端口/局域网访问/令牌重置）；这些参数不开放 API 修改
  - 控制台改由 `createApi()` 自动选择：`/console` 走 HTTP，独立开发仍用 Mock
- **App 读取配置目录（F23）**
  - 配置源码改由 `files/configs` 提供（`ReaderSession`），内置 assets 仅用于首次播种与兜底
  - 顶栏 ⋮ 可切换配置并记住选择（`prefs.currentConfig`）；删除后自动回退
  - `ReaderHost`：`demo.local` 走内置样例页面，其它域名走真实网络（含代理）
- **修复控制台保存/新建无效（F23）**
  - 根因：NanoHTTPD keep-alive 下未消费的请求体被当成下一个请求行，导致后续请求全部失败
  - `AdminServer` 改为每个请求先读完 body 再分发；401 分支同样消费
  - 控制台：保存/新建/删除失败时红字提示具体错误；删除增加二次确认弹窗
- **key 冲突的可操作处理（F24）**
  - 新增 `ApiError`（携带 status 与错误码），界面按 code 分支处理
  - 源码里改 key 后保存 → 弹窗提供「取消 / 改回当前 key / 另存为新 key」
  - PUT 404 回退 POST 改用状态码判断，不再依赖字符串匹配
- **配置热更新（F23）**
  - 服务端在配置增删改后通知 App，列表页立即重载；阅读中延迟到回到列表
  - ⋮ 菜单每次打开重新读取，新建/删除的配置即时可见
- **修复代理默认值被清空（F28）**
  - `ProxyStore.load` 不再用 `saved.isEmpty()` 判断首次使用，只认 `proxy_*` 键；此前 Web 管理的 `web_*` 键会把代理端点读成 null（实际变直连）
  - 解析失败页显示当前代理端点与白名单条数，并提示 `CONNECT` 失败的含义
- **真实配置接入（F28）**
  - 代理默认端口改为 `192.168.31.245:10808`（4000 是静态站，不是代理）
  - 修复 `item.type` 未回传导致 `getDetailInfo` 恒返回 null（详情永远转圈）
  - 详情失败不再被吞：显示错误原因与排查方向
  - 脚本 await 与 OkHttp 超时放宽（60s/90s）并开启重试
  - Coil 与 Media3 走同一代理规则（图片、视频与应用请求一致）
- **列表封面图（F02）**
  - `ItemCard` 用 Coil 加载 `coverImg`（3:4 Crop），为空或失败退回字母占位
  - 图片与视频均走同一代理链路；真实配置实测 24 项封面正常、视频可从进度续播
- **列表封面卡片（F02）**
  - 卡片改为封面 + 标题 + 星标 + 副标题；星标即收藏/取消
  - 单列（左图右文）/ 双列（上图下文）切换，选择对收藏、历史同样生效
  - 触底自动加载下一页（按 `pagination` 合并去重，到底显示"没有更多了"）
  - 收藏页、历史页统一使用同一卡片；历史副标题为浏览时间
- **标签页 / 分页条 / 视频直达播放**
  - 底栏新增"标签"：读取 `getTags` 渲染标签云，点击按该地址重载列表
  - 列表底部分页条：按 `pagination` 渲染页码、当前页高亮、点击跳转（与触底加载共存）
  - 卡片标题超长自动滚动（`basicMarquee`）
  - 视频：解析出地址后直接进播放器并续播；清晰度在播放器内切换（保留进度）；播放页整屏无底栏
- **站点与页码抽屉（F02/F03）**
  - 站点切换改为底部抽屉：搜索框 + 两列站点卡片，当前站点高亮打勾
  - 分页改为右上角页码按钮 + 选页抽屉，列表底部不再被分页条占位
  - 顶栏不再显示"列表 · N 项"；卡片去掉进度副标题，只留封面/标题/星标
- **卡片间距与站点图标**
  - 网格外边距 12→4dp、间距 12→6dp，封面明显变大；标题行压缩到最小
  - 站点抽屉图标读取配置 `favicon`，缺失/失败退回首字色块
- **图片阅读（F05/F12）**
  - 详情解析出 `image` 后直接进阅读器，按进度续看；单图显示 1/1，多图为 1/N
  - 取页失败自动重试一次；解析失败页提供「重试」按钮
  - 超时放宽：脚本 180s、OkHttp 读 120s / 调用 180s（代理下大页面可达 60s+）
- **图片清晰度切换（F05）**
  - 阅读器顶栏可切换「原图（fullUrl）/ 缩略图（url）」，仅当两者地址不同时显示
  - 默认原图；选择写入 `prefs.imageQuality` 并在下次进入时沿用
  - 原图加载失败自动回落展示图，避免整页空白
- **图片相邻页预加载（F05）**
  - 页码/清晰度变化时预取 current-1、+1、+2 三页，翻页不必等下载
  - 修复：单指横滑被缩放手势吞掉导致图片页无法翻页（改为双指或已放大才消费手势）
  - 原图失败自动回落展示图时不再弹提示
- **长按保存图片（F05）**
  - 看图长按保存当前页（按所选清晰度取地址），Toast 提示保存位置
  - API 29+ 走 MediaStore 写 `Pictures/GHS`（免权限）；26~28 退应用外部目录
  - 下载复用代理客户端，并带 Referer 避免图床 403
- **图集解码宽度上限（F05）**
  - `comicImgMaxWidth` 生效：支持 px / dp / vw / % / 纯数字(dp)，默认 1080px
  - 展示与预取请求都按上限解码，避免超长图整张解码
  - 新增扫描器单测覆盖字段抽取
- **剧集（F30）**
  - 新增三段式剧集流程：详情 → 分段（`analysis`）→ 集（`getAnalysisDetail`）→ 播放（`getAnalysisVideoDetail`）
  - 引擎兼容两个方法返回**对象或数组**（配置常见 `return [res]`）
  - 新增 `SeriesScreen`：分段列表 + 5 列集网格 + 「分段」返回
- **剧集播放打通（F30/F06）**
  - `text()` 可读取 `<script>` 内容（DataNode 回落），剧集从 ld+json 取播放地址成功
  - 播放器请求带 Referer 与浏览器 UA
  - 媒体 403 自动直连重试（视频 CDN 拦截代理出口 IP）
- **播放器与剧集续看（F06/F30）**
  - 控制条改用官方 `media3-ui` PlayerView（缓冲/拖动/倍速/字幕/自动隐藏），未引入第三方播放器
  - 播放器内可直接切换线路与选集，副标题显示「线路 · 第 N 集」
  - 剧集记录「线路 + 集 + 位置」，再次打开始终回到上次那一集，并提示「上次看到 mm:ss」（快进 / 从头）
  - 修复记历史时用空白进度覆盖已有记录的问题
- **播放器切换抽屉合并（F06/F30）**
  - 「线路」「选集」合并为一个抽屉，Tab 切换；线路为主 Tab，默认选中并标「当前」
  - 换线路后自动停在同一集；选集为 5 列网格，当前集高亮
- **线路 Tab 交互（F30）**
  - 每条线路一个 Tab（横向滚动），切 Tab 直接显示该线路剧集
  - 剧集详情页去掉「分段 → 集」两级跳转，与播放器抽屉共用 `LineEpisodeTabs`
  - 剧集按线路懒加载并缓存；当前播放的线路+集在网格中高亮
- **播放器修复与增强（F06）**
  - 修复切换剧集后画面冻住：`AndroidView.factory` 只执行一次，PlayerView 仍持有已释放的旧播放器；补 `update` 后恢复
  - 全屏（横屏 + 隐藏系统栏，Activity 补 `configChanges` 避免旋转重建丢状态）
  - 锁屏（隐藏顶栏与手势，只留解锁按钮）
  - 双击右半区快进 10s、左半区后退 10s
- **播放器控制布局（F06）**
  - 全屏移到底部右侧、锁屏移到左侧边缘居中，与主流播放器一致
  - 顶栏只保留返回、标题、「线路 · 第 N 集」与线路/选集入口
- **播放器控制区（F06）**
  - 全屏改为四角括号图标（自绘，不引图标库）
  - 清晰度移到右下角并显示当前档位，顺序：清晰度 → 全屏
- **播放器控制层重做（F06）**
  - 关闭 PlayerView 自带控制器，控制层自绘：顶栏 + 底栏（进度条、上一集/播放暂停/下一集/清晰度/全屏）
  - 单击显隐、3.5s 自动隐藏；双击左/右 40% 快退/快进 10s，中间 20% 播放暂停，900ms 内连击累加
  - 暂停与全屏图标自绘，不引入图标库
- **播放器手势与图标（F06）**
  - 锁图标区分锁定（闭合）与解锁（锁梁倾斜）
  - 左半边上下滑调亮度、右半边调音量
  - 长按 400ms 2x 倍速、松手恢复
  - 「线路 / 选集」抽屉加高
- **首页体验与导航（F02/F14）**
  - 卡片白底 + 阴影、标题字号下调；item 的 tag 悬浮在封面左上角
  - 首页悬浮刷新按钮（当前地址重新取数）；标签点击后回首页
  - 左侧菜单抽屉：设置、引擎自检移入；顶栏站点按钮带 favicon
  - 去掉收藏/历史/标签标题里的计数
  - 新增日志云：配置 `LogMsgUtil` 输出在应用内可见
- **抽屉直接放设置（F14）**
  - `SettingsContent` 抽出，设置页与左侧抽屉共用同一份内容
  - 抽屉改为标准左侧抽屉（88% 宽、遮罩、滑入动画），去掉二级菜单
- **Web 地址与标题（F23/F05/F06）**
  - Web 管理列出全部局域网 IPv4 地址，点击复制
  - 图集阅读器与播放器标题单行展示，超长自动滚动
- **详情解析弹窗（F04）**
  - 点条目不再切中间页，在列表上直接弹出解析框；完成才按类型跳转，取消则停在列表
  - 弹窗宽 320dp，含条目名、已用秒数与整行取消按钮
- **修复（F02/F08/F06）**
  - 退出播放后残留全屏椭圆遮罩：日志胶囊形状取值错误，改为抽屉入口
  - 历史为空：旧数据在 demo 名下，新增一次性迁移到当前站点
  - 播放器接管硬件返回键，锁定时先解锁
- **首页刷新与标题（F02）**
  - 首页顶栏不再显示标签名
  - 刷新按钮移入 Scaffold 槽位，刷新时显示遮罩与 loading
- **历史按站点隔离（F08）**
  - 修复浏览记录仍写入固定 demo key 的问题，改为当前站点
  - 抽屉新增「清空本站历史」
- **筛选/排序面板（F03）**
  - 引擎解码 `getUrlReplace`，`adapterLoadUrl` 参与最终地址生成
  - 顶栏「筛选」按 schema 分组单选，切换后重新取页
- **实时调试台（F25）**
  - `/api/state`、`/api/logs`、`/api/debug/run` 三个接口
  - 控制台右侧抽屉：状态轮询、日志列表、脚本执行
- **人机校验交互（F29）**
  - 校验窗口（可见 WebView）+ 同一 host 单窗口 + 5 分钟超时
  - OkHttp 与 WebView 共用 Cookie，校验后自动重试原请求
  - 403 等直接拒绝时在窗口内显示原因，不再是白屏
- **白名单与图集（F28/F05）**
  - 白名单裸域名覆盖子域，修复"页面能开、图片全裂"
  - 图集切话回到顶部；图片兜底改为 WebView 取字节（base64）
- **代理规则（F28）**
  - 人机校验窗口与普通请求共用代理规则：白名单内直连，非白名单必须走代理，不得绕过
### Fixed

- **真机验证抓到两个 bug（JVM 侧测不出来）**
  - `QuickJsScriptEngine.eval` 把参数写反：封装签名是 `executeScript(script, name)`，写反后脚本内容变成 `"<eval>"`，报 `SyntaxError: unexpected token '<'`
  - `ScriptProbe` 用 `$('.item .title')` 命中两个元素，`helpElText` 拼接成 `AB`；改用 `.eq(0)` 并顺带覆盖 `eq`
- **宿主能力与列表页端到端**
  - `HttpScriptHost`（:core:script）：OkHttp 取页/请求 + 无头 WebView 执行页面脚本（加载→onPageFinished→求值→销毁，全程可诊断超时）
  - `ConfigEngine`（:core:engine）：把「配置 + HTML」映射为 `Item` / `Pagination` / `Tag`，丢弃缺 `jumpUrl` 的条目、保留占位标签、空 url 分页即禁用
  - instrumented test 增至 8 个：宿主能力 3 项 + 列表页端到端 1 项（本地 HTTP 服务，不访问外网）
- **详情页三条链路（图集 / 文本 / 视频）**
  - `ConfigEngine.detailInfo` / `contents` / `comicImages` / `textContent`：把 `async getDetailInfo` 等方法的返回值映射为 `DetailInfo` / `CContent` / `CComic` / `TextContent`
  - 文本正文：作者 `blocks` 优先，否则 `PlainTextToBlocks` 切分；`fullUrl` 优先、`isCurrent`/`quality`/`info` 全量保留
  - instrumented test 增至 13 个（新增详情 5 项）
- **本地仓库 `:core:data`（Room + KSP）**
  - 六张表按 `local-store.md` §3.2 落地：`app_setting` / `reading_progress` / `collect` / `viewed_history` / `search_history` / `config_unit`
  - `LocalRepository`：进度以 `itemUrl` 唯一且"旧时间戳不覆盖新进度"；设置按 `prefs`/`device` 隔离；收藏累加 `count`；历史 `trimHistory(keep)`；搜索历史按 `(configKey,value)` 去重
  - instrumented test 增至 22 个（新增仓库 9 项）
- **演示界面（列表 / 图集 / 文本 / 视频）**
  - 内置合成配置与样例页面（`assets/demo/`），解析链路与真实配置一致，完全离线
  - 列表两列卡片（封面占位/标题/标签/已读进度）、图集网格、文本块渲染、视频清晰度列表
  - 打开详情即写 `reading_progress` / `viewed_history`，返回列表显示"读到第 N / M 页"
  - 修复：主线程等待脚本结果会死锁（改为后台线程创建会话）；返回详情后的加载态卡死（改为回填缓存列表）；文本正文需取 `.html()` 才能保留换行与内嵌图片
- **阅读器内核**
  - 图集阅读器：横向翻页 + 页码指示 + 翻页回写 `chapterOffset`/`percent`，详情页提供"继续阅读 · 第 N 页"
  - 文本阅读器：块渲染 + 上一章/下一章（`prevUrl`/`nextUrl` 为空即禁用），切章回写 `chapterUrl`/`chapterIndex`
  - 阅读器整屏渲染，消除与列表共用外层 Scaffold 造成的双顶栏
- **图集目录与图片加载**
  - 图集阅读器新增目录（`getContents`）：底部弹层列出章节、当前页高亮、点击跳转
  - 图片改用 Coil 加载（图集页与文本图片块），加载中/失败均有占位，不出现破图
  - 演示配置补 `getContents` 与章节链接；在此之前目录恒为 0 项
- **收藏与历史**
  - 底部导航（首页/收藏/历史）；列表卡片心形按钮收藏/取消，状态来自 Room
  - 收藏页网格（可取消）、历史页单列（标题/类型/时间），均有空态提示
  - `ItemCodec`：条目 ↔ 仓库 `value` 列的 JSON 编解码
  - 修复：历史 payload 存成纯标题导致解码失败（恒 0 条）、切 tab 不重载、文本进度分母错误
- **搜索**
  - 顶栏搜索框 → `adapterSearchUrl(keyword)` → 取页 → `getItems` 解析 → 复用列表 UI
  - 结果页显示"「kw」的结果 · N 项"，可清除返回；不支持搜索时给出明确提示
  - `ConfigEngine.searchUrl()`：按 `adapterSearchUrl` 返回值判定，而非 `searchUrl` 字段
- **设置页**
  - 主题（跟随系统/浅色/深色）、字号（小/标准/大/特大）、阅读器默认模式、历史保留条数
  - `SettingsStore` 用 `StateFlow` 暴露状态并落到 `app_setting(prefs)`；主题与字号在 MaterialTheme 外层应用，全局立即生效
  - 字号缩放统一乘以整套 `Typography`，不逐个写死 sp
- **视频播放器（Media3 + HLS）**
  - ExoPlayer + PlayerView 承载在 Compose 中；清晰度行点击进入播放
  - 按进度续播，每 5 秒与退出时回写 `positionMs`/`durationMs`/`percent`
  - 播放失败显示错误码与地址（演示地址不可达时的预期表现）
- **HTTP 代理（F28）**
  - `ProxyRules` 单一判定点：启用→白名单直连、其余走代理；代理自身地址不自环；支持 `host:port` / `.example.com` / `*.example.com`
  - OkHttp 走 `RulesProxySelector`（逐请求判定），支持可选 Basic 认证；WebView 走 `ProxyController`（启用时按白名单 bypass，未启用显式直连）
  - 代理设置存 `app_setting(device)`（不随导出/同步），设置页新增"网络代理"分组；默认地址 `http://192.168.31.245:4000`
  - 新增 11 个 JVM 单测与 2 个 instrumented 测试（本地代理验证白名单直连/非白名单走代理）
- **Web 控制台 v0.1（F24）**
  - `web/console`：Vue 3 + Vite + Monaco 独立静态工程，可脱离 App 单独运行
  - 三栏界面：配置列表 / 源码编辑 / 校验面板；启用开关、新建、删除、Ctrl+S 保存
  - 智能提示来自注入的 `config-api.d.ts`（补全/hover/签名/诊断与规范同源）
  - 本地静态校验：缺必需方法、`key` 格式、体积上限、`net.request().on(...)` 旧写法
  - API 抽象为 `ConsoleApi`：当前 Mock（localStorage），接内置服务时替换为 HTTP 实现
  - Monaco 与 worker 全部按需 chunk：首屏 68.8kB，总量 9.35MB（见 `web-console.md` v1.5）
- **控制台断点（F24）**
  - 编辑器列改 `minmax(0, …)`，窄窗口下不再被两侧固定宽度挤成 0 宽
  - `<900px` 单栏堆叠，源码模式只读并提示改用桌面浏览器，与 `web-console.md` §2 一致
- **内置 Web 管理服务（F23）**
  - `/console` 提供控制台静态资源（SPA 回落），`/api` 提供配置 CRUD 与静态校验，`/healthz` 探活
  - 鉴权：`/api` 需 Bearer 或 Cookie，`/console/?t=` 换取 Cookie；无凭证 401
  - 配置存 `filesDir/configs/<key>.js` + `index.json`，首次运行播种内置示例
  - 设置页新增「Web 管理」（开关/端口/局域网访问/令牌重置）；这些参数不开放 API 修改
  - 控制台改由 `createApi()` 自动选择：`/console` 走 HTTP，独立开发仍用 Mock
- **App 读取配置目录（F23）**
  - 配置源码改由 `files/configs` 提供（`ReaderSession`），内置 assets 仅用于首次播种与兜底
  - 顶栏 ⋮ 可切换配置并记住选择（`prefs.currentConfig`）；删除后自动回退
  - `ReaderHost`：`demo.local` 走内置样例页面，其它域名走真实网络（含代理）
- **修复控制台保存/新建无效（F23）**
  - 根因：NanoHTTPD keep-alive 下未消费的请求体被当成下一个请求行，导致后续请求全部失败
  - `AdminServer` 改为每个请求先读完 body 再分发；401 分支同样消费
  - 控制台：保存/新建/删除失败时红字提示具体错误；删除增加二次确认弹窗
- **key 冲突的可操作处理（F24）**
  - 新增 `ApiError`（携带 status 与错误码），界面按 code 分支处理
  - 源码里改 key 后保存 → 弹窗提供「取消 / 改回当前 key / 另存为新 key」
  - PUT 404 回退 POST 改用状态码判断，不再依赖字符串匹配
- **配置热更新（F23）**
  - 服务端在配置增删改后通知 App，列表页立即重载；阅读中延迟到回到列表
  - ⋮ 菜单每次打开重新读取，新建/删除的配置即时可见
- **修复代理默认值被清空（F28）**
  - `ProxyStore.load` 不再用 `saved.isEmpty()` 判断首次使用，只认 `proxy_*` 键；此前 Web 管理的 `web_*` 键会把代理端点读成 null（实际变直连）
  - 解析失败页显示当前代理端点与白名单条数，并提示 `CONNECT` 失败的含义
- **真实配置接入（F28）**
  - 代理默认端口改为 `192.168.31.245:10808`（4000 是静态站，不是代理）
  - 修复 `item.type` 未回传导致 `getDetailInfo` 恒返回 null（详情永远转圈）
  - 详情失败不再被吞：显示错误原因与排查方向
  - 脚本 await 与 OkHttp 超时放宽（60s/90s）并开启重试
  - Coil 与 Media3 走同一代理规则（图片、视频与应用请求一致）
- **列表封面图（F02）**
  - `ItemCard` 用 Coil 加载 `coverImg`（3:4 Crop），为空或失败退回字母占位
  - 图片与视频均走同一代理链路；真实配置实测 24 项封面正常、视频可从进度续播
- **列表封面卡片（F02）**
  - 卡片改为封面 + 标题 + 星标 + 副标题；星标即收藏/取消
  - 单列（左图右文）/ 双列（上图下文）切换，选择对收藏、历史同样生效
  - 触底自动加载下一页（按 `pagination` 合并去重，到底显示"没有更多了"）
  - 收藏页、历史页统一使用同一卡片；历史副标题为浏览时间
- **标签页 / 分页条 / 视频直达播放**
  - 底栏新增"标签"：读取 `getTags` 渲染标签云，点击按该地址重载列表
  - 列表底部分页条：按 `pagination` 渲染页码、当前页高亮、点击跳转（与触底加载共存）
  - 卡片标题超长自动滚动（`basicMarquee`）
  - 视频：解析出地址后直接进播放器并续播；清晰度在播放器内切换（保留进度）；播放页整屏无底栏
- **站点与页码抽屉（F02/F03）**
  - 站点切换改为底部抽屉：搜索框 + 两列站点卡片，当前站点高亮打勾
  - 分页改为右上角页码按钮 + 选页抽屉，列表底部不再被分页条占位
  - 顶栏不再显示"列表 · N 项"；卡片去掉进度副标题，只留封面/标题/星标
- **卡片间距与站点图标**
  - 网格外边距 12→4dp、间距 12→6dp，封面明显变大；标题行压缩到最小
  - 站点抽屉图标读取配置 `favicon`，缺失/失败退回首字色块
- **图片阅读（F05/F12）**
  - 详情解析出 `image` 后直接进阅读器，按进度续看；单图显示 1/1，多图为 1/N
  - 取页失败自动重试一次；解析失败页提供「重试」按钮
  - 超时放宽：脚本 180s、OkHttp 读 120s / 调用 180s（代理下大页面可达 60s+）
- **图片清晰度切换（F05）**
  - 阅读器顶栏可切换「原图（fullUrl）/ 缩略图（url）」，仅当两者地址不同时显示
  - 默认原图；选择写入 `prefs.imageQuality` 并在下次进入时沿用
  - 原图加载失败自动回落展示图，避免整页空白
- **图片相邻页预加载（F05）**
  - 页码/清晰度变化时预取 current-1、+1、+2 三页，翻页不必等下载
  - 修复：单指横滑被缩放手势吞掉导致图片页无法翻页（改为双指或已放大才消费手势）
  - 原图失败自动回落展示图时不再弹提示
- **长按保存图片（F05）**
  - 看图长按保存当前页（按所选清晰度取地址），Toast 提示保存位置
  - API 29+ 走 MediaStore 写 `Pictures/GHS`（免权限）；26~28 退应用外部目录
  - 下载复用代理客户端，并带 Referer 避免图床 403
- **图集解码宽度上限（F05）**
  - `comicImgMaxWidth` 生效：支持 px / dp / vw / % / 纯数字(dp)，默认 1080px
  - 展示与预取请求都按上限解码，避免超长图整张解码
  - 新增扫描器单测覆盖字段抽取
- **剧集（F30）**
  - 新增三段式剧集流程：详情 → 分段（`analysis`）→ 集（`getAnalysisDetail`）→ 播放（`getAnalysisVideoDetail`）
  - 引擎兼容两个方法返回**对象或数组**（配置常见 `return [res]`）
  - 新增 `SeriesScreen`：分段列表 + 5 列集网格 + 「分段」返回
- **剧集播放打通（F30/F06）**
  - `text()` 可读取 `<script>` 内容（DataNode 回落），剧集从 ld+json 取播放地址成功
  - 播放器请求带 Referer 与浏览器 UA
  - 媒体 403 自动直连重试（视频 CDN 拦截代理出口 IP）
- **播放器与剧集续看（F06/F30）**
  - 控制条改用官方 `media3-ui` PlayerView（缓冲/拖动/倍速/字幕/自动隐藏），未引入第三方播放器
  - 播放器内可直接切换线路与选集，副标题显示「线路 · 第 N 集」
  - 剧集记录「线路 + 集 + 位置」，再次打开始终回到上次那一集，并提示「上次看到 mm:ss」（快进 / 从头）
  - 修复记历史时用空白进度覆盖已有记录的问题
- **播放器切换抽屉合并（F06/F30）**
  - 「线路」「选集」合并为一个抽屉，Tab 切换；线路为主 Tab，默认选中并标「当前」
  - 换线路后自动停在同一集；选集为 5 列网格，当前集高亮
- **线路 Tab 交互（F30）**
  - 每条线路一个 Tab（横向滚动），切 Tab 直接显示该线路剧集
  - 剧集详情页去掉「分段 → 集」两级跳转，与播放器抽屉共用 `LineEpisodeTabs`
  - 剧集按线路懒加载并缓存；当前播放的线路+集在网格中高亮
- **播放器修复与增强（F06）**
  - 修复切换剧集后画面冻住：`AndroidView.factory` 只执行一次，PlayerView 仍持有已释放的旧播放器；补 `update` 后恢复
  - 全屏（横屏 + 隐藏系统栏，Activity 补 `configChanges` 避免旋转重建丢状态）
  - 锁屏（隐藏顶栏与手势，只留解锁按钮）
  - 双击右半区快进 10s、左半区后退 10s
- **播放器控制布局（F06）**
  - 全屏移到底部右侧、锁屏移到左侧边缘居中，与主流播放器一致
  - 顶栏只保留返回、标题、「线路 · 第 N 集」与线路/选集入口
- **播放器控制区（F06）**
  - 全屏改为四角括号图标（自绘，不引图标库）
  - 清晰度移到右下角并显示当前档位，顺序：清晰度 → 全屏
- **播放器控制层重做（F06）**
  - 关闭 PlayerView 自带控制器，控制层自绘：顶栏 + 底栏（进度条、上一集/播放暂停/下一集/清晰度/全屏）
  - 单击显隐、3.5s 自动隐藏；双击左/右 40% 快退/快进 10s，中间 20% 播放暂停，900ms 内连击累加
  - 暂停与全屏图标自绘，不引入图标库
- **播放器手势与图标（F06）**
  - 锁图标区分锁定（闭合）与解锁（锁梁倾斜）
  - 左半边上下滑调亮度、右半边调音量
  - 长按 400ms 2x 倍速、松手恢复
  - 「线路 / 选集」抽屉加高
- **首页体验与导航（F02/F14）**
  - 卡片白底 + 阴影、标题字号下调；item 的 tag 悬浮在封面左上角
  - 首页悬浮刷新按钮（当前地址重新取数）；标签点击后回首页
  - 左侧菜单抽屉：设置、引擎自检移入；顶栏站点按钮带 favicon
  - 去掉收藏/历史/标签标题里的计数
  - 新增日志云：配置 `LogMsgUtil` 输出在应用内可见
- **抽屉直接放设置（F14）**
  - `SettingsContent` 抽出，设置页与左侧抽屉共用同一份内容
  - 抽屉改为标准左侧抽屉（88% 宽、遮罩、滑入动画），去掉二级菜单
- **Web 地址与标题（F23/F05/F06）**
  - Web 管理列出全部局域网 IPv4 地址，点击复制
  - 图集阅读器与播放器标题单行展示，超长自动滚动
- **详情解析弹窗（F04）**
  - 点条目不再切中间页，在列表上直接弹出解析框；完成才按类型跳转，取消则停在列表
  - 弹窗宽 320dp，含条目名、已用秒数与整行取消按钮
- **修复（F02/F08/F06）**
  - 退出播放后残留全屏椭圆遮罩：日志胶囊形状取值错误，改为抽屉入口
  - 历史为空：旧数据在 demo 名下，新增一次性迁移到当前站点
  - 播放器接管硬件返回键，锁定时先解锁
- **首页刷新与标题（F02）**
  - 首页顶栏不再显示标签名
  - 刷新按钮移入 Scaffold 槽位，刷新时显示遮罩与 loading
- **历史按站点隔离（F08）**
  - 修复浏览记录仍写入固定 demo key 的问题，改为当前站点
  - 抽屉新增「清空本站历史」
- **筛选/排序面板（F03）**
  - 引擎解码 `getUrlReplace`，`adapterLoadUrl` 参与最终地址生成
  - 顶栏「筛选」按 schema 分组单选，切换后重新取页
- **实时调试台（F25）**
  - `/api/state`、`/api/logs`、`/api/debug/run` 三个接口
  - 控制台右侧抽屉：状态轮询、日志列表、脚本执行
- **人机校验交互（F29）**
  - 校验窗口（可见 WebView）+ 同一 host 单窗口 + 5 分钟超时
  - OkHttp 与 WebView 共用 Cookie，校验后自动重试原请求
  - 403 等直接拒绝时在窗口内显示原因，不再是白屏
- **白名单与图集（F28/F05）**
  - 白名单裸域名覆盖子域，修复"页面能开、图片全裂"
  - 图集切话回到顶部；图片兜底改为 WebView 取字节（base64）
- **代理规则（F28）**
  - 人机校验窗口与普通请求共用代理规则：白名单内直连，非白名单必须走代理，不得绕过
### Fixed

- **`config-spec` 的 `key` 正则过严**：原 `^[a-z0-9][a-z0-9_-]{1,31}$` 只允许小写，导致 3/5 份真实配置（`EHentai`、`91P`、`hentaiWord`）被判非法。已放宽为 `^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$`，并在 `config-spec` §9.2 记为第 32 条核对记录。这是"实现回流文档"发现的**第一处规范缺陷**

### Changed

- **设计阶段收口：`design.md` 置为 `approved`（v3.0）**，开工闸门打开
- `AGENTS.md` §0 由「设计定稿期红线」改为「实施期规则」：允许写代码，但实现与设计冲突时**先改文档并记录**；新增 DoD 约束"不得提交无法构建/测试的代码"
- `AGENTS.md` §1 代码目录新增 `web/console/`
- `.gitignore` 新增 `.toolchain/`（便携工具链，不入库）
- 产品重定位为「配置驱动的内容容器」：配置决定内容来源、卡片形态、入口显隐、阅读器默认模式
- **配置编写能力进入 v1**：应用内置 HTTP 服务 + 浏览器编辑 + JSON 调试台（此前设想为应用内编辑器）
- 数据模型改为 `config_unit`（`config_json` + `script` 双段式）
- 术语统一：`WebConfig` → `ConfigUnit`、`webKey` → `configKey`、`站点规则` → `配置单元/来源`
- 内置服务统一承载三个出口：`/console`（管理界面）、`/api`（管理与调试）、`/engine`（引擎代理）
- 里程碑 M5 改为「配置与 Web 管理」、M6 改为「同步与打磨」
- 设计文档新增原则 P5「编辑与运行分离」与 D7/D8
- **明确配置面归属（设计文档 §2.6）**：需要键盘与大屏的放 Web 端，其余一律放应用端
- Web 服务端口 / 绑定地址 / 令牌重置改为**仅应用端**配置，API 不提供写接口
- WebDAV 地址 / 账号 / 密码 / 远端目录 / 冲突策略 / 自动同步改为**仅应用端**配置；移除 `PUT /api/webdav`，新增 `POST /api/webdav/resolve`；网页端只保留状态与动作
- 应用设置页新增「Web 管理」「同步」两个分组
- **新增文本内容类型**：`detailType = text`、可选方法 `getTextContent`、`TextContent`/`TextBlock` 模型、`render.textMode`
- **进度统一**：`comic_history` + `video_progress` 合并为 `reading_progress`（`kind` 区分 text/image/video）；接口 `/getCurrentContentUrl`、`/getSeriesCurrentContent`、`/updateCurrentComic` 收敛为 `/getReadingProgress`、`/updateReadingProgress`
- **本地仓库**成为唯一数据源：配置、偏好、进度、收藏、历史统一导出/导入/同步；设置以 `app_setting` 镜像双写
- F20 与 F26 的范围由「配置」扩展为「整个本地仓库」
- 设计文档新增原则 P6「本地仓库权威」与决策 D9
- **新增 HTTP 代理**：新增原则 D10 与 §8.13 通道设计；代理配置属 `device` 作用域不参与同步；代理变更后清空 HTML/图片缓存
- **代理能力全部进 v1**（ADR-0009）：内置转发代理提前，使「认证代理 + 无头 WebView」在 v1 可用；新增**白名单**作为唯一代理判定点（命中即直连，支持域名/子域/通配/CIDR/端口）；**未配置代理即直连**，代理为纯增量能力
- 设置页网络分组明确为 HTTP 代理（开关/地址/端口/认证/白名单/两通道状态/连通性检测），移除 SOCKS
- **新增人机校验通道**：`ChallengeDetector`（状态码 + 响应头 + HTML 特征 + `behavior.challengeMarkers`）、顶层 `ChallengeActivity`、同 host 单飞合并、Cookie 双向同步、优先 OkHttp 重试；挑战页不写缓存
- 新增运行时中断错误码 `E-CHALLENGE-001..004`（与配置校验错误区分：前者可重试）
- 调试 API 增加 `waitForChallenge` / `challengeTimeoutMs` 与事件 `challenge` / `challengeResolved`；Web 调试台新增等待态
- 本地仓库新增「凭据与 Cookie」类别：不导出、不同步、不可明文查看
- **移除远程服务模式**（Q8）：删除 `RemoteRepository`、WS 日志客户端、远程地址设置与 **F18 多服务端**（置 `dropped`）；"数据源模式"设置项消失
- **配置面归属按「阅读是 App 唯一主业」重划**（Q12）：日志（F15）、缓存治理（F16）、备份恢复（F20）、批量管理与同步动作全部移到 Web 端；App 设置页收敛为阅读显示、网络、来源、Web 管理、同步、更多引导、关于
- **正文接受任意 HTML**（Q13）：`getTextContent` 支持 `{ html }`，引擎白名单清洗（标签/属性/URL scheme）后统一转结构块，新增设计文档 §8.15
- **WebDAV 不走代理**（Q14）：独立 `OkHttpClient` + `Proxy.NO_PROXY`，不参与代理白名单判定
- Web 管理界面新增「诊断（日志与缓存）」与「备份与恢复」两块；§6.4 改为职责对照表
- **配置规范按 3 份既有配置查漏补缺（v1.5）**：新增 `inputWidth`、`ifWinExecCode`、§3.5 兼容字段；必需方法 6→7（补 `getUrlReplace`）；明确 `this` 绑定、第二参为 `cheerio`（含 `.load`）、`net.request` 仅 Promise 形态（旧事件式给 `E-CFG-030`）、`getHtml` 以 `hashString(url)` 广播取页进度、`Tag.url` 空值占位、`url`/`fullUrl` 语义、引擎不去重不排序、`adapterGetHtml` 完全接管取页
- GHS Select 补齐 `hasClass`、`toString`、属性等值选择器，并规定取值返回 `''` 而非 `undefined`
- UI 规范同步：标签空 url 占位与固定列网格、分页项按返回项渲染、图片条目 `fullUrl` 优先
- 内核 API 契约同步：`Tag.url` 空值语义、`Detail.url`/`fullUrl` 说明
- M0 出口标准新增「至少 2 份既有配置零改动跑通列表 + 详情」
- **补充事实说明（不新增范围）**：文本正文的实际形态是「纯文本 + 内嵌 `<img src>`」，除图片外没有别的 HTML 结构；因此正文解析的实现重点只有实体解码、换行分段、`<img>` 提取与 URL 校验，ADR-0012 的白名单作为防御性上限保留。仅更新 `design` §5.21/§8.15 与 `config-spec` §5 的表述，**未新增字段、解析器、渲染器或 ADR**
- **配置规范第二轮补齐（v1.6，图集类）**：新增 §4.2.1 `adapterImageCode` 完整契约（5 参 `imgSrc`/`extra`/`Md5`/`url`/`canvas`、异步回写、50ms 轮询、10s 超时）、页面上下文注入 `Md5` 与离屏 canvas；尺寸支持 `vw`/`%`；允许自定义成员方法（`this.xxx()`）；`ElementAttr` 键为 camelCase 值为真实属性名；`Item.author`、`CComic.info`（解图参数，区别于 `extra` 评论）、`CContent.time`、`Selection.toString()` 空集返回 `''`、日志单条 4KB 截断、分页项空 url 禁用；**修正搜索判定为看 `adapterSearchUrl` 返回值**（`searchUrl: ''` 不再等于不支持搜索）
- 图片管线接口变更：`imageUrl(url)` → 增加 `imageData(url, info)`，以 `EngineImage(url, info)` 作为 Coil 的 data，避免解图参数在 URL 传递中丢失
- M0 出口标准提高：至少 2 份既有配置零改动跑通，**其中 1 份必须含图集与解图**
- **Web 编辑器由 CodeMirror 换为 Monaco，并接入类型驱动的智能提示**（ADR-0014）：源码模式使用 Monaco + TypeScript 语言服务；注入 `config-api.d.ts` 后获得补全（19 个注入 API、7 必需/7 可选方法、`ElementAttr`/`ElementTypes` 枚举、配置字段、返回模型）、hover 文档、参数签名与诊断（含旧式 `net.request(...).on` 的 `@deprecated` 提示）；d.ts 是配置规范的派生物、不得反向修改；Monaco 本地打包禁止 CDN、仅源码模式按需加载、控制台产物上限 8MB；窄屏（<900px）源码模式降级为只读
- 新增 `config-spec` §8.1：编辑器智能提示的数据源与一致性约束

### Removed

- **F19 应用内配置编辑器**：编辑统一收敛到 Web 管理界面，编号作废不复用（`feature-registry.md` 保留 `dropped` 记录）
- 移除全部外部项目引用（名称、路径、包名、源码文件名、行数）
- 移除文档中与特定内容类型相关的表述

### Docs

- 新增 `docs/04-engineering/build.md`：便携工具链形态（JDK 21 / Gradle 8.9 解压在工作区）、必需环境变量（`GRADLE_USER_HOME` 必须指到工作区）、踩过的坑（native-platform.dll、截断 zip、执行策略、Truffle 的 AppData/user.home）与当前验证基线
- 新增 `docs/04-engineering/spike-script-runtime.md`：脚本运行时 spike 报告（6 项假设的验证结论、句柄式桥接协议、`user.home` 环境坑、未验证项）

- `AGENTS.md` §2.5「文档独立性」：禁止外部引用，提取的需求必须重写为本项目功能条目
- `AGENTS.md` §2.1 SSOT 清单新增四项：配置规范、Web 服务 API、Web 管理界面、UI 实现规范
- `AGENTS.md` §7 新增红线：禁止在应用内新增「配置编辑」能力
- `AGENTS.md` §7 新增红线：禁止把应用级设置（端口/绑定/令牌、WebDAV 凭证、开关类设置）暴露到 Web 端
- `AGENTS.md` §3 功能增删必改清单保持三处强制（design / registry / CHANGELOG）
- 编号说明：**ADR-0013 为废弃空号**（曾草拟后撤销、从未入库），按「编号永不复用」原则不再分配
