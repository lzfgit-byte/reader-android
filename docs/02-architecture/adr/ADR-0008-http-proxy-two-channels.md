> **状态**：approved
> **版本**：v1.1
> **最后更新**：2026-10-19
> **取代/被取代**：无（两条通道的决策不变）；**阶段划分与白名单判定**已由 [ADR-0009](ADR-0009-proxy-whitelist-single-decision.md) 修订

# ADR-0008：HTTP 代理对两条出站通道同时生效

## 状态

`approved`

## 背景

应用有两条完全不同的出站通道：

| 通道 | 使用者 | 技术 |
|---|---|---|
| **A. 应用请求** | 取页、图片、视频代理、WebDAV、配置校验时的探测 | OkHttp |
| **B. 无头 WebView** | 需要真实浏览器环境的页面（JS 渲染、人机校验） | Chromium WebView |

代理必须两条都覆盖：只在 A 生效时，一旦某来源需要走 B 通道，代理就失效，用户会看到"某些来源能打开、某些打不开"的诡异现象。

而 Android 上这两条通道的代理能力并不对等：

| 能力 | OkHttp | WebView（`androidx.webkit` ProxyController） |
|---|---|---|
| 设置 HTTP 代理 | ✅ 直接构造 `Proxy` | ✅ `ProxyConfig.addProxyRule("host:port")` |
| 代理认证（用户名/密码） | ✅ `proxyAuthenticator` 处理 407 | ❌ **不支持** |
| 绕过列表 | ✅ 自己判断 | ✅ `addBypassRule` |
| 按请求切换 | ✅ | ❌ 进程级生效 |

## 备选方案

| 方案 | 说明 | 取舍 |
|---|---|---|
| A. 只支持应用请求 | OkHttp 走代理，WebView 直连 | 实现最省，但两条通道行为不一致，排查困难 |
| B. 都走 ProxyController + OkHttp 代理 | 各自用原生能力 | 无认证代理下完整可用；**认证代理下 WebView 不可用** |
| C. B + 内置转发代理 | 应用内起一个本地转发代理，WebView 指向 `127.0.0.1`，由转发层补 `Proxy-Authorization` | 认证代理也能覆盖；代价是实现 CONNECT 隧道与字节转发 |
| D. 依赖系统 Wi-Fi 代理 | 让用户去系统设置里配 | 全局影响其他应用，且应用无法感知与提示 |

## 决策

采用 **C 分两阶段**：

**v1（阶段一）**
1. 代理配置只有一份（应用端，`device` 作用域），字段：`enabled`、`host`、`port`、`username?`、`password?`、`bypass[]`、`scope{app, webview}`；
2. **应用请求**：OkHttp `proxy()` + `proxyAuthenticator`（Basic，处理 407）；
3. **无头 WebView**：`ProxyController` + `ProxyConfig`（`androidx.webkit`，需 `WebViewFeature.isFeatureSupported(PROXY_OVERRIDE)`）；
4. **能力探测**：设置页明确显示两条通道各自的状态（可用 / 不支持 / 需要认证不可用），不允许"看起来配好了但实际没生效"。

**v1.1（阶段二）**
5. **内置转发代理**：`127.0.0.1:随机端口`，支持绝对 URI 转发与 `CONNECT` 隧道，向上游补认证头；WebView 指向它，从而覆盖"认证代理 + WebView"的组合。

## 理由

1. **一致性优先**：同一个配置单元在不同页面可能落到 A 或 B 通道，代理行为不一致会让"能不能访问"变成玄学。
2. **不隐藏能力差异**：ProxyController 不支持认证是平台事实，硬做会变成"静默失败"。显式展示两条通道状态，用户才知道该换无认证代理还是等 v1.1。
3. **转发代理是关键补丁而非主路径**：无认证时 `ProxyController` 已足够，转发代理只解决"认证 + WebView"这一种组合，值得做但不该是 v1 的阻塞项。
4. **不做 SOCKS**：需求明确为 HTTP 代理；SOCKS 在 WebView 侧更不可控，留待明确需求再提 ADR。

## 后果

**正面**

- 一份配置覆盖全部出站流量，排障时"改一处、两条通道同时变"
- 认证与不支持的情况可见，不是静默降级
- 视频/图片（走 HTTP）自动受益，不需要额外开关

**负面（必须正视）**

- **`ProxyController` 是进程级的**：WebView 代理不能按请求切换，调试台与正式运行共享同一设置
- 认证代理下 v1 的 WebView 通道不可用（已知限制，v1.1 解决）
- 转发代理阶段需要处理 CONNECT 隧道、并发与超时，实现成本不低
- 代理配置属于 `device`，**不参与同步**（见 [local-store](../../03-api/local-store.md) §2），换机要重填

## 缓解

- 设置页提供"连通性检测"：用当前配置的 `homeUrl` 发 HEAD，显示耗时与出口结果，不内置任何第三方探测地址
- 能力探测失败时给出可操作提示（"当前 WebView 不支持代理" / "代理需要认证，WebView 通道暂不可用"）
- 代理变更后清空 HTML/图片缓存，避免旧直连结果污染判断

## 相关

- [local-store.md](../../03-api/local-store.md) §2（`device` 作用域）
- 设计文档 §1 D10、§5.22、§8.13
- [ADR-0002](ADR-0002-embedded-engine-dual-provider.md)（两条通道的来源）
- [ADR-0003](ADR-0003-script-runtime-quickjs-webview.md)（WebView 通道的定位）
