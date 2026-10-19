> **状态**：approved
> **版本**：v1.10
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v1.9

# 文档总索引

本文件是 `docs/` 的**唯一入口**。任何文档必须在此登记，否则视为孤儿文档（见 [AGENTS.md](../AGENTS.md) §1）。

## 目录结构

```
docs/
├── README.md                      ← 你在这里（唯一入口）
├── 00-process/                    # 流程与规范
│   └── document-policy.md         # 文档管理制度（含功能增删联动协议）
├── 01-product/                    # 产品与功能
│   ├── design.md                  # ★ 产品与功能设计（功能 SSOT）
│   ├── feature-registry.md        # ★ 功能登记表（交付状态 SSOT）
│   └── features/                  # 按需创建：单功能详细规格
├── 02-architecture/               # 架构
│   ├── ui-implementation.md       # ★ UI 实现规范（移动端界面 SSOT）
│   ├── web-console.md             # ★ Web 管理界面实现（浏览器端 SSOT）
│   └── adr/                       # ★ 架构决策记录（决策 SSOT）
│       ├── ADR-0001-native-kotlin-compose.md
│       ├── ADR-0002-embedded-engine-dual-provider.md
│       ├── ADR-0003-script-runtime-quickjs-webview.md
│       ├── ADR-0004-config-format.md
│       ├── ADR-0005-web-console-and-debug.md
│       ├── ADR-0006-webdav-sync.md
│       ├── ADR-0007-local-store-unified-sync.md
│       ├── ADR-0008-http-proxy-two-channels.md
│       ├── ADR-0009-proxy-whitelist-single-decision.md
│       ├── ADR-0010-challenge-top-activity.md
│       ├── ADR-0011-drop-remote-mode.md
│       ├── ADR-0012-html-sanitize-to-blocks.md
│       └── ADR-0014-monaco-editor-intellisense.md
├── 03-api/                        # 契约与规范
│   ├── config-spec.md             # ★ 动态配置规范（配置 SSOT）
│   ├── local-store.md             # ★ 本地仓库（数据/导出/同步 SSOT）
│   ├── web-admin-api.md           # ★ 内置 Web 服务 API（服务 SSOT）
│   └── rest-contract.md           # ★ 数据层契约（接口 SSOT）
├── 04-engineering/                # 工程实践
│   └── build.md                   # 本地构建与验证（含便携工具链与踩坑）
└── 99-archive/                    # 按需创建：已废弃文档
```

★ 标记 = 单一事实来源（SSOT），其余文档遇到相关内容只能链接，不得复制。

## 索引

| 文档 | 内容 | 状态 | 更新 |
|---|---|---|---|
| [00-process/document-policy.md](00-process/document-policy.md) | 文档分类、生命周期、编号、命名、评审、功能增删联动协议 | approved | 2026-10-19 |
| [01-product/design.md](01-product/design.md) | 产品定位、配置驱动的内容容器、F01–F29 功能范围、引擎/代理/校验架构、里程碑、开放问题 | **approved** | 2026-10-19 |
| [01-product/feature-registry.md](01-product/feature-registry.md) | F01–F22 功能登记：类别、优先级、状态、里程碑、关联章节 | draft | 2026-10-19 |
| [02-architecture/ui-implementation.md](02-architecture/ui-implementation.md) | 设计系统、断点、应用骨架、组件契约、页面要点、四态与手势、性能预算 | draft | 2026-10-19 |
| [02-architecture/web-console.md](02-architecture/web-console.md) | Web 管理界面：三栏布局、表单/源码双模式编辑、JSON 调试台、导入导出与同步面板、快捷键 | draft | 2026-10-19 |
| [02-architecture/adr/ADR-0001-native-kotlin-compose.md](02-architecture/adr/ADR-0001-native-kotlin-compose.md) | 决策：原生 Kotlin + Compose | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0002-embedded-engine-dual-provider.md](02-architecture/adr/ADR-0002-embedded-engine-dual-provider.md) | 决策：引擎内置 + 数据层双模式 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0003-script-runtime-quickjs-webview.md](02-architecture/adr/ADR-0003-script-runtime-quickjs-webview.md) | 决策：脚本运行时（QuickJS 主 + WebView 兜底） | review | 2026-10-19 |
| [02-architecture/adr/ADR-0004-config-format.md](02-architecture/adr/ADR-0004-config-format.md) | 决策：配置采用「清单 + 脚本」双段式 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0005-web-console-and-debug.md](02-architecture/adr/ADR-0005-web-console-and-debug.md) | 决策：编辑放在内置 Web 管理界面，应用内只做管理与只读 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0006-webdav-sync.md](02-architecture/adr/ADR-0006-webdav-sync.md) | 决策：配置同步用 WebDAV，v1 手动 + 冲突提示 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0007-local-store-unified-sync.md](02-architecture/adr/ADR-0007-local-store-unified-sync.md) | 决策：本地仓库为唯一数据源，导出/导入/同步统一到仓库层 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0008-http-proxy-two-channels.md](02-architecture/adr/ADR-0008-http-proxy-two-channels.md) | 决策：HTTP 代理对应用请求与无头 WebView 两条通道同时生效 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0009-proxy-whitelist-single-decision.md](02-architecture/adr/ADR-0009-proxy-whitelist-single-decision.md) | 决策：白名单是唯一代理判定点；转发代理进 v1；未配置即直连 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0010-challenge-top-activity.md](02-architecture/adr/ADR-0010-challenge-top-activity.md) | 决策：人机校验用独立顶层 Activity；校验后带 Cookie 优先 OkHttp 重试 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0011-drop-remote-mode.md](02-architecture/adr/ADR-0011-drop-remote-mode.md) | 决策：移除远程服务模式，只保留内置引擎（部分取代 ADR-0002） | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0012-html-sanitize-to-blocks.md](02-architecture/adr/ADR-0012-html-sanitize-to-blocks.md) | 决策：正文接受任意 HTML，清洗后统一转结构块 | approved | 2026-10-19 |
| [02-architecture/adr/ADR-0014-monaco-editor-intellisense.md](02-architecture/adr/ADR-0014-monaco-editor-intellisense.md) | 决策：Web 编辑器用 Monaco，智能提示由注入的 `.d.ts` 驱动 | approved | 2026-10-19 |
| [03-api/config-spec.md](03-api/config-spec.md) | 动态配置规范：形态、字段、脚本方法、加载协议、错误码、脚本 API、交换文件格式 | draft | 2026-10-19 |
| [03-api/local-store.md](03-api/local-store.md) | 本地仓库：数据分类与同步归属、表结构、统一进度模型、导出/导入、WebDAV 同步、保留策略、版本迁移 | draft | 2026-10-19 |
| [03-api/web-admin-api.md](03-api/web-admin-api.md) | 内置 Web 服务 API：命名空间、配置 CRUD、校验、导入导出、调试 run/fetch/events、WebDAV、安全边界 | draft | 2026-10-19 |
| [03-api/rest-contract.md](03-api/rest-contract.md) | 数据层契约：接口路径、字段、参数编码、响应语义、双模式差异 | draft | 2026-10-19 |
| [04-engineering/build.md](04-engineering/build.md) | 本地构建与验证：便携工具链（JDK/Gradle）、环境变量、踩坑、当前验证基线 | draft | 2026-10-19 |

## 状态图例

| 状态 | 含义 | 允许修改 |
|---|---|---|
| `draft` | 起草中，结论未定 | 自由修改 |
| `review` | 待评审 | 只允许按评审意见修改 |
| `approved` | 已定稿，作为实施依据 | 不允许改内容；变更走新文档/新 ADR |
| `superseded` | 已被新文档取代 | 冻结，仅留指针 |
| `archived` | 已废弃 | 冻结 |

## 新增文档的三步

1. 确认它属于 `docs/` 下哪个分类；没有合适分类就先改本文件与 `document-policy.md`。
2. 写文档，**必须**带文档头（状态/版本/最后更新/取代关系）与末尾「变更记录」表格。
3. 在本文件「索引」表中加一行。

## 推荐阅读路径

**第一次接触项目**：`README.md` → `AGENTS.md` → 本文件 → `01-product/design.md`

**要动代码前**：`AGENTS.md` §0（阶段红线）→ `01-product/design.md` → 相关 ADR → `03-api/rest-contract.md`

**要写/改配置**：`03-api/config-spec.md` → 浏览器打开应用内的 Web 管理界面（`02-architecture/web-console.md`）

**要做界面**：`02-architecture/ui-implementation.md`（移动端） / `02-architecture/web-console.md`（浏览器端）

**要动数据/同步**：`03-api/local-store.md`

**要加/删功能**：`AGENTS.md` §3 → `00-process/document-policy.md`「功能增删联动协议」

## 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 建立文档总索引与目录分类，登记首批 7 份文档 |
| v1.1 | 2026-10-19 | 同步文档内化：索引描述改为自有产品口径，契约统一称「数据层契约」，功能范围更新为 F01–F20 |
| v1.2 | 2026-10-19 | 产品重定位为配置驱动的内容容器：新增 `03-api/config-spec.md`（配置规范）与 `02-architecture/ui-implementation.md`（UI 实现规范）、ADR-0004；索引与阅读路径同步 |
| v1.3 | 2026-10-19 | 新增浏览器端配置编写能力：`03-api/web-admin-api.md`（内置 Web 服务 API）、`02-architecture/web-console.md`（Web 管理界面）、ADR-0005/ADR-0006；索引与阅读路径同步 |
| v1.4 | 2026-10-19 | 新增文本阅读与统一本地仓库：`03-api/local-store.md`、ADR-0007；索引与阅读路径同步 |
| v1.5 | 2026-10-19 | 新增 HTTP 代理能力：ADR-0008；索引同步 |
| v1.6 | 2026-10-19 | 代理进 v1 并引入白名单：ADR-0009；索引同步 |
| v1.7 | 2026-10-19 | 新增人机校验通道：ADR-0010；索引同步 |
| v1.8 | 2026-10-19 | 收敛四处决策：ADR-0011（移除远程模式）、ADR-0012（HTML 清洗）；索引同步 |
| v1.9 | 2026-10-19 | 新增 ADR-0014（Web 编辑器 Monaco + 类型驱动智能提示）；索引同步 |
| v1.10 | 2026-10-19 | `design.md` 置为 approved；新增 `04-engineering/build.md`（本地构建与验证）并登记索引 |
