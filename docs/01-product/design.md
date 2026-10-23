> **状态**：approved
> **版本**：v3.1
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v2.11

# 主设计文档

| 项目 | 内容 |
|---|---|
| 文档编号 | DESIGN-001 |
| 目标产物 | **GHS Mobile**（独立 Android 应用，包名 `com.ghs.mobile`） |
| 技术栈 | Kotlin + Jetpack Compose + Media3 + Room + OkHttp |
| 产品形态 | **配置驱动的内容容器**：内容从哪来、长什么样、入口有哪些，全部由传入的配置决定 |
| v1 范围 | 完整功能集（F01–F16、F21、F23–F25），F17/F18/F20/F22/F26 为后续能力 |
| 文档位置 | `docs/01-product/design.md` |

**本文档是"功能范围与交互设计"的唯一权威来源（SSOT）**。改了功能就必须改本文档，规则见 [AGENTS.md](../../AGENTS.md) §3。

相关文档：

- 功能交付状态 → [feature-registry.md](feature-registry.md)
- 动态配置规范 → [config-spec.md](../03-api/config-spec.md)
- UI 实现规范 → [ui-implementation.md](../02-architecture/ui-implementation.md)
- 数据层契约 → [rest-contract.md](../03-api/rest-contract.md)
- 决策理由 → [ADR 目录](../02-architecture/adr/)
- 文档制度 → [document-policy.md](../00-process/document-policy.md)

> 本文档只做产品与架构设计，不包含代码实现。开放问题见第 11 章。

---

## 1. 决策记录

本节只做**摘要与索引**，决策理由的唯一权威来源是 ADR（见 [AGENTS.md](../../AGENTS.md) §4）。

| 编号 | 决策（摘要） | 记录 |
|---|---|---|
| D1 | 用 Kotlin + Jetpack Compose 原生实现 | [ADR-0001](../02-architecture/adr/ADR-0001-native-kotlin-compose.md) |
| D2 | 抓取与解析能力内置在应用内 | [ADR-0002](../02-architecture/adr/ADR-0002-embedded-engine-dual-provider.md) |
| D3 | v1 覆盖完整功能集，不留"占位页" | 产品范围决策，见 §5 与 [CHANGELOG](../../CHANGELOG.md) |
| D4 | 数据层同时支持"内置引擎"与"远程服务"两种模式 | [ADR-0002](../02-architecture/adr/ADR-0002-embedded-engine-dual-provider.md) |
| D5 | 脚本运行时：QuickJS 主通道 + 无头 WebView 兜底 | [ADR-0003](../02-architecture/adr/ADR-0003-script-runtime-quickjs-webview.md) |
| D6 | 配置采用「清单 JSON + 脚本 JS」双段式，兼容单文件内联 | [ADR-0004](../02-architecture/adr/ADR-0004-config-format.md) |
| D7 | 配置编辑只在应用内置的 Web 管理界面，应用内只做管理与只读 | [ADR-0005](../02-architecture/adr/ADR-0005-web-console-and-debug.md) |
| D8 | 配置同步采用 WebDAV，v1 只做手动同步与冲突提示 | [ADR-0006](../02-architecture/adr/ADR-0006-webdav-sync.md) |
| D9 | 全部数据落在一个本地仓库里，导出/导入/同步统一在仓库层 | [ADR-0007](../02-architecture/adr/ADR-0007-local-store-unified-sync.md) |
| D10 | HTTP 代理对「应用请求」与「无头 WebView」两条通道同时生效 | [ADR-0008](../02-architecture/adr/ADR-0008-http-proxy-two-channels.md) |
| D11 | 白名单是唯一的代理判定点；转发代理进入 v1，未配置即直连 | [ADR-0009](../02-architecture/adr/ADR-0009-proxy-whitelist-single-decision.md) |
| D12 | 人机校验用独立顶层 Activity，校验完成带 Cookie 重试 | [ADR-0010](../02-architecture/adr/ADR-0010-challenge-top-activity.md) |
| D13 | 移除远程服务模式，只保留内置引擎 | [ADR-0011](../02-architecture/adr/ADR-0011-drop-remote-mode.md) |
| D14 | 正文接受任意 HTML，但清洗后统一转结构块 | [ADR-0012](../02-architecture/adr/ADR-0012-html-sanitize-to-blocks.md) |
| D15 | Web 编辑器用 Monaco，智能提示由注入的 `.d.ts` 驱动 | [ADR-0014](../02-architecture/adr/ADR-0014-monaco-editor-intellisense.md) |

D2 与 D6 是本文档的**主要复杂度来源**：前者决定要在手机上跑一个内容引擎，后者决定引擎与 UI 的输入是一份可校验的配置。

---

## 2. 产品定位与设计出发点

### 2.1 一句话定位

**一个由配置驱动的内容容器：配置决定「看什么」和「长什么样」，应用负责「加载、渲染、缓存、播放」。**

- 应用**不内置任何内容源**，也不针对任何具体来源写代码；
- 用户导入一份**配置单元**（ConfigUnit），应用立刻获得一个新的内容来源：列表、详情、图集、视频、搜索、筛选全部按这份配置的描述工作；
- 同一份配置决定了 UI 的形态：卡片尺寸与排布、启用的入口、阅读器默认模式、主题强调色；
- **配置的编写在浏览器里完成**：应用内置一个 Web 管理服务，在电脑或手机浏览器里写配置、试跑、看 JSON（见 §8.12）。

### 2.2 六条设计出发点

| 编号 | 出发点 | 对设计的约束 |
|---|---|---|
| P1 | **单机自足** | 抓取、解析、存储、播放全部在应用进程内完成；不要求用户部署任何服务 |
| P2 | **配置驱动** | 内容来源的差异全部收敛到配置；应用代码里**不允许出现来源特判** |
| P3 | **基础底座优先** | 先把"配置加载 → 解析 → 渲染分发 → 缓存 → 媒体"这条底座打磨好，功能才能组合出来 |
| P4 | **阅读优先** | 图片与视频是主场景；内存、手势、进度记忆、预加载按硬指标要求 |
| P5 | **编辑与运行分离** | 编辑面在浏览器（大屏、键盘、并排看 JSON），运行面在设备（真实网络环境）；调试只关心"数据对不对" |
| P6 | **本地仓库权威** | 运行时只读本地仓库；网络是输入，同步是副本。用户可见的数据都能整体导出再导回 |

### 2.3 形态取舍

| 形态 | 说明 | 结果 |
|---|---|---|
| 纯 WebView 壳 | 直接加载目标网页，只做外壳 | 无法统一手势、无法缓存、无法做阅读器。**不采用** |
| 纯客户端（依赖外部服务） | 解析在别处，App 只渲染 | 需要用户额外运行环境。**不采用** |
| **配置驱动的内置引擎** | 配置 + 引擎 + 渲染全在应用内 | **采用** |

### 2.4 底座与功能的层次关系

```
        ┌──────────────────────────────────────────────┐
        │  应用层：首页 / 详情 / 阅读器 / 播放器 / 设置  │
        ├──────────────────────────────────────────────┤
        │  渲染层：渲染器注册表 + 四态容器 + 设计系统    │  ← ui-implementation.md
        ├──────────────────────────────────────────────┤
        │  领域层：Item / Page / DetailInfo / 进度 / 收藏 │  ← rest-contract.md §3
        ├──────────────────────────────────────────────┤
        │  引擎层：配置加载 → 取页 → 解析 → 执行脚本     │  ← config-spec.md
        ├──────────────────────────────────────────────┤
        │  基础设施：网络 / 存储 / 缓存 / 媒体 / 日志    │
        └──────────────────────────────────────────────┘
```

**底座 = 中间三层**。功能都是这层之上的组合，因此 v1 的优先级先给底座（F01/F14/F15/F16/F21），再给浏览主链路。

### 2.5 术语

详见第 4 章；配置怎么写见 [config-spec.md](../03-api/config-spec.md)；UI 怎么实现见 [ui-implementation.md](../02-architecture/ui-implementation.md)。

### 2.6 配置面归属（谁能设置什么）

**判断标准（Q12 定稿）**：**阅读是 App 端唯一的主业。**凡不服务于"立刻能读/能播"、且操作偏复杂的，一律放 Web 端。App 端只保留两类：① 不配就连不上 Web 的引导性设置；② 阅读时随手要用的。

| 项 | 位置 | 理由 |
|---|---|---|
| 阅读/播放主路径（首页、详情、阅读器、播放器、收藏、历史、搜索） | **仅 App** | App 的主业 |
| 阅读显示（主题、字号、行距、列表密度、阅读器默认模式） | **仅 App** | 阅读中随手改 |
| 当前来源切换、单个配置的启用/停用/删除、单条导入导出 | **仅 App（最小集）** | 阅读中可能临时换源；Web 不可达时的兜底 |
| Web 服务开关、端口、绑定地址、令牌重置 | **仅 App** | 连不上 Web 时也必须能改 |
| 代理（HTTP：地址、端口、认证、白名单） | **仅 App** | 属设备网络环境，且连不上时也要能改 |
| WebDAV 地址/账号/密码/目录/策略/自动同步 | **仅 App** | 含凭证；连不上时的兜底 |
| 配置内容编辑、调试运行 | **仅 Web** | 重编辑（[ADR-0005](../02-architecture/adr/ADR-0005-web-console-and-debug.md)） |
| 批量导入导出、bundle、排序拖拽、批量删除 | **仅 Web** | 复杂/批量操作 |
| 备份与恢复（F20） | **仅 Web** | 复杂操作，且需逐条看导入报告 |
| 运行日志（F15）、缓存治理（F16） | **仅 Web** | 诊断类，需要大屏看与逐条比对 |
| WebDAV 同步动作与冲突决策（F26） | **仅 Web** | 有报告与逐条决策；App 端只显示"上次同步" |

三条推论：

- **App 端最小化**：设置页只保留"连不上 Web 就得改"的项与阅读设置，其余以一行入口引导到 Web 管理界面（附地址与二维码）；
- **Web 端不提供设备级设置与凭证的写接口**（见 [web-admin-api](../03-api/web-admin-api.md) §2）；
- **App 端不提供配置内容编辑与调试**（[ADR-0005](../02-architecture/adr/ADR-0005-web-console-and-debug.md)）。

---

## 3. 目标与非目标

### 3.1 目标

- **G1** 交付完整的客户端骨架：首页、收藏、历史、设置四个主入口 + 详情、阅读器、播放器、搜索、系列、单图、评论、配置管理、日志等子页。
- **G2** 内置内容引擎，**导入配置即可支持新来源，无需升级 App**。
- **G3** 配置规范稳定可用：可静态校验、可热重载、可导出导入、向后兼容。
- **G4** 阅读与播放达到"可长期使用"的水准：图片预加载、长图不 OOM、进度记忆、手势齐备、后台播放。
- **G5** UI 完全由配置驱动：入口显隐、卡片形态、阅读器模式随配置变化，代码零特判。
- **G6** 可维护：引擎层不依赖 Android Framework，可在 JVM 单测。

### 3.2 非目标（v1 明确不做）

- 不做账号体系、云同步、多端数据同步。
- 不做内容分发、不做配置商店、不内置任何配置。
- **不做应用内的配置编辑器**：编辑统一在 Web 管理界面完成（F24）；应用内只提供管理、导入导出与只读查看。
- 不做 iOS / 平板专属布局（平板仅要求"不崩、可读"）。
- 不上架应用商店（见第 10 章分发边界）。

---

## 4. 名词与领域模型

| 名词 | 含义 |
|---|---|
| **配置单元（ConfigUnit）** | 一个内容来源的完整描述：元信息 + 渲染参数 + 行为参数 + 解析脚本。用户界面称「来源」 |
| **清单（manifest）** | 配置单元的一种形态：`config.json` + `script.js` |
| **内联（inline）** | 配置单元的一种形态：单个 `config.js` |
| **Item（条目）** | 列表中的一个内容元素：`jumpUrl` `title` `coverImg` `type` `renderType` `tags[]` |
| **Page（列表页）** | 一次列表结果：`items[]` `pagination[]` `tags[]` `urlReplace[]` |
| **DetailInfo（详情）** | `detailType` `details[]` `relations[]` `analysis[]` |
| **detailType** | 决定跳哪个渲染器：`mp4` `m3u8` `image` `comic` `series` `win` |
| **CContent / CComic** | 图集章节目录 / 图集图片组 |
| **Analysis / AnalysisDetail / AnalysisVideoDetail** | 系列三层：总集 → 分集 → 播放源 |
| **urlReplace** | 过滤/排序的 URL 追加参数组（`schema` + `urlAppend[]`） |
| **setTags** | 配置声明启用哪些入口：`标签` `收藏` `历史` `过滤选项` `系统配置` `日志` `配置` |
| **GHS Select** | 脚本中的选择器 API，兼容 jQuery/cheerio 常用语法（§8.4） |
| **ContentEngine** | 进程内的内容引擎：配置加载、取页、解析、脚本执行、事件分发 |
| **EngineSession** | 引擎运行态：当前配置、当前列表地址、当前条目 |
| **Web 管理服务** | 应用内置的 HTTP 服务：提供 Web 管理界面、管理 API 与调试 API（§8.12） |
| **调试台** | 浏览器端的"运行配置并查看 JSON"工具；只验证数据与交互，不渲染媒体 |
| **WebDAV 同步** | 以 `<key>.ghscfg.json` 为单位，与用户自有的 WebDAV 目录双向同步配置 |
| **本地仓库（LocalStore）** | 设备上唯一的运行时数据源：配置、偏好、进度、收藏、历史、搜索词；可整体导出、导入与同步（[local-store](../03-api/local-store.md)） |
| **ReadingProgress** | 统一的进度模型：`kind`（text/image/video）+ 章节或页码 + 位置，替代按媒介分表 |
| **TextContent** | 文本正文的规范化表示：段落块数组（`p`/`h2`/`h3`/`quote`/`image`），不是任意 HTML |
| **代理（Proxy）** | 应用端的 HTTP 代理配置，一份配置同时作用于 OkHttp 通道与无头 WebView 通道（§8.13） |
| **人机校验（Challenge）** | 取页时返回的挑战页而非内容页；需要真人在顶层窗口中完成，通过后凭证落在 Cookie 上（§8.14） |

领域模型以 Kotlin `data class` 定义，字段与 [rest-contract.md](../03-api/rest-contract.md) §3 一一对应；配置字段与 [config-spec.md](../03-api/config-spec.md) §3 一一对应。

---

## 5. 功能清单（v1）

优先级：**P0** 主链路必做 / **P1** 完整体验 / **P2** 后续版本。

| 编号 | 功能 | 类别 | 优先级 | 说明 |
|---|---|---|---|---|
| F01 | 配置管理 | 底座 | P0 | 导入导出、切换、启停、排序、只读查看 |
| F02 | 内容流 | 浏览 | P0 | 双列卡片、类型角标、收藏星标、标题跑马灯 |
| F03 | 分页与过滤 | 浏览 | P0 | 分页跳转 + 排序/筛选面板 |
| F04 | 详情与渲染分发 | 浏览 | P0 | 按 detailType 分发到 6 种渲染器 |
| F05 | 图集阅读器 | 浏览 | P0 | 多图连续/分页、进度上报、续读、手势、预加载 |
| F06 | 视频播放器 | 浏览 | P0 | mp4/m3u8、回环代理、手势、全屏、画中画、进度记忆 |
| F07 | 收藏 | 数据 | P0 | 星标、列表、批量删除 |
| F08 | 历史 | 数据 | P0 | 观看历史 + 续读/续看入口 |
| F09 | 搜索 | 浏览 | P1 | 站内搜索 + 历史记录 + 联想 |
| F10 | 标签筛选 | 浏览 | P1 | 标签点击进入筛选态 |
| F11 | 系列/分集 | 浏览 | P1 | 三层下钻 + 集数面板 |
| F12 | 单图查看器 | 浏览 | P1 | 单图大图 + 缩放 + 评论 |
| F13 | 评论 | 浏览 | P1 | 条目/图片级评论抽屉 |
| F14 | 设置 | 底座 | P0 | 阅读显示、网络代理、来源、Web 管理、同步、关于 |
| F15 | 运行日志 | 底座 | P1 | 引擎事件流可视化、级别过滤、导出 |
| F16 | 缓存治理 | 底座 | P1 | 内存/磁盘三级缓存、上限、清理、命中统计 |
| F17 | 离线下载 | 增强 | P2 | 图集/视频预下载（v1.1） |
| F20 | 数据备份与恢复 | 底座 | P2 | 本地仓库整体导出/导入（v1.1） |
| F21 | 外观与显示设置 | 底座 | P1 | 主题、字号、列表密度、阅读器默认模式覆盖 |
| F22 | 应用更新检查 | 增强 | P2 | 版本检查、下载、校验（v1.2） |
| F23 | 内置 Web 管理服务 | 底座 | P0 | 应用内 HTTP 服务：管理界面 + 管理/调试 API |
| F24 | 配置编辑器（Web） | 底座 | P0 | 浏览器内新建/修改配置，表单与源码双模式 |
| F25 | 实时调试台（Web） | 底座 | P0 | 运行配置方法，返回 JSON、耗时、日志与错误码 |
| F26 | WebDAV 同步 | 底座 | P1 | 本地仓库与自有 WebDAV 目录双向同步（v1.1） |
| F27 | 文本阅读器 | 浏览 | P0 | 章节正文阅读，按本地进度续读 |
| F28 | 网络代理（HTTP） | 底座 | P0 | 一份代理配置，同时作用于应用请求与无头 WebView |
| F29 | 人机校验交互 | 底座 | P0 | 取页命中校验时顶层弹窗，完成后继续加载并解析 |

> **功能编号 `F01`–`F22` 的交付状态以 [feature-registry.md](feature-registry.md) 为准。**
> 本节只描述「做什么」，不描述「做到哪一步」。新增或删除功能时，两处必须同时修改。

### 5.1 F01 配置管理

- **入口**：设置 →「来源」；首页顶栏来源切换器。
- **能力**：列出全部配置单元（图标 + 名称 + 描述）；设为当前；启用/停用；调整顺序；导入（文件选择 / 剪贴板 / 系统分享 Intent）；导出（manifest 或内联合并成单文件）；删除；只读查看源码与字段。
- **加载**：完整协议见 [config-spec.md](../03-api/config-spec.md) §6；本节只约定用户体验：
  - 导入时**逐级反馈**：读取 → 解析 → 校验 → 注册，失败立即停下并显示错误码 + 字段路径 + 原始片段；
  - 失败**不影响**已启用的配置；
  - 同名 `key` 冲突时询问是否覆盖，默认不覆盖。
- **数据**：`config_unit` 表（见 §8.8）。
- **验收**：导入一份清单配置后自动显示名称/图标/描述并出现在列表；切换后首页按该配置渲染；导入缺 `key` 的配置提示 `E-CFG-002` 且原配置不变。

### 5.2 F02 内容流

- **布局**：卡片排布由配置 `render.cardStyle` 决定：
  - `cover`：纯封面；
  - `cover-title`（默认）：封面 + 单行标题（超宽跑马灯）；
  - `list`：左图右文横向列表。
- **卡片尺寸**：由 `render.imgWidth/imgHeight` 推导宽高比，默认 220×147。
- **卡片元素**：封面（4:3，铺满卡片宽度）、单行标题（超长自动滚动）、右侧星标（点击收藏/取消）；卡片为白底 + 阴影，标题用小号字；配置返回的 `tags` 悬浮在封面左上角（最多 3 个）。不显示进度或类型副标题，信息保持最简；网格间距取最小值，优先保证封面尺寸。
- **交互**：点击进详情；点击右下角星标收藏/取消（实心为主题色表示已收藏）；下拉刷新（重置当前列表地址）；**触底自动加载下一页**。
- **单列 / 双列**：顶部提供切换按钮，双列默认；单列采用"左图右文"横向卡片，双列采用"上图下文"卡片。选择对收藏、历史同样生效。
- **收藏与历史复用同一卡片**：星标可直接收藏/取消；历史的副标题为浏览时间。
- **验收**：连续滚动 20 页不 OOM；三种 `cardStyle` 均可正确渲染；单双列切换即时生效；触底加载到底后显示"没有更多了"；断网有错误态与重试。

### 5.3 F03 分页与过滤

- 分页入口在**右上角**：显示当前页码，点开为"选择页码"底部抽屉（网格列出全部页码，当前页高亮，点击直达）。列表底部不再占位。与"触底自动加载下一页"共存：抽屉用于精确跳页，触底用于连续浏览。
- 左侧抽屉含「引擎日志」入口（配置脚本输出），不再在首页悬浮日志胶囊。\n- 左侧抽屉直接展开设置正文（外观 / Web 管理 / 网络代理 / 阅读），不做二级菜单；底部为「引擎自检」入口。\n- 站点切换同样走底部抽屉（"选择站点"：搜索框 + 两列站点卡片，当前打勾）；卡片图标取配置的 `favicon`，缺失时退回首字色块。
- 过滤面板：读取 `urlReplace`（按 `schema` 分组），选中后调 `adapterLoadUrl` 重新加载并回到顶部。
- **验收**：排序切换后列表顺序变化且自动回顶；无 `过滤选项` 配置项时不显示入口。

### 5.4 F04 详情与渲染分发

按 `detailType` 分发（实现见 [ui-implementation.md](../02-architecture/ui-implementation.md) §6.1）：

| detailType | 渲染目标 |
|---|---|
| `mp4` / `m3u8` | 视频播放器（F06） |
| `image` | 多图 → 图集阅读器（F05）；单图 → 单图查看器（F12） |
| `comic` | 图集阅读器（先 `getContents` 取目录） |
| `text` | 文本阅读器（先 `getContents` 取目录，再 `getTextContent` 取正文） |
| `series` | 系列面板（F11） |
| `win` | 内嵌浏览器，外链回落系统浏览器 |

- 顶部：封面、标题、tags（可点）、收藏按钮。
- 中部：`details[]` 条目列表（含 `title` `quality` `comments`）。
- 底部：`relations[]` 相关推荐。
- **验收**：6 种 `detailType` 各有可复现用例；新增渲染器不改导航层代码。

### 5.5 F05 图集阅读器

- **默认模式来自配置** `render.readerMode`：`vertical`（连续滚动）/ `horizontal`（分页）/ `auto`（按图片宽高比自动）。
- **清晰度**：图片有两级地址——`url`（展示图）与 `fullUrl`（原图）。阅读器默认原图，顶栏可切换；选择持久化（`prefs.imageQuality`）。原图加载失败自动回落展示图。
- **手势**：单击显隐浮层（已实现）；双击 2x 缩放（已实现）；双指自由缩放 1x~5x、1x 时横向拖动仍翻页（已实现）；长按保存到相册（已实现）；左缘亮度 / 右缘音量（**待实现**）。
- **进度**：节流上报（默认 1s 或翻页时）；进入时按服务端记录的进度定位。
- **预加载**：当前页 ±1 与 +2 由 Coil 预取（已实现，按所选清晰度取地址）；其余页降优先级（待实现）。

- **边界**：`comicImgMaxWidth` 已生效（支持 px/dp/vw/%/纯数字，默认 1080px，同时作用于展示与预取请求）；超长图（>8192px）**分块解码待实现**。
- **验收**：300 张图从头滚到尾内存稳定在 300MB 以下；杀进程重进回到原页；三种默认模式均可切换且记住选择。

### 5.6 F06 视频播放器

- **引擎**：Media3 ExoPlayer（HLS + DASH），控制层自绘。
- **直连重试**：媒体请求带站点 Referer 与浏览器 UA；遇 403 自动改直连重试一次（视频 CDN 常拦代理出口 IP）。
- **控制条**：用官方 `media3-ui` 的 PlayerView（缓冲指示、拖动、倍速、字幕、自动隐藏），不引入第三方播放器。
- **手势**：单击显隐控制条；双击右半区快进 10s、左半区后退 10s。
- **控制层**：关闭 `PlayerView` 自带控制器，控制层自绘（与主流播放器一致）。
  - 顶栏：返回、标题、「线路 · 第 N 集」、线路/选集
  - 底栏：当前时间 + 进度条 + 总时长；下一行 上一集 / 播放暂停 / 下一集 / 清晰度 / 全屏
  - 锁屏：左侧边缘垂直居中；锁定后单击只切换锁按钮显隐
  - 图标（暂停、全屏）自绘，不引入 `material-icons-extended`
- **手势**：单击显隐控制层（播放中 3.5s 自动隐藏）；双击左 40% 快退、右 40% 快进、中间 20% 播放暂停；900ms 内同方向连续双击按 10s 累加；左半边上下滑调亮度、右半边调音量；长按 400ms 进入 2x 倍速，松手恢复。全屏=横屏 + 隐藏系统栏（Activity 声明 configChanges，旋转不重建）；锁屏后仅保留左侧解锁图标。
- **播放器内切换**：一个「线路 / 选集」抽屉、两个 Tab——**线路为主 Tab**（默认选中，标「当前」），选集为 5 列网格；换线路自动停在同一集。副标题显示「线路 · 第 N 集」；返回键从剧集进入时回剧集页。
- **数据**：`details[].url` 经 `videoUrl(url)` 取得可播地址（内置模式下走回环代理，见 §8.7）。
- **控制条**：播放/暂停、进度拖拽、时间、倍速（0.5–2.0）、画质切换、上下集、全屏、锁定。
- **手势**：左右滑 Seek、上下滑亮度/音量、长按 3x、双击暂停。
- **系统集成**：横屏沉浸式、画中画、后台播放（MediaSession + 通知栏 + 音频焦点）、Wakelock。
- **进度记忆**：按 `jumpUrl` 存本地进度，再次进入自动续播。
- **进入即播**：详情解析出 `mp4/m3u8` 地址后直接进入播放器并续播，不设中间的清晰度列表页。
- **清晰度切换**：多源时在播放器内以菜单切换，保留当前播放位置；单源不显示入口。
- **播放页整屏**：不显示底部导航，避免误触。
- **验收**：m3u8 点播与直播均可播；Seek 响应 <300ms；切后台继续播。

### 5.7 F07 收藏 / F08 历史

- 收藏：条目级开关；列表按来源分组；支持滑动删除与批量删除；乐观更新 + 失败回滚。
- 历史：跨来源统一列表，按时间分组；顶部"继续阅读/继续观看"；按配置 `historyRemember` 截断。
- **验收**：收藏状态与列表实时一致；历史条数不超过配置上限。

### 5.8 F09 搜索 / F10 标签 / F11 系列

- 搜索：输入 → `adapterSearchUrl` 生成地址 → 载入结果；联想来自 `adapterRemoteSearch`；历史本地保存。
- 标签页：底栏入口，读取 `getTags` 渲染标签云（支持配置给出的全量标签）；点击标签按该地址重载列表，标题显示"标签名 · 列表 · N 项"。
- 系列：`analysis[]` → `getAnalysisDetail` → `getAnalysisVideoDetail`，底部 Sheet 展示，边看边切集。

### 5.9 F12 单图查看 / F13 评论

- 单图：全屏、双指缩放、双击缩放、左右切图、保存/分享。
- 评论：`DetailItem.comments` / `CComic.extra`；抽屉列表（时间 + 文本 + 可选图）。

### 5.10 F14 设置

| 分组 | 项 |
|---|---|
| 阅读显示 | 主题、字号、行距、列表密度、阅读器默认模式、状态胶囊（F21） |
| 网络 | 请求超时、UA 覆盖、**代理**（HTTP：开关、地址、端口、认证、白名单、两通道状态与连通性检测，见 F28） |
| 来源 | 当前来源切换、配置列表（启用/停用/删除/单条导入导出，F01 最小集） |
| Web 管理 | 服务开关、端口、绑定地址、令牌重置、局域网地址与二维码（F23） |
| 同步 | WebDAV 地址、账号、密码、远端目录、冲突策略、自动同步、上次同步时间（F26） |
| 更多 | 一行入口："日志、缓存、备份、批量管理请在 Web 管理界面完成"（附地址与二维码） |
| 关于 | 版本、开源许可、使用声明、检查更新（F22） |

### 5.11 F15 运行日志（Web）

- 引擎通过进程内事件总线（`SharedFlow<EngineEvent>`）产生日志、进度、错误。
- **入口只在 Web 管理界面**（§2.6）：级别过滤、按来源/时间筛选、复制、导出文本、跳转到对应配置。
- App 端**不提供日志页**；仅在"更多"入口给出引导，同时保留状态胶囊展示当前抓取进度。
- **验收**：任何一次抓取失败都能在 Web 端定位到失败环节（取页 / 人机校验 / 解析 / 脚本异常）。

### 5.12 F16 缓存治理（Web）

- **三级**：内存（图片 LRU 64MB）→ 磁盘（`cacheDir/engine/{html,img}`）→ 网络。
- **HTML**：按 URL hash 命中即跳过抓取；可在 Web 端整体关闭。
- **图片**：按 `URL hash + 目标尺寸` 建键，LRU 上限可配（默认 512MB）。
- **管理入口在 Web**（§2.6）：容量统计、按来源/类型清理、命中率统计；App 端只保留一个"清理全部缓存"便捷按钮。
- **验收**：连续浏览 100 个详情页后磁盘占用不超上限；清理后首次访问正常重抓。

### 5.13 F17（v1.1 预留）

- **F17 离线下载**：图集与视频预下载，任务队列、断点续传、仅 Wi-Fi、容量上限、单条清理。

> 原 F18「多服务端」已随远程服务模式一起删除（[ADR-0011](../02-architecture/adr/ADR-0011-drop-remote-mode.md)），编号作废且不再复用。

### 5.14 F20（v1.1 预留）

- **F20 数据备份与恢复**：把**整个本地仓库**（配置、偏好、进度、收藏、历史）导出为一个文件，可跨设备恢复；导入支持 `dry-run` / `merge` / `replace` 三种模式，并返回逐类别的导入报告。
- **不同步内容**：设备设置（端口、绑定、凭证、代理、缓存上限）导出但导入时忽略；搜索历史默认不导出。
- 格式与规则见 [local-store.md](../03-api/local-store.md) §5、§6。

> 原 F19「应用内配置编辑器」已删除，编号作废且不再复用；编辑能力由 F24（Web 管理界面）承担，理由见 [ADR-0005](../02-architecture/adr/ADR-0005-web-console-and-debug.md)。

### 5.15 F21 外观与显示设置

- **主题**：跟随系统 / 亮色 / 暗色；配置的 `themeColor` 只覆盖强调色。
- **字号**：默认 / 大 / 特大（对应系统字号偏移），布局不得截断。
- **列表密度**：舒适 / 紧凑（影响卡片间距与标题行数）。
- **阅读器默认模式覆盖**：用户选择优先于配置的 `readerMode`，仅对当前来源生效。
- **状态胶囊开关**：可关闭进度提示。
- **验收**：切换主题即时生效且无需重启；字号"特大"下主链路无截断。

### 5.16 F22 应用更新检查

- 读取远端版本清单（JSON），比对本地 `versionCode`；有新版本时提示并给出下载入口。
- 下载后校验 SHA-256 与包名一致性，校验失败拒绝安装并提示。
- 完全手动触发（设置 → 关于 → 检查更新），不做后台静默安装。
- **验收**：伪造版本清单不会导致误装；校验失败有明确提示。

### 5.17 F23 内置 Web 管理服务

- **入口**：设置 →「Web 管理」；开启后显示局域网地址与二维码。
- **服务内容**：`/console` 管理界面、`/api` 管理 API、`/engine` 引擎代理（默认关闭）。
- **绑定**：默认**仅本机**；切到局域网需显式确认并展示风险；端口默认 `18600`，被占用自动顺延并提示实际端口。
- **端口、绑定地址、令牌重置、服务开关**都在应用端「设置 → Web 管理」中调整（§2.6），**不提供修改这些参数的 API**。
- **鉴权**：一次性 token，扫码即用；应用重启或手动重置后失效。
- **安全**：静态资源白名单、无文件系统 API、请求体 10MB、并发 4、单请求 60s。
- **验收**：电脑浏览器扫码可打开界面；关闭开关后端口立即不再监听；无 token 访问返回 401。

### 5.18 F24 配置编辑器（Web）

- **新建**：清单形态（推荐）/ 内联形态；内联形态自动注入 7 个必需方法骨架。
- **双模式编辑**：表单模式（`meta`/`render`/`behavior` 分组）与源码模式双向同步；源码语法错误时表单只读并提示行号。
- **源码编辑器**：**Monaco Editor**，补全 / hover / 参数签名 / 诊断由 TypeScript 语言服务提供（[ADR-0014](../02-architecture/adr/ADR-0014-monaco-editor-intellisense.md)）。
- **智能提示**：注入 `config-api.d.ts`（配置规范 §3/§8 与内核契约 §3 的派生物），识别注入 API、配置字段、返回模型与已废弃写法；**提示不替代服务端校验**。
- **校验**：保存前调用校验接口，**全部**错误逐条展示（`code` + 字段路径 + 建议），点击可跳转到对应字段或行号；警告不阻塞保存。
- **保存**：`Ctrl/Cmd+S`；本地草稿自动保存，刷新页面不丢；保存成功后可"立即试跑"。
- **边界**：**编辑只在 Web 管理界面**；应用内只提供管理、导入导出与只读查看。窄屏（< 900px）源码模式降级为只读。
- **验收**：不改应用即可新建一个可用来源；非法字段被拦下并精确指出路径；输入 `getH` 能补全出 `getHtml`；漏必需方法时编辑器与服务端都给出一致结论。

### 5.19 F25 实时调试台（Web）

- **目标**：验证"功能与数据交互是否正确"，**不渲染图片、视频或网页**。
- **支持方法**：`getItems` `getPagination` `getTags` `getDetailInfo` `getContents` `getComicImages` `getAnalysisDetail` `getAnalysisVideoDetail` `adapterSearchUrl` `adapterLoadUrl`。
- **输入**：URL；可粘贴 HTML 跳过抓取；`getDetailInfo` 等可传 Item；选项含"强制 WebView""返回原始 HTML"。
- **输出**：JSON 树 + 条数 + **分段耗时**（取页 / 解析 / 脚本）+ **URL 补全对照**（`raw` → `resolved`）+ 日志 + 错误码与字段路径。
- **实时**：运行期订阅事件流（log / progress / step / done），可中断。
- **只取页**：独立入口用于先判断"能不能拿到页面"，把问题定位到取页还是脚本。
- **验收**：空结果显示 `[] (0 条)` 而非空白；脚本异常显示 `code` + 路径 + 异常首行；运行可中断且不残留占用。

### 5.20 F26 WebDAV 同步（v1.1 预留）

- **同步范围 = 本地仓库**：配置、偏好、阅读进度、收藏、观看历史；**设备设置（端口/绑定/凭证/代理/缓存上限）与搜索历史不参与**（§2.6、[local-store](../03-api/local-store.md) §2）。
- **连接参数只在应用端配置**：地址、账号、密码、远端目录、冲突策略、自动同步开关，全部在应用「设置 → 同步」中设置；密码只存应用内加密存储，**任何网页端响应都不返回凭证**。
- **动作只在网页端**：测试连接、拉取、推送、冲突决策在 Web 管理界面完成；App 端只显示"上次同步时间/结果"，不提供按钮（§2.6）。
- **不走代理**：WebDAV 请求固定直连，独立 `OkHttpClient`（`Proxy.NO_PROXY`），不受 §8.13 代理与白名单影响（Q14）。
- **单位**：`repo.json` 清单 + 分片文件（`prefs.json`、`progress.json`、`collects.json`、`view-history.json`、`configs/<key>.ghscfg.json`）。
- **冲突按类别**：配置 `ask`；进度与偏好 `newer-wins`；收藏取并集；其余按 `updatedAt`。
- **粒度**：按清单的 `updatedAt` + `hash` 只同步有变化的分片；单分片失败不影响其他分片。
- **范围**：v1.1 只做手动触发；`autoSync` 字段先落库不自动执行；**不做删除传播**。
- **验收**：只有 `progress` 落后时只拉取该分片；冲突可逐条决策；网页端任何接口都读不到密码。

### 5.21 F27 文本阅读器

- **数据**：`getContents(url)` 取章节目录 → `getTextContent(url)` 取正文。正文的实际形态是**纯文本 + 内嵌 `<img src="…">`**（除此之外没有别的 HTML 结构）；也接受作者已拆好的 `{ blocks }`。引擎按 §8.15 转成结构块，UI 只渲染结构块。
- **默认定位**：本地**无进度 → 第一章**；有进度 → 记录的 `chapterIndex` 与 `chapterOffset`（语义见 [local-store](../03-api/local-store.md) §3.3）。
- **阅读页**：章节标题 + 正文、章节内进度、上一章/下一章、目录抽屉（当前章高亮）、字号/行距/背景（跟随 F21 阅读设置）、`textMode` 决定滚动或分页（配置给默认值，用户可覆盖）。
- **记录**：滚动停止 500ms 或翻页时写进度（节流）；切章、退到后台、退出各补写一次。
- **预加载**：下一章正文。
- **离线**：已读章节正文进缓存（§5.12），无网也能回看。
- **边界**：超长章节分段渲染；正文只渲染结构块，**不执行任何脚本**；来源样式（`style`、`<style>`）一律丢弃，排版由阅读器设置决定（[ADR-0012](../02-architecture/adr/ADR-0012-html-sanitize-to-blocks.md)）。
- **验收**：首次打开落在第一章；退出重进回到原章原位置；杀进程后仍能恢复；无进度数据时不报错；切章顺序不乱。

### 5.22 F28 网络代理（HTTP）

- **可选能力**：**未配置（或关闭）时一律直连**，应用行为与没有该功能时完全一致；代理是纯增量。
- **配置项**（应用端，`device` 作用域，不参与同步）：开关、地址、端口、用户名/密码（可选）、**白名单**、生效范围（应用请求 / 无头 WebView）。
- **白名单 = 不走代理**：默认包含 `localhost`、`127.0.0.1`、`::1` 与三个私网网段（可编辑可删）。条目支持四种写法：`example.com`（含全部子域）、`*.example.com`（仅子域）、`10.0.0.0/8`（CIDR）、`host:port`。**命中即直连**。
- **一份配置、两条通道、一处判定**：应用请求经 OkHttp `ProxySelector`，无头 WebView 指向内置转发代理，两者调用**同一个** `ProxyDecision`（见 §8.13）。
- **认证在 v1 支持**：WebView 自身不支持代理认证，由内置转发代理补 `Proxy-Authorization`，因此"认证代理 + 兜底通道"在 v1 即可用。
- **能力可见**：设置页显示两条通道状态（`可用` / `当前 WebView 不支持代理`）与当前判定（`直连` 或 `经 <host:port>`）。
- **连通性检测**：用当前配置的 `homeUrl` 发 `HEAD`，显示耗时与结果；不内置任何第三方探测地址。
- **副作用**：代理或白名单变更后自动清空 HTML 与图片缓存，避免旧结果污染判断。
- **范围**：仅 HTTP 代理（HTTPS 目标经 `CONNECT`）；不支持 SOCKS。
- **验收**：未配置全链路直连；配置后取页、图片、视频、WebView 兜底四条路径均走代理；白名单内域名**四条路径都不走代理**；认证信息不回显、不写日志。

### 5.23 F29 人机校验交互

- **触发**：取页返回的是挑战页而非内容（检测规则见 §8.14），不是"用户主动点击"。
- **表现**：**全屏顶层窗口**（独立 Activity，从底部升起，独立任务栈、不进最近任务）：
  - 顶部：来源域名 + 一句说明（"该来源要求确认，完成后会自动继续"）；
  - 主体：可见的 WebView，加载原页面，用户正常完成校验（点按、拖动、勾选）；
  - 底部：`取消` 与 `我已完成`（自动检测失败时的手动出口）。
- **完成**：自动检测页面不再命中挑战特征 → 窗口关闭 → **原请求自动继续**，用户无需回到原界面再点一次。
- **后台触发**：发一条通知（"需要完成人机校验 · 域名"），点击进入同一窗口；请求挂起等待。
- **合并**：同一 host 只允许一个窗口，其他请求排队共享结果，避免连弹多个。
- **取消与超时**：取消或超时（默认 5 分钟）后，发起方显示"已取消/已超时，可重试"，不静默卡死。
- **不缓存**：挑战页不写入缓存，并作废该 URL 既有缓存。
- **实现说明**：校验窗口与引擎共用同一套 WebView 代理规则与 Cookie 存储（`CookieManager`），因此完成校验后普通 HTTP 请求会自动携带凭证；站点直接返回 4xx 时 WebView 不触发 `onPageFinished`，窗口改为定时兜底检测并把 HTTP 错误显示在状态条。\n- **验收**：从列表页、详情页、阅读器、网页调试台四个入口分别触发，都能弹出；完成后无需二次操作即拿到内容；同一 host 并发 6 个请求只弹 1 个窗口；取消后原页面显示可重试的错误态而非白屏。

---

### 5.24 F30 剧集（多集/连载）

- **数据来源**：`getDetailInfo` 返回 `detailType: 'series'` 时，`analysis[]` 描述「分段」（如不同线路/清晰度分组），每项含 `url` / `title` / `total`。
- **交互**：**每条线路是一个 Tab**（横向可滚动），切 Tab 直接看该线路的剧集网格，不再有「分段列表 → 集列表」的两级跳转；剧集按线路懒加载并缓存。剧集详情页与播放器抽屉共用同一组件（`LineEpisodeTabs`），当前播放的「线路 + 集」高亮。
- **数据流**：线路由 `analysis` 直接给出；集由 `getAnalysisDetail(analysis, cheerio)` 展开；单集地址由 `getAnalysisVideoDetail(episode, cheerio)` 解析。
- **返回值容错**：这两个方法既可能返回单个对象，也可能返回数组（`[res]`），引擎都接受（数组取第一条）。
- **取脚本内容**：配置常从 `<script type="application/ld+json">` 里读播放地址；脚本内容是 DataNode，`text()` 必须能取到（实现上 `text()` 为空时回落到 `data()`）。
- **界面**：分段用列表（标题 + 集数）；集用 5 列数字网格，顶部「分段」按钮可返回上一层；解析中显示进度，解析失败显示原因与「分段」返回。
- **进度**：记录「线路地址 + 第几集 + 播放位置」；再次打开该剧集时**直接进入上次那一集**，并提示「上次看到 mm:ss」，用户点「快进」才跳转、「从头」则归零。提示未处理期间不回写进度。
- **验收**：真实剧集配置（含 `getAnalysisDetail` / `getAnalysisVideoDetail`）可完整走完「分段 → 集 → 播放」；返回数组或对象均能解析；单集解析失败有可见原因。
## 6. 信息架构与导航

```
BottomNav（Tab 由配置 setTags 决定，见 ui-implementation §4.2）
├── 首页 Home
│   ├── 来源切换（顶栏下拉）
│   ├── 搜索入口 → SearchScreen
│   ├── 内容流（Page）
│   ├── 过滤面板（urlReplace）
│   └── → DetailScreen
├── 收藏 Collect
│   └── → DetailScreen
├── 历史 History
│   └── → DetailScreen / 继续阅读
└── 设置 Settings
    ├── 来源管理 ConfigListScreen → ConfigDetailScreen
    ├── 日志 LogScreen
    ├── 缓存 CacheScreen
    └── 关于 AboutScreen

DetailScreen
├── VideoPlayerScreen      (mp4/m3u8)
├── GalleryReaderScreen    (image 多图 / comic)
├── SingleImageScreen      (image 单图)
├── SeriesSheet            (series)
└── EmbeddedWebScreen      (win)
```

导航用 `androidx.navigation:navigation-compose` + 类型安全路由；四个 Tab 各自保留返回栈与滚动位置。渲染器通过注册表分发，**不在导航层写 `when` 分支**。

---

## 7. 数据源双模式

### 7.1 抽象

```kotlin
interface GhsRepository {                       // UI 只依赖这一层
    suspend fun listConfigUnits(): List<ConfigUnit>
    suspend fun getConfigUnit(key: String): ConfigUnit
    suspend fun setCurrentConfig(key: String)
    suspend fun getPage(configKey: String): Page
    suspend fun loadPage(url: String): Page
    suspend fun getDetailPage(item: Item): DetailInfo
    suspend fun getContent(url: String): List<CContent>
    suspend fun getComicImages(url: String): List<CComic>
    suspend fun getTextContent(url: String): TextContent
    suspend fun getReadingProgress(itemUrl: String): ReadingProgress?
    suspend fun updateReadingProgress(progress: ReadingProgress)
    suspend fun getAnalysisDetail(item: Analysis): List<AnalysisDetail>
    suspend fun getAnalysisVideoDetail(item: AnalysisDetail): List<AnalysisVideoDetail>
    suspend fun isCollect(item: Item): Boolean
    suspend fun saveCollect(item: Item)
    suspend fun cancelCollect(item: Item)
    suspend fun listCollect(configKey: String): List<Item>
    suspend fun listHistory(): List<Item>
    suspend fun search(keyword: String, item: Item?): String
    suspend fun searchRecommend(keyword: String): List<String>
    suspend fun clearCache(suffix: String?)
    fun imageUrl(url: String): String              // 无需解图的图片：直接交给 Coil
    fun imageData(url: String, info: Map<String, Any>?): Any  // 需解图：EngineImage(url, info) 作为 Coil 的 data
    fun videoUrl(url: String): String              // 交给 ExoPlayer 的 DataSource
    val events: SharedFlow<EngineEvent>
}
```

| 实现 | 说明 |
|---|---|
| `LocalRepository` | 进程内引擎 + 本地仓库，**唯一实现**。远程实现已删除（[ADR-0011](../02-architecture/adr/ADR-0011-drop-remote-mode.md)） |

### 7.2 为什么只有一种模式

远程服务模式已移除（[ADR-0011](../02-architecture/adr/ADR-0011-drop-remote-mode.md)）：需求不存在，而双模式会让每条链路维护两套实现与两倍测试矩阵。跨设备需求由 WebDAV 同步（F20 / F26）覆盖，不追求实时一致。

保留 `GhsRepository` 抽象的理由只剩一条：**让引擎与 UI 解耦**——引擎将来若拆成独立进程，接口不变、UI 不改。

### 7.3 内置服务承载的两个出口

同一个内置 HTTP 服务（§8.12）对外提供两个数据出口：

- `/engine/**`：把 `LocalRepository` 按 [rest-contract](../03-api/rest-contract.md) 的路径暴露成 REST，默认关闭，用于 `curl`、自动化回归与外部工具；
- `/api/debug/**`：一次性、无副作用的方法级调用，供 Web 调试台使用（不写历史、不改当前配置）。

---

## 8. 技术架构

### 8.1 模块划分（Gradle 多模块）

```
:app                       Compose 壳、导航、DI、权限、生命周期
:core:model                data class / 枚举 / Result / 错误类型
:core:database             Room：配置 / 设置镜像 / 进度 / 收藏 / 历史 / 搜索 / 日志 + 图片缓存索引（见 local-store §3）
:core:network              OkHttp、CookieJar、UA/Referer 策略、代理、重试
:core:config               配置加载、校验、规范化、注册（config-spec 的实现）
:core:engine               内容引擎：脚本运行时、沙箱、选择器桥、抓取管线
:core:webserver            内置 HTTP 服务：静态资源、管理 API、调试 API、WebDAV 客户端
:core:imageloader          Coil ImageLoader + 自定义 Fetcher
:core:media                Media3 封装、回环代理、MediaSession
:core:designsystem         主题、token、通用组件、渲染器注册表
:core:datastore            DataStore：设置项
:feature:home / :feature:detail / :feature:reader / :feature:player
:feature:collect / :feature:history / :feature:search / :feature:settings
:feature:config / :feature:logs
:web:console             浏览器端管理界面（Vue 3 + TS，构建产物打包到 assets）
```

### 8.2 引擎管线

```
请求 (configKey / url)
  │
  ├─ 1. 取 HTML  CacheManager(HTML) 命中 → 返回
  │        └─ HttpFetcher（OkHttp + Cookie + UA + Referer + 代理 + 配置行为参数）
  │              └─ 有效性判定 isCanContinue(html)
  │                    └─ 失败 → WebViewFetcher（无头渲染后取 outerHTML）
  ├─ 2. 解析   Jsoup.parse(html) → DomBridge 提供 GHS Select（`$`）
  ├─ 3. 执行   ScriptRuntime.eval(config.script, helpers) → 规则实例
  │        └─ getItems($) / getPagination($) / getTags($) / getDetailInfo(item, engine)
  ├─ 4. 归一   相对 URL 补全、类型推断、字段兜底（规则见 config-spec §5）
  └─ 5. 落地   写 CacheManager(HTML)，必要时写 Room
```

### 8.3 脚本运行时选型（最大技术风险）

| 方案 | 原理 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|
| **A. 无头 WebView** | 加载页面 → 注入选择器层与脚本 → `evaluateJavascript` 取回 JSON | 与脚本原生环境一致；DOM 全可用；能过 JS 渲染页 | 内存 60–120MB/实例；慢；必须串行；只能回字符串 | **兜底通道** |
| **B. QuickJS** | 独立 JS 引擎，选择器由 Jsoup 桥接 | 快、内存小、可严格沙箱、可 JVM 单测 | 需 native so；异步桥需自研；无真实 DOM | **主通道** |
| **C. Rhino / GraalJS** | 纯 JVM 引擎 | 无 native 依赖 | Rhino 仅 ES5；GraalJS 在 ART 上不可行 | 淘汰 |
| **D. 改成 Kotlin DSL** | 用 Kotlin 写解析 | 性能最好 | 违背 P2 | 淘汰 |

**决策：B 为主 + A 为兜底**，形成"快路径 + 兜底路径"两级结构；M0 必须先用黄金样本验证（§9.2）。

### 8.4 GHS Select（`$` 兼容层）

| 类别 | 支持范围 |
|---|---|
| 选择器 | `#id` `.cls` `a[href]` **`span[itemprop="name"]`**（属性等值） `div > p` `.a, .b` `li:eq(0)` `li:first` `tr:nth-child(2)` |
| 遍历 | `.each(fn)` `.find()` `.parent()` `.children()` `.next()` `.prev()` `.eq(i)` `.first()` `.last()` `.closest()` `.toArray()` |
| 取值 | `.attr(n)` `.text()` `.html()` `.val()` `.prop(n)` `.data(n)` `.length` `[i]` |
| 判定 | **`.hasClass(name)`**（既有配置靠它判断"当前页/禁用项"） |
| 序列化 | **`.toString()`** 返回该元素的 `outerHTML`（既有配置用它做字符串再加工） |
| 解析 | `parse(html)` / `cheerio.load(html)` 把 HTML 字符串解析为选择器根 |

实现策略：

- 复用 **Jsoup** 提供选择器与遍历；
- JS 与 Jsoup 之间用**元素句柄**（`Int` id ↔ Jsoup Element）传递；
- Jsoup 不支持的伪类（`:eq` `:contains`）做预处理器转换；
- **取值一律返回 `string`，不返回 `undefined`**：`.attr()` / `.text()` 取不到时返回 `''`，保证配置里的 `a || b` 兜底写法成立；
- **未实现的方法不抛错**：记 `unsupported_api` 事件并把该来源标记为"走 WebView 通道"。

### 8.5 取页策略与反爬

| 手段 | 实现 |
|---|---|
| Cookie 维持 | `PersistentCookieJar`（Room 持久化），WebView 与 OkHttp 共享 |
| UA / Referer | 按 `homeUrl` 派生 Referer；配置可覆盖 UA 与附加头 |
| 出口代理 | 见 §8.13：同一份代理配置同时作用于 OkHttp 与无头 WebView |
| 人机校验 | 判定为无效页 → 兜底通道，必要时弹可见 WebView 让用户完成一次 |
| 并发控制 | 同 host 串行 + 指数退避；上限取配置 `behavior.concurrency` |
| 缓存 | HTML 按 URL hash、图片按 URL hash 落盘 |
| 人机校验 | 判定为挑战页 → 顶层校验窗口（§8.14），通过后带 Cookie 重试，不缓存挑战页 |

### 8.6 图片管线

- 识别 `data:image/*` 内联图与需要脚本解图的图片（`adapterImageCode`）；
- **请求数据是 `EngineImage(url, info?)`，不是裸字符串**：`getComicImages` 返回的 `info` 必须一路传到 Fetcher，否则解图参数丢失、整组图变成乱图（§7.1）；
- 解图执行模型（详见 [config-spec](../03-api/config-spec.md) §4.2.1）：把 `info` 作为 `extra`，注入 `Md5` 与离屏 `canvas`，在页面上下文执行 `adapterImageCode`（5 参），以 **50ms 轮询 `imgSrc.value`** 等待异步回写，超时 10s 后回落原图；
- `EngineImageFetcher`（Coil `Fetcher`）：直连拉字节 → 落盘 → 交给 Coil；
- 图集逐张解图很慢：解图并发受 `behavior.concurrency` 限制，并优先解可视区与预加载区；
- 缓存目录 `cacheDir/img/<sha1>`，LRU 可配（解图结果按 `url + info` 建键）；
- 大图按控件尺寸 `inSampleSize` 采样；超长图分段解码；
- **base64 兼容**：内联图（`data:image/*`）与脚本解图结果都走同一 `Fetcher` 归一，UI 无差异。

### 8.7 视频与代理

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. 本地回环代理** | 播放期在 `127.0.0.1:随机端口` 起 mini server：转发请求头（Referer/Range/Cookie）、重写 m3u8（分片、子播放列表、`EXT-X-KEY` 的 URI） | **采用**：唯一能统一处理加密/多级 m3u8 的方案 |
| B. 自定义 `DataSource` | 注入请求头，但无法改写播放列表内部地址 | 仅作快速通道 |

- 代理**仅播放期间启动**，退出播放器即关闭；
- **验收**：含 `EXT-X-KEY` 的加密流可播；Seek 正常；退出后端口释放。

### 8.8 数据库设计（Room）

```sql
config_unit(id, key UNIQUE, format TEXT /* manifest|inline */, config_json TEXT,
            script TEXT, sort INT, enabled INT, config_version TEXT, createTime)
app_setting(scope TEXT /* prefs|device */, key TEXT, value TEXT, updatedAt,
            PRIMARY KEY(scope, key))
reading_progress(id, itemUrl UNIQUE, kind TEXT /* text|image|video */,
                 chapterUrl TEXT, chapterIndex INT, chapterCount INT, chapterOffset INT,
                 positionMs INT, durationMs INT, percent REAL, updatedAt INT)
collect(id, configKey TEXT, url TEXT, value TEXT /*item JSON*/, count INT, createTime)
viewed_history(id, configKey TEXT, url TEXT, value TEXT, createTime)
search_history(id, configKey TEXT, value TEXT, createTime)
log(id, info TEXT, type TEXT, createTime)
image_cache(id, urlHash UNIQUE, path, sizeBytes, lastAccess, hits)
```

要点：

- `config_json` 存**规范化后的 `meta`/`render`/`behavior`**，`script` 单独存源码 → 元信息可被 UI 直接读取，不必执行脚本；
- `value` 存 Item 的 JSON 字符串，读取时反序列化；
- `config_unit.key` 唯一索引；`format` 记录来源形态以便导出复原；
- `reading_progress` 以 `itemUrl` 唯一，**统一表达文本章节、图集页码与视频位置**，续读逻辑只写一套；
- `app_setting` 是 DataStore 的可导出镜像，写入时双写；导出与同步只读镜像，不依赖 DataStore 内部格式；
- 数据分类与同步归属见 [local-store](../03-api/local-store.md) §2；`log` 只保留最近 2000 条。

### 8.9 契约与数据互导

内核 API 契约（[rest-contract](../03-api/rest-contract.md)）用于两处：进程内方法调用（默认路径）与 `/engine` 本地调试出口（§7.3）。

**本地仓库的导出、导入与同步**（F20 / F26）见 [local-store.md](../03-api/local-store.md)：

- 单文件 bundle（`ghs-repo/1`）用于备份与换机；
- 分层目录（`repo.json` + 分片）用于 WebDAV 同步与人工维护；
- 导入有 `dry-run` / `merge` / `replace` 三种模式，冲突按类别判定，单条失败不中断整批。

### 8.10 并发、线程与内存

- 引擎**单例**，串行执行；`Mutex` + `StateFlow` 暴露 `EngineState`（Idle/Loading/Parsing/Error）。
- 网络并发默认 3，且不超过配置的 `behavior.concurrency`；图片下载与 HTML 抓取分池。
- 兜底 WebView 池最多 1 个，超时 30s 强杀，用完 `destroy()` 并置空。
- 大图/长图先读尺寸再采样解码；列表封面按控件尺寸解码。
- 冷启动恢复 `currentConfigKey`，失败回退到第一个启用配置。

### 8.11 引擎层单测策略

引擎（除兜底 WebView）不依赖 Android Framework，可在 JVM 单测：

- **黄金样本**：开发者本地采集真实页面 HTML，存放 `test/fixtures/html/`（不入库，见 `AGENTS.md` §7）；仓库内只保留最小合成样本；
- **配置样本**：`test/fixtures/configs/` 覆盖合法与各类非法配置，断言错误码与字段路径；
- 断言对象：字段名、URL 补全、`type` 推断、`pagination` 解析、校验错误码；
- 断言方式：与人工标注的期望 JSON 逐字段比对。

### 8.12 内置 Web 管理服务与调试台

```
Android 应用进程
├── ContentEngine（单例，串行）
├── Ktor Server :18600
│   ├── /console/**   静态 SPA（Vite 构建产物，打包进 assets）
│   ├── /api/**       管理 + 调试（配置 CRUD、校验、导入导出、run/fetch/events、WebDAV）
│   └── /engine/**    引擎代理（默认关闭）
└── WebDavClient      PROPFIND / GET / PUT
```

要点：

- **同版本发布**：控制台静态产物与 `/api` 一起打包进 APK，不存在外链与版本错配；
- **鉴权与限流**：默认仅本机绑定 + 一次性 token；绑定范围、限流与目录遍历防护见 [web-admin-api](../03-api/web-admin-api.md) §2、§9；
- **调试用独立上下文**：调试执行复用同一引擎与沙箱，但使用独立会话（不写历史、不改 `EngineSession`），避免"点一下调试把当前配置改了"；
- **生命周期**：开关关闭或应用退出即释放端口；无请求 30 分钟可自动停止（可配置）；
- **只回 JSON**：调试不取图片二进制、不代理视频、不渲染网页（§5.19），保证"看数据"这件事足够快。

### 8.13 代理（HTTP）

```
                    ┌────────────────────────────────────────┐
出站请求 ──────────► │ ProxyDecision.shouldProxy(host, port)  │ ← 白名单唯一判定点
                    └───────────────┬────────────────────────┘
                        命中白名单 │            │ 未命中且代理已配置
                                  ▼            ▼
                              直连       ┌──────────────────────────┐
                                        │  A. OkHttp                │
                                        │  取页/图片/视频/WebDAV      │
                                        │  ProxySelector → 上游代理  │
                                        └────────────┬─────────────┘
                                                     │
                                        ┌────────────▼─────────────┐
                                        │  B. 无头 WebView（兜底）    │
                                        │  ProxyController          │
                                        │  → 127.0.0.1 转发代理      │
                                        │  转发代理再判一次白名单     │
                                        └────────────┬─────────────┘
                                                     │ CONNECT / 绝对 URI
                                                     ▼
                                             上游 HTTP 代理
                                          （转发代理补 Proxy-Authorization）
```

| 项 | 实现 |
|---|---|
| 配置来源 | 应用端设置（`device` 作用域），单一来源，两条通道共用 |
| **白名单判定** | `ProxyDecision.shouldProxy(host, port)` 纯函数：域名/子域/通配/CIDR/端口 + 内置回环与私网条目；**两通道共用，单点维护** |
| 未配置时 | 不启动转发代理、不下发 WebView 覆盖、`ProxySelector` 返回 `NO_PROXY` → 全链路直连 |
| A 通道 | 自定义 `ProxySelector` → 命中放行则 `Proxy(HTTP, host:port)`，否则 `NO_PROXY`；`proxyAuthenticator` 处理 407（Basic） |
| B 通道 | `ProxyController.setProxyOverride(ProxyConfig.addProxyRule("127.0.0.1:<自身端口>"))`，进程级；需 `WebViewFeature.isFeatureSupported(PROXY_OVERRIDE)`；WebView 不配 `addBypassRule`，白名单由转发代理执行 |
| 内置转发代理 | `127.0.0.1:随机端口`，支持绝对 URI 转发与 `CONNECT` 隧道、分块传输、双向管道；**仅当代理已配置且 WebView 通道启用时启动** |
| 认证 | 由转发代理向上游补 `Proxy-Authorization`（WebView 自身无此能力），因此认证代理在 v1 即可用 |
| 视频与图片 | 均为 HTTP，自动受益；视频回环代理转发时沿用同一 `OkHttpClient` |
| WebDAV | **不走代理**：独立 `OkHttpClient` + `Proxy.NO_PROXY`，不参与白名单判定（Q14） |
| 连通性 | 用当前配置的 `homeUrl` 做 `HEAD` 探测，记录耗时与状态，不引入第三方探测地址 |
| 变更生效 | 更新 `OkHttpClient` 单例 + 重启/重新下发转发代理与 WebView 覆盖；清空 HTML/图片缓存 |

### 8.14 人机校验通道

取页可能拿到的不是内容，而是挑战页。处理流程：

```
getHtml(url)
  ├─ 正常内容 → 返回
  └─ ChallengeDetector 命中
        ├─ 前台 → 启动 ChallengeActivity（顶层、独立任务栈）
        ├─ 后台 → 发通知（Android 10+ 禁止后台直接启动 Activity）
        └─ 同 host 已有窗口 → 复用，本请求排队（单飞）
              ↓ 用户完成校验
        ├─ Cookie 双向同步（WebView CookieManager ⇄ OkHttp PersistentCookieJar）
        └─ 优先用 OkHttp 带同 Cookie 重试原 URL（与主通道一致）
           仍失败才回落到取 WebView 的 outerHTML
              ↓
        返回实际 HTML，解析继续
```

| 项 | 约定 |
|---|---|
| 检测 | 状态码（403/429/503）+ 响应头（`cf-mitigated`、`server: cloudflare`）+ HTML 特征（`Just a moment...`、`challenge-platform`、`__cf_chl`、`g-recaptcha`、`h-captcha`）；配置可用 `behavior.challengeMarkers` 追加自定义特征 |
| 顶层性 | 独立 `ChallengeActivity`（`NEW_TASK` + 独立 task affinity + `excludeFromRecents`），保证从任意界面乃至无 UI 的后台请求都能弹 |
| 单飞合并 | 同一 host 最多 1 个窗口；其余请求排队共享结果，排队时长计入超时 |
| 完成判定 | 页面不再命中挑战特征即视为通过；另有「我已完成」手动出口 |
| Cookie 同步 | 挑战前把 OkHttp Cookie 推给 `CookieManager`；完成后从 `CookieManager` 读回并入 `PersistentCookieJar` 并 `flush()` |
| 取 HTML | **优先 OkHttp 重试**（原始 HTML，与主通道一致）；WebView `outerHTML` 仅作兜底（渲染后 DOM 可能被 JS 改写） |
| 代理 | 窗口内 WebView 沿用进程级代理覆盖（§8.13），与主通道一致 |
| 超时 | 默认 5 分钟，可配 |
| 缓存 | 挑战页**不写入**缓存；通过后作废该 URL 的既有缓存条目 |
| 外部导航 | 窗口内只允许同 host 与子域；跨站与外部 scheme 拦截或二次确认 |
| Web 调试台 | 事件流推送 `challenge` / `challengeResolved`，页面显示"等待设备完成人机校验"，可配置是否等待 |

错误码（由引擎抛出，UI 与调试台按此展示）：

| code | 场景 | 调用方表现 |
|---|---|---|
| `E-CHALLENGE-001` | 需要校验，等待中 | 保持 loading，显示"等待完成校验" |
| `E-CHALLENGE-002` | 用户取消 | "已取消校验"，提供重试 |
| `E-CHALLENGE-003` | 校验超时 | "校验超时"，提供重试 |
| `E-CHALLENGE-004` | 后台无法弹窗 | 提示"请打开应用完成校验"，并已发通知 |

### 8.15 正文 HTML 安全过滤

文本正文允许配置直接返回 HTML（Q13），但**不直接渲染**，一律经清洗与结构归一（[ADR-0012](../02-architecture/adr/ADR-0012-html-sanitize-to-blocks.md)）：

> **实际形态（决定实现重点）**：正文是**纯文本**，唯一的标记是内嵌的 `<img src="…">`，除此之外没有别的 HTML 结构。
> 因此实现只需做三件事：**HTML 实体解码 → 换行归一与分段 → `<img>` 提取与 URL 校验**。
> 下表的白名单作为**防御性上限**保留（正常内容不会触发），不需要为它做额外设计。

```
getTextContent → { html } 或 { blocks }
                   │ html
                   ▼
            HTMLSanitizer（白名单）
                   ▼
            HtmlToBlocks（结构归一）
                   ▼
            TextBlock[] → TextReaderScreen
```

| 项 | 规则 |
|---|---|
| **实际内容** | 纯文本 + 内嵌 `<img src="…">`；重点只有图片提取与实体解码 |
| 允许标签 | `p` `br` `h1`–`h4` `blockquote` `ul` `ol` `li` `strong` `em` `u` `s` `code` `pre` `a` `img` `hr` `table` `thead` `tbody` `tr` `th` `td` `figure` `figcaption` |
| 允许属性 | `a[href]`、`img[src]`、`img[alt]`、`*[class]`（仅排版提示） |
| 整棵删除 | `script` `style` `iframe` `object` `embed` `form` `input` `button` `svg` `canvas`，以及所有 `on*` 事件属性 |
| URL 白名单 | `http`、`https`、`data:image/*`；`javascript:` `vbscript:` `file:` 一律丢弃 |
| 样式 | 丢弃 `style` 属性与 `<style>`；排版完全由阅读器设置决定 |
| 结构归一 | 清洗后转 `TextBlock[]`（`p`/`h2`/`h3`/`quote`/`list`/`image`/`code`/`table`）；未识别标签降级为 `p` 并保留文本 |
| 体积 | 单章 HTML ≤ 2MB；超出截断并记日志 |
| 空结果 | 清洗后无有效内容 → 按空章节处理，提示"该章无正文"，不报致命错误 |

**UI 侧约束**：`TextReaderScreen` 只接受 `TextBlock[]`，不存在"直接渲染 HTML"的代码路径。

---

## 9. 风险与里程碑

### 9.1 风险表

| 风险 | 影响 | 概率 | 应对 |
|---|---|---|---|
| QuickJS 桥接工作量被低估 | 高 | 中 | **M0 先做 spike**；不达标立即切无头 WebView 全量方案（ADR-0003 退路） |
| 无头 WebView 内存/泄漏 | 高 | 高 | 单例 + 独立进程 + 超时销毁 + LeakCanary 门禁 |
| 配置表达力不足 | 中 | 中 | M0/M1 用真实来源验证；不足时扩展 API 并递增 `requiresApi` |
| 长图 OOM | 中 | 中 | 分块解码、`inSampleSize`、内存上限监控 |
| 脚本语法差异（ES5 vs ES2020） | 中 | 中 | QuickJS 支持 ES2020；对包裹方式做兼容测试 |
| 视频代理与 Range/Seek 兼容 | 中 | 中 | 转发头白名单 + Seek 专项测试 |
| 分发风险 | 中 | 中 | 见第 10 章 |

### 9.2 里程碑

| 阶段 | 内容 | 交付物 | 出口标准 |
|---|---|---|---|
| **M0 可行性验证** | QuickJS 跑通真实配置；Jsoup→GHS Select 覆盖 §8.4；黄金样本比对；**图集链路**（`getContents` → `getComicImages` → `adapterImageCode` 解图） | 技术验证报告 | 与人工标注期望逐字段一致；**至少 2 份既有配置零改动跑通列表 + 详情，其中 1 份必须含图集与解图**；失败则改方案 A 并更新 ADR |
| **M1 底座** | `:core:config` 配置加载与校验 + `:core:engine` + `:core:database`，无 UI | 可跑单测的底座 | 配置校验错误码全覆盖；首页/详情/图组/收藏/历史 6 条链路单测通过 |
| **M2 浏览主链路** | 首页、详情、收藏、历史、设置骨架、网络代理、人机校验 | 可安装 APK | 真机能导入配置并浏览；开启代理后四条路径正常；命中校验能弹窗并继续 |
| **M3 阅读器与播放器** | 图集阅读器、文本阅读器、视频播放器、单图查看器 | — | 手势/续读/进度记忆达标（三种媒介共用一套进度） |
| **M4 检索与增强** | 搜索、标签、系列分集、评论 | — | §5 功能清单全部可达 |
| **M5 配置与 Web 管理** | Web 管理服务、编辑器、调试台、批量导入导出、日志、缓存、备份恢复、外观设置 | — | 能在浏览器里新建配置并试跑成功；日志/缓存/备份只在 Web 端可用 |
| **M6 同步与打磨** | WebDAV 同步、深色/无障碍/性能/崩溃治理 | 候选版本 | 冷启 <1.5s，滚动稳定 60fps |

M0 是**唯一允许回退架构的阶段**，必须先做并产出书面结论。

---

## 10. 内容边界与分发

- 本应用是**内容容器**：不提供、不托管、不分发任何内容，也不内置任何内容源配置；全部内容来源由用户自行导入的配置决定。
- 首次启动展示**使用声明**（一次性确认）：应用仅提供加载与渲染能力，内容来源与合规性由配置使用者自行负责。
- **不上架应用商店**，仅侧载分发；版本检查与下载见 F22。
- 不提供绕过访问控制、破解加密内容的能力；引擎遵循目标来源的公开访问方式。
- 日志与崩溃上报默认关闭；数据仅保存在本机。
- **Web 管理服务默认仅本机可访问**；开启局域网访问需用户显式确认，并依赖一次性访问令牌。服务不提供文件系统能力，仅能访问应用自己的配置与缓存。

---

## 11. 待确认的开放问题

| # | 问题 | 影响 | 建议默认值 |
|---|---|---|---|
| Q1 | 是否接受"M0 先行"、且 M0 结论可能推翻部分架构？ | 排期 | 接受 |
| Q2 | v1 是否需要"从备份文件导入配置 + 收藏历史"？ | F01/F20 工作量 | v1 做配置导入，数据恢复放 v1.1（F20） |
| Q3 | ~~应用内是否需要编辑配置？~~ **已定** | 编辑只在 Web 管理界面（F24），应用内只读 | 已决策，见 ADR-0005 |
| Q4 | 最低支持 Android 版本？ | 决定 WebView 能力与部分 API | **Android 8.0 (API 26)** |
| Q5 | 是否需要**离线下载**（F17）？ | 若需要，M3 要加下载队列/断点续传/加密分片处理 | v1.1 |
| Q6 | 是否需要多语言（中/英）？ | 全部字符串资源化 | v1 仅中文，资源化预留 |
| Q7 | 平板/折叠屏是否要有专属布局？ | 自适应工作量 | v1 保证可读（断点已在 UI 规范定义），v1.1 优化 |
| Q8 | ~~是否保留"远程服务"模式（F18）？~~ **已定：不保留** | 删除 `RemoteRepository`、WS 客户端、多服务端（F18） | 见 ADR-0011 |
| Q9 | 是否需要支持"配置从 URL 安装"（一键订阅）？ | 需要签名/信任机制，安全设计成本高 | v1 不做，仅文件/剪贴板 |
| Q10 | WebDAV 默认冲突策略用 `ask` 还是 `newer-wins`？ | 影响同步交互复杂度 | `ask`（可解释、不误覆盖） |
| Q11 | 局域网访问默认开启还是默认关闭？ | 便利性 vs 安全性 | 默认关闭，扫码时引导开启 |
| Q12 | ~~控制台是否需要支持平板/手机浏览器完整功能？~~ **已定**：非阅读相关、复杂操作一律放 Web 端；App 端只保留阅读与必要配置（§2.6） | 界面职责划分 | 控制台桌面优先；窄屏可完成查看与试跑 |
| Q13 | ~~文本正文是否允许配置直接返回 HTML？~~ **已定：接受任意 HTML**，但必须清洗并转结构块 | 需维护 sanitizer 与白名单 | 见 ADR-0012、§8.15 |
| Q14 | 搜索历史是否参与同步？ | 隐私 vs 跨设备一致 | 默认不同步，应用端可开启 |
| Q15 | ~~代理是否也作用于 WebDAV 与本地回环服务？~~ **已定：WebDAV 不走代理** | 家庭服务器/私网场景 | 见 §8.13、[local-store](../03-api/local-store.md) §7 |

---

## 12. 附录

### 12.1 配置与脚本规范

**见 [config-spec.md](../03-api/config-spec.md)**（SSOT）：配置形态、字段、脚本方法、加载协议、错误码、GHS Script API v1、版本兼容、完整示例。

### 12.2 数据层契约

**见 [rest-contract.md](../03-api/rest-contract.md)**（SSOT）：接口路径、参数编码、领域对象字段、响应包装、图片与视频细则、双模式差异。

三条映射原则：

1. `GhsRepository` 的方法与契约路径一一对应（进程内直接调用，`/engine` 出口按同一路径投影）；
2. `/engine` 出口需按 `item[jumpUrl]=...&item[tags][0][title]=...` 的 qs 嵌套语法编码；
3. 图片与视频方法只生成 URL，交给 Coil / ExoPlayer。

### 12.3 UI 实现

**见 [ui-implementation.md](../02-architecture/ui-implementation.md)**（SSOT）：设计系统、断点、应用骨架、组件契约、页面要点、四态与分页状态机、手势与动效、无障碍、性能预算。

### 12.4 Web 管理服务与界面

- 服务 API（配置 CRUD、校验、导入导出、调试、WebDAV、错误模型、安全边界）→ [web-admin-api.md](../03-api/web-admin-api.md)
- 浏览器端界面（布局、编辑器、调试台、同步面板、快捷键、验收清单）→ [web-console.md](../02-architecture/web-console.md)

---

## 13. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-10-19 | 初稿：确定原生 Kotlin + Compose、内置引擎双模式、v1 覆盖完整能力集 |
| v0.2 | 2026-10-19 | 迁移至标准目录；加文档头与 SSOT 声明；§1 决策表改为摘要 + ADR 链接；接口表迁出 |
| v1.0 | 2026-10-19 | 内化为独立产品文档：删除全部外部项目引用；重写 §2；新增 F19/F20；定义自有脚本 API |
| v2.0 | 2026-10-19 | **重定位为「配置驱动的内容容器」**：§2 改为配置驱动定位与底座分层；配置规范独立成文（config-spec）；UI 实现独立成文（ui-implementation）；新增 D6 与 ADR-0004；数据模型改为 `config_unit` 双段式；新增 F21 外观与显示、F22 应用更新检查；§10 改为通用内容边界，移除特定内容类型表述 |
| v2.1 | 2026-10-19 | **新增配置编写能力**：内置 Web 管理服务（F23）、浏览器配置编辑器（F24）、JSON 实时调试台（F25）、WebDAV 同步（F26）；新增 D7/D8 与 ADR-0005/ADR-0006；新增原则 P5「编辑与运行分离」；**删除 F19 应用内配置编辑器**（编辑统一收敛到 Web）；§7.3 改为内置服务的两个出口；新增 §8.12 与 §12.4；M5/M6 里程碑与 Q3 更新 |
| v2.2 | 2026-10-19 | 新增 §2.6「配置面归属」：应用级设置（Web 服务端口/绑定/令牌、WebDAV 地址与凭证、主题缓存等开关）**只在应用端**，Web 端只承担配置内容的重编辑；F23/F26 同步该边界 |
| v2.3 | 2026-10-19 | **新增文本阅读与统一本地仓库**：F27 文本阅读器（`detailType = text`，默认第一章，按本地进度续读）；F20/F26 范围扩展为整个本地仓库（配置/偏好/进度/收藏/历史）；新增 P6「本地仓库权威」、D9 与 ADR-0007；§4 增加 LocalStore/ReadingProgress/TextContent 名词；§8.8 表结构改为 `reading_progress` + `app_setting`；§8.9 备份格式改指 local-store |
| v2.4 | 2026-10-19 | **新增 HTTP 代理**：F28 一份代理配置同时作用于应用请求与无头 WebView；新增 D10 与 ADR-0008；§8.5 增「出口代理」；新增 §8.13 代理通道图与能力矩阵；§5.22 明确认证代理下 WebView 的 v1 限制；M2 与设置页网络分组同步 |
| v2.5 | 2026-10-19 | **代理进 v1 并加白名单**：新增 D11 与 ADR-0009；白名单成为唯一代理判定点（命中即直连），内置转发代理提前到 v1 以支持「认证代理 + WebView」；明确「未配置即直连」；§8.13 重画通道与判定；§5.22 更新配置项与验收 |
| v2.6 | 2026-10-19 | **新增人机校验通道**：F29 与 D12/ADR-0010；顶层 `ChallengeActivity` + 单飞合并 + Cookie 双向同步 + 优先 OkHttp 重试；新增 §8.14 流程与 `E-CHALLENGE-*` 错误码；§8.5 增「人机校验」；M2 出口补充 |
| v2.7 | 2026-10-19 | **收敛四处决策**（Q8/Q12/Q13/Q14）：① 移除远程服务模式，删除 F18 与 `RemoteRepository`（D13/ADR-0011）；② §2.6 按「阅读是 App 唯一主业」重划归属，日志/缓存/备份/批量管理改由 Web 承担，App 设置页大幅收敛；③ 正文接受任意 HTML 并清洗转结构块（D14/ADR-0012，新增 §8.15）；④ WebDAV 固定直连、不走代理 |
| v2.8 | 2026-10-19 | **按 3 份既有配置查漏补缺**：GHS Select 补 `hasClass` / `toString` / 属性等值选择器与"取值不返回 undefined"；§12.2 去掉"两种模式"表述；M0 出口标准增加"至少 2 份既有配置零改动跑通" |
| v2.9 | 2026-10-19 | **按 2 份图集类配置第二轮补齐**：§7.1 图片接口改为 `EngineImage(url, info)`（`imageData`）；§8.6 重写解图模型（5 参 `adapterImageCode`、Md5/canvas 注入、50ms 轮询回写、并发受限）；M0 出口标准要求覆盖图集与解图链路 |
| v2.10 | 2026-10-19 | 明确文本正文实际形态（**纯文本 + 内嵌 `<img>`**），仅补事实说明，未新增字段或解析器 |
| v2.11 | 2026-10-19 | **F24 源码编辑器改为 Monaco + 类型驱动智能提示**（D15 / [ADR-0014](../02-architecture/adr/ADR-0014-monaco-editor-intellisense.md)）；§5.18 补充编辑器、提示来源、窄屏降级与验收 |
| v3.0 | 2026-10-20 | 实现期回流：M0/M1 完成，模块与测试基线成文（见 docs/04-engineering/build.md） |
| v3.1 | 2026-10-21 | **F02 列表改为封面卡片**：单/双列切换、星标收藏、触底自动加载下一页；收藏与历史复用同一卡片；验收标准同步 |
| v3.2 | 2026-10-21 | **标签页 / 分页条 / 视频直达播放**：F10 标签改为底栏标签云；F03 分页条与触底加载共存；F06 解析后直接播放、清晰度在播放器内切换、播放页整屏；F02 标题超长滚动 |
| v3.3 | 2026-10-21 | **站点/页码改为底部抽屉**：右上角页码按钮 + 选页抽屉；站点选择抽屉（搜索+两列卡片+当前打勾）；卡片去掉进度副标题，标题超长滚动 |
| v3.4 | 2026-10-21 | 列表网格间距收紧（4/6dp）、卡片标题行压缩，封面更大；站点抽屉图标改用配置 `favicon` |
| v3.5 | 2026-10-21 | F05/F12：图片详情解析后直接进阅读器并按进度续看；单图为 1/1 |
| v3.6 | 2026-10-22 | F05/F12 看图手势：双指缩放/双击缩放/单击隐藏顶栏；图片满屏显示 |
| v3.7 | 2026-10-22 | F05 图片清晰度：原图/展示图切换、选择持久化、原图失败回落 |
| v3.8 | 2026-10-22 | F05 相邻页预加载（±1、+2）；修复单指横滑被缩放手势吞掉，1x 下恢复翻页 |
| v3.9 | 2026-10-22 | F05 长按保存到相册（MediaStore，API26~28 退应用目录；走代理下载并带 Referer） |
| v4.0 | 2026-10-22 | F05 `comicImgMaxWidth` 生效（px/dp/vw/%/数字，默认 1080px），展示与预取都按上限解码 |
| v4.1 | 2026-10-22 | 新增 **F30 剧集（多集/连载）**：分段 → 集 → 播放三段式；下一个可用编号改为 F31 |
| v4.2 | 2026-10-22 | F30/F06：剧集播放打通——脚本 DataNode 读取、媒体带 Referer、403 直连重试 |
| v4.3 | 2026-10-22 | F06 控制条改用 media3-ui PlayerView、播放器内换线路/选集；F30 记录线路+集+位置并直接续看、续看提示 |
| v4.4 | 2026-10-22 | F06/F30：线路与选集合并为一个 Tab 抽屉，线路为主 Tab |
| v4.5 | 2026-10-22 | F30：线路作为 Tab（详情页与播放器抽屉统一），切 Tab 即看该线路剧集 |
| v4.6 | 2026-10-22 | F06：修复切换剧集画面冻住（PlayerView 未更新播放器）；新增全屏、锁屏、双击快进 10s |
| v4.7 | 2026-10-22 | F06：全屏移到右下角、锁屏移到左侧居中，顶栏只留导航与选集入口 |
| v4.8 | 2026-10-22 | F06：全屏改图标并把清晰度移到右下角，控制区顺序对齐主流播放器 |
| v4.9 | 2026-10-22 | F06：控制层自绘（顶栏+底栏+进度条+上一集/下一集），双击分区与连击累加，控制层自动隐藏 |
| v5.0 | 2026-10-22 | F06：锁图标区分两种形态、亮度/音量滑动、长按倍速、线路抽屉加高 |
| v5.1 | 2026-10-22 | F02/F14：卡片白底阴影+悬浮 tag、首页悬浮刷新、标签点击回首页、左侧菜单（设置/引擎自检）、日志云 |
| v5.2 | 2026-10-22 | F14：左侧抽屉直接展开设置正文（去掉二级菜单），标准抽屉交互 |
| v5.3 | 2026-10-22 | F23 列出全部局域网地址并可点击复制；F05/F06 标题单行滚动 |
| v5.4 | 2026-10-22 | F04：详情解析改为可取消弹窗并显示已用时间 |
| v5.5 | 2026-10-22 | F04：解析改为列表原地弹窗（不切中间页），弹窗加宽 |
| v5.6 | 2026-10-22 | 日志改抽屉入口（修遮罩残留）；历史一次性迁移；播放器接管返回键 |
| v5.7 | 2026-10-23 | F02：首页不显示标签名；刷新按钮加 loading 状态 |
| v5.8 | 2026-10-23 | F08：历史按站点 key 写入（修复混在一起）；新增清空本站历史 |
| v5.9 | 2026-10-23 | F03：筛选/排序面板（urlReplace + adapterLoadUrl） |
| v6.0 | 2026-10-23 | F25：实时调试台（/api/state、/api/logs、/api/debug/run） |
| v6.1 | 2026-10-23 | F29：人机校验窗口、Cookie 打通、403 错误态 |
