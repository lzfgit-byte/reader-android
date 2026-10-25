package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghs.mobile.core.engine.config.ConfigEngine
import com.ghs.mobile.core.engine.script.ScriptHostTransport
import com.ghs.mobile.core.model.DetailType
import com.ghs.mobile.core.model.Item
import com.ghs.mobile.core.script.WebViewScriptEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 详情页三条链路的真机验证：图集（comic）/ 文本（text）/ 视频（m3u8）。
 *
 * 全部走 `async getDetailInfo` + `await getHtml(...)` + `cheerio.load(...)`，
 * 即真实配置的写法；宿主用假实现返回固定 HTML，不联网。
 */
@RunWith(AndroidJUnit4::class)
class DetailChainInstrumentedTest {

    private class FakeTransport : ScriptHostTransport {
        override fun fetchHtml(url: String): String = when {
            url.contains("/comic/") -> COMIC_HTML
            url.contains("/text/") -> TEXT_HTML
            url.contains("/video/") -> VIDEO_HTML
            url.contains("/chapters") -> CHAPTERS_HTML
            else -> "<html><body></body></html>"
        }

        override fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: String?,
        ): ScriptHostTransport.HostResponse =
            ScriptHostTransport.HostResponse(200, emptyMap(), "{}")

        override fun executePage(url: String, code: String, show: Boolean): String = ""
    }

    private fun config(): String = """
        const getData = (): WebConfig => /* break */ ({
          key: 'detail-demo',
          name: 'Detail Demo',
          homeUrl: 'https://example.com',
          getUrlReplace(§) { return []; },
          getItems(§) { return []; },
          getPagination(§) { return []; },
          getTags(§) { return []; },
          async getDetailInfo(item, cheerio) {
            const html = await getHtml(item.jumpUrl);
            const §p = cheerio.load(html);
            if (item.jumpUrl.indexOf('/comic/') >= 0) {
              const images = [];
              §p('.thumb a').each(function (i, el) {
                const §a = §(el);
                images.push({
                  url: helpElAttr(§a, 'href'),
                  fullUrl: helpElAttr(§a, 'data-full'),
                  title: helpElText(§a)
                });
              });
              return { detailType: 'comic', details: images };
            }
            if (item.jumpUrl.indexOf('/text/') >= 0) {
              return {
                detailType: 'text',
                details: [{ url: item.jumpUrl, title: helpElText(§p('h1')) }]
              };
            }
            const sources = [];
            §p('video source').each(function (i, el) {
              const §s = §(el);
              sources.push({
                type: 'm3u8',
                url: helpElAttr(§s, 'src'),
                quality: helpElAttr(§s, 'label')
              });
            });
            return { detailType: 'm3u8', details: sources };
          },
          async getContents(url, cheerio) {
            const html = await getHtml(url);
            const §c = cheerio.load(html);
            const chapters = [];
            §c('.chapter a').each(function (i, el) {
              const §a = §(el);
              chapters.push({
                title: helpElText(§a),
                url: helpElAttr(§a, 'href'),
                isCurrent: §a.hasClass('current')
              });
            });
            return chapters;
          },
          async getComicImages(url, cheerio) {
            return [{ url: url + '/1.jpg', info: { aid: 7 } }];
          },
          async getTextContent(url, cheerio) {
            return {
              title: '第一章',
              html: '第一段\n\n第二段<img src="https://x.test/p.jpg">',
              prevUrl: '',
              nextUrl: '/text/2'
            };
          },
          adapterLoadUrl(url) { return url; },
          adapterSearchUrl(key) { return this.homeUrl + key; },
        });
    """.trimIndent().replace('§', '$')

    private fun engine(): ConfigEngine {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return ConfigEngine(WebViewScriptEngine(context), FakeTransport()).load(config())
    }

    @Test
    fun comic_detail_uses_full_url() {
        engine().use { engine ->
            val info = engine.detailInfo(Item(jumpUrl = "https://x.test/comic/1", title = "图集"))
            assertTrue("应返回详情", info != null)
            assertEquals(DetailType.COMIC, info!!.detailType)
            assertEquals(2, info.details.size)
            assertEquals("/p/1", info.details[0].url)
            // fullUrl 优先
            assertEquals("https://img.test/1.jpg", info.details[0].preferredUrl)
            assertEquals("/p/2", info.details[1].url)
            assertEquals("/p/2", info.details[1].preferredUrl)
        }
    }

    @Test
    fun text_detail_and_text_content() {
        engine().use { engine ->
            val info = engine.detailInfo(Item(jumpUrl = "https://x.test/text/1", title = "文本"))
            assertTrue(info != null)
            assertEquals(DetailType.TEXT, info!!.detailType)
            assertEquals("第一章", info.details.single().title)

            val text = engine.textContent("https://x.test/text/1")
            assertEquals("第一章", text.title)
            assertEquals("/text/2", text.nextUrl)
            // 三个块：两段文字 + 一张图
            assertEquals(3, text.blocks.size)
            assertEquals("第一段", text.blocks[0].text)
            assertEquals("https://x.test/p.jpg", text.blocks[2].src)
        }
    }

    @Test
    fun video_detail_maps_quality() {
        engine().use { engine ->
            val info = engine.detailInfo(Item(jumpUrl = "https://x.test/video/1", title = "视频"))
            assertTrue(info != null)
            assertEquals(DetailType.M3U8, info!!.detailType)
            assertEquals(2, info.details.size)
            assertEquals("https://cdn.test/a.m3u8", info.details[0].url)
            assertEquals("1080P", info.details[0].quality)
            assertEquals(DetailType.M3U8, info.details[0].type)
        }
    }

    @Test
    fun contents_and_comic_images() {
        engine().use { engine ->
            val chapters = engine.contents("https://x.test/chapters")
            assertEquals(3, chapters.size)
            assertEquals("第 1 话", chapters[0].title)
            assertTrue("当前章标记应保留", chapters[1].isCurrent)

            val images = engine.comicImages("https://x.test/comic/1")
            assertEquals(1, images.size)
            assertEquals("https://x.test/comic/1/1.jpg", images[0].url)
            assertEquals(7L, (images[0].info?.get("aid") as Number).toLong())
        }
    }

    @Test
    fun unsupported_detail_returns_null() {
        engine().use { engine ->
            val info = engine.detailInfo(Item(jumpUrl = "https://x.test/unknown/1"))
            // 配置这段分支会返回 m3u8 但没有任何 source -> details 为空，类型仍是 m3u8
            assertTrue(info == null || info.details.isEmpty())
        }
    }

    private companion object {
        val COMIC_HTML = """
            <html><body>
              <div class="thumb"><a href="/p/1" data-full="https://img.test/1.jpg">第 1 页</a></div>
              <div class="thumb"><a href="/p/2">第 2 页</a></div>
            </body></html>
        """.trimIndent()

        val TEXT_HTML = """
            <html><head><title>文本</title></head><body><h1>第一章</h1></body></html>
        """.trimIndent()

        val VIDEO_HTML = """
            <html><body>
              <video>
                <source src="https://cdn.test/a.m3u8" label="1080P">
                <source src="https://cdn.test/a720.m3u8" label="720P">
              </video>
            </body></html>
        """.trimIndent()

        val CHAPTERS_HTML = """
            <html><body>
              <div class="chapter"><a href="/c/1">第 1 话</a></div>
              <div class="chapter"><a href="/c/2" class="current">第 2 话</a></div>
              <div class="chapter"><a href="/c/3">第 3 话</a></div>
            </body></html>
        """.trimIndent()
    }
}
