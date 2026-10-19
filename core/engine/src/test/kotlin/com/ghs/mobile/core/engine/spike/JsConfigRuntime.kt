package com.ghs.mobile.core.engine.spike

import com.ghs.mobile.core.engine.select.GhsSelect
import com.ghs.mobile.core.engine.select.Selection
import java.security.MessageDigest
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyExecutable

/**
 * 脚本运行时 spike（**仅测试期**）。
 *
 * 目的：在不引入 QuickJS/native 的前提下，先回答三个问题：
 * 1. 内联配置按 break 标记切分后能否被求值（TS 前缀不参与执行）
 * 2. `$` 桥接能否支撑真实配置里的 `getItems` / `getTags` / `getPagination`
 * 3. 7 个必需方法能否被**语义**校验（而不是文本正则）
 *
 * 桥接方式是 **句柄 + 方法派发**（`__select` → int handle），与真机 QuickJS 走 JNI 的形态一致，
 * 因此这份 spike 的结论可以直接迁移（见 ADR-0003）。
 */
class JsConfigRuntime : AutoCloseable {

    private val selections = HashMap<Int, Selection>()
    private var seq = 0
    private var document: GhsSelect = GhsSelect.empty()

    val logs = mutableListOf<String>()

    private val context: Context = Context.newBuilder("js").build()

    init {
        bindHost()
        context.eval("js", PRELUDE)
        context.eval("js", GLOBALS)
        loadDocument("")
    }

    /** 切换当前文档（配置里的 `$` 都作用在它上面） */
    fun loadDocument(html: String) {
        document = GhsSelect.parse(html)
    }

    /**
     * 求值一份内联配置，返回配置对象，并绑定为 JS 全局 `config`。
 * 只执行 break 标记之后的部分——前面的 `(): WebConfig =>` 是给编辑器看的，不参与运行。
     */
    fun loadConfig(source: String): Value {
        val body = source.substringAfter(BREAK, "").trim()
        require(body.isNotEmpty()) { "配置里找不到 /* break */ 或分隔符之后为空" }
        val value = context.eval("js", "(function(){ return $body })()")
        context.getBindings("js").putMember("config", value)
        return value
    }

    fun hasMethod(method: String): Boolean =
        context.eval("js", "typeof config.${method} === 'function'").asBoolean()

    /** 调用配置方法（`this` 绑定到配置对象） */
    fun invoke(method: String, argExpression: String = "$"): Value =
        context.eval("js", "config.$method($argExpression)")

    fun eval(script: String): Value = context.eval("js", script)

    override fun close() = context.close()

    // ── 句柄表 ────────────────────────────────────────────────────────────────

    private fun register(selection: Selection): Int {
        selections[++seq] = selection
        return seq
    }

    private fun selection(handle: Int): Selection =
        selections[handle] ?: throw IllegalArgumentException("未知句柄 $handle")

    // ── 宿主函数 ──────────────────────────────────────────────────────────────

    private fun bindHost() {
        val bindings = context.getBindings("js")

        bindings.putMember("__select", ProxyExecutable { args ->
            register(selectBy(args[0].asString()))
        })
        bindings.putMember("__len", ProxyExecutable { args ->
            selection(args[0].asInt()).length
        })
        bindings.putMember("__at", ProxyExecutable { args ->
            register(selection(args[0].asInt()).eq(args[1].asInt()))
        })
        bindings.putMember("__eq", ProxyExecutable { args ->
            register(selection(args[0].asInt()).eq(args[1].asInt()))
        })
        bindings.putMember("__find", ProxyExecutable { args ->
            register(selection(args[0].asInt()).find(args[1].asString()))
        })
        bindings.putMember("__attr", ProxyExecutable { args ->
            selection(args[0].asInt()).attr(args[1].asString())
        })
        bindings.putMember("__text", ProxyExecutable { args ->
            selection(args[0].asInt()).text()
        })
        bindings.putMember("__html", ProxyExecutable { args ->
            selection(args[0].asInt()).html()
        })
        bindings.putMember("__outer", ProxyExecutable { args ->
            selection(args[0].asInt()).toString()
        })
        bindings.putMember("__hasClass", ProxyExecutable { args ->
            selection(args[0].asInt()).hasClass(args[1].asString())
        })
        bindings.putMember("__data", ProxyExecutable { args ->
            selection(args[0].asInt()).data(args[1].asString())
        })
        bindings.putMember("__parent", ProxyExecutable { args ->
            register(selection(args[0].asInt()).parent())
        })
        bindings.putMember("__children", ProxyExecutable { args ->
            register(selection(args[0].asInt()).children())
        })
        bindings.putMember("__next", ProxyExecutable { args ->
            register(selection(args[0].asInt()).next())
        })
        bindings.putMember("__prev", ProxyExecutable { args ->
            register(selection(args[0].asInt()).prev())
        })
        bindings.putMember("__closest", ProxyExecutable { args ->
            register(selection(args[0].asInt()).closest(args[1].asString()))
        })
        bindings.putMember("__filter", ProxyExecutable { args ->
            register(selection(args[0].asInt()).filter(args[1].asString()))
        })
        bindings.putMember("__hash", ProxyExecutable { args -> sha1(args[0].asString()) })
        bindings.putMember("__log", ProxyExecutable { args ->
            logs += args.joinToString(" ") { it.toString() }
            null
        })
    }

    private fun selectBy(selector: String): Selection = document(selector)

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val BREAK = "/* break */"

        /** JS 侧最小 `$` 实现：包一层句柄，暴露配置脚本用到的全部方法 */
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
            globalThis.getHtml = function () { return Promise.reject(new Error('spike: 不联网')); };
            globalThis.getCurrentItems = function () { return []; };
            globalThis.executeJs = function () { return Promise.reject(new Error('spike: 不执行无头页面')); };
            globalThis.net = { request: function () { return Promise.reject(new Error('spike: 不联网')); } };
        """.trimIndent()
    }
}
