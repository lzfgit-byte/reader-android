> **状态**：approved
> **版本**：v1.0
> **最后更新**：2026-10-19
> **取代/被取代**：无（落实 [ADR-0003](ADR-0003-script-runtime-quickjs-webview.md) 的"QuickJS 主通道"，不改其结论）

# ADR-0015：脚本运行时选用支持宿主回调的 QuickJS 封装

## 状态

`approved`

## 背景

[ADR-0003](ADR-0003-script-runtime-quickjs-webview.md) 定了"QuickJS 主 + 无头 WebView 兜底"，但没定**用哪个 QuickJS 绑定**。

这个选择由一条硬约束决定：`GHS Select` 的取值必须**同步**返回。

```js
const $title = $el.find('.title');
const title = helpElText($title);   // 这一行必须立刻拿到字符串
```

所以运行时必须支持 **JS → Kotlin 的同步回调**。只支持"求值并返回 JSON"的绑定无法实现。

## 备选方案

| 方案 | 是否支持 JS→宿主同步回调 | 说明 |
|---|---|---|
| A. `app.cash.quickjs:quickjs-android:0.9.2` | ❌ | 只有 `evaluate` / `set` / `get` / `compile`，**没有函数注入**。`javap` 确认其公共 API 不存在任何 `JSFunction` 类型 |
| B. **`io.github.taoweiji.quickjs:quickjs-android:1.4.6`** | ✅ | 提供 `JSFunction` + `JavaCallback`（`invoke(JSObject, JSArray)`），可把 Kotlin 闭包注入为 JS 函数；自带 arm64-v8a / armeabi-v7a / x86 / x86_64 四个 ABI 的 `libquickjs.so` |
| C. 自建 NDK + JNI 绑定 | ✅ | 完全可控、可跟进 quickjs-ng，但需要自己维护 CMake 构建、ABI 矩阵与 JNI 桥，成本显著更高 |
| D. GraalJS | 不适用于 Android | 需要完整 JDK；只用于 **JVM 侧验证**（见 [spike 报告](../../04-engineering/spike-script-runtime.md)） |

## 决策

采用 **方案 B**，并配合三层结构把"引擎"与"桥"解耦：

```
:core:engine   ScriptEngine（接口）+ GhsScriptBridge（句柄表、方法派发、JS prelude）
                ↳ 纯 JVM，可在 JVM 上单测；不含任何 native 依赖
:core:script   QuickJsScriptEngine（把 taoweiji 的 API 适配成 ScriptEngine）
                ↳ Android library，持有 .so
:app           ScriptProbe（真机自检：启动耗时、ES2020 探测、选择器取值、配置求值）
```

桥接协议沿用 spike 的结论：**整型句柄 + 方法派发**，跨界只传 `String` / `Int` / `Boolean`。

## 理由

1. **满足硬约束**：`JavaCallback` 让 `$`、`attr`、`text`、`hasClass` 都能同步返回，配置脚本无需改写。
2. **不动 native 构建**：四个 ABI 的 `.so` 已预编译，避免了"为一个验证目标先搭 NDK 工具链"。
3. **可替换**：引擎侧只依赖 `ScriptEngine` 接口，换绑定（或改用自建 JNI）时桥接与引擎代码不用动。
4. **可离线验证一半**：桥接逻辑（句柄表、取值语义、`toString`、空集）在 JVM 单测里全覆盖，native 只负责"把 JS 调用转成 Kotlin 调用"。

## 后果

**正面**

- 真机形态落地，不需要维护 native 构建
- APK 增加约 2.7MB（四 ABI 的 `.so`），可接受
- 桥接与引擎可单测，native 层面积小

**负面 / 风险**

- **第三方封装的 QuickJS 版本不受我们控制**：其 `.so` 内置的 QuickJS 版本决定了 ES 特性支持范围；若后续需要 quickjs-ng 的新特性，只能自建绑定（届时写新 ADR 取代本决策）
- **线程约束**：QuickJS runtime 非线程安全，所有调用必须在创建它的同一线程串行执行；引擎调度层需要显式保证
- 该封装的错误信息格式与我们的 `E-CFG-*` 不对齐，需要在中层做一次映射

## 缓解

- `QuickJsScriptEngine` 在类注释与 KDoc 中标注线程约束；调用方统一走单线程调度
- `ScriptProbe` 把"能不能跑"变成可见的自检项（启动耗时 / ES2020 探测 / 选择器 / 配置求值），换设备或换封装时立刻能发现
- 桥接层不引用任何 `com.quickjs.*` 类型，封装替换成本被限制在 `:core:script` 一个文件

## 相关

- [ADR-0003](ADR-0003-script-runtime-quickjs-webview.md)（QuickJS 主 + WebView 兜底）
- [config-spec](../../03-api/config-spec.md) §8（脚本 API 语义）
- [spike-script-runtime.md](../../04-engineering/spike-script-runtime.md)（执行模型的 JVM 侧验证）
