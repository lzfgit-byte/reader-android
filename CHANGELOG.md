# 变更日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

**本项目约定（见 `AGENTS.md` §3）：功能的新增、删除、改名、范围变更，必须同时更新
`docs/01-product/design.md` 与 `docs/01-product/feature-registry.md`，并在本文件记录。**

分类：`Added` 新增 / `Changed` 变更 / `Removed` 删除 / `Deprecated` 弃用 / `Fixed` 修复 / `Docs` 文档。

---

## [Unreleased]

### Added

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
