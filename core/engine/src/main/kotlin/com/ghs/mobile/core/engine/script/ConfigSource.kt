package com.ghs.mobile.core.engine.script

/**
 * 内联配置的切分（`config-spec` §2）。
 *
 * 内联形态 = 一段 JS，用 [BREAK_MARKER] 分成两段：
 * - 前半段是**元信息包裹**（可能带 TypeScript 注解，如 `(): WebConfig =>`），**不参与执行**
 * - 后半段是配置对象字面量，交给脚本引擎求值
 */
object ConfigSource {

    const val BREAK_MARKER: String = "/* break */"

    data class Parts(
        val header: String,
        val body: String,
        val hasBreakMarker: Boolean,
    )

    fun split(source: String): Parts {
        val index = source.indexOf(BREAK_MARKER)
        return if (index < 0) {
            Parts(header = "", body = source.trim(), hasBreakMarker = false)
        } else {
            Parts(
                header = source.substring(0, index).trim(),
                body = source.substring(index + BREAK_MARKER.length).trim(),
                hasBreakMarker = true,
            )
        }
    }

    /** 取要执行的部分；body 为空时抛 [IllegalArgumentException] */
    fun bodyOf(source: String): String {
        val body = split(source).body
        require(body.isNotEmpty()) { "配置里没有可执行的脚本（$BREAK_MARKER 之后为空）" }
        return body
    }
}
