> **状态**：draft
> **版本**：v1.13
> **最后更新**：2026-10-26
> **取代/被取代**：取代同文件 v1.10

# 配置规范（Config Unit）

**本文件是"动态载入配置"的唯一权威来源（SSOT）**：定义配置单元的形态、字段、脚本契约、加载协议、校验规则与版本兼容。

适用读者：实现引擎的人、编写配置的人、做配置校验工具的人。

相关文档：

- 功能与交互 → [design.md](../01-product/design.md)
- UI 如何消费配置 → [ui-implementation.md](../02-architecture/ui-implementation.md)
- 返回值字段级定义 → [rest-contract.md](rest-contract.md) §3
- 为什么这样设计 → [ADR-0004](../02-architecture/adr/ADR-0004-config-format.md)

---

## 1. 设计原则

| 编号 | 原则 | 落地要求 |
|---|---|---|
| C1 | **配置即数据** | 除脚本外的一切（来源地址、渲染参数、行为参数）都是可静态校验的声明式数据，不执行代码也能读出来 |
| C2 | **脚本只取数，不画界面** | 脚本的职责是把页面/接口响应变成领域模型；渲染、缓存、播放全部由应用负责 |
| C3 | **加载可失败** | 任何一步失败都必须给出明确错误码，且**不破坏已启用的旧配置** |
| C4 | **向后兼容** | 新字段一律可选；旧配置在新版引擎上必须照常工作 |
| C5 | **零来源特判** | 应用代码里不允许出现任何来源专有逻辑；差异只能出现在配置里 |

---

## 2. 配置形态

引擎支持两种形态，二者**规范化后等价**。

### 2.1 清单形态（manifest，推荐）

两个文件放在同一目录：

```
my-source/
├── config.json     # 元信息 + 渲染 + 行为
└── script.js       # 解析脚本
```

- **优点**：元信息可静态校验、可被 UI 直接读取（不执行脚本也能显示名称、图标、卡片尺寸）；
- **推荐用于**：需要分发、需要被校验工具检查、含多个可选脚本的配置。

### 2.2 内联形态（inline）

单文件 `config.js`，内容是**一个返回完整配置对象的表达式**（含方法）：

- **优点**：单文件、便于快速试验；
- **代价**：元信息必须执行脚本才能得到，静态校验能力弱；
- **要求**：内联形态在导入时必须执行一次"探测执行"（沙箱内，限时 5s）以提取 `meta`，探测失败即拒绝导入。

### 2.3 规范化结果

两种形态载入后都归一为同一条记录：

```json
{
  "key": "demo",
  "format": "manifest",
  "config": { "meta": {…}, "render": {…}, "behavior": {…} },
  "script": "<JS 源码>",
  "configVersion": "1.0.0",
  "sort": 0,
  "enabled": true
}
```

### 2.4 交换文件格式

单配置导出与 WebDAV 同步使用同一格式，文件名 `<key>.ghscfg.json`：

```json
{
  "$schema": "ghs-config/1",
  "format": "manifest",
  "config": { "meta": { }, "render": { }, "behavior": { } },
  "script": "<JS 源码>",
  "updatedAt": "2026-10-19T10:00:00Z",
  "checksum": "sha256:…"
}
```

| 字段 | 说明 |
|---|---|
| `$schema` | 固定为 `ghs-config/1`，跨版本识别用 |
| `format` | `manifest` \| `inline` |
| `config` | 规范化后的三段配置 |
| `script` | 脚本源码（内联形态的脚本也放这里） |
| `updatedAt` | 最后修改时间，同步时的比较依据 |
| `checksum` | 覆盖 `config` + `script`，用于快速判断是否变化 |

多配置打包为 `ghs-configs.bundle.json`：

```json
{ "$schema": "ghs-configs/1", "configs": [ { …交换对象… } ] }
```

导入时 `checksum` 不匹配**只告警不拒绝**（允许用户手工改过文件后导入）。

---

## 3. 字段规范

### 3.1 `meta`（元信息）

| 字段 | 类型 | 必填 | 默认 | 约束与说明 |
|---|---|---|---|---|
| `key` | string | **是** | — | 唯一标识，正则 `^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$`（**允许大小写**：既有配置有 `EHentai`、`91P`、`hentaiWord`）；大小写敏感；一经使用不建议修改（收藏/历史按它分组） |
| `name` | string | **是** | — | 展示名，1–24 字符 |
| `version` | string | 否 | `1.0.0` | 配置自身版本（语义版本），用于导入时提示升级 |
| `homeUrl` | string | **是** | — | 列表首页地址；**同时是相对 URL 的解析基准** |
| `favicon` | string | 否 | 空 | 图标地址；也支持 `data:` 内联图（`data:image/png;base64,...` 或 URL 编码形式）。位图（png / jpg / gif / webp / ico）与矢量图（**svg**）都可以：svg 由 AndroidSVG 解码，尺寸优先取 `viewBox`、其次 `width`/`height`；取不到或解码失败一律按"无图标"处理（站点卡片退回首字色块） |
| `searchUrl` | string | 否 | 空 | 搜索地址前缀，由 `adapterSearchUrl` 拼接 |
| `description` | string | 否 | 空 | 一句话说明，展示在配置列表 |
| `requiresApi` | integer | 否 | `1` | 需要的脚本 API 版本（见 §8）；高于引擎支持版本时拒绝加载 |

### 3.2 `render`（渲染参数，决定 UI 长什么样）

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `imgWidth` | string | 否 | `220px` | 卡片宽度；支持 `px`/`dp`，纯数字按 dp |
| `imgHeight` | string | 否 | `147px` | 卡片高度（决定宽高比） |
| `cardStyle` | enum | 否 | `cover-title` | `cover`（仅封面）/ `cover-title`（封面 + 标题）/ `list`（左图右文横向列表） |
| `drawWidth` | string | 否 | `auto` | 详情页内容最大宽度 |
| `inputWidth` | string | 否 | `auto` | 首页搜索框宽度；`auto` 表示占满剩余空间 |
| `readerMode` | enum | 否 | `vertical` | 多图阅读器默认模式：`vertical`（连续滚动）/ `horizontal`（分页）/ `auto`（按图片宽高比自动选） |
| `textMode` | enum | 否 | `scroll` | 文本阅读器默认模式：`scroll`（滚动）/ `paged`（分页） |
| `comicImgMaxWidth` | string | 否 | `1080px` | 图集单图解码宽度上限 |
| `setTags` | string[] | 否 | `["收藏","历史","系统配置"]` | 启用哪些入口，见 §3.4 |
| `themeColor` | string | 否 | 空 | 主题强调色（`#RRGGBB`），为空则用应用默认色 |

> **尺寸单位**：`imgWidth` / `imgHeight` / `drawWidth` / `inputWidth` / `comicImgMaxWidth` 接受 `px`、`dp`、**`vw`**（视口宽度百分比）、**`%`**（父容器百分比）、纯数字（按 dp）。`auto` 表示由布局决定。既有配置里出现过 `comicImgMaxWidth: '80vw'` 这种写法。

### 3.3 `behavior`（行为参数）

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `historyRemember` | integer | 否 | `100` | 观看历史最多保留条数，超出丢弃最旧 |
| `requestTimeoutMs` | integer | 否 | `20000` | 单次取页超时，范围 3000–120000 |
| `concurrency` | integer | 否 | `2` | 同来源并发请求数，范围 1–5 |
| `retry` | integer | 否 | `1` | 失败重试次数，范围 0–3 |
| `cacheHtml` | boolean | 否 | `true` | 是否缓存页面 HTML |
| `useWebView` | boolean | 否 | `false` | 强制走无头浏览器通道（JS 渲染页面/需要浏览器环境时） |
| `headers` | object | 否 | `{}` | 附加请求头，如 `{"Referer": "https://example.com/"}`；`User-Agent` 可覆盖。**当前引擎尚未实现该字段**（写入不生效）：Referer 由引擎按 `homeUrl` 派生——取页、OkHttp 图片请求、`adapterImageCode` 解图请求都已带上（站点图标用**该站点自己**的 `homeUrl`）；WebView 兜底那条路发不出程序化的 Referer，不依赖它。**视频**走另一条规则：播放器按条目来源页（`jumpUrl`，缺省退回站点 origin）设默认请求头，playlist、playlist 里返回的子播放列表与分片都带同一个 Referer——媒体 CDN 普遍校验它，缺了会 403 |
| `challengeMarkers` | string[] | 否 | `[]` | 追加的人机校验特征（HTML 关键词），与内置特征合并后判定；命中即触发顶层校验窗口（§7、设计文档 §8.14） |
| `ifWinExecCode` | string | 否 | 空 | **JS 片段**：当取页必须降级到无头浏览器通道时，先注入执行这段代码（常用于在窗口里直接改写 DOM 或提示"不支持"），再取 `outerHTML` |

### 3.4 `setTags` 取值

| 值 | 效果 |
|---|---|
| `标签` | 首页显示标签筛选入口 |
| `收藏` | 显示收藏 Tab |
| `历史` | 显示历史 Tab |
| `过滤选项` | 显示排序/筛选面板 |
| `系统配置` | 显示设置 Tab |
| `日志` | 设置中显示运行日志入口 |
| `配置` | 设置中显示配置管理入口 |

> 缺失的入口不渲染（不是置灰）。这是 UI 由配置驱动的核心一环，见 [ui-implementation.md](../02-architecture/ui-implementation.md) §4.2。

> `日志` 在 v1 只影响 **Web 管理界面**的入口显隐（App 端不提供日志页，见设计文档 §2.6）。

### 3.5 兼容字段（引擎接受但忽略）

既有配置里常见的运行时状态字段，引擎**接受但自行管理**，不参与语义：

| 字段 | 处理 |
|---|---|
| `currentUrlReplace` | 忽略；排序状态由引擎的 `Page.urlReplace` 维护 |
| `currentUrl` | 忽略；当前列表地址由 `EngineSession` 持有 |
| `setTags` 之外的未知字段 | 忽略并记 `warn`，不阻断加载 |

这保证手写配置（顶层直接写 `currentUrlReplace: null`）无需改动即可导入。

---

## 4. 脚本规范

脚本必须是一个**返回配置对象的表达式**，由引擎执行后得到含方法的对象。

### 4.1 必需方法（共 7 个）

| 方法 | 签名 | 调用时机 | 返回值 |
|---|---|---|---|
| `getItems` | `($) => Item[]` | 列表页载入完成后 | 条目数组，见 [rest-contract](rest-contract.md) §3.2 |
| `getPagination` | `($) => Pagination[]` | 同一次列表页载入 | 分页数组，可返回 `[]` |
| `getTags` | `($) => Tag[]` | 同一次列表页载入 | 标签数组，可返回 `[]` |
| `getUrlReplace` | `($) => UrlReplace[]` | 同一次列表页载入 | 排序/筛选分组，可返回 `[]` |
| `getDetailInfo` | `(item, cheerio) => Promise<DetailInfo \| null>` | 用户点击条目 | 详情；返回 `null` 表示"不支持该类型"，UI 提示后返回 |
| `adapterLoadUrl` | `(url, urlReplaces) => string` | 翻页/排序 | 最终请求地址 |
| `adapterSearchUrl` | `(keyword) => string` | 用户提交搜索 | 搜索结果页地址 |

### 4.1.1 调用约定

1. **`this` 绑定为配置对象**：方法内可用 `this.homeUrl`、`this.searchUrl`，因此 `getItems` 里写 `this.homeUrl + href` 是合法的。
2. **第二个参数是 `cheerio` 对象**：`getDetailInfo(item, cheerio)` 的第二参可直接 `cheerio.load(html)`；它同时挂载 §8 的全部能力（`cheerio.getHtml`、`cheerio.net` 等）。全局也暴露同名 `cheerio`，两种写法等价。
3. **允许异步**：所有方法可以是 `async`，引擎会 `await`。
4. **顺序与副作用**：`getItems`/`getPagination`/`getTags`/`getUrlReplace` 在一次载入中**依次**调用，共享同一个 `$`；不要依赖它们之间的隐式全局变量（并行/重入时不可靠）。
5. **允许自定义成员**：配置对象除 7 个必需方法与若干可选方法外，可以定义**任意自定义方法/属性**（如 `thatGethtml(url)`、`toggleUrl(url)`），并在其他方法里通过 `this.xxx()` 调用。引擎只校验必需方法存在，不限制多余成员。
6. **`ElementAttr` 的键是 camelCase，值是真实属性名**：`ElementAttr.dataSrc === 'data-src'`、`ElementAttr.dataOriginal === 'data-original'`、`ElementAttr.dataWebp === 'data-webp'`，其余（`src`/`href`/`title`/`alt`/`class`/`poster`）键值同名。

### 4.2 可选方法

| 方法 | 签名 | 未实现时的表现 |
|---|---|---|
| `getContents` | `(url, engine) => Promise<CContent[]>` | 图集目录为空，阅读器退化为单组图 |
| `getComicImages` | `(url, engine) => Promise<CComic[]>` | 该来源不支持图集阅读 |
| `getAnalysisDetail` | `(item, engine) => Promise<AnalysisDetail[]>` | 系列面板不显示分集 |
| `getAnalysisVideoDetail` | `(item, engine) => Promise<AnalysisVideoDetail[]>` | 分集无播放源 |
| `adapterRemoteSearch` | `(keyword, engine) => Promise<string[]>` | 搜索无联想词 |
| `adapterGetHtml` | `(url) => Promise<string>` | 走默认取页管线（见下方优先级说明） |
| `getTextContent` | `(url, engine) => Promise<TextContent>` | 该来源不支持文本阅读（`detailType = text` 时无正文，报 `E-CFG-021`） |
| `adapterImageCode` | string（JS 片段） | 图片按原地址直取 |

**`adapterGetHtml` 优先级**：一旦定义，引擎**完全接管取页**——不再走默认的 OkHttp / WebView 管线，直接使用其返回值（它内部通常会调用 `getHtml`、`executeJs` 或带特殊请求）。返回值仍会写入 HTML 缓存，仍受代理与白名单约束。

**"JS 片段"型配置项**（`adapterImageCode`、`ifWinExecCode`）是**字符串代码**，不是函数：引擎在无头页面上下文中 `evaluateJavascript` 执行它。

### 4.2.1 `adapterImageCode` 契约（关键）

字符串内容必须是**一段返回函数的表达式**（形如 `return (…) => { … }` 或 `(…) => { … }`）。引擎执行 `new Function(code)()` 得到函数后，用**固定 5 个参数**在页面上下文调用：

```js
(imgSrc, extra, Md5, url, canvas) => void
```

| 参数 | 类型 | 方向 | 说明 |
|---|---|---|---|
| `imgSrc` | `{ value: string }` | 入 + **出** | 传入原图地址；脚本把处理结果（通常是 `data:image/jpeg;base64,…`）**回写到 `imgSrc.value`** |
| `extra` | object | 入 | 来自 `CComic.info`（如 `{ aid, scrambleId }`） |
| `Md5` | object | 入 | 注入的 spark-md5，提供 `Md5.hashStr(str)` |
| `url` | string | 入 | 当前图片的原始地址（用于从 URL 中提取分片参数） |
| `canvas` | `{ value: HTMLCanvasElement }` | 入 | 引擎预先创建的离屏 canvas，脚本用其 2D 上下文重绘 |

执行模型：

1. **允许异步回写**：脚本通常在 `img.onload` 回调里改 `imgSrc.value`，因此引擎不能同步读取；
2. 引擎以 **50ms 间隔轮询** `imgSrc.value`，值发生变化即视为完成；默认超时 **10s**；
3. 超时未回写 → 记 `E-CFG-020` 并**回落到原图直取**（不阻断整组图）；
4. 该代码只在**页面上下文**运行（需要 `Image`/`Canvas`/`document`），**不在 QuickJS 沙箱内**；
5. 同一张图只执行一次，结果进图片缓存。

### 4.3 脚本约束

| 约束 | 值 | 违反后果 |
|---|---|---|
| 单次执行超时 | 10s（取页另计，最长 120s） | 中止并报 `E-CFG-020` |
| 内存 | 单次脚本分配上限 32MB | 中止并报 `E-CFG-022` |
| 全局副作用 | 禁止修改原型链、禁止注册持久定时器 | 沙箱拦截 |
| 外部依赖 | 不可 `require`/`import` | 沙箱拦截 |
| 网络 | 只能经 `getHtml` / `net.request` / `request_string_get` / `executeJs`；`net.request` **仅允许与 `homeUrl` 同源** | 越界请求被拒绝并记 `error` |
| 缺失的运行时能力 | 沙箱内**没有** `fetch`、`Buffer`、`require`、`process`、`window`、`document` | 调用即 `ReferenceError` |

---

## 5. 返回值契约

字段级定义以 [数据层契约](rest-contract.md) §3 为准，本节只说明**脚本侧的义务**：

1. **`jumpUrl` 必须存在且稳定**（收藏/历史/进度都以它为键）；缺失该项的条目会被丢弃并记 `warn`。
2. `title`、`coverImg` 缺失时以空串补齐，条目仍然保留。
3. **相对 URL 一律可以返回**，引擎会补全（规则见 §5.1）。
4. `type` 缺失时按 `image` 处理；`renderType` 缺失时按 `normal` 处理。
5. 数组类返回值必须是数组，返回 `null`/`undefined` 视为 `[]` 并记 `warn`。
6. `getDetailInfo` 返回的 `detailType` 必须是 §5.2 之一，否则报 `E-CFG-021`。
7. `getTextContent` 可返回 `{ blocks }`、`{ html }` 或两者（`blocks` 优先）。给 `html` 时引擎按设计文档 §8.15 清洗并转为结构块；**UI 永远只收到结构块**。**实际形态**：正文是**纯文本**，唯一的标记是内嵌的 `<img src="…">`，除此之外没有其它 HTML 结构（见设计文档 §8.15）。
8. **`url` 与 `fullUrl`**：`details[].url` 是"展示用地址"（缩略图或直链），`fullUrl` 是"原图地址"。阅读器优先用 `fullUrl`，失败回落 `url`；`fullUrl` 为空时用 `url`。
9. **`Tag.url` 为空字符串表示占位/分隔**：不可点击，`title` 原样展示（既有配置广泛用它做分栏对齐与分隔线）。引擎不得丢弃这类项。
10. **引擎不去重、不排序**：顺序与内容保持脚本返回的 `Item[]` 原样；去重是脚本自己的责任（例如按 `jumpUrl` 判重）。
11. `getDetailInfo` 返回 `null` 视为"不支持该内容类型"，UI 提示后返回上一页，不报错误码。
12. `Analysis.total` 仅表示"已知集数"，`0` 表示未知/只有一集；分集列表由 `getAnalysisDetail` 自行兜底。
13. **是否支持搜索看 `adapterSearchUrl` 的返回值，不看 `searchUrl`**：`searchUrl` 只是作者自行使用的前缀变量，允许为空字符串（既有配置普遍是 `searchUrl: ''` + 硬编码的 `adapterSearchUrl`）。只有 `adapterSearchUrl` 返回空串时才隐藏搜索入口。
14. **空白与缺失一律按"空"处理**：`url` / `fullUrl` / `coverImg` / `jumpUrl` / `favicon` 在入库前统一 `trim()`（既有配置出现过带前导空格的地址）；`Tag.url` 为 `undefined` 与 `''` 等价，均渲染为占位。
15. **`Item.author`**：可选字段（作者/上传者），有则展示、无则不占位。
16. **`CComic.info` 与 `CComic.extra` 是两件事**：`info` 是传给 `adapterImageCode` 的**解图参数**；`extra` 是该图关联的**评论数组**。两者可同时存在（图集常见）。
17. **额外字段保留但 UI 可忽略**：如 `CContent.time`（章节时间）、`Item.author`。`E-CFG-006` 只针对**已知字段的类型错误**，未知字段不报错。
18. **`Selection.toString()` 空集返回 `''`**（不返回 `undefined`、不抛错），使 `$img.toString().trim() !== ''` 这类存在性判断成立。
19. **日志单条截断**：`LogMsgUtil` / `ConsoleLogUtil` 单条上限 **4KB**，超出截断并标注；传入 `Selection` 时按其 `toString()` 输出（既有配置会把整页 HTML 打进日志）。
20. **分页项 `url` 为空表示禁用**（既有写法 `isCurrent: urlX === ''`）；`isCurrent: true` 且 `url: ''` 表示"当前页、不可点"。引擎不过滤分页项，UI 按返回项渲染。

### 5.1 URL 补全规则

| 返回值形态 | 补全方式 |
|---|---|
| `https://…` / `http://…` | 原样使用 |
| `//host/path` | 沿用 `homeUrl` 的协议 |
| `/path` | 与 `homeUrl` 的 origin 拼接 |
| `path` | 以 `homeUrl` 为基准做相对解析 |
| `data:image/…` | 视为内联图，不补全 |

### 5.2 `detailType` 取值

| 值 | 含义 | 应用行为 |
|---|---|---|
| `mp4` | 直链视频 | 视频播放器 |
| `m3u8` | HLS 流 | 视频播放器（走 HLS） |
| `image` | 图片/图组 | 单图查看器或多图阅读器 |
| `comic` | 有目录的图集 | 阅读器 + 目录 |
| `text` | 有目录的文本 | 文本阅读器（目录 + 正文） |
| `series` | 系列（多集） | 系列面板 |
| `win` | 必须内嵌网页 | 内嵌浏览器 |

---

## 6. 加载协议

```
① 发现 ──► ② 读取 ──► ③ 解析 ──► ④ 校验 ──► ⑤ 注册 ──► ⑥ 启用
                                         └── 失败 ──► 报错 + 保留旧配置
```

| 步骤 | 行为 | 失败处理 |
|---|---|---|
| ① 发现 | 扫描 `assets/configs/`（内置）、`filesDir/configs/`（用户）；另可由剪贴板、文件选择、系统分享 Intent 触发 | 目录不存在即跳过 |
| ② 读取 | 单配置总大小 ≤ 1MB | 超限 `E-CFG-010` |
| ③ 解析 | manifest → `JSON.parse`；inline → 沙箱探测执行 | `E-CFG-001` / `E-CFG-004` |
| ④ 校验 | 字段级 + 语义级（§7） | 返回首个错误码与路径，如 `render.cardStyle` |
| ⑤ 注册 | 按 `key` upsert 到 `config_unit` | 冲突时询问用户是否覆盖 |
| ⑥ 启用 | 写 `enabled`/`sort`；`setCurrentConfig` 切换当前 | 切换失败回滚到原配置 |

补充规则：

- **原子性**：①–⑤ 任一失败都不落库，配置列表保持原状。
- **保留原件**：校验失败的原始文件复制到 `cacheDir/rejected/`，便于排查。
- **热重载**：用户目录 `configs/` 变更后自动重载（防抖 1s）；重载失败保留内存中的旧版本。
- **当前配置**：`currentConfigKey` 持久化，冷启动恢复；若该配置已被删除，回退到排序第一且启用的配置。

---

## 7. 校验规则与错误码

| 错误码 | 含义 | 触发条件 | 用户可见提示 |
|---|---|---|---|
| `E-CFG-001` | 解析失败 | JSON 语法错误 / 内联脚本语法错误 | 显示行号与片段 |
| `E-CFG-002` | 缺少必填字段 | `key` / `name` / `homeUrl` 为空 | 指出缺哪个字段 |
| `E-CFG-003` | `key` 格式非法 | 不满足 §3.1 正则 | 显示期望格式 |
| `E-CFG-004` | 脚本缺失 | `script` 为空或无法构造函数 | 提示必须提供脚本 |
| `E-CFG-005` | 必需方法缺失 | 7 个必需方法任一不存在 | 指出方法名 |
| `E-CFG-006` | 字段类型错误 | 类型与 §3 不符（如 `imgWidth` 传数字对象） | 指出字段路径 |
| `E-CFG-007` | 枚举值非法 | `cardStyle`/`readerMode`/`setTags` 取值不在白名单 | 列出合法值 |
| `E-CFG-008` | 数值越界 | `concurrency` 超出 1–5 等 | 显示范围 |
| `E-CFG-010` | 体积超限 | 单配置 > 1MB | — |
| `E-CFG-011` | 版本不兼容 | `requiresApi` > 引擎支持版本 | 提示升级 App |
| `E-CFG-020` | 脚本执行超时 | 超过 10s | — |
| `E-CFG-021` | 返回值不符合模型 | `detailType` 非法、`getItems` 非数组 | 指出字段 |
| `E-CFG-022` | 内存超限 | 单次脚本分配 > 32MB | — |
| `E-CFG-030` | 使用了不支持的旧式 API | 如 `net.request(...).on('response', …)` 事件式调用 | 警告（不阻断），提示改写为 Promise 形态 |
| `E-CHALLENGE-001` | 需要人机校验 | 取页返回挑战页，等待用户完成 | 在设备上完成校验后自动继续 |
| `E-CHALLENGE-002` | 用户取消校验 | 用户关闭校验窗口 | 已取消，可重试 |
| `E-CHALLENGE-003` | 校验超时 | 超过等待上限（默认 5 分钟） | 校验超时，可重试 |
| `E-CHALLENGE-004` | 后台无法弹窗 | 应用在后台，已发通知 | 请打开应用完成校验 |

前四组为**配置校验错误**（导入/保存时判定，不可重试）；`E-CHALLENGE-*` 为**运行时中断**（可重试），流程见设计文档 §8.14。

校验分两级：

- **静态校验**（不执行代码）：适用 manifest，覆盖 `E-CFG-002/003/006/007/008/010/011`；
- **语义校验**（需执行）：覆盖 `E-CFG-004/005` 与方法返回值。

---

## 8. 运行环境（GHS Script API v1）

脚本执行时注入下列能力。**这些 API 是本项目对配置作者的公开承诺**，变更需按 §9 处理。

| API | 签名 | 说明 |
|---|---|---|
| `$` | `(selector) => Selection` | 选择器，语义见 [design.md §8.4](../01-product/design.md)（含 `hasClass` / `toString` / 属性等值） |
| `parse` | `(html) => Selection` | 把 HTML 字符串解析为选择器根 |
| `cheerio` | `{ load(html) }` | 与 `parse` 等价；**既有脚本以 `cheerio.load(html)` 写法为主**，两者都要支持 |
| `getHtml` | `(url, forceWebView?) => Promise<string>` | 取 HTML，含自动兜底；**取页过程中以 `hashString(url)` 为 key 向 `eventEmitter` 广播进度字符串** |
| `getHtmlWithProcess` | `(url) => Promise<string>` | 同上，进度另经 `ProgressMsgUtil` 上报 |
| `net.request` | `(options) => Promise<{status, headers, body}>` | 通用请求：`url` / `method` / `headers` / `body`（字符串或对象）；**仅允许与 `homeUrl` 同源** |
| `request_string_get` | `(url) => Promise<string>` | 原始文本 GET |
| `hashString` | `(input) => string` | 稳定哈希：同一输入在任何进程与版本下恒定（缓存键与事件 key 依赖它） |
| `helpElAttr` | `(el, name) => string` | 取属性；空值返回 `''` 而非 `undefined` |
| `helpElText` | `(el) => string` | 取文本；空值返回 `''` |
| `ElementAttr` | object | `src` `href` `title` `alt` `class` `poster` `dataSrc` `dataOriginal` `dataWebp` `dataType` `dataEcho` `dataSource` `dataError` |
| `ElementTypes` | object | `a` `img` `h1` `h4` `p` `span` `div` `video` `source` |
| `LogMsgUtil` | `sendLogMsg(...args)` | 写运行日志；参数任意类型按 `String()` 化（传对象请先 `JSON.stringify`） |
| `ConsoleLogUtil` | `log(...args)` | 兼容 `console` 用法 |
| `NotifyMsgUtil` | `sendNotifyMsg(title, msg, key)` / `close(key)` | 状态胶囊通知；`key` 通常取 `hashString(url)` |
| `ProgressMsgUtil` | `sendProgress(pct, key, title?)` / `close(key)` | 进度上报 |
| `MessageUtil` | `info/error/success/warning(msg)` | 轻提示 |
| `eventEmitter` | `on(key, fn)` / `off(key, fn)` / `emit(key, ...args)` | 脚本内事件总线；**订阅取页进度就靠它** |
| `Md5` | `{ hashStr(v) }` | **仅在 `adapterImageCode` 的页面上下文注入**（spark-md5）；沙箱内不存在 |
| `executeJs` | `(url, code, show?) => Promise<any>` | 在无头页面上下文执行脚本；返回值经 JSON 序列化传递（不支持 DOM / Buffer） |
| `getCurrentItems` | `() => Item[]` | 取当前列表已解析条目 |

`requiresApi: 1` 表示依赖上表全部内容；后续新增 API 时递增，旧配置不受影响。

补充约定：

- **第二参数就是 `cheerio`**：`getDetailInfo(item, cheerio)` 的第二参可直接 `cheerio.load(html)`，并额外挂载上表全部能力（`cheerio.getHtml`、`cheerio.net` 等）；全局也暴露同名对象，两种写法等价。
- **`this` 绑定为配置对象**：方法内可用 `this.homeUrl` / `this.searchUrl`。
- **沙箱内不存在的 Node/浏览器能力**：`Buffer`、`fetch`、`require`、`process`、`window`、`document` —— 需要请求用 `net.request` / `getHtml`，需要解析用 `cheerio.load`。
- **页面上下文才有**：`document`、`Image`、`canvas`、`Md5` —— 只在 `executeJs` 注入代码与 `adapterImageCode` 中可用。
- **旧式事件 API 不支持**：`net.request(...).on('response', …)` 这类 Electron 风格写法会给出 `E-CFG-030` 警告，请改成 Promise 形态。

### 8.2 异步语义（v1 实现约定）

`getHtml` / `net.request` / `executeJs` 在 v1 是**同步宿主实现**：Kotlin 侧阻塞取回结果后立即返回。

- 配置里写 `await getHtml(url)` **语义不变**——`await` 一个非 Promise 值会立即得到它
- 但 `Promise.all([...])` 这类「并发组合」不会真的并发，只是顺序执行
- 若配置需要真正的并发或 `new Promise` 驱动的回调（如等待页面某事件），依赖宿主引擎的事件循环；
  v1 由 WebView 通道提供（见 [ADR-0016](../02-architecture/adr/ADR-0016-async-script-host-webview.md)）
- QuickJS 通道没有事件循环（封装未暴露 `JS_ExecutePendingJob`），**只支持同步方法**，不得用于异步配置

---
### 8.1 编辑器智能提示的数据源

Web 管理界面的源码编辑器（Monaco）通过注入一份 `config-api.d.ts` 获得补全、hover、参数签名与诊断（见 [ADR-0014](../02-architecture/adr/ADR-0014-monaco-editor-intellisense.md)）。

| 项 | 约定 |
|---|---|
| 数据来源 | 本节 API 表 + §3 字段表 + [rest-contract](rest-contract.md) §3 的返回模型 |
| 关系 | d.ts 是**派生物**：改规范必须同步改 d.ts；**不得以 d.ts 反推修改本文件** |
| 一致性 | 用单测比对 d.ts 导出成员名与本表逐项比对，缺一即失败 |
| 判定权 | d.ts 诊断只是编辑期快速反馈，**保存前仍以 `/api/validate` 为准** |

---

## 9. 版本与兼容

| 变化类型 | 是否兼容 | 处理 |
|---|---|---|
| 新增可选字段 | 兼容 | 升级次版本号 |
| 新增可选方法 / 新增 API | 兼容（递增 `requiresApi`） | 配置可不改 |
| 修改字段语义、删除字段 | **不兼容** | 必须新增 ADR，并提供迁移说明 |
| 修改 `detailType` 或 `setTags` 取值 | **不兼容** | 同上 |

引擎对不兼容配置的行为：拒绝加载并报 `E-CFG-011`，**不做静默降级**。

### 9.1 兼容性核对（既有配置）

用 3 份不同形态的既有配置逐行核对后，本轮补齐/明确了下列能力（只列能力，不列来源与选择器）：

| # | 既有用法 | 之前状态 | 现在 |
|---|---|---|---|
| 1 | 顶层写 `currentUrlReplace: null` / `currentUrl` | 未定义 | §3.5 兼容字段：接受并忽略 |
| 2 | `inputWidth` | 缺失 | §3.2 新增 |
| 3 | `ifWinExecCode`（无头窗口注入代码） | 缺失 | §3.3 新增 |
| 4 | `getUrlReplace($)` | 未列为必需 | §4.1 改为 **7 个** 必需方法 |
| 5 | `getDetailInfo(item, cheerio)` 内 `cheerio.load(html)` | 第二参叫作 `engine` | §8：第二参就是 `cheerio`，同时挂载 engine 能力 |
| 6 | 方法内使用 `this.homeUrl` / `this.searchUrl` | 未定义 | §4.1.1 明确 `this` 绑定为配置对象 |
| 7 | `$el.hasClass('current')` | 选择器 API 未列 | design §8.4 补 `hasClass` |
| 8 | `$el.toString()` 取 `outerHTML` | 未列 | design §8.4 补 `toString` |
| 9 | `[itemprop="name"]` 这类属性等值选择器 | 只支持 `[href]` | design §8.4 补属性等值 |
| 10 | `net.request(...).on('response')` 事件式 | 规范只写了 Promise 形态 | §8 明确不支持，给 `E-CFG-030` 警告 |
| 11 | 订阅 `eventEmitter.on(hashString(url))` 看取页进度 | 契约未写 | §8 固化"`getHtml` 按 `hashString(url)` 广播进度" |
| 12 | `Tag.url === ''` 作占位/分隔 | 未定义 | §5.9 明确不可点、不得丢弃 |
| 13 | `details[].url` 与 `fullUrl` 并用 | 未说明关系 | §5.8 明确"展示地址 / 原图地址" |
| 14 | `getDetailInfo` 返回 `null` | 未定义 | §5.11 视为不支持该类型 |
| 15 | `searchUrl: ''` | 未定义 | §5.13 隐藏搜索入口 |
| 16 | 脚本自行 `findIndex` 去重 | 未说明引擎是否去重 | §5.10 明确引擎不去重、不排序 |
| 17 | `adapterGetHtml` 自行取页 | 优先级未写明 | §4.2 明确"完全接管取页" |
| 18 | `ElementAttr.alt` / `.class` | 常量表未列全 | §8 列全 13 个属性名 |

补充结论：

- 三份配置在**沙箱内**都没有实际调用 `fetch` / `Buffer`（仅出现在被注释或未调用的代码里），因此把二者排除在沙箱外是安全且可行的；
- 三份都不要 `net.request` 的事件式 API 完成必需功能，所以 v1 只提供 Promise 形态是合理的，旧写法给警告而不阻断；
- 三份都用 `cheerio.load` 而非 `parse`，第二参数的命名因此必须兼容 `cheerio`。

### 9.2 兼容性核对（第二轮：图集类配置）

第二批 2 份配置都是**图集**（走 `getContents` / `getComicImages` / `adapterImageCode`），补齐了此前完全没有真实用例的链路：

| # | 既有用法 | 之前状态 | 现在 |
|---|---|---|---|
| 19 | `comicImgMaxWidth: '80vw'` | 单位只有 px/dp | §3.2 支持 `vw` / `%`，所有尺寸字段统一 |
| 20 | 配置内定义 `thatGethtml` / `toggleUrl` 并在其他方法里 `this.xxx()` | 未说明是否允许 | §4.1.1 第 5 条：允许任意自定义成员 |
| 21 | `ElementAttr.dataSrc` / `dataOriginal` | 常量表只写了连字符形式 | §4.1.1 第 6 条：键 camelCase、值为真实属性名 |
| 22 | `Item.author`（作者/上传者） | 未定义 | §5.15 支持为可选字段 |
| 23 | `CComic.info`（解图参数）与 `CComic.extra`（评论）**并存** | 只定义了 `extra` 作评论 | §5.16 明确两者分工，契约补 `info` |
| 24 | `CContent.time`（章节时间） | 未定义 | §5.17 归为"额外字段：保留、UI 可忽略" |
| 25 | 用 `$sel.toString().trim() !== ''` 判断节点存在 | 补了 `toString`，但未定空集行为 | §5.18 空集返回 `''` |
| 26 | 把整页 HTML 传进 `LogMsgUtil` | 无上限 | §5.19 单条 4KB 截断 |
| 27 | `searchUrl: ''`，但 `adapterSearchUrl` 返回可用地址 | 上一轮误定为"空则隐藏搜索" | §5.13 改为**看 `adapterSearchUrl` 返回值** |
| 28 | `adapterImageCode` 是"返回函数的表达式"，签名 5 参 | 只写了"JS 片段" | §4.2.1 完整契约（`imgSrc`/`extra`/`Md5`/`url`/`canvas` + 轮询回写） |
| 29 | 解图代码依赖 `Md5.hashStr` 与离屏 canvas | 未提供 | §8 注入 `Md5` 与 `canvas` |
| 30 | 图片必须带解图参数才能出图 | `imageUrl(url)` 会丢掉 `info` | 设计文档 §7.1 / §8.6 改为 `EngineImage(url, info)` |
| 31 | `ProgressMsgUtil` 用任意字符串作 key（如 `'all'`） | 未说明 | §8 明确 key 可为任意字符串 |
| 32 | `key` 含大写（`EHentai` / `91P` / `hentaiWord`） | 正则只允许小写，**3 份真实配置被判非法** | §3.1 放宽为 `^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$`（**实现期回流修正**） |

> 第 32 条是实施阶段（`InlineConfigScanner` 跑真实样本）发现的**规范缺陷**，不是配置错误——已按"实现回流文档"修正。

结论：**图集链路的三个真实难点**是"解图参数要跟着图片走""解图是异步回写""依赖注入的 Md5/canvas"，都已在规范里定死；M0 必须把图集类配置纳入回归集。

---

## 10. 安全边界

- 脚本运行在**沙箱**内：无文件系统、无直接网络、无反射调用；
- 只能通过 §8 的 API 与外界交互，全部走白名单；
- 内联形态的"探测执行"与正式执行在同一沙箱中，超时与内存限制一致；
- 导入第三方配置时展示**权限提示**：该配置可读取应用内的收藏/历史（通过返回值影响），但不能上传；
- 配置中的 `headers` 不允许携带 Cookie 值以外的凭据；Cookie 由应用统一管理。

---

## 11. 完整示例

### 11.1 清单形态

`config.json`：

```json
{
  "key": "demo",
  "name": "示例源",
  "version": "1.0.0",
  "homeUrl": "https://example.com/list",
  "favicon": "https://example.com/favicon.ico",
  "searchUrl": "https://example.com/search?q=",
  "requiresApi": 1,
  "render": {
    "imgWidth": "200px",
    "imgHeight": "150px",
    "cardStyle": "cover-title",
    "readerMode": "auto",
    "setTags": ["标签", "收藏", "历史", "系统配置"]
  },
  "behavior": {
    "historyRemember": 100,
    "requestTimeoutMs": 20000,
    "useWebView": false
  }
}
```

`script.js`：

```js
({
  getItems($) {
    const list = [];
    $('#list .card').each((i, el) => {
      const $el = $(el);
      const href = $el.find('a').attr('href');
      if (!href) return;
      list.push({
        jumpUrl: href,
        title: $el.find('.title').text().trim(),
        coverImg: $el.find('img').attr('data-src') || $el.find('img').attr('src'),
        type: $el.attr('data-kind') === 'video' ? 'video' : 'image',
        renderType: 'normal',
        tags: [],
      });
    });
    return list;
  },

  getPagination($) {
    return $('#pager a').map((i, el) => ({
      title: $(el).text().trim(),
      url: $(el).attr('href'),
      isCurrent: $(el).hasClass('active'),
    })).toArray();
  },

  getTags($) {
    return $('#tags a').map((i, el) => ({
      title: $(el).text().trim(),
      url: $(el).attr('href'),
    })).toArray();
  },

  async getDetailInfo(item, engine) {
    const html = await engine.getHtml(item.jumpUrl);
    const $ = engine.parse(html);
    const video = $('video').attr('src');
    return {
      detailType: video ? 'm3u8' : 'image',
      renderType: 'normal',
      details: [{
        type: video ? 'm3u8' : 'image',
        url: video || item.jumpUrl,
        title: item.title,
        comments: [],
      }],
      relations: [],
    };
  },

  adapterLoadUrl(url) { return url; },
  adapterSearchUrl(keyword) { return this.searchUrl + encodeURIComponent(keyword); },
});
```

### 11.2 内联形态

```js
/* break */
({
  key: 'demo-inline', name: '示例内联源', homeUrl: 'https://example.com/list',
  render: { imgWidth: '180px', imgHeight: '240px', cardStyle: 'cover' },
  behavior: { useWebView: false },

  getItems($) { /* 同上 */ return []; },
  getPagination($) { return []; },
  getTags($) { return []; },
  async getDetailInfo(item, engine) { /* 同上 */ },
  adapterLoadUrl(url) { return url; },
  adapterSearchUrl(keyword) { return this.searchUrl + keyword; },
});
```

---

## 12. 验收清单

- [ ] 导入 manifest 配置后，列表页按 `cardStyle` 与 `imgWidth/imgHeight` 渲染
- [ ] 删除 `render.readerMode` 后使用默认 `vertical`，不报错
- [ ] 缺少 `key` 时导入失败并给出 `E-CFG-002` 与字段名
- [ ] `cardStyle` 传非法值时给出 `E-CFG-007` 并列出合法值
- [ ] 脚本抛异常时：日志页可见错误、已启用配置不受影响
- [ ] 内联配置探测执行超过 5s 时导入被拒
- [ ] 热重载：修改 `config.json` 后 1s 内生效
- [ ] 删除当前配置后，自动回退到第一个启用配置

---

## 13. 编辑、保存与同步

- **编辑入口**：只在 Web 管理界面（[web-console.md](../02-architecture/web-console.md)）；应用内只提供管理、导入导出与只读查看。
- **保存前置**：保存前必须通过 §7 校验；校验未通过不允许落库。
- **保存语义**：保存即写入 `config_unit` 并即时生效（若为当前且启用的配置），无需重启应用。
- **草稿与试跑**：编辑器可试跑**未保存的草稿**（调试接口支持 `target.draft`），试跑不落库、不影响正式运行。
- **同步**：以 §2.4 交换文件为单位，通过 WebDAV 双向同步；冲突策略见 [ADR-0006](../02-architecture/adr/ADR-0006-webdav-sync.md)。
- **权威性**：设备上的 `config_unit` 是运行时唯一权威；WebDAV 远端只是副本，冲突时按策略决策，不做自动合并。

---

## 14. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 建立配置规范：两种形态、字段表、脚本契约、加载协议、错误码、API 白名单、版本兼容与示例 |
| v1.1 | 2026-10-19 | 新增 §2.4 交换文件格式（`.ghscfg.json` / bundle）与 §13 编辑、保存与同步；明确「编辑只在 Web、设备为运行时权威」 |
| v1.2 | 2026-10-19 | 新增 `detailType = text` 与可选方法 `getTextContent`（返回规范化段落块）；`render` 增加 `textMode` 字段 |
| v1.3 | 2026-10-19 | 新增 `behavior.challengeMarkers`（自定义人机校验特征）与 `E-CHALLENGE-*` 运行时中断错误码；区分配置校验错误与运行时可重试错误 |
| v1.4 | 2026-10-19 | `getTextContent` 允许返回任意 `html`（引擎清洗转结构块，见 ADR-0012）；明确 blocks 优先 |
| v1.5 | 2026-10-19 | **按 3 份既有配置查漏补缺**：新增 `inputWidth`、`ifWinExecCode`、§3.5 兼容字段；必需方法 6→7（`getUrlReplace`）；明确 `this` 绑定、第二参为 `cheerio`、`net.request` Promise 形态与 `E-CFG-030`、`getHtml` 进度广播契约、`Tag` 空 url 占位、`url`/`fullUrl` 语义、引擎不去重、`adapterGetHtml` 优先级；API 表补全 `ElementAttr`/`ElementTypes` 与 `hashString` 稳定性 |
| v1.6 | 2026-10-19 | **按 2 份图集类配置第二轮补齐**：新增 §4.2.1 `adapterImageCode` 完整契约（5 参、异步回写、Md5/canvas 注入、轮询超时）；尺寸支持 `vw`/`%`；允许自定义成员；`ElementAttr` 键值映射；`Item.author`、`CComic.info`、`CContent.time`、`Selection.toString()` 空集、日志截断、分页禁用语义；**修正搜索判定为看 `adapterSearchUrl`**；新增 §9.2 核对表 |
| v1.7 | 2026-10-19 | 补充文本正文实际形态（**纯文本 + 内嵌 `<img>`**）的说明，未新增字段或解析器 |
| v1.8 | 2026-10-19 | 新增 §8.1：编辑器智能提示的数据源为 `config-api.d.ts`（本节与 §3、rest-contract §3 的派生物），明确"改规范必须同步 d.ts、不得反向修改规范" |
| v1.9 | 2026-10-19 | **实现期回流修正**：`key` 正则放宽为允许大小写（3 份真实配置因大写被判非法）；§9.2 新增第 32 条核对记录 |
| v1.10 | 2026-10-20 | 新增 §8.2 异步语义：`getHtml`/`net.request`/`executeJs` 为同步宿主实现，`await` 语义不变；QuickJS 通道仅支持同步，异步由 WebView 承担（ADR-0016） |
| v1.11 | 2026-10-26 | `favicon` 明确支持的格式：位图（png / jpg / gif / webp / ico）、矢量图 **svg**（AndroidSVG 解码，尺寸取 `viewBox` 其次 `width`/`height`）、`data:` 内联图（base64 / URL 编码）；取不到或解码失败一律按"无图标"处理 |
| v1.12 | 2026-10-26 | 修正 §3.3 `headers` 的说明：该字段**引擎尚未实现**（写入不生效），并写明 Referer 实际由引擎按 `homeUrl` 派生（图片请求同样适用，修 mycomic 等图床的 403） |
| v1.13 | 2026-10-26 | §3.3 `headers` 补全 Referer 的实际覆盖范围：**视频**按条目来源页（`jumpUrl`，缺省退回站点 origin）设默认请求头，playlist 与 playlist 返回的子列表/分片都带同一个 Referer（媒体 CDN 缺 Referer 会 403） |
