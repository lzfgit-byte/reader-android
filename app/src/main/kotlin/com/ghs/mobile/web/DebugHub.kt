package com.ghs.mobile.web

/**
 * 运行时调试信息的中转站。
 *
 * App 侧在关键节点调用 [publish] 写入当前状态快照；Web 端通过
 * `GET /api/state` 读取，用于实时调试台（F25）。
 *
 * 刻意保持极简：只有最近一次快照与一个可选的求值器，不做历史缓存——
 * 历史由日志（LogHub）承担。
 */
object DebugHub {

    /** 最近一次状态快照（JSON 文本）；空串表示 App 还没进入首个页面 */
    @Volatile
    var snapshot: String = ""

    /**
     * 在配置脚本上下文里执行一段 JS 并返回结果文本。
     *
     * 由 App 侧在会话创建后注入；为 null 表示当前没有活动会话，
     * Web 端据此提示"App 尚未加载配置"。
     */
    @Volatile
    var evaluator: ((String) -> String)? = null

    fun publish(json: String) {
        snapshot = json
    }

    /** 会话关闭时清空求值器，避免调用到已销毁的引擎 */
    fun clearEvaluator() {
        evaluator = null
    }
}