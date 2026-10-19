package com.ghs.mobile.core.engine.config

import com.ghs.mobile.core.model.ErrorCode
import com.ghs.mobile.core.model.Issue
import com.ghs.mobile.core.model.ValidationResult

/**
 * 内联形态配置的**文本级静态预检**。
 *
 * ⚠️ 定位：这是"不执行脚本"的快速预检，只能看出**缺失与明显误用**；
 * 字段类型、枚举合法性、返回值模型等必须由语义校验（执行脚本）判定。
 * 语义校验在脚本运行时落地后接入（见设计文档 §8.3）。
 *
 * 之所以需要它：内联配置在导入/编辑时先给一次即时反馈，避免"点了保存才发现少方法"。
 */
object InlineConfigScanner {

    const val MAX_BYTES = 1_000_000

    /** 必需方法（config-spec §4.1，共 7 个） */
    val REQUIRED_METHODS = listOf(
        "getItems",
        "getPagination",
        "getTags",
        "getUrlReplace",
        "getDetailInfo",
        "adapterLoadUrl",
        "adapterSearchUrl",
    )

    /** 可选方法（config-spec §4.2） */
    val OPTIONAL_METHODS = listOf(
        "getContents",
        "getComicImages",
        "getAnalysisDetail",
        "getAnalysisVideoDetail",
        "adapterRemoteSearch",
        "adapterGetHtml",
        "getTextContent",
    )

    private val KEY = Regex("""\bkey\s*:\s*(['"])(.*?)\1""")
    private val NAME = Regex("""\bname\s*:\s*(['"])(.*?)\1""")
    private val HOME_URL = Regex("""\bhomeUrl\s*:\s*(['"])(.*?)\1""")

    private val KEY_FORMAT = Regex("""^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$""")

    /** 属性级方法声明：行首缩进 + 名字 + 参数表 + `{`；允许 `async` 与名字后的空格 */
    private val METHOD_DECL = Regex(
        """(?m)^[ \t]+(async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{""",
    )

    /** 这些不是配置方法，出现在正文里也很正常 */
    private val NOT_METHODS = setOf(
        "if", "for", "while", "switch", "catch", "function", "return", "else", "do", "try",
        "constructor", "super", "new", "await", "yield", "typeof", "delete", "void",
    )

    private val DEPRECATED_NET_EVENT = Regex("""\.on\(\s*['"](response|error)['"]""")

    data class ScanResult(
        val key: String?,
        val name: String?,
        val homeUrl: String?,
        val methods: Set<String>,
        val missingRequired: List<String>,
        val usesDeprecatedNetRequest: Boolean,
        val hasBreakMarker: Boolean,
    )

    fun scan(source: String): ScanResult {
        val methods = METHOD_DECL.findAll(source)
            .mapNotNull { m -> m.groupValues[2].takeIf { it !in NOT_METHODS } }
            .toSet()

        return ScanResult(
            key = KEY.find(source)?.groupValues?.get(2)?.trim(),
            name = NAME.find(source)?.groupValues?.get(2)?.trim(),
            homeUrl = HOME_URL.find(source)?.groupValues?.get(2)?.trim(),
            methods = methods,
            missingRequired = REQUIRED_METHODS.filterNot { it in methods },
            usesDeprecatedNetRequest = DEPRECATED_NET_EVENT.containsMatchIn(source) &&
                source.contains("net.request("),
            hasBreakMarker = source.contains("/* break */"),
        )
    }

    /**
     * 预检：只产出**静态可判**的错误与警告。
     * 通过（`ok == true`）不代表配置正确，只代表没发现明显问题。
     */
    fun validate(source: String, sizeBytes: Int = source.toByteArray().size): ValidationResult {
        val errors = mutableListOf<Issue>()
        val warnings = mutableListOf<Issue>()
        val scan = scan(source)

        if (sizeBytes > MAX_BYTES) {
            errors += Issue(ErrorCode.SIZE_LIMIT, "配置超过 1MB（$sizeBytes 字节）", path = "script")
        }

        if (scan.key.isNullOrBlank()) {
            errors += Issue(ErrorCode.MISSING_REQUIRED_FIELD, "缺少 key", path = "meta.key")
        } else if (!KEY_FORMAT.matches(scan.key)) {
            errors += Issue(
                ErrorCode.INVALID_KEY,
                "key 格式非法：${scan.key}",
                path = "meta.key",
                hint = "需匹配 ^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$（允许大小写）",
            )
        }

        if (scan.name.isNullOrBlank()) {
            errors += Issue(ErrorCode.MISSING_REQUIRED_FIELD, "缺少 name", path = "meta.name")
        }

        if (scan.homeUrl.isNullOrBlank()) {
            errors += Issue(ErrorCode.MISSING_REQUIRED_FIELD, "缺少 homeUrl", path = "meta.homeUrl")
        }

        for (missing in scan.missingRequired) {
            errors += Issue(
                ErrorCode.REQUIRED_METHOD_MISSING,
                "缺少必需方法 $missing",
                path = "script.$missing",
            )
        }

        if (!scan.hasBreakMarker) {
            warnings += Issue(
                ErrorCode.PARSE_FAILED,
                "未找到 /* break */ 分隔符，可能不是内联形态",
                path = "script",
                hint = "内联配置需用 /* break */ 分隔元信息与脚本体",
            )
        }

        if (scan.usesDeprecatedNetRequest) {
            warnings += Issue(
                ErrorCode.DEPRECATED_API,
                "使用了 net.request(...).on('response') 旧式事件写法",
                path = "script",
                hint = "改为 await net.request(options) 的 Promise 形态",
            )
        }

        return ValidationResult(errors = errors, warnings = warnings)
    }
}
