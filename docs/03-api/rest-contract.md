> **状态**：draft
> **版本**：v0.7
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v0.6

# 数据层契约

**本文件是"内核 API 契约"的唯一权威来源（SSOT）。**

它描述引擎的方法、参数与字段语义，供两处使用：

- **进程内调用**（默认路径）：`GhsRepository` → `LocalRepository` → 引擎，不经过 HTTP；
- **本地调试出口**：`/engine/**`（默认关闭），把同一组方法按 HTTP 暴露给 `curl` 与自动化回归（见 [web-admin-api](web-admin-api.md) §6）。

> 远程服务模式已移除（[ADR-0011](../02-architecture/adr/ADR-0011-drop-remote-mode.md)）：本契约不再用于连接外部服务端。

## 1. 基础约定

| 项 | 值 |
|---|---|
| Base URL（远程） | `http://<host>:4000` |
| 协议 | HTTP，**全部为 GET** |
| 请求 Content-Type | 无（参数走 query string） |
| 响应 Content-Type | `application/json` |
| 鉴权 | 无 |
| 默认超时 | 60s（可配 5–180s）；图片 5s（可配 3–120s） |
| 字符集 | UTF-8 |
| 错误包装 | **无统一 envelope**；HTTP 非 2xx 即失败 |

> 本机内置服务以 `/engine` 前缀暴露**同一组路径**（见 [web-admin-api](web-admin-api.md) §6），参数与响应完全一致。

## 2. 参数编码（`/engine` 出口）

服务端用 Express 的 qs 解析，**支持嵌套**。客户端需把对象展开成方括号语法：

| 对象字段 | query 参数名 |
|---|---|
| `item.jumpUrl` | `item[jumpUrl]` |
| `item.title` | `item[title]` |
| `item.coverImg` | `item[coverImg]` |
| `item.type` | `item[type]` |
| `item.renderType` | `item[renderType]` |
| `item.tags[i].title` | `item[tags][i][title]` |
| `analysis.url` | `item[url]` |
| `analysis.title` | `item[title]` |
| `analysis.total` | `item[total]` |

规则：

1. 所有值必须 URL 编码（`encodeURIComponent` 语义），特别是 `jumpUrl` 本身含 `?` `&` `%`。
2. 值为空字符串时**可以省略**；`item[coverImg]` 若是 base64 内联图（`data:image/...`）则**必须省略**（避免 URL 超长）。
3. 数组下标从 0 递增，不留空洞。
4. 本地模式直接传对象，调用方不应感知编码细节（编码器单独成类并有单测）。

## 3. 领域对象

### 3.1 ConfigUnit（配置单元）

完整的字段规范见 [config-spec.md](config-spec.md) §3（SSOT）。本节只列**接口层**必须返回的部分：

| 字段 | 类型 | 说明 |
|---|---|---|
| `key` | string | 唯一标识，同时是接口参数 `configKey` |
| `name` | string | 展示名 |
| `format` | `manifest` \| `inline` | 配置形态 |
| `version` | string | 配置版本（语义版本） |
| `description` | string? | 一句话说明 |
| `config` | object | 规范化后的 `meta` / `render` / `behavior` |
| `sort` | number | 排序 |
| `enabled` | boolean | 是否启用 |

> 接口**不返回脚本源码**（体积大且属敏感内容）：需要导出时调用 `/getConfigScript`，或由本地数据库直读。

### 3.2 Item（内容元素）

| 字段 | 类型 | 说明 |
|---|---|---|
| `jumpUrl` | string | 详情页地址（唯一键） |
| `title` | string | 标题 |
| `author` | string? | 作者/上传者；可选，缺失不占位 |
| `coverImg` | string | 封面；可能是 URL，也可能是 base64 |
| `type` | `video` \| `image` \| `text` \| `comic` | 内容类型；含 Video 标签的按视频处理 |
| `renderType` | `normal` \| `force` | `force` 表示强制内嵌渲染 |
| `tags` | Tag[] | 标签 |

### 3.3 Page / Pagination / Tag / UrlReplace

| 对象 | 字段 |
|---|---|
| `Page` | `items: Item[]`、`pagination: Pagination[]`、`tags: Tag[]`、`urlReplace: UrlReplace[]` |
| `Pagination` | `title`、`url`、`isCurrent: boolean` |
| `Tag` | `title`、`url`（**为空或缺失都表示占位/分隔，不可点击**；入库前 `trim()`，引擎不得丢弃） |
| `UrlReplace` | `schema: string`（分组名）、`urlAppend: UrlAppend[]` |
| `UrlAppend` | `value`、`param`、`title`、`current: boolean` |

### 3.4 DetailInfo

| 字段 | 类型 | 说明 |
|---|---|---|
| `detailType` | `mp4` \| `m3u8` \| `image` \| `text` \| `win` \| `comic` \| `series` | 决定跳哪个渲染器 |
| `renderType` | `normal` \| `force` | |
| `details` | Detail[] | 具体条目 |
| `relations` | Item[] | 相关推荐 |
| `analysis` | Analysis[]? | 剧集总表 |

`Detail`：`type`、`url`、`fullUrl`、`title`、`quality`、`comments: Comment[]`、`uniqueKey`。

- `url` = 展示地址（缩略图或直链）；`fullUrl` = 原图地址。
- 阅读器优先用 `fullUrl`，失败或为空时回落 `url`。

`Comment`：`datetime`、`comment`、`image?`。

### 3.5 图集、文本与系列

| 对象 | 字段 |
|---|---|
| `CContent` | `title`、`url`、`isCurrent`、`time?`（章节时间，可选，UI 可忽略） |
| `CComic` | `url`、`info?`（**解图参数**，传给 `adapterImageCode` 的 `extra`）、`extra?`（**该图的评论数组** `Comment[]`） |
| `Analysis` | `url`、`title`、`total: number` |
| `AnalysisDetail` | `url`、`title` |
| `AnalysisVideoDetail` | `type`（`mp4`/`m3u8`）、`url`、`name`、`description` |
| `TextContent` | `title`、`blocks?: TextBlock[]`、`html?: string`（引擎清洗后转 `blocks`）、`prevUrl?`、`nextUrl?` |
| `TextBlock` | `type`（`p` / `h2` / `h3` / `quote` / `image`）、`text?`、`src?` |
| `ReadingProgress` | `itemUrl`、`kind`（`text`/`image`/`video`）、`chapterUrl?`、`chapterIndex`、`chapterCount`、`chapterOffset`、`positionMs`、`durationMs`、`percent`（0–100）、`updatedAt` |

## 4. 接口清单

| # | 路径 | 参数 | 返回 | 说明 |
|---|---|---|---|---|
| 1 | `/listConfigUnits` | — | `ConfigUnit[]` | 所有配置单元 |
| 2 | `/getConfigUnit` | `configKey` | `ConfigUnit` | 单个配置单元 |
| 3 | `/getConfigScript` | `configKey` | `string` | 配置脚本源码（导出用） |
| 4 | `/setCurrentConfig` | `configKey` | 空 | 切换当前配置 |
| 5 | `/getPage` | `configKey` | `Page` | 列表页 |
| 6 | `/loadPage` | `url` | `Page` | 指定 URL 加载列表页（分页、标签、搜索都用它） |
| 7 | `/getDetailPage` | `item[...]` | `DetailInfo` | 详情页 |
| 8 | `/isCollect` | `item[...]` | `true` / `false` | 是否已收藏 |
| 9 | `/saveCollect` | `item[...]` | 空 | 收藏 |
| 10 | `/cancelCollect` | `item[...]` | 空 | 取消收藏 |
| 11 | `/listCollect` | `configKey` | `Entity[]` | 收藏列表（见 §5 包装格式） |
| 12 | `/listHistory` | — | `Entity[]` | 观看历史 |
| 13 | `/search` | `search`、`item[...]` | **JSON 字符串** | 返回搜索结果页地址（注意是字符串，不是对象） |
| 14 | `/searchRecommend` | `search` | `string[]` | 搜索联想 |
| 15 | `/deleteSearch` | `searchValue` | 空 | 删除一条搜索历史 |
| 16 | `/getContent` | `url` | `CContent[]` | 章节/图集目录（`text` 与 `comic` 共用） |
| 17 | `/getComicIImages` | `url` | `CComic[]` | 图集图片组（注意路径双 `I`） |
| 18 | `/getTextContent` | `url` | `TextContent` | 文本正文（规范化段落块） |
| 19 | `/getReadingProgress` | `itemUrl` | `ReadingProgress` \| `null` | 读取进度（三种媒介通用） |
| 20 | `/updateReadingProgress` | `itemUrl`、`kind`、`chapterUrl?`、`chapterIndex?`、`chapterOffset?`、`positionMs?`、`percent?` | 空 | 上报进度（幂等 upsert） |
| 21 | `/getAnalysisDetail` | `item[...]` | `AnalysisDetail[]` | 分集列表 |
| 22 | `/getAnalysisVideoDetail` | `item[...]` | `AnalysisVideoDetail[]` | 分集播放源 |
| 23 | `/getImage` | `url` | **base64 字符串** | 图片（见 §6） |
| 24 | `/videoProxy` | `url` | 视频流 | 视频代理（见 §7） |
| 25 | `/getHtml` | `url` | `string` | 原始 HTML |
| 26 | `/clearCurrentUrl` | — | 空 | 重置当前列表地址 |
| 27 | `/cacheSuffixClean` | `fileSuffix` | 空 | 清理缓存（`html` / `image`） |

### 4.1 顺序要求（隐含状态机）

`/getPage` `/loadPage` `/getDetailPage` `/getContent` `/getComicIImages` `/getAnalysisDetail` 依赖引擎的"当前配置"与"当前列表地址"状态，因此：

- 调这些接口前必须先 `/getPage` 或 `/setCurrentConfig`；
- 并发调用同一配置的列表接口会产生状态竞争，客户端必须串行。

## 5. 列表类响应的包装格式

`/listCollect` 与 `/listHistory` 返回**数据库实体数组**，`value` 字段是 **Item 的 JSON 字符串**（需要二次 `JSON.parse`）：

```json
[
  {
    "id": 12,
    "type": "demo",
    "url": "https://…/item-page/",
    "value": "{\"jumpUrl\":\"…\",\"title\":\"…\",\"type\":\"image\",\"tags\":[…]}",
    "count": 0,
    "createTime": "2026-10-19T10:00:00.000Z"
  }
]
```

客户端解析顺序：`响应[] → 取 .value → JSON.parse → Item`。

## 6. `/getImage`

返回**图片的 base64 字符串**（不是 JSON 对象，但被 `JSON.stringify` 包了一层引号）。

客户端需做归一化（不同来源返回格式不一致）：

1. 去掉首尾空白；
2. 若是 `"...."`（带 JSON 引号）先解包；
3. 若含 `data:image/...;base64,` 前缀，去掉前缀；
4. 若含 `base64` 字面量残留，从其后开始取；
5. 去掉内部所有空白字符；
6. `base64Decode` 得到字节。

**防盗链/JS 解图**：当图片 URL 带 `__ghs_adapter_image_extra` 查询参数时，服务端会用 `adapterImageCode` 在无头窗口解出真图。客户端必须**原样透传该参数**，不得清理未知 query。

> Android 内置引擎**不采用 base64 中转**（内存与体积双输），改为直连 + 磁盘缓存，但对上层暴露同一 `imageUrl()` 语义。见设计文档 §8.6。

## 7. `/videoProxy`

| 项 | 说明 |
|---|---|
| 用途 | 转发视频/图片流，绕过 Referer 与跨域限制 |
| 请求头转发白名单 | `connection` `pragma` `cache-control` `user-agent` `accept-encoding` `accept` `accept-language` `cookie` `range` |
| 响应头透传白名单 | `accept-ranges` `access-control-allow-origin` `age` `cache-control` `cf-cache-status` `cf-ray` `content-length` `content-range` `content-type` `date` `etag` `last-modified` `nel` `pragma` `report-to` `server` `vary` `x-cache` `x-content-type-options` `x-frame-options` `x-powered-by` `x-xss-protection` |
| CORS | `Access-Control-Allow-Origin: *` |
| Range | 必须支持（Seek 依赖） |
| **m3u8 重写** | 若 URL 或 `content-type` 命中 `m3u8`/`mpegurl`：把整个播放列表按行读取，`URI="..."` 内的密钥/子播放列表地址、以及所有非 `#` 开头的分片行，**全部改写为 `/videoProxy?url=<absolute>`** 后再返回 |

m3u8 重写规则（必须严格按以下规范实现，否则加密流会失败）：

- 相对地址先按播放列表自身 URL 解析为绝对地址；
- `#EXT-X-KEY:URI="…"` 的 URI 也要改写；
- 返回 `Content-Type: application/vnd.apple.mpegurl`，`Cache-Control: no-cache`；
- 重写后的内容长度需重新计算。

## 8. 进程内调用与 `/engine` 出口的差异

| 项 | 进程内调用（默认） | `/engine` 出口（调试/工具） |
|---|---|---|
| 传输 | 方法调用 | HTTP GET |
| 参数编码 | 直接传对象 | qs 嵌套编码（§2） |
| 图片 | 直连 + 磁盘缓存 | `getImage` 返回 base64，便于外部工具消费 |
| 视频 | 本机回环代理 | 同一回环代理地址 |
| 日志 | 进程内 `SharedFlow` | 同一事件总线；不提供 WebSocket |
| 存储 | 本机 Room | 同左 |
| 状态机 | `EngineSession` 持有 | 同左 |

**对上层要求**：UI 只依赖 `GhsRepository`；`/engine` 只是同一份能力的 HTTP 投影，**不得**因此出现只为出口服务的分支逻辑。

## 9. 契约变更规则

1. 新增/修改接口或字段，**必须同时**更新本文件、`design.md` 相关章节、`CHANGELOG.md`。
2. 向后兼容优先：新增字段可选项；删除字段需标注版本并保留至少一个大版本。
3. 不允许客户端"猜"字段：文档没写的字段视为不稳定，不得依赖。
4. 本契约的生产者是**本项目**：实现与文档不一致时，先判断哪一方更符合产品意图，再同步修正另一方，不允许长期存在偏差。

## 10. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-10-19 | 从 `design.md` v0.1 §12.2 的映射表升级为独立契约文档，补齐参数编码、领域对象、响应包装、图片/视频细则、双模式差异 |
| v0.2 | 2026-10-19 | 内化为本项目独占契约：删除外部项目引用；标题改为「数据层契约」；响应头白名单改为完整自述清单；示例站点标识改为中性值 |
| v0.3 | 2026-10-19 | 术语对齐配置驱动定位：`WebConfig`→`ConfigUnit`、`webKey`→`configKey`；新增 `/getConfigScript`；接口表重排为 27 项；字段级定义统一指向配置规范 |
| v0.4 | 2026-10-19 | 新增文本内容类型：`detailType = text`、`/getTextContent`（`TextContent`/`TextBlock`）；进度统一为 `ReadingProgress`，`/getCurrentContentUrl`、`/getSeriesCurrentContent`、`/updateCurrentComic` 收敛为 `/getReadingProgress`、`/updateReadingProgress`；补注本机 `/engine` 前缀映射 |
| v0.5 | 2026-10-19 | 契约定性改为「内核 API」：删除远程服务模式表述，§8 改为「进程内调用与 `/engine` 出口的差异」；`TextContent` 增加可选 `html` |
| v0.6 | 2026-10-19 | 明确 `Tag.url` 为空表示占位/分隔（不得丢弃）、`Detail.url` 与 `fullUrl` 的展示/原图语义 |
| v0.7 | 2026-10-19 | 补 `Item.author`、`CComic.info`（解图参数，区别于 `extra` 评论）、`CContent.time`（可选） |
