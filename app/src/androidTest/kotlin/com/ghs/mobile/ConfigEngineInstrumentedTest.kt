package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghs.mobile.core.engine.config.ConfigEngine
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import com.ghs.mobile.core.model.ItemType
import com.ghs.mobile.core.script.WebViewScriptEngine
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 列表页端到端：本地服务提供 HTML → 配置脚本解析 → 领域模型。
 *
 * 覆盖：`$`/`each`/`find`/`helpElAttr`/`helpElText` 与 `ElementAttr`/`ElementTypes`，
 * 以及 `getItems`/`getPagination`/`getTags` 到 `Item`/`Pagination`/`Tag` 的映射。
 */
@RunWith(AndroidJUnit4::class)
class ConfigEngineInstrumentedTest {

    private lateinit var server: ServerSocket
    private lateinit var host: HttpScriptHostLike

    private val port: Int get() = server.localPort

    /** 只提供取页能力的宿主（本测试不请求接口、不执行页面） */
    private class HttpScriptHostLike(private val port: Int) : ScriptHostTransport {
        override fun fetchHtml(url: String): String = LIST_HTML
        override fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
        ): ScriptHostTransport.HostResponse = ScriptHostTransport.HostResponse(200, emptyMap(), "")

        override fun executePage(url: String, code: String): String = ""
    }

    private fun config(): String = """
        const getData = (): WebConfig => /* break */ ({
          key: 'list-demo',
          name: 'List Demo',
          homeUrl: 'http://127.0.0.1:$port',
          getUrlReplace($) { return []; },
          getItems($) {
            const result = [];
            $('#list .card').each(function (index, element) {
              const §card = $(element);
              const §link = §card.find(ElementTypes.a);
              result.push({
                jumpUrl: helpElAttr(§link, ElementAttr.href),
                title: helpElText(§card.find('.title')),
                coverImg: helpElAttr(§card.find(ElementTypes.img), ElementAttr.dataSrc),
                type: §card.hasClass('video-card') ? 'video' : 'image',
                tags: [{ title: §card.hasClass('vip') ? 'VIP' : '免费', url: '' }]
              });
            });
            return result;
          },
          getPagination($) {
            const pages = [];
            $('#pager li').each(function (index, element) {
              const §item = $(element);
              pages.push({
                title: helpElText(§item),
                url: helpElAttr(§item.find('a'), 'href'),
                isCurrent: §item.hasClass('active')
              });
            });
            return pages;
          },
          getTags($) {
            return [
              { title: '首页', url: this.homeUrl },
              { title: '分隔', url: '' }
            ];
          },
          async getDetailInfo(item, cheerio) { return null; },
          adapterLoadUrl(url) { return url; },
          adapterSearchUrl(key) { return this.homeUrl + key; },
        });
    """.trimIndent().replace('§', '$')

    @Before
    fun setUp() {
        server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching { server.accept()?.close() }
        }
        host = HttpScriptHostLike(server.localPort)
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
    }

    @Test
    fun list_page_becomes_domain_model() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        WebViewScriptEngine(context).use { engine ->
            ConfigEngine(engine, host).use { configEngine ->
                configEngine.load(config())
                assertTrue("应有 getItems", configEngine.hasMethod("getItems"))

                configEngine.withHtml(LIST_HTML)
                val page = configEngine.page()

                assertEquals("应解析出 2 个条目", 2, page.items.size)

                val first = page.items[0]
                assertEquals("/detail/1", first.jumpUrl)
                assertEquals("第一条", first.title)
                assertEquals("/cover/1.jpg", first.coverImg)
                assertEquals(ItemType.VIDEO, first.type)
                assertEquals("VIP", first.tags.single().title)
                assertTrue("占位标签应保留且不可点击", first.tags.single().isPlaceholder)

                val second = page.items[1]
                assertEquals(ItemType.IMAGE, second.type)
                assertEquals("免费", second.tags.single().title)

                assertEquals(2, page.pagination.size)
                assertEquals("1", page.pagination[0].title)
                assertTrue(page.pagination[0].isCurrent)
                assertEquals("/page/2", page.pagination[1].url)
                assertTrue("当前页 url 为空即禁用", page.pagination[0].disabled)

                assertEquals(2, page.tags.size)
                assertEquals("http://127.0.0.1:$port", page.tags[0].url)
                assertTrue("空 url 标签是占位", page.tags[1].isPlaceholder)
            }
        }
    }

    private companion object {
        val LIST_HTML = """
            <html><body>
              <div id="list">
                <div class="card video-card vip">
                  <a href="/detail/1"><img data-src="/cover/1.jpg"><span class="title">第一条</span></a>
                </div>
                <div class="card">
                  <a href="/detail/2"><img data-src="/cover/2.jpg"><span class="title">第二条</span></a>
                </div>
              </div>
              <ul id="pager">
                <li class="active"><a>1</a></li>
                <li><a href="/page/2">2</a></li>
              </ul>
            </body></html>
        """.trimIndent()
    }
}
