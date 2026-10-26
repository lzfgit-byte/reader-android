# 站点适配的常见坑（按踩到的顺序）

每条都对应线上真实现象。遇到先对照这里，再改选择器。

## 1. 被 Cloudflare 挡住：配置无解

- 现象：`fetchHtml 403` + 正文标题 `Just a moment...` / 「请稍候…」；`page items=0`。
- 判断：HTML 里有 `_cf_chl_opt` / `challenge-platform`，或弹了人机校验窗口。
- 处理：这是**网络/校验问题，不是选择器问题**。让站点在应用里过一次校验，或换出口网络。
  写配置时不要试图用 UA/伪装绕过去。

## 2. 懒加载封面：`src` 是占位图

- 现象：卡片全是同一张灰色图或空白。
- 原因：真地址在 `data-src` / `data-original` / `data-webp` / `srcset`。
- 写法：`helpElAttr($img, ElementAttr.dataSrc) || helpElAttr($img, ElementAttr.src)`。

## 3. 分页只给「下一页」，或者根本没分页

- 「触底加载下一页」靠 `getPagination` 里**当前页之后的第一条**；返回 `[]` 就没有无限滚动。
- 当前页必须 `isCurrent: true`；禁用项用 `url: ''`（引擎不过滤，UI 只按返回项渲染）。
- 页码是 JS 拼的（`javascript:gotoPage(2)`）时，用 `adapterLoadUrl` 把它换成真实地址。

## 4. 详情页与图集不是一回事

- 详情页有一组小图 → `detailType: 'image'`，每项给 `url`（缩略/直链）与 `fullUrl`（原图）。
- 有「话/卷」目录 → `'comic'` + `getContents`（目录）+ `getComicImages`（按话取图）。
  漏了 `getContents` 时目录为空，阅读器只剩单组图。
- 单话里图片由 JS 拼出来（乱序分片）→ 用 `adapterImageCode`（字符串代码，页面上下文执行，
  异步回写 `imgSrc.value`），并把分片参数放进 `CComic.info`。

## 5. 文本正文：只保留 `<img>`

- `getTextContent` 返回 `html` 时，引擎清洗成结构块：**除 `<img src="…">` 外都是纯文本**。
  不要指望 `<table>` / `<span class="bold">` 之类的结构被保留。

## 6. 搜索入口的显隐看返回值

- `adapterSearchUrl` 返回空串 → 隐藏搜索；`searchUrl` 是空串**不代表**不支持搜索。

## 7. 相对地址与协议相对地址

- 返回 `//host/x`、`/x`、`x` 都能被补全；`data:` 原样保留。
- 但 `javascript:` / `#` 这类链接补不出来：这类卡片直接跳过，别塞进 `jumpUrl`。

## 8. 防盗链与必需请求头

- 图片/视频 403 但页面正常 → 试 `behavior.headers` 加 `Referer`（同站首页）。
- 需要 Cookie 的站点：Cookie 由应用统一管理，**不要**写进配置。

## 9. 页面要 JS 渲染

- 静态 HTML 里没有任何条目 → `behavior.useWebView: true`，或 `ifWinExecCode` 注入等待/展开脚本。
- 但 `useWebView` 更慢、更吃内存：先确认真的没有 SSR。

## 10. `/api/debug/run` 只吃单行表达式

- 引擎把 `code` 套进 `return ( … )`：多行代码、行尾 `//` 注释都会让整段解析失败，接口安静地返回 `null`。
- 写成一条 `(function(){ … })()` 单行表达式；需要换行时先在外面拼好。
- 拿不到结果时先怀疑这一点，再去怀疑选择器。

## 11. `javascript:` / `#` 链接要挡掉

- 卡片链接是 `href="javascript:;"` 或 `"#"` 时补不出地址，条目会变成"点了没反应"。
- 判断：`if (!href || href.indexOf('javascript:') === 0 || href === '#') return;`

## 12. 把 `127.0.0.1:10808` 当成开发机代理

- 手机上的 `127.0.0.1` 是手机自己。直接填进应用代理，等于把所有请求指向手机上的空端口：
  白名单为空时"其余一律走代理"，结果是**连本来能直连的站点也打不开**。
- 正确做法：`adb reverse tcp:10808 tcp:10808`，让手机本机 10808 转发到开发机的 10808。
- `adb` 重连 / 设备重启后 `reverse` 失效，症状是"刚才还好好的，突然全站超时"——先 `adb reverse --list` 看一眼。
- 对拍出口 IP：手机 `curl -x http://127.0.0.1:10808 https://api.ipify.org` 与开发机同一条命令的输出必须一致；
  手机不走代理直连时会是另一个 IP——两个数一样，才说明隧道真的通了。

## 13. 用 PowerShell 发含中文的 JSON body 会"假失败"

- 现象：POST `/api/validate` 传一份带中文注释的配置，明明方法都缩进对齐了，却全报 `E-CFG-005 缺少必需方法`；
  换成 node 直接发同样的字节 → `ok: true`。
- 原因在客户端编码，不在服务端（服务端 12KB body 都收得住）。发这类请求用 node/`curl --data-binary` 发原始 UTF-8 字节。
- 判断技巧：先用一份纯 ASCII 的小配置验证接口本身，再换成真实配置——两次结论不一致就怀疑客户端。

## 14. 分页控件是 AJAX 按钮时，别把整份页码当分页数组

- 常见形态：`<a href="#videos" data-parameters="sort_by:…;from:2">2</a>`——链接是假的，页码在 data 属性里。
- 站点常在末尾塞一个「跳到最后一页」，直接顺着用，触底加载会从第 5 页一步跳到第 489 页。
- 正确做法：只取「包含当前页的连续区间」，并保证区间里有 `当前页 + 1`；总页数从控件文字（`5/489`）读。
- 地址形态要实测：`/<x>/2/` 能用、`?page=2` 被忽略、`?sort_by=` 能改排序——同一个站点里三种写法可能只活一种。

## 15. 一次只验证一件事

先用调试接口数条数，再进详情看类型，最后看媒体。混在一起改，出问题分不清是选择器还是类型。

## 16. 同一站点里，搜索页和列表页是两个不同的列表块

- 现象：分类页/标签页分页正常，一进搜索结果分页就空——列表有 24 条，但触底没有"下一页"，也没有页码。
- 原因：搜索结果是**单独渲染的一套块**，id 和页码参数都不通用：
  `#list_videos_videos_list_search_result_*` + `from_videos+from_albums:N`，
  而分类/标签页是 `#list_videos_common_videos_list_*` + `from:N`。选择器只写了后者，前者自然空。
- 正确做法：`getPagination` 用**候选容器表**逐个探测，命中哪个就用那套规则；地址生成带 `mode`
  （路径式 `/<x>/2/`、查询式 `?from_videos=2`），换排序/换标签时两种都要能退回第 1 页。
- 三个连带坑（都是实测踩到的）：
  1. 搜索页翻页**没有路由**：`/search/<kw>/2/` 是 404，`?page=` 被忽略，只有 `?from_videos=N` 有效（N 就是页码）。
  2. 末页那个"下一页"是 `--disabled` 的假链接，值比总页数大 1（`from_videos=14` → 404）；分页 `a` 里
     class 带 `disabled` 的一律跳过，否则会一路生成 404 地址。
  3. 搜索页的 `link[rel=canonical]` 是**解码后**的中文（`/search/娜娜/`）。拿它当 base 要把非 ASCII 重新编码，
     但别整串 `encodeURI`/`encodeURIComponent`——会把已有的 `%E5` 二次编码成 `%25E5`。

## 17. 配置里调用 `getHtml` 拿到的还是相对地址

- 现象：列表、封面一切正常，**一点条目就失败**，`/api/state` 的 `detailError` 是
  `脚本错误：Error: Expected URL scheme 'http' or 'https' but /readbooks/… was passed in`。
- 原因：站点给的 `href` 都是站内相对路径。引擎只在**条目入库时**补全 `jumpUrl`，
  配置自己调用 `getHtml(url)` / `getComicImages(url)` 时拿到的是原样字符串，OkHttp 直接抛 scheme 错误。
- 正确做法：`getItems` 里就把 `jumpUrl`、分页 `url`、目录 `url` 全部拼成绝对地址
  （`origin + href`，`origin` 取 `homeUrl` 去掉结尾斜杠）；相对地址只在"交给引擎"的返回值里才安全，
  在"配置内部再取页"的路上不安全。
- 判断技巧：`detailError` 出现这条，先看 `getItems` 返回的 `jumpUrl` 是否以 `/` 开头——是就别再查选择器了。

## 18. 换语言前缀（`/cn`、`/zh-tw`）后，绝对地址正则全部落空

- 现象：把 `homeUrl` 从 `https://mycomic.com/` 换成 `https://mycomic.com/cn` 之后，列表变 **0 条**，
  但页面本身正常（`/api/state` 里 `url` 对、`tags` 还有 20 个，只有 `items: 0`）。
- 原因：语言前缀会出现在**所有链接**上（`https://mycomic.com/cn/comics/1128`），而 `getItems` 里用
  `^https?://site\.com/comics/\d+$` 这种精确正则过滤 href，一条都匹配不上，整页卡片被 `return` 掉。
- 正确做法：正则留可选前缀 `^https?://site\.com(/[a-z]{2})?/comics/\d+$`，或只判断 path 里含 `/comics/<id>`。
  另外，配置里**手工拼**的地址（章节、资料库、排行、搜索）也要跟着前缀走——用 `this.homeUrl` 去算，
  别写死域名，否则列表是简体、点进去又变回繁體。
- 判断技巧：`items: 0` 但 `tags` 正常、页面在浏览器里能开，先看卡片链接长什么样，再看正则要不要前缀。

## 19. `src` 是 1x1 透明占位、真地址在 `data-src`（lozad）

- 现象：列表**第一张封面正常，第二张起全是空白灰块**；`items` 数量却完全正确。
- 原因：列表用 lozad 懒加载，非首屏卡片的 `src` 是 `data:image/png;base64,…`（1x1 透明占位），真地址在 `data-src`。
  只读 `src` 等于把占位图当封面，界面渲染出来就是一块灰。
- 正确做法：`coverImg = data-src || src`，且**丢掉 `data:` 开头的值**（宁可空着让 UI 出首字占位，也别渲染 1x1）；
  页图（`getComicImages`）同理，常见形态就是"前几张给 src、其余给 data-src"。

## 20. 目录被拆成多段（单话 / 单行本 / 番外），别只抓第一个数组

- 现象：目录里只有正文单话，**番外/特别篇一条都没有**；换成"把能找到的数组全拼上"，又会混进「第110卷」这种
  整卷合集——和单话内容重复，进度、跳话全乱。
- 原因：书目页把目录拆成多个 `<div x-data='{ … chapters: [...] … }'>` 段，每段的标题紧跟在属性后面：
  `<div data-flux-subheading><div>单话</div>`（同一页里还有「单行本」「番外篇」两段）。
  只 `indexOf('chapters: [')` 取第一个，就只拿到一段。
- 正确做法：循环找出**每一个** `chapters: [`，各自按方括号配对截数组，再用"数组之后最近的那个
  `data-flux-subheading` 文本"给这段归类；然后按语义取舍与排序（本例：只要 单话 + 番外篇，跳过 单行本，
  且番外排在单话后面）。
- 判断技巧：目录条数明显少于站点上看到的段数之和、或出现「第N卷」这种标题，就是这个坑。



