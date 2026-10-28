package com.ghs.mobile.core.script

/**
 * 取页结果的三分类：**挑战页 / 太短不可能是正文 / 正文档**。
 *
 * 这里的判据只认**标题与结构标记**，不看页面长度——长度小既可能是挑战页，
 * 也可能是 WebView 的网络错误页（离线、代理不通、DNS 失败）。把长度当成挑战判据，
 * 纯网络故障就会被当成"需要人机校验"：先白等两分钟，再弹一个没有任何可确认内容的
 * 空白校验窗（现场表现见 CHANGELOG「网络失败被误判成人机校验」）。
 *
 * 长度只在反方向使用：短到不可能是列表页的，判为**取页失败**，既不弹窗也不当正文。
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

    fun titleOf(html: String): String =
        TITLE_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
}
