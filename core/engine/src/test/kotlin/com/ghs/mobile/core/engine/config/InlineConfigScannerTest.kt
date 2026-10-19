package com.ghs.mobile.core.engine.config

import com.ghs.mobile.core.model.ErrorCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class InlineConfigScannerTest {

    /**
     * 逐行拼装内联配置，避免对缩进做脆弱的字符串替换。
     * `omit` 里的方法不会被写入。
     */
    private fun config(
        key: String? = "demo",
        name: String? = "Demo",
        homeUrl: String? = "https://example.com",
        omit: Set<String> = emptySet(),
        itemsBody: String = "return [];",
    ): String = buildString {
        appendLine("const getData = (): WebConfig => /* break */ ({")
        key?.let { appendLine("  key: '$it',") }
        name?.let { appendLine("  name: '$it',") }
        homeUrl?.let { appendLine("  homeUrl: '$it',") }
        if ("getUrlReplace" !in omit) appendLine("  getUrlReplace($) { return []; },")
        if ("getItems" !in omit) appendLine("  getItems($) { $itemsBody },")
        if ("getPagination" !in omit) appendLine("  getPagination($) { return []; },")
        if ("getTags" !in omit) appendLine("  getTags($) { return []; },")
        if ("getDetailInfo" !in omit) appendLine("  async getDetailInfo(item, cheerio) { return null; },")
        if ("adapterLoadUrl" !in omit) appendLine("  adapterLoadUrl(url) { return url; },")
        if ("adapterSearchUrl" !in omit) appendLine("  adapterSearchUrl(key) { return this.homeUrl + key; },")
        appendLine("});")
    }

    @Test
    fun `完整配置通过预检`() {
        val result = InlineConfigScanner.validate(config())
        assertTrue(result.ok, "不应有错误：${result.errors}")
        assertTrue(result.warnings.isEmpty(), "不应有警告：${result.warnings}")
    }

    @Test
    fun `抽取 key name homeUrl 与方法集合`() {
        val scan = InlineConfigScanner.scan(config())
        assertEquals("demo", scan.key)
        assertEquals("Demo", scan.name)
        assertEquals("https://example.com", scan.homeUrl)
        assertTrue(scan.hasBreakMarker)
        assertTrue(scan.missingRequired.isEmpty())
        assertTrue(InlineConfigScanner.REQUIRED_METHODS.all { it in scan.methods })
    }

    @Test
    fun `key 允许大写与数字开头`() {
        for (k in listOf("demo", "EHentai", "91P", "hentaiWord", "a1")) {
            val result = InlineConfigScanner.validate(config(key = k))
            assertTrue(result.ok, "key=$k 应通过：${result.errors}")
        }
    }

    @Test
    fun `key 格式非法报 E-CFG-003`() {
        for (bad in listOf("Bad Key!", "-lead", "a")) {
            val result = InlineConfigScanner.validate(config(key = bad))
            assertTrue(result.errors.any { it.errorCode == ErrorCode.INVALID_KEY }, "key=$bad 应报 E-CFG-003")
        }
    }

    @Test
    fun `缺少必需方法逐个报 E-CFG-005`() {
        val result = InlineConfigScanner.validate(config(omit = setOf("getUrlReplace")))
        assertFalse(result.ok)
        val missing = result.errors.filter { it.errorCode == ErrorCode.REQUIRED_METHOD_MISSING }
        assertEquals(1, missing.size)
        assertTrue(missing.single().message.contains("getUrlReplace"))
        assertEquals("script.getUrlReplace", missing.single().path)
    }

    @Test
    fun `缺少多个必需方法时全部列出`() {
        val result = InlineConfigScanner.validate(config(omit = setOf("getTags", "getPagination")))
        val missing = result.errors.filter { it.errorCode == ErrorCode.REQUIRED_METHOD_MISSING }
        assertEquals(2, missing.size)
    }

    @Test
    fun `缺少 key 与 homeUrl 报 E-CFG-002`() {
        val result = InlineConfigScanner.validate(config(key = null, homeUrl = null))
        val paths = result.errors
            .filter { it.errorCode == ErrorCode.MISSING_REQUIRED_FIELD }
            .mapNotNull { it.path }
        assertEquals(listOf("meta.key", "meta.homeUrl"), paths)
    }

    @Test
    fun `超过 1MB 报 E-CFG-010`() {
        val result = InlineConfigScanner.validate(config(), sizeBytes = 1_000_001)
        assertTrue(result.errors.any { it.errorCode == ErrorCode.SIZE_LIMIT })
    }

    @Test
    fun `旧的 net request 事件写法给 E-CFG-030 警告但不阻断`() {
        val src = config(
            itemsBody = """
                const request = net.request({ url: this.homeUrl });
                request.on('response', () => {});
                return [];
            """.trimIndent(),
        )
        val result = InlineConfigScanner.validate(src)
        assertTrue(result.ok, "旧式写法只应给警告，不应阻断：${result.errors}")
        assertTrue(result.warnings.any { it.errorCode == ErrorCode.DEPRECATED_API })
    }

    @Test
    fun `Promise 形态的 net request 不报警告`() {
        val src = config(
            itemsBody = """
                const res = await net.request({ url: this.homeUrl });
                return [];
            """.trimIndent(),
        )
        val result = InlineConfigScanner.validate(src)
        assertTrue(result.warnings.none { it.errorCode == ErrorCode.DEPRECATED_API })
    }

    @Test
    fun `控制流关键字不会被误判为配置方法`() {
        val src = config(
            itemsBody = """
                if (true) { }
                for (let i = 0; i < 3; i++) { }
                while (false) { }
                return [];
            """.trimIndent(),
        )
        val scan = InlineConfigScanner.scan(src)
        assertTrue(scan.methods.none { it in setOf("if", "for", "while") }, "误判：${scan.methods}")
    }

    @Test
    fun `缺少 break 分隔符时给警告`() {
        val result = InlineConfigScanner.validate(config().replace("/* break */ ", ""))
        assertTrue(result.warnings.any { it.message.contains("break") })
    }

    // ── 真实配置样本（tmp/configs 不入库，缺失时自动跳过） ──

    private fun fixtureDir(): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = dir?.resolve("tmp/configs")
            if (candidate != null && candidate.isDirectory) return candidate
            dir = dir?.parentFile
        }
        return null
    }

    private fun samples(): List<File> {
        val dir = fixtureDir()
        assumeTrue(dir != null, "本地没有 tmp/configs，跳过真实样本校验")
        val files = dir!!.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.sortedBy { it.name }.orEmpty()
        assumeTrue(files.isNotEmpty(), "tmp/configs 里没有 .js 样本，跳过")
        return files
    }

    @Test
    fun `真实配置样本全部通过静态预检`() {
        val failures = mutableListOf<String>()
        for (file in samples()) {
            val source = file.readText()
            val scan = InlineConfigScanner.scan(source)
            val result = InlineConfigScanner.validate(source, source.toByteArray().size)

            println(
                "[%s] key=%s methods=%d missing=%s warns=%s".format(
                    file.name, scan.key, scan.methods.size,
                    scan.missingRequired, result.warnings.map { it.errorCode.code },
                ),
            )
            if (!result.ok) failures += "${file.name}: ${result.errors.map { it.message }}"
        }
        assertTrue(failures.isEmpty(), "真实样本预检失败：\n" + failures.joinToString("\n"))
    }

    @Test
    fun `真实样本里只有含旧式事件写法的那个被标记`() {
        val flagged = samples().filter { InlineConfigScanner.scan(it.readText()).usesDeprecatedNetRequest }.map { it.name }
        println("旧式 net.request 事件写法：$flagged")
        assertTrue(flagged.size <= 1, "不应有多个样本使用旧式事件写法：$flagged")
    }
}
