> **状态**：approved
> **版本**：v1.0
> **最后更新**：2026-10-25
> **取代/被取代**：无（补充 [ADR-0016](ADR-0016-async-script-host-webview.md) 的宿主生命周期部分）

# ADR-0017：WebView 统一为受管窗口（WebWindow / WebWindows）

## 状态

`approved`

## 背景

应用里有五处各自 `new WebView(...)`，彼此不知道对方存在，也没有统一的生命周期：

| 出处 | 用途 | 原生命周期 |
|---|---|---|
| `WebViewScriptEngine` | 配置脚本引擎（跑 `getHtml` / `page` / `detail`） | 每会话一个，`close()` 销毁 |
| `HttpScriptHost.executePage` | 取页失败时用无头 WebView 兜底，并在其中执行配置给的 JS | **每次调用新建，用完销毁** |
| `UrlBrowserScreen` | 设置里的「网页浏览」 | 每次进入新建，**离开不销毁** |
| `ChallengeActivity` | 人机校验窗口 | 每个 Activity 一个，`onDestroy` 销毁 |
| `WebViewImageFallback` | 图片兜底（同源 fetch 取字节） | 每次取图新建，用完销毁 |

审计出两个实际缺陷：

1. **网页浏览窗口从不销毁**：`AndroidView` 只把 View 从视图树摘下，WebView 的原生资源与渲染器不会释放，每进一次页面就多留一份。
2. **「显示 webview」关掉后实例挂在已销毁的 Activity 上**：镜像窗口把取页 WebView 摘下挂到自己窗口，`finish()` 时没有摘回去，`destroy()` 时它仍有父容器。

同时，"每次新建 WebView" 的代价很实在：冷启动 1.5–3s、常驻几十 MB。取页兜底与图片兜底都是高频路径。

## 备选方案

| 方案 | 可行性 | 问题 |
|---|---|---|
| A. 保持现状，只修两个泄漏 | ✅ | 实例数没有上界；取页/取图仍每次冷启动 |
| B. 只做按用途单例 | ✅ | 图片兜底是并发路径（最多 2 个并发），单例会互相踩 |
| C. **窗口对象 + 三种复用策略（单例 / 一次性 / 对象池）** | ✅ | 需要一套统一生命周期与复用重置纪律 |
| D. 完整复刻 Electron 窗口（每窗口独立 session/进程/开发者工具） | ❌ | Android WebView 没有 per-window 代理、没有 session 分区、没有内嵌 devtools |

## 决策

采用 **方案 C**：所有 WebView 从 `WebWindows` 取，由 `WebWindow` 承载生命周期。

```
WebWindows（注册表 + 对象池）
  ├─ acquire(spec) / release(window) / current(name) / trim() / destroyAll()
  └─ WindowSpec(name, reuse, poolMin, poolMax, desktopUa, evalBridge)

WebWindow（一个受管窗口）
  ├─ 状态机：IDLE → LOADING → READY / CLOSED（关闭后不可再用）
  ├─ load() / eval() / stop()（内部切主线程；eval 允许后台线程阻塞等待）
  ├─ attachTo(container) / detachFromParent()（同一时刻只挂一个容器）
  ├─ setPageFinishedListener / setHttpErrorListener / setErrorListener / setProgressListener
  └─ setBridge()：页面里 `__ghsWin.done/error` 回传 Promise 结果
```

三种复用策略与用途一一对应：

| 策略 | 窗口 | 说明 |
|---|---|---|
| `SINGLETON` | `engine`、`fetch`、`browser` | 常驻复用；`browser` 离开页面时显式 `destroy()` |
| `PER_TASK` | `challenge` | 每次校验一个干净窗口，结束即销毁 |
| `POOL(min=1, max=3)` | `image` | 空闲复用、超限等待、回收线程 5s 收敛 |

关键实现约束（都是踩过的坑）：

1. **创建 / 销毁不在锁内做**：WebView 必须在主线程创建，持锁等主线程会与主线程自己来 `acquire` 死锁。
2. **窗口复用时按任务隔离回调**：`fetch` 窗口每次任务前重设桥与回调，任务对象（`FetchTask`）自带 latch，避免上一个任务的结果写进下一个任务的槽位。
3. **`fetch` 同一时刻只跑一个任务**：`ReentrantLock.tryLock(15s)`，拿不到直接报"取页窗口正忙"，不排队堆积。
4. **线程约定**：`eval` 禁止主线程调用（会把 `evaluateJavascript` 的回调堵死）；`load` 在主线程调用时不阻塞，等页面就绪由回调驱动。

## 不能复刻的部分（已确认不影响功能）

| Electron 能力 | WebView 现实 | 处理 |
|---|---|---|
| 每窗口独立代理（`session.setProxy`） | `ProxyController` 是进程级 | 保持现状：网页浏览页进入时套临时规则、退出还原全局 |
| `preload`（页面脚本执行前注入） | 无等价物 | 用 `onPageFinished` + DOM 轮询 + `evaluateJavascript`（取页链路已验证） |
| 内嵌 DevTools 面板 | 只能 `chrome://inspect` 远程调试 | 不做 |
| 全局快捷键 show/hide | 无 | 沿用主界面右上角「显示 webview」入口 |

## 理由

1. **实例数有上界**：常驻最多 3 个（engine / fetch / browser）+ 校验窗 + 图片池 3 个，替代过去"每次取页/取图新建"。
2. **消除两个确定的泄漏**：网页浏览离开即销毁；镜像窗口关闭即摘回父容器。
3. **行为不变**：`HttpScriptHost` / `WebViewScriptEngine` 的对外契约与日志不变，OkHttp 仍是默认取页通道，WebView 只在"兜底取页 / 执行配置 JS / 人机校验 / 图片兜底"四种情况出现。
4. **可测**：`WebWindowsInstrumentedTest` 在真机上验证单例、池化复用、销毁后重建三条语义。

## 后果

**正面**

- 取页兜底与图片兜底不再支付 WebView 冷启动
- 句柄统一后，「显示 webview」可以挂任意窗口，不再依赖各处自己把实例塞进全局
- 生命周期集中，关闭/销毁只有一条路径

**负面 / 风险**

| 风险 | 说明 | 缓解 |
|---|---|---|
| 复用污染 | 复用的窗口保留上一个页面的 cookie / storage / Service Worker | 每次换文档由 `loadUrl` 重建 JS 上下文；需要绝对干净时用 `PER_TASK` |
| 池并发上限 | 并发取图超过 3 时会短暂超限 | 等待上限 10s，超时建临时窗口；回收线程 5s 收敛到 `poolMin` |
| 桥暴露面 | `__ghsWin` 对取页页面的脚本可见 | 仅 `fetch` 窗口注入；后续可换 `WebViewCompat.addWebMessageListener` + origin 白名单（未做） |
| 调试期改动面大 | 五处入口同时替换 | 保留旧的 `WebViewFallbackHub` 广播作为「显示 webview」信号源，逐处回归 |

## 迁移路径

1. 已落地：五处创建点全部改为 `WebWindows.acquire(...)`，两个缺陷修复
2. 未做：`__ghsWin` 换成 origin 受限的 `addWebMessageListener`
3. 未做：设置项暴露池参数（`poolMin` / `poolMax`）

## 相关

- [ADR-0016](ADR-0016-async-script-host-webview.md)（WebView 作为脚本宿主）
- [ADR-0009](ADR-0009-proxy-whitelist-single-decision.md)（代理是进程级、单一决策点）
- [ADR-0010](ADR-0010-challenge-top-activity.md)（人机校验窗口是独立 Activity）
