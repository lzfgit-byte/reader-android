# GHS Mobile · Android

**GHS Mobile** 是一个独立的 Android 原生应用，本质是**由配置驱动的内容容器**：

> 配置决定「看什么」和「长什么样」，应用负责「加载、渲染、缓存、播放」。

应用不内置任何内容来源。导入一份**配置单元**（ConfigUnit），立刻获得一个新来源：列表、详情、图集、视频、搜索、筛选全部按这份配置的描述工作；同一份配置还决定卡片尺寸与排布、入口显隐、阅读器默认模式。

- 技术栈：Kotlin + Jetpack Compose + Media3 + Room + OkHttp
- 包名：`com.ghs.mobile`
- 最低版本：Android 8.0（API 26）

## 核心特征

| 特征 | 说明 |
|---|---|
| 配置驱动 | 来源差异全部收敛到配置（清单 JSON + 解析脚本），应用代码零特判 |
| **浏览器内写配置** | 应用自带 Web 管理服务：电脑/手机浏览器里编辑配置、实时试跑、看 JSON |
| 单机自足 | 不要求部署服务、不要求与电脑同网 |
| 底座优先 | 配置加载 → 取页 → 解析 → 渲染分发 → 缓存 → 媒体，逐层可测 |
| 阅读优先 | 图集阅读器、HLS 播放器、手势、进度记忆、预加载 |
| 双数据源 | 默认内置引擎，也可连接自建服务实现多设备共享 |
| 配置同步 | 通过自有 WebDAV 目录在多设备间同步配置 |

## 当前阶段

**设计定稿期（M0 之前）—— 尚未生成代码。**

设计文档通过评审后才会创建 Gradle 工程。阶段红线见 [AGENTS.md](AGENTS.md) §0。

## 从这里开始读

| 我想了解 | 去哪里 |
|---|---|
| 项目怎么管、我该遵守什么 | [AGENTS.md](AGENTS.md) |
| 文档在哪、目录怎么分 | [docs/README.md](docs/README.md) |
| 要做哪些功能 | [docs/01-product/design.md](docs/01-product/design.md) |
| 功能做到哪一步了 | [docs/01-product/feature-registry.md](docs/01-product/feature-registry.md) |
| **配置怎么写** | [docs/03-api/config-spec.md](docs/03-api/config-spec.md) |
| **Web 管理服务 API** | [docs/03-api/web-admin-api.md](docs/03-api/web-admin-api.md) |
| **浏览器端界面怎么实现** | [docs/02-architecture/web-console.md](docs/02-architecture/web-console.md) |
| **手机端界面怎么实现** | [docs/02-architecture/ui-implementation.md](docs/02-architecture/ui-implementation.md) |
| 接口字段是什么 | [docs/03-api/rest-contract.md](docs/03-api/rest-contract.md) |
| 为什么要这么设计 | [docs/02-architecture/adr/](docs/02-architecture/adr/) |
| 改了什么 | [CHANGELOG.md](CHANGELOG.md) |

## 目录

```
.
├── AGENTS.md          # 代理工作守则（先读）
├── README.md
├── CHANGELOG.md
└── docs/
    ├── README.md              # 文档总索引
    ├── 00-process/            # 流程与规范
    ├── 01-product/            # 产品与功能设计
    ├── 02-architecture/       # 架构、UI 实现、ADR
    ├── 03-api/                # 配置规范 + 数据层契约
    └── 99-archive/            # 废弃文档
```

代码目录（`app/`、`core/*`、`feature/*`）在 M1 阶段按设计文档 §8.1 创建。

## 声明

本应用是内容容器：不提供、不托管、不分发任何内容，也不内置任何内容来源配置。所有内容来源由用户自行导入的配置决定。详见设计文档第 10 章。
