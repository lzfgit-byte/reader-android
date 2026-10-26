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




## 21. 播放地址藏在 `eval(function(p,a,c,k,e,d){…})` 打包里

- 现象：详情页 HTML 里只有一个 `<video class="player">`，**没有 `src`、没有 `<source>`、全文只出现一次 `.m3u8`**，
  而浏览器里能放。按"找 video/src"的思路写 `getDetailInfo` 会直接返回 `null`。
- 原因：站点用 Dean Edwards 的 packer 把播放地址压成一段
  `eval(function(p,a,c,k,e,d){…}('source=\'8://7.6/…\'',16,16,'m3u8|…'.split('|'),0,{}))`；
  解包后是一段赋值语句：`source='https://…/playlist.m3u8';source842='…/720p/video.m3u8';source1280='…/1080p/video.m3u8';`。
- 正确做法：在 `getDetailInfo` 里就地把 packer 解开再取地址：
  1. 正则抠出四个参数 `('…',a,c,'词表'.split('|'))`；
  2. 打包串里的 `\'` 要**先还原成 `'`**，否则后面按引号取地址一条都取不到；
  3. 标准解码是"把 base36 token 按词表替换"：`while (c--) p = p.replace(new RegExp('\\b'+c.toString(36)+'\\b','g'), k[c])`；
  4. 再从解码结果里按 `source` / `source1280` / `source842` 取 URL（注意别让 `source` 的匹配吃掉 `source1280`，
     必须带 `\s*=`）。
- 站点常备的坑：**`source1280` 的 URL 不一定真是 1080p**——没有 1080p 的片子会指向 `…/720p/video.m3u8`，
  两个变量同一条 URL。所以要按 URL 判重，并按路径里的 `1080p` / `720p` 段决定清晰度标签，别信变量名。
- 判断技巧：详情页 `.m3u8` 只出现一次、且出现在 `eval(function(` 附近，就是打包；用
  `/api/debug/run` 跑一次解码、把结果打出来，确认能拿到绝对地址后再写进配置。

## 22. 改配置时别手搓 JSON body，写坏了会伪装成"站点 404"

- 现象：更新完配置后首页打不开，报 `取页失败：HTTP 404 https://site/…`，但同一个 URL 用
  `/api/debug/run` 里的 `getHtml('…')` 却是 200；重启后有时好、有时坏。
- 原因：配置原文被**写坏了**。用 shell 的字符串拼接造 `{"config": "…"}` body 时，一旦引号/反斜杠没转义对，
  存进去的就不是脚本，而是 `{"value":"…"}` 这种包装，或整段源码被 JSON 二次转义（`/` 变成 `\/`）。
  症状很有欺骗性：源码被转义成 `homeUrl: 'https:\/\/site\/path'` 后，扫描器取出的地址带反斜杠，
  取页请求的 path 就是 `\/path`，站点如实返回 404——看起来像镜像/节点问题。
- 正确做法：**用脚本生成 body 再传**，例如
  `python -c "…json.dumps({'format':'inline','config':src,'script':''})…" > body.json` 然后
  `curl --data-binary @body.json -H 'Content-Type: application/json'`。
- 判断技巧：改完立刻 `GET /api/configs/<key>/raw` 看一眼原文——长度应该和本地文件一致、`homeUrl` 是全斜杠；
  出现 `{"value":` 或被双重转义的 `/`，就是 body 写坏了，别去怀疑站点。

## 23. 视频 m3u8 报 403 = 缺 Referer，别急着判"IP 被封"

- 现象：详情页能解析出 m3u8 地址，播放器却立刻 `ERROR_CODE_IO_BAD_HTTP_STATUS`；把同一条地址贴进浏览器
  也是 `Sorry, you have been blocked`，连站点根路径都打不开——看起来像出口 IP 被整站封了。
- 原因：视频 CDN（本例 `surrit.com`）按 **Referer** 放行，缺 Referer 一律 403（Cloudflare 拦截页）。
  用 `curl` 复现时更迷惑：curl 自己的 TLS 指纹本来就会被挡，带不带 Referer 都是 403，证明不了什么。
- **正确的取证方式**：在应用里用 `net.request` 做 A/B 对照——同一条 m3u8，一次带 Referer、一次不带：
  ```js
  (function(){
    var u='https://cdn.example.com/xxx/playlist.m3u8';
    var a=net.request({url:u,headers:{'Referer':'https://site.example/detail/123'}});
    var b=net.request({url:u});
    return 'A '+a.status+' | B '+b.status;   // 期望 A=200、B=403
  })()
  ```
  A 返回 `#EXTM3U`、B 返回 Cloudflare 拦截页，就说明是 Referer 而不是 IP/代理的问题。
- 结论要求：**playlist 之后的子播放列表和分片也要带同一个 Referer**，否则会"能开播、播几秒断"。
  应用侧的做法是在播放器的 DataSource 上设默认请求头（一次设置，覆盖全部后续请求），
  配置侧不需要写任何东西；如果某个播放器/站点仍失败，检查的是"默认请求头有没有挂到分片请求上"。
- 判断技巧：403 时先看**同一条 URL 带 Referer 是否 200**，再看根路径是否也 403（也被挡=更像 IP/WAF 规则，
  单文件被挡=更像 Referer/签名）。别用 `curl` 的结论下判断。


## 24. 页图地址是加密的（`data-locked`）+ S3 预签名只有 60 秒

- 现象：阅读页里 `<img src="">` 全是空的，整页一个图片地址都找不到（`grep images.` 也搜不到），
  但浏览器里能看；或者能看，翻到后面几页突然全是 403。
- 原因（两条一起出现）：
  1. 真地址不在 `src`，而在 `<img class="jcomic-img" data-locked="JCOMIC_TRAPS_<base64>">`。
     站点自己的 JS 是这么解的：**去掉 `JCOMIC_TRAPS_` 前缀 → 整串反转 → `atob`**，解出来就是图片地址。
     沙箱里 `atob` 是可用的（`typeof atob === 'function'`），不用自己写 base64。
  2. 解出来的地址是 **S3 预签名**（`X-Amz-Expires=60`）：60 秒后签名过期，再请求就是 403。
     所以「列表载入就立刻渲染」「阅读按顺序翻」没问题，放很久再回来看旧页一定 403——这是站点设计，
     配置侧无解，别当 bug 查。
- 正确做法：`getComicImages` 里遍历 `.jcomic-img`，优先解 `data-locked`，没有锁的才退回 `src`。
- 判断技巧：`<img>` 有 class 没有 src + 页面里有 `data-locked` / `data-…` 这类"锁"属性，
  先在页面内联 `<script>` 里搜这个属性名——解密流程通常就写在里面（本例是 `IntersectionObserver` 回调）。

## 25. 图床被 Cloudflare 按请求速率拦：单张 200、连发几张就 403 挑战

- 现象：列表封面第一次能加载，滑几下之后全部变成"重新加载"；阅读器里几乎全部 403，还会不停弹校验窗口。
  同一个图片地址用 `curl` 测又是 403，看起来像"这个图床挂了"。
- 原因：图片主机的 Cloudflare 规则对**请求速率 + 客户端特征**敏感。实测同一出口、同一签名地址：
  - 只带 `Referer`、用默认 UA → **200**；
  - 紧接着连发 3 个（换 UA / 带浏览器 `Accept`）→ **全部 403 挑战**。
  也就是说单发能过、成串就拦，阅读器一次预取十几张必然触发。
  另外注意：**这个图床直连是 TLS 被断**（`ERR_CONNECTION_CLOSED`），只能走代理。
- 取证的坑：`curl` 的 TLS 指纹本来就会被挡，用它判断"图床是否可用"会得出错误结论；
  用 `net.request` 做单发/连发对照才看得出来（先 `Referer` 单发 → 200，再连发 → 403）。
- 交付口径：这种情况下配置是好的（列表/标签/目录/解出的图片地址都对），
  但"图片能不能显示"取决于出口 IP 与请求节奏，必须如实写成「未验证」，并说明换出口节点/降低并发后再试。

