package com.ghs.mobile.core.engine.script

import com.ghs.mobile.core.engine.select.GhsSelect
import com.ghs.mobile.core.engine.select.Selection
import java.security.MessageDigest

/**
 * GHS 脚本桥：把 [GhsSelect] 与运行时工具注入 JS，并管理选择器句柄。
 *
 * 设计要点（与 GraalJS spike 一致，真机 QuickJS 复用同一套协议）：
 * - **句柄 + 派发**：JS 侧拿到的只是一个整数句柄，所有方法都是 `__xxx(handle, ...)` 的薄包装
 * - 只传基本类型（String / Int / Boolean）跨越 JS↔Kotlin 边界
 * - 取值一律返回 `''` 而不是 null（配置里的 `a || b` 兜底才成立）
 *
 * 本类不含任何 JS 引擎依赖，可在 JVM 上单测（见 `GhsScriptBridgeTest`）。
 */
class GhsScriptBridge(private val engine: ScriptEngine) {

    private val handles = HashMap<Int, Selection>()
    private var sequence = 0
    private var document: GhsSelect = GhsSelect.empty()

    /** 供测试断言用的日志收集 */
    val logs = mutableListOf<String>()

    /** 切换当前文档（配置里的 `$` 作用在它上面） */
    fun loadDocument(html: String) {
        document = if (html.isEmpty()) GhsSelect.empty() else GhsSelect.parse(html)
    }

    /** 注入宿主函数与 JS 侧 prelude；返回自身便于链式调用 */
    fun install(): GhsScriptBridge {
        bindHostFunctions()
        engine.eval(PRELUDE, "ghs-prelude")
        engine.eval(GLOBALS, "ghs-globals")
        return this
    }

    /**
     * 求值一份内联配置，返回配置对象，并绑定为 JS 全局 `config`。
     * 只执行 [ConfigSource.bodyOf] 之后的部分。
     */
    fun loadConfig(source: String): Any? {
        val body = ConfigSource.bodyOf(source)
        val config = engine.eval("(function(){ return $body })()", "config")
        engine.setGlobal("config", config)
        return config
    }

    fun hasMethod(method: String): Boolean =
        engine.eval("typeof config.$method === 'function'", "probe") == true

    /** 调用配置方法（`this` 绑定到配置对象） */
    fun invoke(method: String, argumentExpression: String = "$"): Any? =
        engine.eval("config.$method($argumentExpression)", "invoke:$method")

    private fun register(selection: Selection): Int {
        handles[++sequence] = selection
        return sequence
    }

    private fun selection(handle: Any?): Selection =
        handles[(handle as? Number)?.toInt() ?: -1]
            ?: throw IllegalArgumentException("未知选择器句柄：$handle")

    private fun intOf(value: Any?): Int = (value as? Number)?.toInt() ?: 0

    private fun textOf(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }

    private fun bindHostFunctions() {
        engine.setFunction("__select") { args -> register(select(textOf(args.getOrNull(0)))) }
        engine.setFunction("__len") { args -> selection(args.getOrNull(0)).length }
        engine.setFunction("__at") { args -> register(selection(args.getOrNull(0)).eq(intOf(args.getOrNull(1)))) }
        engine.setFunction("__eq") { args -> register(selection(args.getOrNull(0)).eq(intOf(args.getOrNull(1)))) }
        engine.setFunction("__find") { args ->
            register(selection(args.getOrNull(0)).find(textOf(args.getOrNull(1))))
        }
        engine.setFunction("__attr") { args ->
            selection(args.getOrNull(0)).attr(textOf(args.getOrNull(1)))
        }
        engine.setFunction("__text") { args -> selection(args.getOrNull(0)).text() }
        engine.setFunction("__html") { args -> selection(args.getOrNull(0)).html() }
        engine.setFunction("__outer") { args -> selection(args.getOrNull(0)).toString() }
        engine.setFunction("__hasClass") { args ->
            selection(args.getOrNull(0)).hasClass(textOf(args.getOrNull(1)))
        }
        engine.setFunction("__data") { args ->
            selection(args.getOrNull(0)).data(textOf(args.getOrNull(1)))
        }
        engine.setFunction("__parent") { args -> register(selection(args.getOrNull(0)).parent()) }
        engine.setFunction("__children") { args -> register(selection(args.getOrNull(0)).children()) }
        engine.setFunction("__next") { args -> register(selection(args.getOrNull(0)).next()) }
        engine.setFunction("__prev") { args -> register(selection(args.getOrNull(0)).prev()) }
        engine.setFunction("__closest") { args ->
            register(selection(args.getOrNull(0)).closest(textOf(args.getOrNull(1))))
        }
        engine.setFunction("__filter") { args ->
            register(selection(args.getOrNull(0)).filter(textOf(args.getOrNull(1))))
        }
        engine.setFunction("__hash") { args -> sha1(textOf(args.getOrNull(0))) }
        engine.setFunction("__log") { args ->
            logs += args.joinToString(" ") { textOf(it) }
            null
        }
    }

    private fun select(selector: String): Selection =
        if (selector.isEmpty()) Selection(emptyList()) else document(selector)

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {

        /** JS 侧最小 `$`：包一层句柄，暴露配置脚本用到的全部方法 */
        val PRELUDE = """
            globalThis.__wrap = function (h) {
              return {
                __h: h,
                get length() { return __len(h); },
                each: function (fn) { for (var i = 0; i < __len(h); i++) fn(i, __wrap(__at(h, i))); },
                find: function (s) { return __wrap(__find(h, s)); },
                attr: function (n) { return __attr(h, n); },
                text: function () { return __text(h); },
                html: function () { return __html(h); },
                data: function (n) { return __data(h, n); },
                hasClass: function (n) { return __hasClass(h, n); },
                toString: function () { return __outer(h); },
                eq: function (i) { return __wrap(__eq(h, i)); },
                first: function () { return __wrap(__eq(h, 0)); },
                last: function () { return __wrap(__eq(h, -1)); },
                parent: function () { return __wrap(__parent(h)); },
                children: function () { return __wrap(__children(h)); },
                next: function () { return __wrap(__next(h)); },
                prev: function () { return __wrap(__prev(h)); },
                closest: function (s) { return __wrap(__closest(h, s)); },
                filter: function (s) { return __wrap(__filter(h, s)); }
              };
            };

            globalThis.${'$'} = function (arg) {
              if (typeof arg === 'string') return __wrap(__select(arg));
              if (arg && typeof arg.__h === 'number') return __wrap(arg.__h);
              return __wrap(__select(String(arg == null ? '' : arg)));
            };

            globalThis.cheerio = { load: function (html) { return globalThis.${'$'}; } };
        """.trimIndent()

        /** 配置脚本可用的全局符号（值语义与 config-spec §8 一致） */
        val GLOBALS = """
            globalThis.ElementAttr = {
              src: 'src', href: 'href', title: 'title', alt: 'alt', class: 'class', poster: 'poster',
              dataSrc: 'data-src', dataOriginal: 'data-original', dataWebp: 'data-webp',
              dataType: 'data-type', dataEcho: 'data-echo', dataSource: 'data-source', dataError: 'data-error'
            };
            globalThis.ElementTypes = {
              a: 'a', img: 'img', h1: 'h1', h4: 'h4', p: 'p', span: 'span', div: 'div', video: 'video', source: 'source'
            };
            globalThis.helpElAttr = function (el, name) { return el && el.__h ? __attr(el.__h, name) : ''; };
            globalThis.helpElText = function (el) { return el && el.__h ? __text(el.__h) : ''; };
            globalThis.hashString = function (s) { return __hash(String(s)); };
            globalThis.LogMsgUtil = { sendLogMsg: function () { __log.apply(null, arguments); } };
            globalThis.ConsoleLogUtil = { log: function () { __log.apply(null, arguments); } };
            globalThis.MessageUtil = {
              info: function () {}, error: function () {}, success: function () {}, warning: function () {}
            };
            globalThis.NotifyMsgUtil = { sendNotifyMsg: function () {}, close: function () {} };
            globalThis.ProgressMsgUtil = { sendProgress: function () {}, close: function () {} };
            globalThis.eventEmitter = { on: function () {}, off: function () {}, emit: function () {} };
            globalThis.getCurrentItems = function () { return []; };
        """.trimIndent()
    }
}
