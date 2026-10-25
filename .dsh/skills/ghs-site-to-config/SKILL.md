---
name: ghs-site-to-config
description: 把一个网站（列表页 + 详情页 + 图片/视频）做成 GHS Mobile 的动态配置（config.js 或 config.json + script.js），并在设备上验证条目、详情与媒体能取到。
whenToUse: 用户给出一个站点地址要求「做成配置 / 加个来源 / 适配一下」，或要修已有配置的选择器、分页、封面、详情类型。
---

# 网站 → 配置

把「一个网页」变成「一个配置」：先取证，再写选择器，最后在设备上验证。没有验证过的部分不许写成已完成。

本技能是本仓库的项目资产（`.dsh/skills/ghs-site-to-config/`）：`SKILL.md` 是「站点 → 配置」**操作流程**的唯一出处，
配置**格式**仍以 `docs/03-api/config-spec.md` 为准（目录规则见 `AGENTS.md` §1 补充规则 6）。

路径说明：下文 `docs/…`、`AGENTS.md` 相对**工作区根目录**；`templates/`、`references/` 指本技能目录，
二者是只读资源——复制出去改，不要就地改。

## 0. 权威文档（先读，不要凭记忆写字段）

| 想知道 | 读哪 |
|---|---|
| 字段、枚举、7 个必需方法、错误码、沙箱 API | `docs/03-api/config-spec.md` §3 §4 §5 §7 §8 |
| `Item` / `DetailInfo` / `CComic` / `Page` 的字段 | `docs/03-api/rest-contract.md` §3 |
| 应用怎么消费配置（卡片、详情、阅读器） | `docs/01-product/design.md` §5.2–§5.4 |
| 设备上的取页/求值接口 | `docs/03-api/web-admin-api.md` |

规范与实现冲突时按 `AGENTS.md` §3 回流文档，**不要在配置里绕过去**。

## 1. 先侦察，再写代码

1. **取列表页 HTML**：`web_fetch <homeUrl>`。
2. **被挡就不要硬抓**：`403` + `Just a moment...` / 「请稍候…」= Cloudflare 托管挑战，**配置解决不了**。
   改为在应用里打开该站点，用调试接口取页（`POST /api/debug/run {"code":"getHtml('…')"}`），
   或让用户先过校验；取到内容再继续。
3. **只记事实，不猜**：
   - 条目容器选择器（`li` / `article` / `div.card`…）与每页条数；
   - 详情链接在 `href` 还是 `data-href`；
   - 标题在 `h2` / `.title` / `img[alt]`；
   - 封面：`img.src` 常是占位图，真地址多在 `data-src` / `data-original` / `data-webp` / `srcset`；
   - 分页：`?page=N`、「下一页」、「加载更多」接口；**搜索页/筛选页常常是另一套列表块**（id 与页码参数都不同，
     见 `references/pitfalls.md` 第 16 条），要分别抓一次、分别验证；
   - 标签云、排序/筛选（`urlReplace`）；
   - 详情页形态：直链视频 / m3u8 / 一组图片 / 目录 + 多话。
4. **先只写 `getItems` 跑通**：条数与标题对得上，再写详情。一次验证一件事。

### 需要代理的站点

症状是直连拿不到页面：连接被重置 / `unexpected end of stream` / `CONNECT 失败` / 请求超时。此时先解决网络再谈选择器。

- **代理在应用内设**：左侧抽屉 → 设置 → 网络代理，填 `http://127.0.0.1:10808` 添加并选中。
  代理属于设备级设置（存在 `app_setting(device)`），不跟随账号同步。
- **手机上的 `127.0.0.1` 是手机自己**，不是开发机。要先打反向隧道，否则填 `127.0.0.1` 等于把全部流量
  指向一个空端口（白名单为空时"其余一律走代理"，表现为**所有站点都打不开**）：

  ```powershell
  adb reverse tcp:10808 tcp:10808
  ```

  `adb` 重连、设备重启后 `reverse` 会失效，**每次重连都要重做**。
- 也可以直接填开发机的局域网地址（项目默认 `http://192.168.31.245:10808`），前提是代理软件监听
  `0.0.0.0` 而不是只监听 `127.0.0.1`，且手机与开发机同网段。
- 白名单命中即直连、`forceProxy` 命中必走代理；站点自带 CDN 子域的，白名单别只写裸域。
- 人机校验窗口也走同一套代理：能不能过取决于**出口 IP**，不是靠换 UA。

## 2. 选形态

- **inline**：单文件 `config.js`，内容是一个返回对象的表达式——试验期首选。
- **manifest**：`config.json`（`meta` / `render` / `behavior`）+ `script.js`——交付、分发、要静态校验时用。

模板：`templates/config.js`（inline）、`templates/manifest/`（两文件）。复制出去改，不要改本 skill 目录（只读资源）。

## 3. 写配置的硬约束

- `key`：`^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$`，大小写敏感，**上线后别改**（收藏/历史按它分组）。
- `homeUrl`：必填，同时是**相对 URL 的解析基准**。
- **7 个必需方法**：`getItems` `getPagination` `getTags` `getUrlReplace` `getDetailInfo` `adapterLoadUrl` `adapterSearchUrl`，少一个即 `E-CFG-005`。
- `adapterSearchUrl` 返回**空串** = 不支持搜索（入口隐藏）；`searchUrl` 只是给作者用的前缀变量。
- `this` 绑定配置对象，方法里可用 `this.homeUrl`。
- 沙箱里**没有** `fetch` / `document` / `window` / `Buffer` / `require`。取页用 `getHtml`，同源请求用 `net.request`，需要页面上下文用 `executeJs` / `adapterImageCode`。
- 选择器是**本项目方言**：`$`、`.find` `.eq` `.each` `.attr` `.text` `.html` `.hasClass` `.map` `.toArray` `.toString`；取属性优先 `helpElAttr(el, ElementAttr.dataSrc)`、取文本 `helpElText(el)`；常量 `ElementAttr`、`ElementTypes`。
- 相对地址可以直接返回：`//x` 沿用协议、`/x` 拼 origin、`x/y` 相对 `homeUrl`；`data:` 不补全。
- `jumpUrl` 必须唯一稳定（收藏、历史、进度都以它为键）；引擎**不去重、不排序**，判重是脚本的事。

## 4. 详情按类型写（`detailType` 决定跳哪个渲染器）

| 站点长什么样 | 返回 |
|---|---|
| `<video>` / `<source>` / 直链 | `m3u8` 或 `mp4`，`details[]` 每项 `{type,url,quality?}` |
| 一组 `<img>`，或详情页就一张大图 | `image`，`details[]` 每项 `{url, fullUrl?, title?}` |
| 有「话/卷」目录，再按话取图 | `comic` + `getContents` + `getComicImages` |
| 有章节目录的正文 | `text` + `getTextContent`（返回 `{blocks}` 或 `{html}`） |
| 多线路 / 多集 | `series` + `getAnalysisDetail` / `getAnalysisVideoDetail` |
| 必须内嵌网页才能看 | `win` |

`getDetailInfo` 返回 `null` = 不支持该类型：UI 提示后返回上一页，不报错。

## 5. 验证（自取证据，见 `AGENTS.md` §8）

先确认出口：需要代理的站点，手机侧 `adb shell "curl -s -m 15 -x http://127.0.0.1:10808 https://api.ipify.org"` 的出口 IP 必须与开发机侧一致；对不上说明 `adb reverse` 没生效，或应用里选的不是这条代理。

1. 保存：走 Web 管理界面的配置编辑（`docs/03-api/web-admin-api.md`），或放 `filesDir/configs/`。
2. `adb logcat -s GHS` 看 `page items=N`：N > 0 且与页面条数一致。
3. 首页：有封面、有标题；点第一条能进详情。
4. 详情：图片出得来、视频能播；图集看目录话数与单话页数。
5. 表达式直接试（避免反复装包）：
   `{"code":"(function(){var h=cheerio.load(getHtml('https://…'));return h('.item').length})()"}`
   调试接口把 `code` 套进 `return ( … )` 求值：**必须写成单行表达式**，多行或行尾 `//` 注释都会解析失败（返回 `null`）。
6. **只写到验证过的那一步**；没在设备上跑过的，交付时说「未验证」。

## 6. 交付清单

- `key`、`homeUrl`、内容类型（image / comic / video / text / series）
- 已验证：列表条数、详情类型、媒体可播（附日志或截图口径）
- 未验证：搜索、分页到底、多话目录、需要登录的页面
- 站点前提：是否必须走代理、是否会弹人机校验

## 7. 不要做

- 不要把账号、Cookie、密钥写进配置或提交进仓库。
- 不要为某个站点在应用代码里加特判（`AGENTS.md` §7）。
- 不要用 `net.request` 跨域，也不要「借」别的站的接口。
- 不要伪造 UA 绕过人机校验：校验窗口用真实设备 UA，能不能过取决于出口网络。
- 不要在没跑通 `getItems` 的情况下先写详情与图集。
