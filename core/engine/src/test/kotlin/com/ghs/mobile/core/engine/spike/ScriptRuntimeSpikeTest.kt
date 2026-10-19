package com.ghs.mobile.core.engine.spike

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 脚本运行时 spike：验证「内联配置可求值 + `$` 桥接可用 + 必需方法可语义校验」。
 *
 * 真机形态是 QuickJS（ADR-0003），这里用 GraalJS 先把**执行模型**验证通，
 * 桥接方式同为「句柄 + 方法派发」，结论可迁移。
 */
class ScriptRuntimeSpikeTest {

    private val requiredMethods = listOf(
        "getItems", "getPagination", "getTags", "getUrlReplace",
        "getDetailInfo", "adapterLoadUrl", "adapterSearchUrl",
    )

    // ── 合成配置：把桥接方法全用一遍 ──────────────────────────────────────────

    private val syntheticHtml = """
        <div id="list">
          <div class="item vip" data-id="1">
            <a href="/1"><span class="title">A</span><img data-src="/a.jpg" src="/a2.jpg"></a>
          </div>
          <div class="item" data-id="2">
            <a href="/2"><span class="title">B</span><img src="/b.jpg"></a>
          </div>
        </div>
        <ul class="pager">
          <li class="active"><a>1</a></li>
          <li><a href="/p2">2</a></li>
        </ul>
    """.trimIndent()

    /**
     * 合成配置：把桥接方法全用一遍。
     * JS 里的 `$` 用 `§` 占位后再替换，避免被 Kotlin 字符串模板当作插值。
     */
    private val syntheticConfig = """
        const getData = (): WebConfig => /* break */ ({
          key: 'demo',
          name: 'Demo',
          homeUrl: 'https://example.com/',
          getUrlReplace(§) { return []; },
          getItems(§) {
            const res = [];
            §('#list .item').each((i, el) => {
              const §el = §(el);
              const §a = §el.find(ElementTypes.a);
              res.push({
                jumpUrl: helpElAttr(§a, ElementAttr.href),
                title: helpElText(§el.find('.title')),
                cover: helpElAttr(§el.find(ElementTypes.img), ElementAttr.dataSrc) ||
                       helpElAttr(§el.find(ElementTypes.img), ElementAttr.src),
                vip: §el.hasClass('vip'),
                hasDataId: §el.toString().indexOf('data-id') >= 0,
              });
            });
            return res;
          },
          getPagination(§) {
            const out = [];
            §('.pager li').each((i, el) => {
              const §el = §(el);
              out.push({ title: helpElText(§el), url: helpElAttr(§el.find('a'), 'href'), isCurrent: §el.hasClass('active') });
            });
            return out;
          },
          getTags(§) { return [{ title: '占位', url: '' }]; },
          async getDetailInfo(item, cheerio) { return null; },
          adapterLoadUrl(url) { return url; },
          adapterSearchUrl(key) { return this.homeUrl + key; },
        });
    """.trimIndent().replace('§', '$')

    // ── 测试 ────────────────────────────────────────────────────────────────

    @Test
    fun `合成配置的 getItems 走通桥接`() {
        JsConfigRuntime().use { rt ->
            rt.loadDocument(syntheticHtml)
            rt.loadConfig(syntheticConfig)

            val items = rt.invoke("getItems")
            assertEquals(2, items.arraySize.toInt())

            val first = items.getArrayElement(0)
            assertEquals("/1", first.getMember("jumpUrl").asString())
            assertEquals("A", first.getMember("title").asString())
            // data-src 优先于 src
            assertEquals("/a.jpg", first.getMember("cover").asString())
            assertTrue(first.getMember("vip").asBoolean())
            assertTrue(first.getMember("hasDataId").asBoolean())

            val second = items.getArrayElement(1)
            assertEquals("/2", second.getMember("jumpUrl").asString())
            assertEquals("/b.jpg", second.getMember("cover").asString())
        }
    }

    @Test
    fun `合成配置的 getPagination 与 hasClass`() {
        JsConfigRuntime().use { rt ->
            rt.loadDocument(syntheticHtml)
            rt.loadConfig(syntheticConfig)

            val pages = rt.invoke("getPagination")
            assertEquals(2, pages.arraySize.toInt())
            val current = pages.getArrayElement(0)
            assertEquals("1", current.getMember("title").asString())
            assertTrue(current.getMember("isCurrent").asBoolean())
            assertEquals("", current.getMember("url").asString())
            assertEquals("/p2", pages.getArrayElement(1).getMember("url").asString())
        }
    }

    @Test
    fun `this 绑定到配置对象`() {
        JsConfigRuntime().use { rt ->
            rt.loadConfig(syntheticConfig)
            val url = rt.invoke("adapterSearchUrl", "'abc'")
            assertEquals("https://example.com/abc", url.asString())
        }
    }

    // ── 真实配置：语义级校验（替代文本正则） ──────────────────────────────────

    private fun samples(): List<File> {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = dir?.resolve("tmp/configs")
            if (candidate != null && candidate.isDirectory) {
                val files = candidate.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.sortedBy { it.name }.orEmpty()
                if (files.isNotEmpty()) return files
            }
            dir = dir?.parentFile
        }
        assumeTrue(false, "本地没有 tmp/configs 样本，跳过真实配置校验")
        return emptyList()
    }

    @Test
    fun `五份真实配置都能求值且具备 7 个必需方法`() {
        for (file in samples()) {
            JsConfigRuntime().use { rt ->
                rt.loadConfig(file.readText())
                val missing = requiredMethods.filterNot { rt.hasMethod(it) }
                assertTrue(missing.isEmpty(), "${file.name} 缺少必需方法：$missing")
                println("[${file.name}] 7 个必需方法齐全")
            }
        }
    }

    @Test
    fun `真实配置的静态标签方法可执行并返回预期条数`() {
        // (文件, 方法, 期望条数) —— 这些方法不依赖页面结构，可在空文档下直接跑
        val expectations = listOf(
            Triple("18comic.js", "getTags", 12),
            Triple("EHentai.js", "getTags", 22),
            Triple("hentaiWord.js", "getTags", 18),
            Triple("shasha.js", "getTags", 0),
            Triple("EHentai.js", "getPagination", 0),
        )
        val byName = samples().associateBy { it.name }

        for ((name, method, expected) in expectations) {
            val file = byName[name] ?: continue
            JsConfigRuntime().use { rt ->
                rt.loadDocument("")
                rt.loadConfig(file.readText())
                val value = rt.invoke(method)
                val actual = if (value.hasArrayElements()) value.arraySize.toInt() else -1
                println("[$name] $method -> $actual (期望 $expected)")
                if (actual != expected) {
                    throw AssertionError("[$name] $method 返回 $actual 条，期望 $expected 条")
                }
            }
        }
    }

    @Test
    fun `真实配置里的静态标签确实用 homeUrl 拼接`() {
        val byName = samples().associateBy { it.name }
        val file = byName["18comic.js"] ?: return

        JsConfigRuntime().use { rt ->
            rt.loadConfig(file.readText())
            val tags = rt.invoke("getTags")
            val firstUrl = tags.getArrayElement(0).getMember("url").asString()
            assertTrue(firstUrl.startsWith("https://"), "标签地址应为绝对地址：$firstUrl")
            println("[18comic.js] 首个标签地址：$firstUrl")
        }
    }

    @Test
    fun `含旧式事件写法的配置也能安全求值`() {
        val byName = samples().associateBy { it.name }
        val file = byName["hentaiWord.js"] ?: return

        JsConfigRuntime().use { rt ->
            // postDataByRequest 里是旧式 net.request(...).on(...)，只定义未调用，求值不应失败
            val config: Value = rt.loadConfig(file.readText())
            assertTrue(config.hasMember("postDataByRequest"), "应保留自定义方法")
            assertTrue(rt.hasMethod("getItems"))
        }
    }
}
