> **状态**：approved
> **版本**：v1.0
> **最后更新**：2026-10-20
> **取代/被取代**：**部分取代** [ADR-0015](ADR-0015-quickjs-binding-choice.md)（QuickJS 仍是同步求值通道，但不再是唯一的脚本宿主）

# ADR-0016：异步脚本改由 WebView 宿主承担

## 状态

`approved`

## 背景

[ADR-0015](ADR-0015-quickjs-binding-choice.md) 选了 `io.github.taoweiji.quickjs:quickjs-android:1.4.6`
作为脚本运行时，前提是"它能同步回调 Kotlin"。落地后做了两轮真机实验，结论与预期不符：

| 实验 | 期望 | 实测 |
|---|---|---|
| `Promise.resolve().then(...)` 后读取标志位 | `done` | **`pending`（永不前进）** |
| `(async () => { await Promise.resolve(); ... })()` | `awaited` | **`running`（卡在 await）** |
| 主动 `QuickJS.postEventQueue { }` 泵事件队列 | 推进微任务 | **仍为 `pending`** |
| `typeof setTimeout` / `queueMicrotask` | 函数 | **`undefined`** |

原因：该封装在 Java 层没有暴露 `JS_ExecutePendingJob`。QuickJS 的微任务队列必须由宿主显式驱动，
没有这个入口，`Promise` 就是死的。

而真实配置**必须**依赖异步：

| 配置 | 引擎侧异步用法 |
|---|---|
| 全部 5 份 | `await getHtml(...)`（13 处）、`await executeJs(...)`（6 处） |
| 91P | `getDetailInfo` 里 `return new Promise(...)` + `.then(...)` |
| hentaiWord | `postDataByRequest` 里 `return new Promise(...)` |
| 18comic / EHentai | `thatGethtml` 里 `new Promise(...)` 包超时 |

因此"把 `await` 从源码里去掉"也不可行——`new Promise` 同样需要微任务。

## 备选方案

| 方案 | 可行性 | 代价 |
|---|---|---|
| A. 继续用 QuickJS，仅支持同步方法 | ❌ | 5 份真实配置全部不可用，等于放弃核心能力 |
| B. 自建 JNI 封装，暴露 `JS_ExecutePendingJob` | ✅ 最终形态 | 需要 NDK + 自己维护 native 构建与四 ABI，周期长 |
| C. **换用带事件循环的宿主（WebView）** | ✅ 立即可用 | 内存与启动开销高于 QuickJS；需主线程与 Activity/Context |
| D. 源码级降级（删 `async`/`await`） | ❌ | 只对纯 `await host()` 有效；`new Promise` / `.then` 无解 |

## 决策

采用 **方案 C**，并按"宿主能力分层"落地：

```
:core:engine   ScriptEngine（含 evalAwait）+ GhsScriptBridge + ScriptHostTransport
                ↳ 纯 JVM，桥接与宿主契约可单测
:core:script   WebViewScriptEngine ← v1 主通道（真事件循环，await/Promise 可用）
               QuickJsScriptEngine ← 仅同步求值（保留：启动快、内存小，适合纯同步配置）
```

关键设计：**宿主调用保持同步**。

- JS → Java 走 `@JavascriptInterface`，Java 侧阻塞返回（取页、请求都在这里阻塞）
- `await getHtml(...)` 依然成立：`await` 一个非 Promise 值会立即得到它
- 因此不需要把 HTTP 塞进 JS 事件循环，也不需要 Promise 桥接层

## 理由

1. **唯一能立刻通过真实配置的方案**：WebView 有完整 ECMAScript 事件循环，`Promise` / `await` / `setTimeout` 原生可用。
2. **改动面小**：桥接协议（句柄 + 方法派发）不变，`GhsScriptBridge` 一行未动主体逻辑，只把宿主能力抽成 `ScriptHostTransport`。
3. **可回退**：`ScriptEngine` 接口不变，将来换成自建 JNI 的 QuickJS 时，只需新增一个实现并删掉 WebView 通道。
4. **异步语义被真机验证覆盖**：`AsyncBridgeInstrumentedTest` 在模拟器上跑通了
   `await getHtml` → `cheerio.load` → `helpElText` → `await net.request` → `await new Promise + setTimeout` → `await executeJs` 的完整链路。

## 后果

**正面**

- 真实配置的异步方法可以执行（已验证）
- 宿主调用同步，脚本语义简单：`await` 只是"立刻拿到值"
- 桥接与宿主契约在 JVM 上可测，native/WebView 只承担"执行 JS"

**负面 / 风险**

| 风险 | 说明 | 缓解 |
|---|---|---|
| 内存与启动 | WebView 常驻内存约 50–100MB，首次初始化数百毫秒 | 单例复用一个隐藏 WebView；只在需要执行脚本时初始化 |
| 线程约束 | WebView 必须在主线程创建与求值 | 引擎内部统一 post 到主线程，调用方无需关心 |
| 安全面 | JS 与 Java 通过 `@JavascriptInterface` 互通 | 只注册一个桥对象，方法白名单固定；不加载外部页面（`loadDataWithBaseURL` + 固定 origin） |
| 与 ADR-0003 的偏差 | 原计划"QuickJS 主 + WebView 兜底"，现在主次对调 | 本 ADR 记录偏差；QuickJS 保留为同步通道，未来自建 JNI 后可再换回 |

## 迁移路径

1. v1：WebView 主 + QuickJS 仅同步
2. 若后续需要更低开销：自建 JNI 封装（暴露 `JS_ExecutePendingJob`），新增 `QuickJsAsyncScriptEngine`
3. 切换时只需替换 `ScriptEngine` 实现，桥接、宿主契约、配置语义都不动

## 相关

- [ADR-0003](ADR-0003-script-runtime-quickjs-webview.md)（脚本运行时双通道）
- [ADR-0015](ADR-0015-quickjs-binding-choice.md)（QuickJS 封装选型，被本 ADR 部分取代）
- [spike-script-runtime.md](../../04-engineering/spike-script-runtime.md)（真机验证记录）
