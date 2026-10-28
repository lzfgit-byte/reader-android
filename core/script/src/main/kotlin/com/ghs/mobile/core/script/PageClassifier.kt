package com.ghs.mobile.core.script

/**
 * 取页结果的四分类：**挑战页 / WebView 网络错误页 / 太短不可能是正文 / 正文档**。
 *
 * 这里的判据只认**标题与结构标记**，不看页面长度——长度小既可能是挑战页，
 * 也可能是 WebView 的网络错误页（离线、代理不通、DNS 失败）。把长度当成挑战判据，
 * 纯网络故障就会被当成"需要人机校验"：先白等两分钟，再弹一个没有任何可确认内容的
 * 空白校验窗（现场表现见 CHANGELOG「网络失败被误判成人机校验」）。
 *
 * 长度只在反方向使用：短到不可能是列表页的，判为**取页失败**，既不弹窗也不当正文。
 * 错误页单独认（见 [isWebViewErrorPage]）：它往往比正文门槛还长，漏认就会把
 * "站点打不开"显示成"这个站点没有内容"。
 * 抽成独立对象是为了能脱离 Android 跑单测（见 `PageClassifierTest`）。
 */
internal object PageClassifier {

    /** 一页正文的最小长度：比这还短的只可能是错误页 / 拦截页 / 空响应 */
    const val MIN_CONTENT_LENGTH = 512

    /** 挑战页标题特征：命中即视为需要人机校验 */
    private val CHALLENGE_TITLES = listOf(
        // Cloudflare 一类：标题按浏览器语言本地化，英文、中文都要覆盖
        "just a moment", "checking your browser", "attention required",
        "please wait", "captcha", "verify you are human",
        "请稍候", "請稍候", "正在进行安全验证", "正在验证您是否", "安全验证",
        "人机验证", "人机校验",
        // 工作量验证（proof-of-work）类拦截页：标题常见「安全防御 - 工作量验证」
        "工作量验证", "安全防御",
    )

    /** 挑战页结构标记：标题被改掉也认得出（只取挑战页独有、正文页不会出现的） */
    private val CHALLENGE_MARKERS = listOf(
        "_cf_chl_opt", "cf-chl-", "challenge-platform", "cf-browser-verification",
    )

    /**
     * WebView / Chromium 网络错误页的特征。
     *
     * 这类页面既不是挑战页也不是正文，但它往往**超过** [MIN_CONTENT_LENGTH]：
     * Android WebView 打不开页面时渲染的是本地错误页（标题 `Webpage not available`、
     * 正文里带 `net::ERR_PROXY_CONNECTION_FAILED` 之类的错误码），两三千字节、
     * 里面还有样例链接，按长度判"够长"就会当成正常页面解析，结果是列表 0 条、
     * 界面显示「没有更多了」而不是取页失败——用户只会以为站点空了。
     */
    private val ERROR_PAGE_MARKERS = listOf(
        "net::err_",                // 错误页正文里的错误码（ERR_PROXY_CONNECTION_FAILED / ERR_NAME_NOT_RESOLVED …）
        "chrome-error://",          // Chromium 错误页的内部地址
        "webpage not available",    // Android WebView 错误页标题
        "this site can't be reached", // 桌面 Chrome 错误页标题
    )

    private val TITLE_REGEX =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** 是不是挑战页：标题或结构标记任一命中。空白页不算挑战页。 */
    fun isChallenge(html: String): Boolean {
        if (html.isBlank()) return false
        if (isChallengeTitle(titleOf(html))) return true
        return CHALLENGE_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    fun isChallengeTitle(title: String): Boolean {
        if (title.isEmpty()) return false
        return CHALLENGE_TITLES.any { title.contains(it, ignoreCase = true) }
    }

    /** 短到不可能是正文：按取页失败处理，既不弹校验窗也不缓存 */
    fun tooShortToBeContent(html: String): Boolean = html.length < MIN_CONTENT_LENGTH

    /**
     * 是不是 WebView / Chromium 的**网络错误页**（离线、代理不通、DNS 失败、连接被拒）。
     *
     * 这类页面一律按取页失败处理：不当正文（否则列表 0 条）、不弹人机校验窗（没有可确认的内容）。
     */
    fun isWebViewErrorPage(html: String): Boolean {
        if (html.isBlank()) return false
        return ERROR_PAGE_MARKERS.any { html.contains(it, ignoreCase = true) }
    }

    /** 一句话说明"这为什么不是正文"，用于日志与报错文案 */
    fun describeNonContent(html: String): String = when {
        isWebViewErrorPage(html) -> "WebView 打不开页面（${titleOf(html).ifEmpty { "错误页" }}）"
        html.isBlank() -> "取回空页面"
        tooShortToBeContent(html) -> "只取回 ${html.length} 字符的短页"
        else -> "取回的不是正文页"
    }

    fun titleOf(html: String): String =
        TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
}
