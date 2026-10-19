> **状态**：draft
> **版本**：v1.0
> **最后更新**：2026-10-19
> **取代/被取代**：无

# Spike 报告：脚本运行时（JVM 侧）

本文件记录 M0 的**第一段验证**：用 JVM 侧 GraalJS 验证"内联配置能否被执行、`$` 桥接能否支撑真实脚本"。
真机形态仍是 QuickJS + 无头 WebView（[ADR-0003](../02-architecture/adr/ADR-0003-script-runtime-quickjs-webview.md)）。

---

## 1. 验证了什么

| # | 假设 | 结论 | 证据 |
|---|---|---|---|
| 1 | 内联配置按 `break 标记` 切分后可直接求值（前面的 `(): WebConfig =>` 不参与执行） | ✅ | 5/5 份真实配置均求值成功 |
| 2 | 求值后 7 个必需方法都存在且可调用 | ✅ | 逐份断言，`missing=[]` |
| 3 | 桥接方式「int 句柄 + 方法派发」够用 | ✅ | 合成配置跑通 `each/find/attr/text/hasClass/toString` |
| 4 | `this` 绑定到配置对象 | ✅ | `adapterSearchUrl` 返回 `homeUrl + key` |
| 5 | 静态方法（不依赖页面结构）能返回预期数据 | ✅ | `getTags`：18comic 12、EHentai 22、hentaiWord 18、shasha 0；EHentai `getPagination` 0 |
| 6 | 含旧式 `net.request(...).on(...)` 的配置不会在求值阶段炸掉 | ✅ | 该类代码只定义未调用；仅由静态预检给 `E-CFG-030` 警告 |

## 2. 桥接形态（与真机一致）

```
JS 侧:  $ / each(fn) / find / attr / text / hasClass / toString / eq ...
          │  （全部方法都只是 __xxx(handle, ...) 的薄包装）
          ▼
Kotlin: __select(selector) -> Int handle
        __len/__at/__attr/__text/__hasClass/__outer/__find/... -> 基本类型
        HashMap<Int, Selection> 持有选择器快照
```

**为什么这样设计**：真机 QuickJS 走 JNI 时也只能传基本类型 + 句柄，这份 spike 的桥接协议可以直接迁移，
不会出现"spike 能跑、真机要重写"的落差。

## 3. 环境坑（重要）

GraalJS 的 Truffle 运行时会把 native 库解压到 `%LOCALAPPDATA%\org.graalvm.polyglot`。
在受限环境里该目录不可写，表现为：

```
java.nio.file.AccessDeniedException: C:\Users\<user>\AppData\Local\org.graalvm.polyglot
```

解法（已写进 `core/engine/build.gradle.kts` 的 `tasks.test`）：

```kotlin
systemProperty("java.io.tmpdir", scratch)
systemProperty("LOCALAPPDATA", scratch.resolve("localappdata"))
systemProperty("APPDATA", scratch.resolve("appdata"))
systemProperty("user.home", scratch.resolve("home"))   // 关键：Truffle 从 user.home 推导
```

只改环境变量不够，必须改**系统属性**——Truffle 读的是 `user.home`。

## 4. 没有验证的部分（下一段 M0）

| 待验证 | 为什么现在验不了 | 计划 |
|---|---|---|
| QuickJS 的 ES2020 兼容性 | 需要 Android NDK 与 native 构建 | 装 SDK/NDK 后用同一批配置回归 |
| `$` 桥接的真实 HTML 结果 | 手上没有可入库的真实页面（`AGENTS.md` §7），且不允许访问来源 | 用 `tmp/` 本地快照（不入库）比对 |
| 异步能力（`getHtml`/`net.request`/`executeJs`） | 本 spike 把它们替换成 reject 桩，只验证"不联网也能求值" | 接 HTTP 桩后验证 Promise 桥 |
| 无头 WebView 兜底 | 需要 Android 运行时 | M0 第二阶段 |

## 5. 结论

**执行模型成立**：切分 → 求值 → 拿到配置对象 → `this` 绑定 → 句柄式 `$` 桥接 → 方法返回领域数据。
`ADR-0003` 的"QuickJS 主 + WebView 兜底"不需要在协议层面调整，风险收敛到 QuickJS 的 JS 特性覆盖与异步桥。

## 6. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 记录 JVM 侧脚本运行时 spike：6 项假设的验证结论、桥接协议、Truffle 环境坑、未验证项与结论 |
