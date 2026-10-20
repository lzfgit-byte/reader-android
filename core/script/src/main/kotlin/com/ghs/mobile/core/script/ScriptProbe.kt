package com.ghs.mobile.core.script

import com.ghs.mobile.core.engine.script.GhsScriptBridge
import com.quickjs.JSArray

/**
 * 真机上的脚本运行时自检：把 QuickJS、桥接、配置求值三件事串起来跑一遍。
 *
 * 用在 `:app` 的引擎探针页——**它不是演示**，而是"这台设备上 QuickJS 到底能不能跑我们的配置"的证据。
 * 之所以放在库里而不是 UI 里：后续的 instrumented test 可以直接复用。
 */
object ScriptProbe {

    data class Line(val label: String, val value: String, val ok: Boolean? = null)

    private val html = """
        <div id="list">
          <div class="item"><a href="/1"><span class="title">A</span></a></div>
          <div class="item"><a href="/2"><span class="title">B</span></a></div>
        </div>
    """.trimIndent()

    private val demoConfig = """
        const getData = (): WebConfig => /* break */ ({
          key: 'demo',
          name: 'Demo',
          homeUrl: 'https://example.com',
          getUrlReplace($) { return []; },
          getItems($) { return []; },
          getPagination($) { return []; },
          getTags($) { return [{ title: 'A', url: this.homeUrl + '/a' }, { title: 'B', url: '' }]; },
          async getDetailInfo(item, cheerio) { return null; },
          adapterLoadUrl(url) { return url; },
          adapterSearchUrl(key) { return this.homeUrl + key; },
        });
    """.trimIndent()

    private val requiredMethods = listOf(
        "getItems", "getPagination", "getTags", "getUrlReplace",
        "getDetailInfo", "adapterLoadUrl", "adapterSearchUrl",
    )

    fun run(): List<Line> {
        val started = System.currentTimeMillis()
        val lines = mutableListOf<Line>()
        try {
            QuickJsScriptEngine().use { engine ->
                lines += Line("QuickJS 启动", "${System.currentTimeMillis() - started} ms")

                val sum = engine.eval("1 + 1")
                lines += Line("算术求值 1+1", describe(sum), ok = describe(sum) == "2")

                val global = engine.eval("typeof globalThis")
                lines += Line("globalThis", describe(global), ok = describe(global) == "object")

                val es2020 = engine.eval(
                    "(function(){ return typeof BigInt === 'function' " +
                        "&& typeof Promise === 'function' " +
                        "&& typeof Symbol === 'function'; })()",
                )
                lines += Line("ES2020 基础能力", describe(es2020), ok = describe(es2020) == "true")

                val bridge = GhsScriptBridge(engine).install()
                bridge.loadDocument(html)

                val count = engine.eval("\$('#list .item').length")
                lines += Line("选择器 \$('.item').length", describe(count), ok = describe(count) == "2")

                val href = engine.eval("\$('.item a').attr('href')")
                lines += Line("取值 \$('a').attr('href')", describe(href), ok = describe(href) == "/1")

                // 选择器命中两个 .title，取第一个（顺便验证 eq(0)）
                val title = engine.eval("helpElText(\$('.item .title').eq(0))")
                lines += Line("helpElText", describe(title), ok = describe(title) == "A")

                bridge.loadConfig(demoConfig)
                val present = requiredMethods.count { bridge.hasMethod(it) }
                lines += Line("配置求值 · 必需方法", "$present / ${requiredMethods.size}", ok = present == requiredMethods.size)

                val tags = describeArray(bridge.invoke("getTags"))
                lines += Line("调用 getTags", tags, ok = tags.startsWith("2"))

                val search = describe(bridge.invoke("adapterSearchUrl", "'k'"))
                lines += Line(
                    "this 绑定 adapterSearchUrl",
                    search,
                    ok = search == "https://example.comk",
                )
            }
        } catch (t: Throwable) {
            lines += Line("运行时异常", t::class.java.simpleName + ": " + (t.message ?: ""), ok = false)
        }
        lines += Line("总耗时", "${System.currentTimeMillis() - started} ms")
        return lines
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun describeArray(value: Any?): String = when (value) {
        is JSArray -> "${value.length()} 项"
        is List<*> -> "${value.size} 项"
        null -> "null"
        else -> describe(value)
    }
}
