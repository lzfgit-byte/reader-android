package com.ghs.mobile.core.engine.net

import kotlin.test.Test
import kotlin.test.assertEquals

class UrlNormalizerTest {

    @Test
    fun `去空白与 fragment`() {
        assertEquals("https://x.test/a", UrlNormalizer.normalize("  https://x.test/a#top  "))
    }

    @Test
    fun `去跟踪参数保留其余 query`() {
        assertEquals(
            "https://x.test/a?id=7&page=2",
            UrlNormalizer.normalize("https://x.test/a?utm_source=x&id=7&from=list&page=2&spm=abc"),
        )
    }

    @Test
    fun `去末尾斜杠`() {
        assertEquals("https://x.test/a/b", UrlNormalizer.normalize("https://x.test/a/b/"))
        assertEquals("https://x.test", UrlNormalizer.normalize("https://x.test/"))
    }

    @Test
    fun `保留端口与多级路径`() {
        assertEquals("http://192.168.1.9:8080/x/y", UrlNormalizer.normalize("http://192.168.1.9:8080/x/y/"))
    }

    @Test
    fun `只有跟踪参数时 query 被完全去掉`() {
        assertEquals("https://x.test/a", UrlNormalizer.normalize("https://x.test/a?utm_medium=email"))
    }

    @Test
    fun `非法输入只做去空白与去尾斜杠`() {
        assertEquals("not a url", UrlNormalizer.normalize(" not a url "))
        assertEquals("", UrlNormalizer.normalize("   "))
    }

    @Test
    fun `多次归一化结果稳定`() {
        val once = UrlNormalizer.normalize("https://x.test/a/?utm_source=z#frag")
        val twice = UrlNormalizer.normalize(once)
        assertEquals(once, twice)
    }
}
