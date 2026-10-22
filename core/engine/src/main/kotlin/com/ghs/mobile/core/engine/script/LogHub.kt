package com.ghs.mobile.core.engine.script

/**
 * 配置脚本日志的汇聚点。
 *
 * 配置里 `LogMsgUtil.sendLogMsg(...)` / `console.log` 最终都走桥接的 `__log`，
 * 这里留存最近若干条并提供监听，界面据此显示"日志云"浮层。
 *
 * 不依赖协程库（engine 模块保持轻量）：用监听回调 + 环形缓冲。
 */
object LogHub {

    /** 最多保留条数：日志只用于排障，不做持久化 */
    private const val MAX_ENTRIES = 50

    /** 单条最大字符数，超出截断（规范要求 4KB 上限，这里更保守） */
    private const val MAX_CHARS = 1_000

    private val history = ArrayDeque<String>()

    private val listeners = mutableListOf<(List<String>) -> Unit>()

    /** 最近日志（旧的在前） */
    @Synchronized
    fun recent(): List<String> = history.toList()

    @Synchronized
    fun addListener(listener: (List<String>) -> Unit) {
        listeners += listener
        listener(history.toList())
    }

    @Synchronized
    fun removeListener(listener: (List<String>) -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun push(message: String) {
        val text = message.trim().take(MAX_CHARS)
        if (text.isEmpty()) return
        history.addLast(text)
        while (history.size > MAX_ENTRIES) history.removeFirst()
        val snapshot = history.toList()
        listeners.forEach { runCatching { it(snapshot) } }
    }

    @Synchronized
    fun clear() {
        history.clear()
        listeners.forEach { runCatching { it(emptyList()) } }
    }
}
