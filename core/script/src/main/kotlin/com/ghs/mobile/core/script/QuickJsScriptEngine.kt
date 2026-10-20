package com.ghs.mobile.core.script

import com.ghs.mobile.core.engine.script.ScriptEngine
import com.quickjs.JSContext
import com.quickjs.JSFunction
import com.quickjs.JSValue
import com.quickjs.JavaCallback
import com.quickjs.QuickJS

/**
 * QuickJS 实现的 [ScriptEngine]（真机主通道，见 ADR-0003）。
 *
 * ⚠️ 线程约束：QuickJS runtime 不是线程安全的，**所有调用必须在创建它的同一线程**上进行。
 * 调用方（引擎调度层）需要保证串行化。
 */
class QuickJsScriptEngine : ScriptEngine {

    private val runtime: QuickJS = QuickJS.createRuntimeWithEventQueue()
    private val context: JSContext = runtime.createContext()

    /**
     * 注意参数顺序：QuickJS 封装的签名是 `executeScript(script, name)`——
     * 名字在后。真机上写反会让脚本内容变成 `"<eval>"`，报
     * `SyntaxError: unexpected token '<'`（已由 instrumented test 抓到过）。
     */
    override fun eval(script: String, name: String): Any? =
        context.executeScript(script, name)

    override fun setGlobal(name: String, value: Any?) {
        when (value) {
            null -> context.set(name, JSValue.NULL())
            is JSValue -> context.set(name, value)
            is String -> context.set(name, value)
            is Int -> context.set(name, value)
            is Long -> context.set(name, value.toDouble())
            is Double -> context.set(name, value)
            is Float -> context.set(name, value.toDouble())
            is Boolean -> context.set(name, value)
            else -> context.set(name, value.toString())
        }
    }

    override fun setFunction(name: String, fn: (args: List<Any?>) -> Any?) {
        val function = JSFunction(
            context,
            JavaCallback { _, args ->
                val list = ArrayList<Any?>(args.length())
                for (index in 0 until args.length()) {
                    list += args.get(index)
                }
                fn(list)
            },
        )
        context.set(name, function)
    }

    override fun close() {
        runCatching { context.close() }
        runCatching { runtime.close() }
    }
}
