/**
 * GHS Mobile · 配置脚本 API 类型定义（GHS Script API v1）
 *
 * ⚠️ 本文件是 `docs/03-api/config-spec.md` §3/§8 与 `docs/03-api/rest-contract.md` §3 的**派生物**。
 *    - 改规范 → 必须同步改本文件（由 `pnpm check:dts` 校验）
 *    - 不得以本文件反推修改规范
 *
 * 用途：Web 管理界面把本文件注入 Monaco（`addExtraLib`），
 *      获得补全 / hover 文档 / 参数签名 / 诊断。见 ADR-0014。
 */

// ───────────────────────────── 选择器（GHS Select） ─────────────────────────────

/**
 * 选择器结果集，兼容 jQuery / cheerio 常用写法。
 * 取值一律返回字符串（取不到返回空串 ''），不会返回 undefined。
 */
interface Selection {
  /** 命中元素个数 */
  length: number
  /** 遍历，`el` 为裸元素句柄，用 `$(el)` 再包装 */
  each(fn: (index: number, el: any) => void): Selection
  /** 在子树中筛选 */
  find(selector: string): Selection
  /** 父节点 */
  parent(): Selection
  /** 子节点 */
  children(): Selection
  /** 下一个兄弟 */
  next(): Selection
  /** 上一个兄弟 */
  prev(): Selection
  /** 按下标取 */
  eq(index: number): Selection
  /** 第一个 */
  first(): Selection
  /** 最后一个 */
  last(): Selection
  /** 最近的祖先匹配 */
  closest(selector: string): Selection
  /** 是否含某个 class —— 判断「当前页 / 禁用项」靠它 */
  hasClass(name: string): boolean
  /** 取属性；不存在返回 '' */
  attr(name: string): string
  /** 取文本；不存在返回 '' */
  text(): string
  /** 取内部 HTML */
  html(): string
  /** 取表单值 */
  val(): string
  /** 取属性（property 语义） */
  prop(name: string): string
  /** 取 data-* 属性 */
  data(name: string): string
  /** 按 `name` 过滤，比 `find` 更快且更贴近「只保留匹配项」的语义 */
  filter(selector: string): Selection
  /** 是否命中过元素 */
  is(selector: string): boolean
  /** 转为数组 */
  toArray(): any[]
  /** 返回该元素的 outerHTML；空集返回 '' */
  toString(): string
}

type Selector = (selector: string) => Selection

/** cheerio 兼容对象（第二参数与全局同名） */
interface CheerioLike {
  /** 把 HTML 字符串解析为选择器根 */
  load(html: string): Selector
  /** 再暴露一遍运行时能力，便于 `cheerio.getHtml(...)` 写法 */
  getHtml: typeof getHtml
  net: typeof net
  parse: typeof parse
}

// ───────────────────────────── 常量表 ─────────────────────────────

/** 属性名常量（键 camelCase，值为真实属性名） */
interface ElementAttrMap {
  src: 'src'
  href: 'href'
  title: 'title'
  alt: 'alt'
  class: 'class'
  poster: 'poster'
  dataSrc: 'data-src'
  dataOriginal: 'data-original'
  dataWebp: 'data-webp'
  dataType: 'data-type'
  dataEcho: 'data-echo'
  dataSource: 'data-source'
  dataError: 'data-error'
}

/** 标签名常量 */
interface ElementTypeMap {
  a: 'a'
  img: 'img'
  h1: 'h1'
  h4: 'h4'
  p: 'p'
  span: 'span'
  div: 'div'
  video: 'video'
  source: 'source'
}

// ───────────────────────────── 运行环境 ─────────────────────────────

/**
 * 取 HTML。含自动兜底：普通请求失败会降级到无头浏览器通道。
 * 取页过程中会以 `hashString(url)` 为 key 向 `eventEmitter` 广播进度字符串。
 */
declare function getHtml(url: string, forceWebView?: boolean): Promise<string>

/** 带进度上报的取 HTML */
declare function getHtmlWithProcess(url: string): Promise<string>

/** 把 HTML 字符串解析为选择器根（等价于 `cheerio.load`） */
declare function parse(html: string): Selector

/** 通用请求。**仅允许与 homeUrl 同源**；返回结果一次性给出（不是事件流）。 */
declare const net: {
  request(options: {
    url: string
    method?: string
    headers?: Record<string, string>
    body?: string | Record<string, unknown>
  }): Promise<{ status: number; headers: Record<string, string>; body: string }>
}

/** 原始文本 GET */
declare function request_string_get(url: string): Promise<string>

/** 稳定哈希：同一输入在任何进程 / 版本下恒定（缓存键与事件 key 依赖它） */
declare function hashString(input: string): string

/** 取属性；不存在返回 '' */
declare function helpElAttr(sel: Selection, name: string): string

/** 取文本；不存在返回 '' */
declare function helpElText(sel: Selection): string

/** 在无头页面上下文执行脚本；返回值经 JSON 序列化传递 */
declare function executeJs(url: string, code: string, show?: boolean): Promise<any>

/** 取当前列表已解析条目 */
declare function getCurrentItems(): Item[]

/** 状态胶囊不足时可用此写运行日志；参数任意类型会被字符串化（单条上限 4KB） */
declare const LogMsgUtil: { sendLogMsg(...args: any[]): void }
/** 兼容 console 用法 */
declare const ConsoleLogUtil: { log(...args: any[]): void }
/** 顶层通知；key 通常取 `hashString(url)` */
declare const NotifyMsgUtil: {
  sendNotifyMsg(title: string, msg: string, key: string): void
  close(key: string): void
}
/** 进度上报；key 可为任意字符串（如 `'all'`） */
declare const ProgressMsgUtil: {
  sendProgress(percentage: number, key: string, title?: string, msg?: string): void
  close(key: string): void
}
/** 轻提示 */
declare const MessageUtil: {
  info(msg: string): void
  error(msg: string): void
  success(msg: string): void
  warning(msg: string): void
}

/** 脚本内事件总线；订阅取页进度常用 `eventEmitter.on(hashString(url), fn)` */
declare const eventEmitter: {
  on(key: string, fn: (...args: any[]) => void): void
  off(key: string, fn: (...args: any[]) => void): void
  emit(key: string, ...args: any[]): void
}

/** 只在 `adapterImageCode` 的页面上下文注入（spark-md5） */
declare const Md5: { hashStr(input: string): string }

// ───────────────────────────── 领域模型 ─────────────────────────────

interface Tag {
  title: string
  /** 为空或缺失表示占位 / 分隔，不可点击 */
  url?: string
}

interface Pagination {
  title: string
  url: string
  isCurrent: boolean
}

interface UrlAppend {
  value: string
  param: string
  title: string
  current: boolean
}

interface UrlReplace {
  schema: string
  urlAppend: UrlAppend[]
}

interface Item {
  /** 详情页地址；唯一键，缺失会被丢弃 */
  jumpUrl: string
  title: string
  coverImg: string
  /** 缺失按 image 处理 */
  type?: 'video' | 'image' | 'comic' | 'text'
  /** 缺失按 normal 处理 */
  renderType?: 'normal' | 'force'
  tags?: Tag[]
  /** 作者 / 上传者，可选 */
  author?: string
}

interface Page {
  items: Item[]
  pagination: Pagination[]
  tags: Tag[]
  urlReplace?: UrlReplace[]
}

interface Comment {
  datetime: string
  comment: string
  image?: string
}

interface Detail {
  type?: 'mp4' | 'm3u8' | 'image' | 'comic' | 'text' | 'series' | 'win'
  /** 展示地址（缩略图或直链） */
  url: string
  /** 原图地址；阅读器优先用它 */
  fullUrl?: string
  title?: string
  quality?: string
  comments?: Comment[]
  uniqueKey?: string
}

interface Analysis {
  url: string
  title: string
  /** 已知集数；0 表示未知 / 只有一集 */
  total: number
}

interface AnalysisDetail {
  url: string
  title: string
}

interface AnalysisVideoDetail {
  type?: 'mp4' | 'm3u8'
  url?: string
  name?: string
  description?: string
  [key: string]: any
}

interface DetailInfo {
  detailType: 'mp4' | 'm3u8' | 'image' | 'text' | 'comic' | 'series' | 'win'
  renderType?: 'normal' | 'force'
  details: Detail[]
  relations?: Item[]
  analysis?: Analysis[]
}

interface CContent {
  title: string
  url: string
  isCurrent: boolean
  /** 章节时间，可选（UI 可忽略） */
  time?: string
}

interface CComic {
  url: string
  /** 解图参数，会作为 `adapterImageCode` 的 `extra` 传入 */
  info?: Record<string, any>
  /** 该图关联的评论 */
  extra?: Comment[]
}

interface TextBlock {
  type: 'p' | 'image'
  /** `p` 的正文 */
  text?: string
  /** `image` 的地址 */
  src?: string
}

interface TextContent {
  title?: string
  /** 纯文本，可内嵌 `<img src="…">` */
  html?: string
  /** 作者已拆好的块，优先于 html */
  blocks?: TextBlock[]
  prevUrl?: string
  nextUrl?: string
}

interface ReadingProgress {
  itemUrl: string
  kind: 'text' | 'image' | 'video'
  chapterUrl?: string
  chapterIndex: number
  chapterCount: number
  chapterOffset: number
  positionMs?: number
  durationMs?: number
  percent: number
  updatedAt: number
}

// ───────────────────────────── 配置对象 ─────────────────────────────

interface ConfigMeta {
  /** 唯一标识，`^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$`（允许大小写） */
  key: string
  name: string
  version?: string
  /** 首页地址，同时是相对 URL 的解析基准 */
  homeUrl: string
  favicon?: string
  /** 仅作作者自己的前缀变量，是否支持搜索看 adapterSearchUrl */
  searchUrl?: string
  description?: string
  /** 需要的脚本 API 版本；高于引擎支持版本会被拒绝加载 */
  requiresApi?: number
  /**
   * 该来源的请求是否走代理。默认 `true`。
   *
   * **只有显式写 `false` 才直连**；设备侧的代理总开关是最终闸门——
   * 开关关掉时任何来源都直连，这里写 `true` 也不会生效。
   */
  isProxy?: boolean
  /**
   * 强制走代理的地址（命中即代理，**优先于 `isProxy`**）。
   *
   * 写法与旧白名单一致：`example.com`（同时覆盖其子域，如 `cdn.example.com`）、
   * `host:port`（额外限定端口）、`*`（全部地址）。
   * 典型用法：站点整体直连（`isProxy: false`），但图床 / 视频 CDN 必须过代理。
   */
  forceProxy?: string[]
}

interface ConfigRender {
  imgWidth?: string
  imgHeight?: string
  cardStyle?: 'cover' | 'cover-title' | 'list'
  drawWidth?: string
  inputWidth?: string
  readerMode?: 'vertical' | 'horizontal' | 'auto'
  textMode?: 'scroll' | 'paged'
  comicImgMaxWidth?: string
  setTags?: Array<'标签' | '收藏' | '历史' | '过滤选项' | '系统配置' | '日志' | '配置'>
  themeColor?: string
}

interface ConfigBehavior {
  historyRemember?: number
  requestTimeoutMs?: number
  concurrency?: number
  retry?: number
  cacheHtml?: boolean
  useWebView?: boolean
  headers?: Record<string, string>
  challengeMarkers?: string[]
  /** JS 片段：降级到无头浏览器通道时先注入执行 */
  ifWinExecCode?: string
}

/**
 * 配置对象（`const getData = (): WebConfig => ({ ... })` 的返回类型）。
 *
 * 方法以**配置对象自身**为 `this` 调用，因此可用 `this.homeUrl` / `this.searchUrl`。
 * 允许定义任意自定义成员（如 `thatGethtml`、`toggleUrl`），引擎不校验多余成员——
 * 因此这里保留索引签名，避免编辑器对自定义方法报错。
 */
interface WebConfig extends ConfigMeta, ConfigRender, ConfigBehavior {
  /** 列表条目。必需。 */
  getItems(sel: Selector): Item[]
  /** 分页。必需，可返回 []。 */
  getPagination(sel: Selector): Pagination[]
  /** 标签。必需，可返回 []；`url` 为空的项作占位。 */
  getTags(sel: Selector): Tag[]
  /** 排序 / 筛选分组。必需，可返回 []。 */
  getUrlReplace(sel: Selector): UrlReplace[]
  /** 详情。必需；返回 null 表示不支持该类型。第二参是 cheerio 对象（含 `.load`）。 */
  getDetailInfo(item: Item, cheerio: CheerioLike): Promise<DetailInfo | null>
  /** 翻页 / 排序后的最终地址。必需。 */
  adapterLoadUrl(url: string, urlReplaces?: UrlReplace[]): string
  /** 搜索结果页地址。必需；返回空串表示不支持搜索。 */
  adapterSearchUrl(keyword: string): string

  /** 章节 / 图集目录。可选。 */
  getContents?(url: string, cheerio: CheerioLike): Promise<CContent[]>
  /** 图集图片。可选。 */
  getComicImages?(url: string, cheerio: CheerioLike): Promise<CComic[]>
  /** 分集列表。可选。 */
  getAnalysisDetail?(item: Analysis, cheerio: CheerioLike): Promise<AnalysisDetail[]>
  /** 分集播放源。可选。 */
  getAnalysisVideoDetail?(item: AnalysisDetail, cheerio: CheerioLike): Promise<AnalysisVideoDetail[]>
  /** 搜索联想。可选。 */
  adapterRemoteSearch?(keyword: string, cheerio: CheerioLike): Promise<string[]>
  /** 接管取页。可选；一旦定义，引擎不再走默认管线。 */
  adapterGetHtml?(url: string): Promise<string>
  /** 文本正文。可选。 */
  getTextContent?(url: string, cheerio: CheerioLike): Promise<TextContent>
  /** 图片解图脚本。可选：一段返回函数的表达式，签名 (imgSrc, extra, Md5, url, canvas)。 */
  adapterImageCode?: string

  /** 兼容字段：引擎接受但忽略 */
  currentUrlReplace?: UrlReplace[] | null
  /** 兼容字段：引擎接受但忽略 */
  currentUrl?: string | null

  /** 允许任意自定义成员 */
  [key: string]: any
}

// ───────────────────────────── 全局符号 ─────────────────────────────

declare const $: Selector
declare const cheerio: CheerioLike
declare const ElementAttr: ElementAttrMap
declare const ElementTypes: ElementTypeMap
