> **状态**：draft
> **版本**：v1.1
> **最后更新**：2026-10-19
> **取代/被取代**：取代同文件 v1.0

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

| 待验证 | 为什么当时验不了 | 现状 |
|---|---|---|
| QuickJS 的 ES2020 兼容性 | 需要 Android 运行时 | ✅ **已验**（见 §6） |
| `$` 桥接的真实 HTML 结果 | 手上没有可入库的真实页面（`AGENTS.md` §7），且不允许访问来源 | 仍待：需本地快照比对 |
| 异步能力（`getHtml`/`net.request`/`executeJs`） | 本 spike 把它们替换成 reject 桩，只验证"不联网也能求值" | 仍待：需接 HTTP 桩 |
| 无头 WebView 兜底 | 需要 Android 运行时 | 仍待：M0 第二阶段 |

## 5. 结论

**执行模型成立**：切分 → 求值 → 拿到配置对象 → `this` 绑定 → 句柄式 `$` 桥接 → 方法返回领域数据。
`ADR-0003` 的"QuickJS 主 + WebView 兜底"不需要在协议层面调整，风险收敛到 QuickJS 的 JS 特性覆盖与异步桥。

## 6. 真机验证（已完成）

在 Android 模拟器（API 37，x86_64）上运行 `:app` 的 instrumented test：

```
adb shell am instrument -w \
  -e class com.ghs.mobile.ScriptRuntimeInstrumentedTest \
  com.ghs.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

结果 **`OK (1 test)`**，`ScriptProbe` 10 项自检全部通过：

| 自检项 | 实测值 |
|---|---|
| QuickJS 启动 | 20 ms |
| `1 + 1` | `2` |
| `typeof globalThis` | `object` |
| ES2020 基础能力（`BigInt`/`Promise`/`Symbol`） | `true` |
| `$('.item').length` | `2` |
| `$('a').attr('href')` | `/1` |
| `helpElText($('.item .title').eq(0))` | `A` |
| 配置求值 · 7 个必需方法 | `7 / 7` |
| `getTags` | `2 项` |
| `this` 绑定 `adapterSearchUrl` | `https://example.comk` |
| 全流程耗时 | 106 ms |

**真机验证抓到两个真实 bug**（JVM/GraalJS 侧都测不出来）：

| # | 现象 | 原因 | 修复 |
|---|---|---|---|
| 1 | `QuickJSException: SyntaxError: unexpected token '<' at 1 + 1:1` | QuickJS 封装的签名是 `executeScript(script, name)`，我把参数写反了，脚本内容变成 `"<eval>"` | 改回 `(script, name)`，并在 KDoc 里标注这个坑 |
| 2 | 自检项 `helpElText=AB`（期望 `A`） | 探针用了 `$('.item .title')`，它命中两个元素、`text` 拼接成 `AB` | 探针改用 `.eq(0)`，顺带把 `eq` 也纳入覆盖 |

这两条正说明"能在 JVM 上跑"与"能在真机上跑"不是一回事——原生库参数顺序、绑定语义都只有真机才暴露。

## 7. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-19 | 记录 JVM 侧脚本运行时 spike：6 项假设的验证结论、桥接协议、Truffle 环境坑、未验证项与结论 |
| v1.1 | 2026-10-20 | 新增 §6 真机验证：模拟器上 instrumented test 通过、10 项实测值、抓到并修复的 2 个真机 bug |
