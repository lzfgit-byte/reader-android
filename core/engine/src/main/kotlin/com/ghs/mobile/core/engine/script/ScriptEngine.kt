package com.ghs.mobile.core.engine.script

/**
 * 脚本引擎抽象。实现方只需提供四件事：求值、异步求值、设全局、注入宿主函数。
 *
 * 之所以要这层抽象：
 * - **桥接逻辑**（句柄表 + 方法派发 + JS 侧 prelude）与具体引擎无关，放在 [GhsScriptBridge] 里，可在 JVM 上单测
 * - 真机实现见 `:core:script`（WebView 主通道 / QuickJS 仅同步求值）
 *
 * 约定：
 * - 所有调用都必须在**创建引擎的同一线程**上进行（QuickJS runtime 不是线程安全的）
 * - [eval] 的返回值是 JSON 语义的：`String` / `Double` / `Boolean` / `null` / `Map` / `List`
 */
interface ScriptEngine : AutoCloseable {

    /** 执行一段脚本并返回最后一个表达式的值 */
    fun eval(script: String, name: String = "<eval>"): Any?

    /**
     * 执行一段脚本并**等它 resolve**（用于 `async` 方法）。
     *
     * 默认实现退化成同步 [eval]：引擎没有事件循环时（QuickJS 封装）无法推动微任务，
     * 只能求值同步脚本。有真事件循环的实现（WebView）必须覆盖它。
     */
    fun evalAwait(script: String, name: String = "<eval>", timeoutMs: Long = 180_000): Any? =
        eval(script, name)

    /** 设置全局变量 */
    fun setGlobal(name: String, value: Any?)

    /** 注入一个宿主函数，`args` 是 JS 传进来的参数列表 */
    fun setFunction(name: String, fn: (args: List<Any?>) -> Any?)
}
