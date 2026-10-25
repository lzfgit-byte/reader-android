package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghs.mobile.core.engine.script.GhsScriptBridge
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import com.ghs.mobile.core.script.WebViewScriptEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 异步桥的真机验证：在**真事件循环**（WebView）上跑一份使用 `async` / `await` /
 * `new Promise` / `setTimeout` 的配置，确认异步语义成立。
 *
 * 背景：QuickJS 封装没有 `JS_ExecutePendingJob`，微任务永不前进（实测），
 * 所以异步路径改由 WebView 承担（见 ADR-0016）。
 */
@RunWith(AndroidJUnit4::class)
class AsyncBridgeInstrumentedTest {

    /** 假宿主：不联网，但完整走"同步宿主调用 + JS await"的链路 */
    private class FakeTransport : ScriptHostTransport {
        override fun fetchHtml(url: String): String =
            "<div class='item'><a href='/x'>T</a></div>"

        override fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
        ): ScriptHostTransport.HostResponse =
            ScriptHostTransport.HostResponse(
                status = 200,
                headers = mapOf("X-Test" to "1"),
                body = """{"ok":true}""",
            )

        override fun executePage(url: String, code: String, show: Boolean): String = "page-result"
    }

    private val config = """
        const getData = (): WebConfig => /* break */ ({
          key: 'async-demo',
          name: 'Async Demo',
          homeUrl: 'https://example.com',
          getUrlReplace($) { return []; },
          getItems($) { return []; },
          getPagination($) { return []; },
          getTags($) { return []; },
          async getDetailInfo(item, cheerio) {
            const html = await getHtml(item.jumpUrl);
            const §page = cheerio.load(html);
            const title = helpElText(§page('.item a'));
            const response = await net.request({ url: item.jumpUrl + '/api', method: 'GET' });
            const delayed = await new Promise(function (resolve) {
              setTimeout(function () { resolve(true); }, 10);
            });
            const pageValue = await executeJs(item.jumpUrl, 'return 1');
            return {
              title: title,
              status: response.status,
              body: JSON.parse(response.body).ok,
              delayed: delayed,
              page: pageValue
            };
          },
          adapterLoadUrl(url) { return url; },
          adapterSearchUrl(key) { return this.homeUrl + key; },
        });
    """.trimIndent().replace('§', '$')

    @Test
    fun async_config_awaits_host_calls_and_promises() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        WebViewScriptEngine(context).use { engine ->
            val bridge = GhsScriptBridge(engine).install(FakeTransport())

            val loaded = bridge.loadConfig(config)
            assertTrue("配置应求值成功", loaded.isNotEmpty())

            val configType = engine.eval("typeof globalThis.config")
            val keys = engine.eval("String(Object.keys(globalThis.config || {}))")
            val hasGetItems = engine.eval("typeof globalThis.config.getItems")
            val probe = engine.eval("typeof config.getItems === 'function'")
            assertTrue(
                "期望 7 个必需方法。诊断：configType=$configType keys=$keys getItemsType=$hasGetItems probe=$probe",
                listOf(
                    "getItems", "getPagination", "getTags", "getUrlReplace",
                    "getDetailInfo", "adapterLoadUrl", "adapterSearchUrl",
                ).all { bridge.hasMethod(it) },
            )

            // 第二参是 cheerio（配置里的 getDetailInfo(item, cheerio) 依赖它）
            val detail = bridge.invokeAwait(
                "getDetailInfo",
                "{ jumpUrl: 'https://x.test/a' }, cheerio",
            )
            println("ASYNC detail=$detail")

            val map = detail as? Map<*, *> ?: error("应返回对象，实际：$detail")
            assertEquals("T", map["title"])
            assertEquals(200L, (map["status"] as Number).toLong())
            assertEquals(true, map["body"])
            assertEquals(true, map["delayed"])
            assertEquals("page-result", map["page"])
        }
    }
}
