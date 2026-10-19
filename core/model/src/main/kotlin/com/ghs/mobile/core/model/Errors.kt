package com.ghs.mobile.core.model

/**
 * 错误码。与 `docs/03-api/config-spec.md` §7 一一对应。
 *
 * 分两类：
 * - [Kind.CONFIG]：配置校验错误，不可重试
 * - [Kind.RUNTIME]：运行期中断，可重试
 */
enum class ErrorCode(val code: String, val kind: Kind) {
    // ── 配置校验（E-CFG-*） ──
    PARSE_FAILED("E-CFG-001", Kind.CONFIG),
    MISSING_REQUIRED_FIELD("E-CFG-002", Kind.CONFIG),
    INVALID_KEY("E-CFG-003", Kind.CONFIG),
    SCRIPT_MISSING("E-CFG-004", Kind.CONFIG),
    REQUIRED_METHOD_MISSING("E-CFG-005", Kind.CONFIG),
    FIELD_TYPE_MISMATCH("E-CFG-006", Kind.CONFIG),
    INVALID_ENUM("E-CFG-007", Kind.CONFIG),
    NUMBER_OUT_OF_RANGE("E-CFG-008", Kind.CONFIG),
    SIZE_LIMIT("E-CFG-010", Kind.CONFIG),
    INCOMPATIBLE_VERSION("E-CFG-011", Kind.CONFIG),
    SCRIPT_TIMEOUT("E-CFG-020", Kind.CONFIG),
    INVALID_RETURN_MODEL("E-CFG-021", Kind.CONFIG),
    SCRIPT_MEMORY_LIMIT("E-CFG-022", Kind.CONFIG),
    DEPRECATED_API("E-CFG-030", Kind.CONFIG),

    // ── 运行期中断（E-CHALLENGE-*） ──
    CHALLENGE_REQUIRED("E-CHALLENGE-001", Kind.RUNTIME),
    CHALLENGE_CANCELLED("E-CHALLENGE-002", Kind.RUNTIME),
    CHALLENGE_TIMEOUT("E-CHALLENGE-003", Kind.RUNTIME),
    CHALLENGE_BACKGROUND("E-CHALLENGE-004", Kind.RUNTIME);

    enum class Kind { CONFIG, RUNTIME }

    val retryable: Boolean get() = kind == Kind.RUNTIME

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun of(code: String): ErrorCode? = byCode[code]
    }
}

/**
 * 引擎异常。`path` 是字段路径（如 `render.cardStyle`），`hint` 是修复建议。
 */
class EngineException(
    val errorCode: ErrorCode,
    override val message: String,
    val path: String? = null,
    val hint: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** 校验结果：错误与警告分开，警告不阻塞保存 */
data class Issue(
    val errorCode: ErrorCode,
    val message: String,
    val path: String? = null,
    val hint: String? = null,
)

data class ValidationResult(
    val errors: List<Issue> = emptyList(),
    val warnings: List<Issue> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()
}
