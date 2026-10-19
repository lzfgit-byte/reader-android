package com.ghs.mobile.core.engine.script

/**
 * 脚本引擎抽象。实现方只需提供三件事：求值、设全局、注入宿主函数。
 *
 * 之所以要这层抽象：
 * - **桥接逻辑**（句柄表 + 方法派发 + JS 侧 prelude）与具体引擎无关，放在 [GhsScriptBridge] 里，可在 JVM 上单测
 * - 真机实现是 QuickJS（`:core:script`），验证期实现是 GraalJS（测试期）
 *
 * 约定：
 * - 所有调用都必须在**创建引擎的同一线程**上进行（QuickJS runtime 不是线程安全的）
 * - [eval] 的返回值是 JSON 语义的：`String` / `Double` / `Boolean` / `null` / `Map` / `List`
 */
interface ScriptEngine : AutoCloseable {

    /** 执行一段脚本并返回最后一个表达式的值 */
    fun eval(script: String, name: String = "<eval>"): Any?

    /** 设置全局变量 */
    fun setGlobal(name: String, value: Any?)

    /** 注入一个宿主函数，`args` 是 JS 传进来的参数列表 */
    fun setFunction(name: String, fn: (args: List<Any?>) -> Any?)
}
