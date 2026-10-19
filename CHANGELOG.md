# 变更日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

**本项目约定（见 `AGENTS.md` §3）：功能的新增、删除、改名、范围变更，必须同时更新
`docs/01-product/design.md` 与 `docs/01-product/feature-registry.md`，并在本文件记录。**

分类：`Added` 新增 / `Changed` 变更 / `Removed` 删除 / `Deprecated` 弃用 / `Fixed` 修复 / `Docs` 文档。

---

## [Unreleased]

### Added

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

### Changed

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

- `AGENTS.md` §2.5「文档独立性」：禁止外部引用，提取的需求必须重写为本项目功能条目
- `AGENTS.md` §2.1 SSOT 清单新增四项：配置规范、Web 服务 API、Web 管理界面、UI 实现规范
- `AGENTS.md` §7 新增红线：禁止在应用内新增「配置编辑」能力
- `AGENTS.md` §7 新增红线：禁止把应用级设置（端口/绑定/令牌、WebDAV 凭证、开关类设置）暴露到 Web 端
- `AGENTS.md` §3 功能增删必改清单保持三处强制（design / registry / CHANGELOG）
- 编号说明：**ADR-0013 为废弃空号**（曾草拟后撤销、从未入库），按「编号永不复用」原则不再分配
