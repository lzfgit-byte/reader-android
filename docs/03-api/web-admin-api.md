> **状态**：draft
> **版本**：v1.3
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v1.2

# 内置 Web 管理服务 API

**本文件是"应用内置 HTTP 服务"的唯一权威来源（SSOT）**：启动与访问方式、命名空间、管理 API、调试 API、WebDAV API、错误模型与安全边界。

- 界面怎么实现 → [web-console.md](../02-architecture/web-console.md)
- 配置格式 → [config-spec.md](config-spec.md)
- 引擎接口 → [rest-contract.md](rest-contract.md)（本服务以 `/engine` 前缀代理一份）

---

## 1. 定位

应用在设备上启动一个**内置 HTTP 服务**，同时承担三种角色：

| 角色 | 命名空间 | 使用者 |
|---|---|---|
| **Web 管理界面** | `/console/**` | 浏览器（同机或局域网） |
| **管理 API** | `/api/**` | Web 管理界面、外部脚本 |
| **引擎 API** | `/engine/**` | 外部工具、自动化回归（映射 [rest-contract](rest-contract.md) 的 27 个接口） |

设计意图：**编辑面在浏览器，运行面在设备**。手机屏幕不适合写代码与看 JSON，但设备握着真实网络环境与引擎，因此把编辑器搬到浏览器，把执行留在设备。

---

## 2. 启动与访问

| 项 | 默认 | 说明 |
|---|---|---|
| 开关 | 关闭 | 设置 →「Web 管理」手动开启；开启后显示地址 |
| 绑定地址 | `0.0.0.0` | 可选「仅本机」`127.0.0.1`（默认更安全，局域网编辑需显式切换） |
| 端口 | `18600` | 可改；被占用时自动顺延并提示实际端口 |
| 访问 URL | `http://<设备IP>:18600/console/` | 设置页展示局域网地址 + **二维码** |
| 鉴权 | 无（内网信任） | 不校验令牌：服务只应在本机或可信局域网内开启。设置页按**实际监听范围**显示地址：仅本机时给 `127.0.0.1`，开启局域网访问后才列出局域网 IP，均可点击复制 |
| 空闲超时 | 关闭时释放 | 服务停止即释放端口；无请求 30 分钟可配置自动停止 |

> **以上全部是应用端设置**：服务开关、绑定地址、端口、令牌重置只在「设置 → Web 管理」中调整，**本 API 不提供任何修改这些参数的接口**。理由：网页端是"连上之后"才存在的，不能让连不上的人在网页里改连接参数。**网络代理**（HTTP 总开关、代理列表）同属应用级设置，网页端不可配置；来源是否走代理写在配置的 `isProxy` / `forceProxy` 里。归属规则见 [design.md](../01-product/design.md) §2.6。

访问方式：

```
http://192.168.1.20:18600/console/                ← 直接用局域网地址访问
（无鉴权头；API 与页面同源）
```

- 首次带 `t` 访问后，服务端写 `HttpOnly` Cookie，后续页面与 API 走同源 Cookie；
- 服务开启后，局域网内任何设备都可访问；关闭服务即断开；
- 因此**不要**在不可信网络开启「允许局域网访问」。

---

## 3. 命名空间与鉴权矩阵

| 前缀 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/console/**` | GET | Cookie 或 `?t=` | 静态资源（SPA），未鉴权时返回引导页要求扫码 |
| `/api/**` | 任意 | 无 | 管理与调试 |
| `/engine/**` | GET | 无 | 引擎接口代理，默认关闭 |
| `/healthz` | GET | 无 | 存活探测（不含敏感信息） |

统一约定：

- 请求体上限 **10MB**（调试时可粘贴大段 HTML）；
- 响应 `Content-Type: application/json; charset=utf-8`（静态资源除外）；
- 所有时间字段为 ISO-8601 UTC 字符串。

---

## 4. 管理 API

### 4.1 配置 CRUD

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/configs` | 列表（不含脚本源码） |
| GET | `/api/configs/{key}` | 单个配置（含 `config`、`format`、`enabled`、`sort`） |
| GET | `/api/configs/{key}/script` | 脚本源码（纯文本） |
| POST | `/api/configs` | 新建；body：`{ format, config, script }`；**先校验后落库** |
| PUT | `/api/configs/{key}`（按请求体 `enabled` 落库） | 保存；同上；`key` 不可改（改名走 duplicate + delete） |
| PATCH | `/api/configs/{key}` | 局部更新：`{ enabled?, sort? }` |
| DELETE | `/api/configs/{key}` | 删除；若为当前配置则自动切换到第一个启用项 |
| POST | `/api/configs/{key}/duplicate` | 复制为新 key（body 可指定新 key） |
| POST | `/api/configs/order` | 批量排序：`{ keys: [...] }` |

### 4.2 校验（不落库）

```
POST /api/validate
{
  "format": "manifest",
  "config": { "key": "demo", "name": "示例", "homeUrl": "https://example.com/list", "render": {...}, "behavior": {...} },
  "script": "({ getItems($){...}, ... })"
}
```

响应：

```json
{
  "ok": false,
  "errors": [
    { "code": "E-CFG-007", "path": "render.cardStyle", "message": "取值非法，允许 cover|cover-title|list", "hint": "cover-title" },
    { "code": "E-CFG-005", "path": "script", "message": "缺少必需方法 getTags" }
  ],
  "warnings": [
    { "path": "render.imgHeight", "message": "未设置，将按默认 147px" }
  ]
}
```

- 校验分两级：静态（字段）与语义（脚本方法、可解析性），返回**全部**错误而非首个；
- `ok=true` 才允许保存；`warnings` 不阻塞保存。

### 4.3 导入导出

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/configs/export` | 整包导出：`{version, exportedAt, configs:[{key,name,enabled,sort,source}]}`，可直接回灌导入 |
| GET | `/api/configs/{key}/raw` | 单份脚本原文（`text/plain` + `Content-Disposition`，供浏览器直接下载） |
| POST | `/api/configs/import` | `{config}` 单份（key 取自脚本内 `key:`，已存在则覆盖）或 `{configs:[...]}` 整包；返回 `{imported, keys}` |

> 覆盖导入保留原有的 `enabled` / `sort`。导入与其他 `/api` 一样不校验令牌（内网信任模型）。
> 尚未实现：按 `format=manifest|inline` 指定导出格式、`import-bundle` 的逐条结果——当前统一按脚本原文处理。

#### 收藏导入导出（F07）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/favorites/export` | 导出全部站点的收藏：`{version, exportedAt, items:[{configKey,url,payload,count,createTime}]}`；`url` 为相对地址，`payload` 为条目原 JSON |
| POST | `/api/favorites/import` | 请求体同上（也接受 `favorites` 或裸数组）；按 `configKey+url` **覆盖**写入，返回 `{imported}` |

'> 写成幂等覆盖而非追加：同一收藏重复导入不会产生重复行，便于多设备互相同步。
> 提供了 `count` 时按该值原样写入（不累加），用于迁移「看过多少次」；未提供则按一次收藏处理。'


### 4.4 运行时控制

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/state` | 当前配置、引擎状态（Idle/Loading/Parsing/Error） |
| POST | `/api/state/current` | `{ key }` 切换当前配置 |
| POST | `/api/cache/clear` | `{ suffix?: "html"\|"image" }` |
| GET | `/api/logs?level=&limit=` | 最近运行日志 |
| GET | `/api/meta` | 应用版本、引擎版本、脚本 API 版本、服务端口 |

---

## 5. 调试 API

调试台的唯一目标：**验证功能与数据交互是否正确**，因此**只返回 JSON，不渲染图片/视频/网页**。

### 5.1 执行配置方法

```
POST /api/debug/run
{
  "target": { "key": "demo" } | { "draft": { "format": "manifest", "config": {...}, "script": "..." } },
  "entry": "getItems",
  "args": {
    "url": "https://example.com/list",     // getItems 等列表方法可用
    "html": "<html>…",                     // 可选：直接用粘贴的 HTML，跳过抓取
    "item": { "jumpUrl": "…", "title": "…", "coverImg": "…", "type": "image", "renderType": "normal", "tags": [] },
    "keyword": "test"
  },
  "options": { "useWebView": false, "timeoutMs": 20000, "includeRawHtml": false,
               "waitForChallenge": true, "challengeTimeoutMs": 300000 }
}
```

支持的 `entry`：

| entry | 说明 | 必需 args |
|---|---|---|
| `getItems` | 列表条目 | `url` 或 `html` |
| `getPagination` | 分页 | `url` 或 `html` |
| `getTags` | 标签 | `url` 或 `html` |
| `getDetailInfo` | 详情 | `item` |
| `getContents` | 图集目录 | `url` |
| `getComicImages` | 图集图片 | `url` |
| `getAnalysisDetail` | 分集 | `item` |
| `getAnalysisVideoDetail` | 播放源 | `item` |
| `adapterSearchUrl` | 搜索地址 | `keyword` |
| `adapterLoadUrl` | 翻页地址 | `url` |

响应：

```json
{
  "ok": true,
  "entry": "getItems",
  "durationMs": 812,
  "data": [ { "jumpUrl": "…", "title": "…", "coverImg": "…", "type": "image", "renderType": "normal", "tags": [] } ],
  "stats": { "count": 24, "fetchMs": 640, "parseMs": 40, "scriptMs": 132, "usedWebView": false, "cacheHit": false },
  "urls": [ { "field": "coverImg", "raw": "/img/1.jpg", "resolved": "https://example.com/img/1.jpg" } ],
  "logs": [ { "level": "info", "at": "…", "message": "页面加载：url> https://example.com/list" } ],
  "error": null
}
```

失败时：

```json
{
  "ok": false,
  "durationMs": 30010,
  "error": {
    "code": "E-CFG-020",
    "message": "脚本执行超时（10s）",
    "path": "script.getItems",
    "hint": "检查是否有阻塞循环或未 await 的 Promise",
    "stack": "at getItems (eval:3:12)"
  },
  "logs": [ … ]
}
```

### 5.2 只取页（不解析）

```
POST /api/debug/fetch
{ "url": "https://example.com/list", "useWebView": false, "includeRawHtml": false }

→ { "ok": true, "status": 200, "bytes": 184320, "ms": 640, "cacheHit": false,
    "usedWebView": false, "title": "…", "htmlPreview": "<html>…前 4KB…" }
```

用途：先确认"能不能拿到页面"，再确认"解析得对不对"，把问题定位到取页还是脚本。

### 5.3 实时事件流

```
GET /api/debug/events            (Server-Sent Events)
```

事件类型：

| 事件 | 载荷 | 用途 |
|---|---|---|
| `log` | `{ level, message, at }` | 脚本日志、引擎日志 |
| `progress` | `{ key, percentage, title }` | 抓取进度 |
| `step` | `{ phase, ms, detail }` | 取页 / 解析 / 脚本 各阶段耗时 |
| `challenge` | `{ host, sourceKey, waiting: true }` | **命中人机校验**，需要用户在设备上完成；页面应显示等待提示 |
| `challengeResolved` | `{ host, ok, ms, reason? }` | 校验结束（通过 / 取消 / 超时） |
| `done` | `{ ok, durationMs }` | 一次运行结束 |

调试台在运行期间订阅该流，结束后自动关闭连接。

### 5.4 调试的返回约定

1. **只返回领域模型 JSON**，不返回图片二进制、不返回视频地址的媒体流、不返回渲染后的 HTML（除非显式 `includeRawHtml=true`）；
2. **相对 URL 同时返回 `raw` 与 `resolved`**，让作者一眼看出补全是否正确；
3. `stats` 必含分段耗时，便于定位慢在取页还是脚本；
4. `logs` 返回本次运行产生的全部日志（最多 500 条）；
5. 失败必须带 `code` + `path` + `hint`，与 [config-spec](config-spec.md) §7 的错误码一致；
6. **命中人机校验时**：先推 `challenge` 事件，运行**不立即返回**（`waitForChallenge` 默认 `true`）；用户在设备完成后推 `challengeResolved` 并继续。设为 `false` 时立即返回 `E-CHALLENGE-001`，由页面自行决定是否重试。

---

## 6. 引擎 API（可选）

`/engine/**` 以 [rest-contract](rest-contract.md) 的同一套路径与参数对外暴露引擎能力：

```
GET /engine/getPage?configKey=demo
GET /engine/listConfigUnits
```

- 默认**关闭**，在设置 →「Web 管理」中单独开启；
- 用途：自动化回归脚本、外部工具、CI 冒烟；
- 与调试 API 的区别：`/engine` 走完整链路（含缓存与状态机），`/api/debug` 是一次性、无副作用（不写历史、不改当前配置）。

---

## 7. WebDAV API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/webdav/status` | 只读状态：是否已配置、远端目录、上次同步时间与结果、冲突列表 |
| POST | `/api/webdav/test` | 用**应用内已保存的配置**做连通性测试（请求体为空） |
| POST | `/api/webdav/pull` | 从远端拉取 → 逐条校验 → 应用 |
| POST | `/api/webdav/push` | 从本地推送 → 逐条 `PUT` |
| POST | `/api/webdav/resolve` | 提交冲突决策：`{ key, choice: "local" \| "remote" }` |

> **连接参数不在本 API 范围**：WebDAV 地址、账号、密码、远端目录、冲突策略与自动同步开关**只在应用端**「设置 → 同步」中配置。网页端只展示状态并触发动作，**任何响应都不返回凭证**（`GET /api/webdav/status` 只给 `configured: true/false` 与 `remoteDir`）。

同步语义：

- **单位**：一个配置文件 = 一个 `<key>.ghscfg.json`（格式见 [config-spec](config-spec.md) §2.4）；
- **方向**：`pull` / `push` / 双向（`strategy: "newer-wins" | "local-wins" | "remote-wins" | "ask"`）；
- **冲突**：比对 `updatedAt` 与 `ETag`；`ask` 策略下返回冲突列表，由页面对话框确认；
- **删除**：v1 不同步删除（避免误删远端），只在状态里提示"远端已不存在"；
- **凭证**：只在应用端设置与更新，存 `EncryptedSharedPreferences`；网页端不可读、不可写；
- **不走代理**：WebDAV 使用独立 `OkHttpClient` 且 `Proxy.NO_PROXY`，不受 §8.13 的代理与白名单影响；
- **失败**：单条失败不中断整批，逐条返回结果。

---

## 8. 错误模型与状态码

统一响应：

```json
{ "ok": false, "error": { "code": "E-API-004", "message": "…", "path": "…", "hint": "…" } }
```

| HTTP | 含义 | 典型 code |
|---|---|---|
| 200 | 成功 | — |
| 400 | 请求体非法 | `E-API-001` |
| 401 | 保留（当前不返回：无鉴权） | `E-API-002` |
| 404 | 配置或路径不存在 | `E-API-003` |
| 409 | 冲突（key 已存在、WebDAV 冲突） | `E-API-004` |
| 413 | 请求体超限 | `E-API-005` |
| 422 | 校验未通过（返回 `errors[]`） | 透传 `E-CFG-*` |
| 500 | 服务内部错误 | `E-API-500` |
| 503 | 引擎忙（正在执行其他任务） | `E-API-006` |

> 运行时中断（人机校验）不是 HTTP 错误：`/api/debug/run` 在 `waitForChallenge: true` 时挂起并推送事件；设为 `false` 时返回 `ok:false` 且 `error.code` 为 `E-CHALLENGE-*`（见 [config-spec](config-spec.md) §7）。

---

## 9. 安全边界

1. **默认仅本机**（`127.0.0.1`）；切到局域网需用户在设置页显式确认，并展示风险提示；
2. **无令牌**：请求不做鉴权（内网信任模型），服务默认只监听本机；
3. **静态资源白名单**：`/console` 只服务打包内的文件，禁止目录遍历与任意路径读取；
4. **不提供文件系统 API**：不能通过该服务读写设备任意文件；
5. **请求体与并发限制**：10MB / 4 并发 / 60s 单请求上限；
6. **脚本沙箱**：调试执行的脚本与正式运行同一沙箱（超时、内存、无文件系统）；
7. **CORS**：仅允许同源；如需跨源调试，需在设置页显式添加白名单，默认空；
8. **服务停止即释放**：关闭开关或应用退出后端口不再监听；
9. **凭证不出应用**：WebDAV 账号密码只存应用内的加密存储，网页端没有任何读取或写入通道；同步动作由服务端用已存凭证执行。

---

## 10. 版本与兼容

- API 版本固定在前缀外：`X-Ghs-Api-Version: 1`（响应头），路径不带版本号；
- 新增字段/接口为兼容变更；删除或改语义需提升版本并保留旧路径一个版本；
- `/console` 静态资源与 `/api` 同版本发布，不允许旧前端连新后端。

---

## 11. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 建立内置 Web 管理服务 API：三个命名空间、配置 CRUD、校验、导入导出、调试（run/fetch/events）、引擎代理、WebDAV、错误模型与安全边界 |
| v1.1 | 2026-10-19 | 明确配置面归属：端口/绑定/令牌等应用级设置不提供 API；WebDAV 仅保留状态与动作接口，**移除 `PUT /api/webdav`**（地址与凭证只在应用端配置）；新增 `POST /api/webdav/resolve`；安全边界增加「凭证不出应用」 |
| v1.2 | 2026-10-19 | 调试接口补人机校验：`options.waitForChallenge` / `challengeTimeoutMs`、事件 `challenge` 与 `challengeResolved`、`E-CHALLENGE-*` 说明；修正错误表重复行 |
| v1.3 | 2026-10-19 | WebDAV 明确**不走代理**（独立客户端 + `Proxy.NO_PROXY`）；`/engine` 定位补注为「本地调试出口」而非远程服务 |
