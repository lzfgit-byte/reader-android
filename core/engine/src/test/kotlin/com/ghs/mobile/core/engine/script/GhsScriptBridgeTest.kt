package com.ghs.mobile.core.engine.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 用假引擎验证**桥接本身**（句柄表 + 方法派发 + 取值语义）。
 *
 * 真正的 JS 求值由引擎测试覆盖：JVM 侧见 `ScriptRuntimeSpikeTest`（GraalJS），
 * 真机侧由 `:app` 的引擎探针在 QuickJS 上跑。
 */
class GhsScriptBridgeTest {

    /** 只记录注册项、不执行 JS 的假引擎 */
    private class FakeScriptEngine : ScriptEngine {
        val globals = mutableMapOf<String, Any?>()
        val functions = mutableMapOf<String, (List<Any?>) -> Any?>()
        val evaluated = mutableListOf<String>()

        override fun eval(script: String, name: String): Any? {
            evaluated += name
            return null
        }

        override fun setGlobal(name: String, value: Any?) {
            globals[name] = value
        }

        override fun setFunction(name: String, fn: (args: List<Any?>) -> Any?) {
            functions[name] = fn
        }

        override fun close() = Unit

        fun call(name: String, vararg args: Any?): Any? {
            val fn = functions[name] ?: error("未注册宿主函数 $name")
            return fn(args.toList())
        }
    }

    private val html = """
        <div id="list">
          <div class="item vip" data-id="1"><a href="/1"><span class="title">A</span><img data-src="/a.jpg"></a></div>
          <div class="item"><a href="/2"><span class="title">B</span><img src="/b.jpg"></a></div>
        </div>
    """.trimIndent()

    private fun bridge(): Pair<GhsScriptBridge, FakeScriptEngine> {
        val engine = FakeScriptEngine()
        val bridge = GhsScriptBridge(engine).install()
        bridge.loadDocument(html)
        return bridge to engine
    }

    @Test
    fun `install 注册全部宿主函数与两段 prelude`() {
        val (_, engine) = bridge()
        val expected = listOf(
            "__select", "__len", "__at", "__eq", "__find", "__attr", "__text", "__html",
            "__outer", "__hasClass", "__data", "__parent", "__children", "__next", "__prev",
            "__closest", "__filter", "__hash", "__log",
        )
        for (name in expected) {
            assertNotNull(engine.functions[name], "缺少宿主函数 $name")
        }
        assertEquals(listOf("ghs-prelude", "ghs-globals"), engine.evaluated)
    }

    @Test
    fun `选择器与长度`() {
        val (_, engine) = bridge()
        val handle = engine.call("__select", ".item")
        assertEquals(2, engine.call("__len", handle))
    }

    @Test
    fun `取属性 取文本 取外壳`() {
        val (_, engine) = bridge()
        val items = engine.call("__select", ".item")
        val firstLink = engine.call("__find", engine.call("__at", items, 0), "a")

        assertEquals("/1", engine.call("__attr", firstLink, "href"))
        assertEquals("A", engine.call("__text", engine.call("__find", firstLink, ".title")))
        assertTrue((engine.call("__outer", engine.call("__at", items, 0)) as String).startsWith("<div"))
    }

    @Test
    fun `缺失属性返回空串而不是 null`() {
        val (_, engine) = bridge()
        val items = engine.call("__select", ".item")
        assertEquals("", engine.call("__attr", items, "nope"))
        assertEquals("", engine.call("__attr", engine.call("__select", "#missing"), "href"))
        assertEquals(0, engine.call("__len", engine.call("__select", "#missing")))
        assertEquals("", engine.call("__outer", engine.call("__select", "#missing")))
    }

    @Test
    fun `class 判断与 data 读取`() {
        val (_, engine) = bridge()
        val items = engine.call("__select", ".item")
        assertEquals(true, engine.call("__hasClass", engine.call("__at", items, 0), "vip"))
        assertEquals(false, engine.call("__hasClass", engine.call("__at", items, 1), "vip"))
        assertEquals("1", engine.call("__data", engine.call("__at", items, 0), "id"))
    }

    @Test
    fun `eq 与负索引`() {
        val (_, engine) = bridge()
        val items = engine.call("__select", ".item")
        val last = engine.call("__eq", items, -1)
        assertEquals("/2", engine.call("__attr", engine.call("__find", last, "a"), "href"))
    }

    @Test
    fun `遍历 next prev closest`() {
        val (_, engine) = bridge()
        val first = engine.call("__at", engine.call("__select", ".item"), 0)
        val second = engine.call("__next", first)
        assertEquals("/2", engine.call("__attr", engine.call("__find", second, "a"), "href"))
        assertEquals(1, engine.call("__len", engine.call("__closest", second, "#list")))
    }

    @Test
    fun `空选择器返回空集而不是抛错`() {
        val (bridge, engine) = bridge()
        assertEquals(0, engine.call("__len", engine.call("__select", "")))
        bridge.loadDocument("")
        assertEquals(0, engine.call("__len", engine.call("__select", "div")))
    }

    @Test
    fun `hashString 稳定且为 sha1`() {
        val (_, engine) = bridge()
        assertEquals("86f7e437faa5a7fce15d1ddcb9eaeaea377667b8", engine.call("__hash", "a"))
        assertEquals(engine.call("__hash", "abc"), engine.call("__hash", "abc"))
    }

    @Test
    fun `日志被收集`() {
        val (bridge, engine) = bridge()
        engine.call("__log", "hello", 42)
        assertEquals(listOf("hello 42"), bridge.logs)
    }

    @Test
    fun `未知句柄抛错`() {
        val (_, engine) = bridge()
        var failed = false
        try {
            engine.call("__attr", 9999, "href")
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed, "应当对未知句柄抛 IllegalArgumentException")
    }
}
