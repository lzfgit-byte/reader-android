> **状态**：draft
> **版本**：v1.4
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v1.3

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
| `key` | string | **是** | — | 唯一标识，正则 `^[a-z0-9][a-z0-9_-]{1,31}$`；一经使用不建议修改（收藏/历史按它分组） |
| `name` | string | **是** | — | 展示名，1–24 字符 |
| `version` | string | 否 | `1.0.0` | 配置自身版本（语义版本），用于导入时提示升级 |
| `homeUrl` | string | **是** | — | 列表首页地址；**同时是相对 URL 的解析基准** |
| `favicon` | string | 否 | 空 | 图标地址；支持 `data:` 内联图 |
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
| `readerMode` | enum | 否 | `vertical` | 多图阅读器默认模式：`vertical`（连续滚动）/ `horizontal`（分页）/ `auto`（按图片宽高比自动选） |
| `textMode` | enum | 否 | `scroll` | 文本阅读器默认模式：`scroll`（滚动）/ `paged`（分页） |
| `comicImgMaxWidth` | string | 否 | `1080px` | 图集单图解码宽度上限 |
| `setTags` | string[] | 否 | `["收藏","历史","系统配置"]` | 启用哪些入口，见 §3.4 |
| `themeColor` | string | 否 | 空 | 主题强调色（`#RRGGBB`），为空则用应用默认色 |

### 3.3 `behavior`（行为参数）

| 字段 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `historyRemember` | integer | 否 | `100` | 观看历史最多保留条数，超出丢弃最旧 |
| `requestTimeoutMs` | integer | 否 | `20000` | 单次取页超时，范围 3000–120000 |
| `concurrency` | integer | 否 | `2` | 同来源并发请求数，范围 1–5 |
| `retry` | integer | 否 | `1` | 失败重试次数，范围 0–3 |
| `cacheHtml` | boolean | 否 | `true` | 是否缓存页面 HTML |
| `useWebView` | boolean | 否 | `false` | 强制走无头浏览器通道（JS 渲染页面/需要浏览器环境时） |
| `headers` | object | 否 | `{}` | 附加请求头，如 `{"Referer": "https://example.com/"}`；`User-Agent` 可覆盖 |
| `challengeMarkers` | string[] | 否 | `[]` | 追加的人机校验特征（HTML 关键词），与内置特征合并后判定；命中即触发顶层校验窗口（§7、设计文档 §8.14） |

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

---

## 4. 脚本规范

脚本必须是一个**返回配置对象的表达式**，由引擎执行后得到含方法的对象。

### 4.1 必需方法

| 方法 | 签名 | 调用时机 | 返回值 |
|---|---|---|---|
| `getItems` | `($) => Item[]` | 列表页载入完成后 | 条目数组，见 [rest-contract](rest-contract.md) §3.2 |
| `getPagination` | `($) => Pagination[]` | 同一次列表页载入 | 分页数组，可返回 `[]` |
| `getTags` | `($) => Tag[]` | 同一次列表页载入 | 标签数组，可返回 `[]` |
| `getDetailInfo` | `(item, engine) => Promise<DetailInfo>` | 用户点击条目 | 详情，决定跳哪个渲染器 |
| `adapterLoadUrl` | `(url, urlReplaces) => string` | 翻页/排序 | 最终请求地址 |
| `adapterSearchUrl` | `(keyword) => string` | 用户提交搜索 | 搜索结果页地址 |

### 4.2 可选方法

| 方法 | 签名 | 未实现时的表现 |
|---|---|---|
| `getContents` | `(url, engine) => Promise<CContent[]>` | 图集目录为空，阅读器退化为单组图 |
| `getComicImages` | `(url, engine) => Promise<CComic[]>` | 该来源不支持图集阅读 |
| `getAnalysisDetail` | `(item, engine) => Promise<AnalysisDetail[]>` | 系列面板不显示分集 |
| `getAnalysisVideoDetail` | `(item, engine) => Promise<AnalysisVideoDetail[]>` | 分集无播放源 |
| `adapterRemoteSearch` | `(keyword, engine) => Promise<string[]>` | 搜索无联想词 |
| `adapterGetHtml` | `(url) => Promise<string>` | 走默认取页管线 |
| `getTextContent` | `(url, engine) => Promise<TextContent>` | 该来源不支持文本阅读（`detailType = text` 时无正文，报 `E-CFG-021`） |
| `adapterImageCode` | string（JS 片段） | 图片按原地址直取 |

### 4.3 脚本约束

| 约束 | 值 | 违反后果 |
|---|---|---|
| 单次执行超时 | 10s（取页另计，最长 120s） | 中止并报 `E-CFG-020` |
| 内存 | 单次脚本分配上限 32MB | 中止并报 `E-CFG-022` |
| 全局副作用 | 禁止修改原型链、禁止注册持久定时器 | 沙箱拦截 |
| 外部依赖 | 不可 `require`/`import` | 沙箱拦截 |
| 网络 | 只能经 `getHtml` / `net.request` / `request_string_get` | 直接网络调用不可用 |

---

## 5. 返回值契约

字段级定义以 [数据层契约](rest-contract.md) §3 为准，本节只说明**脚本侧的义务**：

1. **`jumpUrl` 必须存在且稳定**（收藏/历史/进度都以它为键）；缺失该项的条目会被丢弃并记 `warn`。
2. `title`、`coverImg` 缺失时以空串补齐，条目仍然保留。
3. **相对 URL 一律可以返回**，引擎会补全（规则见 §5.1）。
4. `type` 缺失时按 `image` 处理；`renderType` 缺失时按 `normal` 处理。
5. 数组类返回值必须是数组，返回 `null`/`undefined` 视为 `[]` 并记 `warn`。
6. `getDetailInfo` 返回的 `detailType` 必须是 §5.2 之一，否则报 `E-CFG-021`。
7. `getTextContent` 可返回 `{ blocks }`、`{ html }` 或两者（`blocks` 优先）。给 `html` 时引擎按设计文档 §8.15 清洗并转为结构块；**UI 永远只收到结构块**。

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
| `E-CFG-005` | 必需方法缺失 | 6 个必需方法任一不存在 | 指出方法名 |
| `E-CFG-006` | 字段类型错误 | 类型与 §3 不符（如 `imgWidth` 传数字对象） | 指出字段路径 |
| `E-CFG-007` | 枚举值非法 | `cardStyle`/`readerMode`/`setTags` 取值不在白名单 | 列出合法值 |
| `E-CFG-008` | 数值越界 | `concurrency` 超出 1–5 等 | 显示范围 |
| `E-CFG-010` | 体积超限 | 单配置 > 1MB | — |
| `E-CFG-011` | 版本不兼容 | `requiresApi` > 引擎支持版本 | 提示升级 App |
| `E-CFG-020` | 脚本执行超时 | 超过 10s | — |
| `E-CFG-021` | 返回值不符合模型 | `detailType` 非法、`getItems` 非数组 | 指出字段 |
| `E-CFG-022` | 内存超限 | 单次脚本分配 > 32MB | — |
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
| `$` | `(selector) => Selection` | 选择器，语义见 [design.md §8.4](../01-product/design.md) |
| `parse` | `(html) => Selection` | 把 HTML 字符串解析为选择器根，供二次取页后解析 |
| `getHtml` | `(url, forceWebView?) => Promise<string>` | 取 HTML，含自动兜底 |
| `getHtmlWithProcess` | `(url) => Promise<string>` | 带进度上报的取 HTML |
| `net.request` | `(options) => Promise<Response>` | 通用请求：`url`/`method`/`headers`/`body` |
| `request_string_get` | `(url) => Promise<string>` | 原始文本 GET |
| `hashString` | `(input) => string` | 稳定哈希，用于缓存键 |
| `helpElAttr` | `(el, name) => string` | 取属性 |
| `helpElText` | `(el) => string` | 取文本 |
| `ElementAttr` | object | 常见属性名常量 |
| `ElementTypes` | object | 常见标签名常量 |
| `LogMsgUtil` | `log(...args)` | 写运行日志（进日志页） |
| `ConsoleLogUtil` | `log(...args)` | 兼容 `console` 用法 |
| `NotifyMsgUtil` | `sendNotifyMsg(title, msg, key)` / `close(key)` | 状态胶囊通知 |
| `ProgressMsgUtil` | `sendProgress(pct, key, title?)` / `close(key)` | 进度上报 |
| `MessageUtil` | `info/error/success/warning(msg)` | 轻提示 |
| `eventEmitter` | `on/off/emit` | 脚本内事件总线 |
| `executeJs` | `(url, code, show?) => Promise<any>` | 无头执行脚本 |
| `getCurrentItems` | `() => Item[]` | 取当前列表已解析条目 |

`requiresApi: 1` 表示依赖上表全部内容；后续新增 API 时递增，旧配置不受影响。

`getDetailInfo` / `getContents` 等方法的第二个参数 `engine` 暴露上表全部能力（如 `engine.getHtml`、`engine.parse`、`engine.$` 指向当前已载入文档）。

---

## 9. 版本与兼容

| 变化类型 | 是否兼容 | 处理 |
|---|---|---|
| 新增可选字段 | 兼容 | 升级次版本号 |
| 新增可选方法 / 新增 API | 兼容（递增 `requiresApi`） | 配置可不改 |
| 修改字段语义、删除字段 | **不兼容** | 必须新增 ADR，并提供迁移说明 |
| 修改 `detailType` 或 `setTags` 取值 | **不兼容** | 同上 |

引擎对不兼容配置的行为：拒绝加载并报 `E-CFG-011`，**不做静默降级**。

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
