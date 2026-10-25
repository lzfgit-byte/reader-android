> **状态**：draft
> **版本**：v1.0
> **最后更新**：2026-10-25
> **取代/被取代**：无

# 复盘：人机校验死循环（2026-10-25）

被挡站点返回 Cloudflare 托管挑战（`HTTP 403` + `Just a moment...` / 中文「请稍候…」）时，
应用表现是：**反复弹「人机校验」窗口，点完验证又刷新回验证页，永远进不了内容**。

本文记录现象、根因、取证、已修复部分与仍未解决部分，供后续继续处理。功能定义见
[design.md §5.23](../01-product/design.md)，窗口模型见
[ui-implementation.md §4.1.1](../02-architecture/ui-implementation.md)。

## 1. 现象

- 首页取页被挡后界面停在「开始解析…」，重复触发后表现为一直转圈；
- 人机校验窗口出现时是**白底**，只有「取消 / 我已完成」，没有可点的验证控件；
- 等一会儿再回来，窗口又出现一次（同一条请求超时后重试），观感就是"一直在循环"；
- 极少数时候窗口里能看到 Cloudflare 的勾选框，但点完之后页面刷新、回到勾选框。

## 2. 根因（三条，均已自取证据）

### 2.1 校验窗口从未加载页面（决定性）

`ChallengeActivity` 里这一行整形时被并进了上一行的 `//` 注释：

```kotlin
// 桌面页面：UA 已在 settings 里设置…         val pageHeaders = …         view.loadUrl(url, pageHeaders)
```

编译照过，但 `view.loadUrl(...)` 属于注释内容，**永远不执行**。于是窗口是一张空白 WebView：
用户没有可点的验证控件，`ChallengeGate` 等到 5 分钟超时返回 `false`，原请求失败；
用户再点重试 → 再弹一次 → 表现为死循环。

证据：窗口截图白底无内容；日志里除了 `challenge http 403` 之外没有任何页面事件
（`challenge page finished` 从未出现）；修复后同一窗口出现进度条并产生网络请求。

### 2.2 验证期间反复重载，验证进度被作废

兜底取页遇到验证页时每 2.5s 重载一次（最多 3 次），`awaitChallengeCleared` 里又每 10s 重载一次。
Cloudflare 挑战的组件状态挂在当前文档上，重载即作废：
控制台报 `[Cloudflare Turnstile] Cannot find Widget cf-chl-widget-…`、验证从零开始。
这就是"点了验证又刷新重新验证"的直接来源。

### 2.3 挑战页被当成正文缓存

挑战页标题会按浏览器语言本地化（中文环境是「请稍候…」，不是 `Just a moment...`），
`CHALLENGE_TITLES` 只收英文，于是 403 挑战页被判成正常正文：

```
I/GHS fetchHtml 403 → webview fallback https://<site>
I/GHS webview html len=28446 title=请稍候… url=https://<site>   ← 当正文返回
I/GHS page items=0 tags=12                                       ← 首页"没有更多了"
```

挑战页还会被写进 HTML 缓存，导致该 URL 之后一直解析出 0 条。

## 3. 另一个被排除的方向：UA 与 JS 指纹矛盾

曾判断"窗口白屏 = UA 伪装导致被判定伪造"，实测确认了矛盾存在：

```
navigator.userAgent      = Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/155…
navigator.userAgentData  = {"brands":[{"brand":"Android WebView"}],"mobile":true,"platform":"Android"}
navigator.platform       = Linux aarch64
```

但由于窗口根本没加载页面（见 2.1），这条当时**不构成实际原因**；现在已顺手改掉：
校验窗口用设备真实 UA（`UserAgents.DEVICE_UA`）、只带 `Accept-Language`、不附桌面 Client Hints，
取页侧仍用桌面 UA，凭证靠共享 `CookieManager` 传递。改造后日志可确认：

```
I/GHS challenge window ua=Mozilla/5.0 (Linux; Android 17; PKX110 … Chrome/155… Mobile Safari/537.36
```

## 4. 已修复（提交 `15c25ca` / `c4f5816` / `e9254f2` / `f52cfdc` / `1eacad7`）

| 问题 | 处理 |
|---|---|
| 窗口不加载页面 | 恢复 `loadUrl`；校验窗口独立、用真实设备 UA；关闭即销毁 |
| 验证期间重载 | 删除两处重载，改为**只探测不重载**；需人工点选的托管挑战直接交给用户窗口，只有自算型（工作量验证）才原地等待 |
| 挑战页当正文 | 标题表补中英文变体；新增结构标记判据（`_cf_chl_opt` / `cf-chl-` / `challenge-platform` / `cf-browser-verification`）；非 2xx 兜底补 492；挑战页不入缓存 |
| 无限等待 | 页面就绪上限 45s，到点按当前 DOM 处理 |
| 取证困难 | WebView `onConsoleMessage` 接入 `GHS` 日志；校验窗口打印实际 UA |

## 5. 仍未解决

**换真实 UA 后，挑战页在应用窗口里依然是白页**：进度条停在个位数，页面脚本占住渲染线程
（`webview fallback failed IllegalStateException: 页面脚本执行超时`），因此
"点勾选 → 通过 → 自动继续取页"这条链路**没有走通**，被挡站点目前仍不可用。

已排除的假设：窗口未加载（已修）、重载作废（已修）、挑战页当正文（已修）、
请求 IP 与页面 IP 不一致（OkHttp 与 WebView 同走代理，出口 IP 实测同为 `23.177.40.49`）。

待验证的假设（按优先级）：

1. **挑战页子资源过不去**：Turnstile 的 iframe/脚本来自 `challenges.cloudflare.com`，
   经当前代理可能被拦或超时 → 主文档到了但页面停在白屏。验证方式：记录该域在 WebView 里的
   请求结果（成功/失败/耗时），对比直连与代理。
2. **该代理出口 IP 已被加重挑战**：同一出口短时间内触发十几次挑战后，站点可能把
   "点一下"升级为算力型挑战，手机渲染线程直接被占满。验证方式：换一个代理出口重试；
   若立刻渲染出勾选框即可确认。
3. **渲染线程被占用的真实来源**：白屏期间 `evaluateJavascript` 超时、CPU 却不高，
   需确认是主线程阻塞还是导航未提交（可用 `WebViewClient.onPageCommitVisible` 打点区分）。

## 6. 教训

- **不要用单行注释承载代码**：整形/自动格式化时最容易吞掉整行语句。提交前搜一次
  `^\s*//.*\b(val|var|view\.|window\.|return)\b\s{3,}` 这类形迹。
- **本机对照实验必须同网络路径**：用系统浏览器对照时，它不走应用代理，
  结论无效（本次一度据此误判为"出口 IP 对真实浏览器也不友好"）。
- **挑战类页面只探测、不重载**：任何"重试即重载"的策略都会把用户的验证进度清零。
- **判定要覆盖本地化文案**：只按英文标题判断会把中文环境的挑战页当正文缓存。
