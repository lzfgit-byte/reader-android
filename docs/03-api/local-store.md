> **状态**：draft
> **版本**：v1.1
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v1.0

# 本地仓库规范（Local Store）

**本文件是"本地数据"的唯一权威来源（SSOT）**：数据分类、表结构、键与身份、导出格式、导入规则、WebDAV 同步、保留策略与版本迁移。

- 配置单元本身的格式 → [config-spec.md](config-spec.md)
- 引擎接口的字段语义 → [rest-contract.md](rest-contract.md) §3
- 为什么这样设计 → [ADR-0007](../02-architecture/adr/ADR-0007-local-store-unified-sync.md)

---

## 1. 定位与原则

**本地仓库是设备上唯一的运行时数据源。** 网络只是它的输入之一，同步只是它的副本机制。

| 编号 | 原则 | 含义 |
|---|---|---|
| L1 | **本地权威** | 运行时只读本地；任何远端（WebDAV）都是副本，冲突时按策略决策，不做隐式合并 |
| L2 | **一切可导出** | 用户在界面上能看到的数据，都必须能导出成一个文件再导回来 |
| L3 | **同步可关** | 关闭同步不影响任何功能；同步失败不影响使用 |
| L4 | **身份稳定** | 数据以 `itemUrl` / `key` 为主键；主键变化等于换了一条数据 |
| L5 | **可迁移** | 每个导出文件带 `$schema` 版本，导入时按版本链迁移 |
| L6 | **设备相关不冒充数据** | 端口、绑定地址、凭证这类"本机才成立"的设置，**不参与同步也不参与跨设备导入** |

---

## 2. 数据分类

| 类别 | 内容 | 存储 | 同步 | 导出 | 说明 |
|---|---|---|---|---|---|
| **配置** `configs` | 配置单元（清单 + 脚本） | Room `config_unit` | ✅ | ✅ | 格式见 [config-spec](config-spec.md) §2 |
| **偏好设置** `prefs` | 主题、字号、列表密度、阅读器默认模式、状态胶囊开关、历史保留条数 | DataStore | ✅ | ✅ | 跨设备有意义 |
| **设备设置** `device` | Web 服务开关/端口/绑定、WebDAV 地址与凭证、缓存上限、**代理（HTTP 地址/端口/认证/白名单）**、超时、并发 | DataStore + 加密存储 | ❌ | ⚠️ 导出但导入时忽略 | 见 L6；导出仅为备份本机配置 |
| **凭据与 Cookie** | WebDAV 密码、代理认证、站点 Cookie（含人机校验通过后的通行 Cookie） | EncryptedSharedPreferences + Room | ❌ | ❌ | 不导出、不同步；界面不可明文查看 |
| **阅读进度** `progress` | 文本章节与位置、图集页码、视频播放位置 | Room `reading_progress` | ✅ | ✅ | 见 §3.3 |
| **收藏** `collects` | 收藏条目 | Room `collect` | ✅ | ✅ | |
| **观看历史** `viewHistory` | 浏览过的条目 | Room `viewed_history` | ✅ | ✅ | 按 `historyRemember` 截断 |
| **搜索历史** `searchHistory` | 搜索词 | Room `search_history` | ❌ | ✅ | 含隐私倾向，默认不同步 |
| **运行日志** `logs` | 引擎与抓取日志 | Room `log` | ❌ | ❌ | 仅本机排查用，最多 2000 条 |

> **判据**：换一台设备仍然成立 → 同步；只在当前设备/网络成立 → 不同步。

---

## 3. 存储结构

```sql
-- 配置
config_unit(id, key UNIQUE, format TEXT, config_json TEXT, script TEXT,
            sort INT, enabled INT, config_version TEXT, createTime)

-- 偏好 + 设备设置（DataStore 的持久化镜像，便于导出/同步）
app_setting(scope TEXT /* prefs | device */, key TEXT, value TEXT, updatedAt,
            PRIMARY KEY(scope, key))

-- 阅读/观看进度（统一表，替代按媒介分表）
reading_progress(
    id INTEGER PRIMARY KEY,
    itemUrl TEXT UNIQUE,         -- 条目主键（归一化后）
    kind TEXT,                   -- text | image | video
    chapterUrl TEXT,             -- text/image 当前章节或图组
    chapterIndex INTEGER,        -- 章节序号（0 基）
    chapterCount INTEGER,        -- 章节总数，未知为 0
    chapterOffset INTEGER,       -- 章节内位置：段落索引 / 图片序号
    positionMs INTEGER,          -- video 播放位置
    durationMs INTEGER,          -- video 时长
    percent REAL,               -- 0–100 总体进度
    updatedAt INTEGER            -- 毫秒时间戳，冲突比较依据
)

-- 收藏
collect(id, configKey TEXT, url TEXT, value TEXT, count INT, createTime,
        UNIQUE(configKey, url))

-- 观看历史
viewed_history(id, configKey TEXT, url TEXT, value TEXT, createTime, UNIQUE(url))

-- 搜索历史
search_history(id, configKey TEXT, value TEXT, createTime, UNIQUE(configKey, value))

-- 日志（不导出、不同步）
log(id, info TEXT, type TEXT, createTime)
```

要点：

- **进度统一一张表**：文本、图集、视频共用 `reading_progress`，靠 `kind` 区分，`UNIQUE(itemUrl)` 保证一条内容一份进度；
- **设置镜像表** `app_setting` 是 DataStore 的可导出投影：写入设置时同步双写，导出/同步读它，避免 DataStore 序列化格式外泄；
- `value` 仍存条目 JSON 字符串（与契约一致），便于跨端互导。

### 3.3 进度语义

| kind | `chapterUrl` | `chapterIndex` | `chapterOffset` | `positionMs` |
|---|---|---|---|---|
| `text` | 当前章节地址 | 章节序号 | 章节内段落索引 | — |
| `image` | 当前图组地址 | 图组序号 | 图片序号 | — |
| `video` | — | 播放源序号 | — | 播放位置 |

- `percent` 由 `(chapterIndex + 章节内比例) / chapterCount` 估算，`chapterCount = 0` 时退化为章节内比例；
- **新内容（无进度记录）视为 chapterIndex = 0**，即第一条/第一章，读取时按此默认。

---

## 4. 键与身份

| 数据 | 主键 | 归一化规则 |
|---|---|---|
| 配置 | `config_unit.key` | 大小写敏感，不可变 |
| 进度 | `itemUrl` | 去掉 `#fragment`；去掉跟踪参数（`utm_*`、`from`、`spm`）；保留其余 query；结尾 `/` 归一为无 `/` |
| 收藏 | `(configKey, url)` | 同上 |
| 历史 | `url` | 同上 |
| 搜索 | `(configKey, value)` | 去首尾空白、转小写比较 |

归一化由 `UrlNormalizer` 统一实现并有单测；**导出时保留归一化后的值**，避免跨设备再次归一产生分歧。

---

## 5. 导出格式

### 5.1 单文件 bundle（默认）

文件名：`ghs-repo-<yyyyMMdd-HHmm>.bundle.json`

```json
{
  "$schema": "ghs-repo/1",
  "appVersion": "1.0.0",
  "exportedAt": "2026-10-19T10:00:00Z",
  "sections": {
    "configs": [ { "key": "demo", "format": "manifest", "config": {…}, "script": "…", "sort": 0, "enabled": true } ],
    "prefs":   { "theme": "system", "fontScale": 1.0, "readerMode": "vertical", "historyRemember": 100 },
    "progress":[ { "itemUrl": "…", "kind": "text", "chapterIndex": 3, "chapterOffset": 42, "percent": 12.5, "updatedAt": 1760000000000 } ],
    "collects":[ { "configKey": "demo", "url": "…", "value": "{…}", "count": 0 } ],
    "viewHistory":[ … ],
    "searchHistory":[ … ]
  },
  "device": { "webPort": 18600, "bind": "loopback", "cacheImageMb": 512 }
}
```

- `device` 段导出但**导入时默认忽略**（可勾选"同时恢复本机设置"）；
- `searchHistory` 默认不导出，需在导出对话框勾选；
- 不含 WebDAV 凭证与任何 token。

### 5.2 分层目录（供 WebDAV / 人工维护）

```
repo/
├── repo.json                 # $schema、版本、各分片 updatedAt 与 hash、索引
├── prefs.json
├── progress.json
├── collects.json
├── view-history.json
└── configs/
    └── <key>.ghscfg.json     # 与 config-spec §2.4 完全一致
```

- 配置沿用单配置文件格式，便于与"只导出一个配置"互换；
- `repo.json` 的作用是**清单与冲突判定依据**（每个分片的 `updatedAt` + `hash`）。

---

## 6. 导入规则

| 模式 | 行为 |
|---|---|
| `dry-run` | 只解析与校验，返回将发生的变化，不落库 |
| `merge`（默认） | 按主键 upsert；冲突按 §6.2 |
| `replace` | 清空对应类别后导入（危险操作，需二次确认） |

### 6.2 冲突判定

| 类别 | 冲突规则 |
|---|---|
| `configs` | 同 key 时比较 `updatedAt`；相同则保留本地；导入前必须过 [config-spec](config-spec.md) §7 校验 |
| `prefs` | 字段级覆盖（新文件优先） |
| `progress` | `updatedAt` 较新者胜；相同则保留本地 |
| `collects` | 按 `(configKey, url)` 去重，`count` 取两者较大值 |
| `viewHistory` | 按 `url` 去重，`createTime` 较新者胜 |
| `searchHistory` | 按 `(configKey, value)` 去重 |

### 6.3 导入报告

```json
{
  "ok": true,
  "sections": {
    "configs":  { "added": 2, "updated": 1, "skipped": 0, "failed": 1 },
    "progress": { "added": 8, "updated": 3, "skipped": 0, "failed": 0 }
  },
  "failures": [ { "section": "configs", "key": "bad-one", "code": "E-CFG-005", "message": "缺少必需方法 getTags" } ]
}
```

- 单条失败不中断整批；
- 失败项原样保留在导入报告里，便于用户修正后重试。

---

## 7. WebDAV 同步

**连接参数只在应用端配置**（见 [design.md](../01-product/design.md) §2.6），凭证永不出应用。

| 项 | 约定 |
|---|---|
| 单位 | §5.2 的分层目录：`repo.json` + 各分片 |
| 方向 | `pull` / `push` / 双向 |
| 冲突 | 分类级策略：`prefs` 与 `progress` 支持 `newer-wins`；`configs` 支持 `ask`；`collects` 恒为并集 |
| 设备设置 | 不参与同步 |
| 搜索历史 | 默认不同步，可在应用端开启 |
| **代理** | WebDAV **不走代理**，固定直连（独立客户端 + `Proxy.NO_PROXY`） |
| 删除传播 | 不做（避免跨设备误删） |
| 部分成功 | 单分片失败不回滚其他分片，状态里标注哪些分片落后 |

同步流程：

```
① 读取远端 repo.json
② 比较各分片 updatedAt + hash
③ 需要拉取的分片 → GET → 解析 → 按 §6.2 判定 → 落库
④ 需要推送的分片 → 序列化 → PUT → 回写 repo.json
⑤ 更新本地同步状态（时间、结果、落后分片）
```

---

## 8. 保留与清理

| 数据 | 保留策略 |
|---|---|
| 观看历史 | 每配置最多 `historyRemember` 条（默认 100） |
| 搜索历史 | 每配置最多 50 条 |
| 运行日志 | 最多 2000 条，滚动淘汰 |
| 阅读进度 | 永久保留；用户可单条删除 |
| 收藏 | 永久保留 |

清理入口：应用设置 →「存储」；网页端不提供数据清理（属应用级操作，见 §2.6）。

---

## 9. 版本与迁移

| `$schema` | 变化 | 迁移方式 |
|---|---|---|
| `ghs-repo/1` | 初版 | — |

规则：

- 新增字段为可选，导入时按默认值补齐；
- 删除或改语义必须提升 `$schema` 版本，并提供迁移函数；
- 版本高于应用支持时，拒绝导入并提示升级应用（不做"尽力导入"）；
- 每个迁移函数必须有单测样本（旧文件 → 新结构）。

---

## 10. 验收清单

- [ ] 导出 → 清空 → 导入后，配置、进度、收藏、历史完全一致
- [ ] `dry-run` 不产生任何写入
- [ ] 文本内容无进度记录时，打开落在第一章
- [ ] 有进度记录时，打开落在记录的章节与段落位置
- [ ] `device` 段导入被忽略，且不会覆盖本机端口/绑定
- [ ] 导出文件不含任何凭证明文
- [ ] 导入单条配置校验失败时，其余条目成功导入并出现在报告里
- [ ] WebDAV 仅 `progress` 落后时，只拉取该分片
- [ ] 同步关闭时，全部功能不依赖网络
- [ ] `$schema` 高于支持版本时拒绝导入并给出明确提示

---

## 11. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 建立本地仓库规范：数据分类与同步归属、表结构、进度统一模型、键归一化、导出格式（bundle / 分层）、导入与冲突规则、WebDAV 同步流程、保留策略、版本迁移与验收清单 |
| v1.1 | 2026-10-19 | 新增「凭据与 Cookie」类别（不导出、不同步、不可明文查看）；`device` 组补充代理白名单字段 |
