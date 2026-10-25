> **状态**：draft
> **版本**：v1.1
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

## 5. 复核：定位到 4 个具体缺陷（2026-10-25 15:57，抓包 13:55–15:57）

被挡站点仍不可用，但结论要改口径：**挑战组件是能渲染、能加载的，问题是"过不去"，
而且应用自己会在几条路径上把机会掐掉**。连续抓包把原来的"白屏"细化成下面 4 个可修缺陷，
前三个能直接用日志时间戳对上。

### 5.1 缺陷 A：不带 CF 标记的拦截页被判成"校验通过"

```
14:36:39.354  fetchHtml 492 → 展示校验窗口 https://<site>
14:36:40.731  challenge http 492 https://<site>/
14:36:40.741  challenge page finished: https://<site>/
14:36:43.953  人机校验完成，重试 https://<site>          ← 距页面完成 2.5 s
14:36:44.631  人机校验后仍被挡（挑战页） https://<site>
```

`2.5 s` 正好是 `FIRST_CHECK_DELAY_MS`，说明这一步**不是用户点的**，是 `DETECT_JS`
自动判定通过。判据是「标题不含关键词 + 无 `_cf_chl_opt` 标记 + `body.innerText` 长度 > 200」，
而站点自定义拦截页（本例 492 页）正文足够长、标题也不命中关键词 → 假通过 → 重放请求
仍被挡 → 窗口再弹一次。用户看到的"点了也过不去、又弹一次"有相当一部分来自这里。

修法：判据要证明"已经拿到目标内容"，而不是"看起来不像挑战页"——例如要求导航后的 URL
与目标一致、或由配置声明 `readySelector` 证明目标结构已出现；退一步至少要把
「主框架出过 4xx/5xx」也算作未通过。

### 5.2 缺陷 B：`403` 两连击会把正在进行的校验窗掐死

Cloudflare 的挑战页**本身就是 403**，所以窗口每次打开时 `httpErrorCount` 已经是 1：

```
14:24:33.586  challenge http 403 …      ← 挑战页自身
14:30:13.512  challenge http 403 …      ← 同上
14:32:41.805  challenge http 403 …      ← 同上
14:18:04.927  challenge http 403 …
14:18:04.928  challenge 连续 2 次 403，放弃本次校验     ← 第二条 403 直接关窗
```

这条启发式原本想识别"代理出口被封"，实际是在用户还有机会的时候把窗口关掉。

修法：第一条 403 属于挑战页自身，不计入；只在「未通过验证 + 多次 403 + 期间没有任何
页面加载完成」时才判定出口被封。

### 5.3 缺陷 C：闸门超时后没人接收迟到的成功

```
14:10:22.911  fetchHtml 403 → 展示校验窗口
14:15:22.920  fetchHtml done 321350ms        ← 正好 300.0 s：闸门 5 分钟上限到点
14:15:24.339  challenge page finished        ← 1.4 s 后窗口里的页面才加载完成
14:18:38.419  fetchHtml 403 → 展示校验窗口
14:23:38.432  fetchHtml done 321030ms        ← 又是 300.0 s
14:23:40.392  challenge page finished        ← 2.0 s 后
```

`ChallengeGate.await` 到点返回 `false`，但校验窗口还开着；用户在此之后才完成验证时，
Cookie 已经写进 `CookieManager`，这一次取页却早已失败——没有任何东西消费这个成功。

修法：超时不要直接判死——窗口里的页面完成后主动重放本次请求（或通知界面刷新），
至少不要留下"窗口还开着、请求已死"的中间态。

### 5.4 缺陷 D：兜底 WebView 在空文档上抛错

```
14:36:39.354  webview fallback failed IllegalStateException:
              页面脚本出错：TypeError: Cannot read properties of null (reading 'outerHTML')
```

`document.documentElement` 为 null（文档还没建好）时脚本直接抛错，兜底路径当场失败，
而不是继续等页面就绪。这是"手快就白屏"的一个来源。修法：脚本里对
`documentElement`/`body` 判空后返回空串，让调用方按"还没就绪"处理。

### 5.5 已排除 / 仍未排除

已排除：窗口未加载（已修）、重载作废（已修）、挑战页当正文（已修）、
请求 IP 与页面 IP 不一致（OkHttp 与 WebView 同走代理，出口 IP 实测同为 `23.177.40.49`）。

仍未排除（需要新证据，不能靠推测）：

1. **代理出口 IP 被加重挑战**：同一出口一天内触发十余次挑战后，站点可能把"点一下"
   升级为需要更多信号的计算型挑战。验证方式：换一个出口重试，若立刻出勾选框即可确认。
2. **验证提交是否被代理改写**：`challenges.cloudflare.com` 的 `api.js` 与
   `…/rch/…/light/fbE/new/normal` iframe 在窗口里是加载成功并执行了的（其中脚本的
   console 输出可见，噪声只有 `Unrecognized feature: 'xr-spatial-tracking'`、
   `Cannot find Widget cf-chl-widget-…` 和一条 `data:,` 被 CSP 拦下），但验证提交请求的
   结果码没抓到。验证方式：在窗口里用 `PerformanceObserver` 记录该请求的
   `responseStatus`。
3. **勾选框是否真的可见**：抓包时段没有同一时刻的截图，只能确认 iframe 文档执行了，
   不能确认用户看得到、点得动。下次复现必须同时截图。

## 6. 教训

- **不要用单行注释承载代码**：整形/自动格式化时最容易吞掉整行语句。提交前搜一次
  `^\s*//.*\b(val|var|view\.|window\.|return)\b\s{3,}` 这类形迹。
- **本机对照实验必须同网络路径**：用系统浏览器对照时，它不走应用代理，
  结论无效（本次一度据此误判为"出口 IP 对真实浏览器也不友好"）。
- **挑战类页面只探测、不重载**：任何"重试即重载"的策略都会把用户的验证进度清零。
- **判定要覆盖本地化文案**：只按英文标题判断会把中文环境的挑战页当正文缓存。
- **"看起来不像挑战页"不等于"通过"**：判据的产物必须是「目标内容出现」。否则拦截页会被
  判成成功，窗口一关、重试再挡，观感就是死循环。
- **时间戳能反推竞态**：`done 300.0 s` 与 `page finished +1.4 s` 这种整数对齐，是
  "超时判死"最直接的证据；不抓连续日志就只能靠猜。

## 7. 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-25 | 初版：现象、三条根因（窗口 `loadUrl` 被注释吞掉 / 验证期间反复重载 / 挑战页当正文缓存）、取证、已修复清单、待验证假设 |
| v1.1 | 2026-10-25 | 依据 13:55–15:57 连续抓包复核：§5 改写为 4 个可修缺陷（拦截页假通过 / 403 两连击掐窗 / 闸门超时丢结果 / 空文档抛错），均带时间戳；原"白屏"结论与三条假设按新证据归并到 §5.5；补两条教训与本文档变更记录 |
