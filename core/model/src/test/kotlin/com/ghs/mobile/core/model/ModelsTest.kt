package com.ghs.mobile.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsTest {

    @Test
    fun `空 url 的 tag 视为占位`() {
        assertTrue(Tag("-----").isPlaceholder)
        assertTrue(Tag("分隔", "   ").isPlaceholder)
        assertFalse(Tag("标签", "https://x.test/t").isPlaceholder)
    }

    @Test
    fun `item 归一化去空白并过滤空标签`() {
        val item = Item(
            jumpUrl = "  https://x.test/a  ",
            title = "  标题  ",
            coverImg = " https://x.test/c.jpg ",
            author = "   ",
            tags = listOf(Tag(" A ", " u "), Tag("", "x")),
        ).normalized()

        assertEquals("https://x.test/a", item.jumpUrl)
        assertEquals("标题", item.title)
        assertEquals("https://x.test/c.jpg", item.coverImg)
        assertNull(item.author)
        assertEquals(1, item.tags.size)
        assertEquals("A", item.tags[0].title)
        assertEquals("u", item.tags[0].url)
    }

    @Test
    fun `item 缺失 type 与 renderType 时取默认值`() {
        val item = Item(jumpUrl = "https://x.test/a")
        assertEquals(ItemType.IMAGE, item.type)
        assertEquals(RenderType.NORMAL, item.renderType)
    }

    @Test
    fun `detail 优先用 fullUrl 并在为空时回落 url`() {
        assertEquals("https://x.test/full.jpg", Detail(url = "https://x.test/thumb.jpg", fullUrl = "https://x.test/full.jpg").preferredUrl)
        assertEquals("https://x.test/thumb.jpg", Detail(url = "https://x.test/thumb.jpg", fullUrl = "  ").preferredUrl)
        assertEquals("https://x.test/thumb.jpg", Detail(url = "https://x.test/thumb.jpg", fullUrl = null).preferredUrl)
    }

    @Test
    fun `分页项 url 为空表示禁用`() {
        assertTrue(Pagination(title = "3", url = "").disabled)
        assertTrue(Pagination(title = "current", url = "", isCurrent = true).disabled)
        assertFalse(Pagination(title = "4", url = "https://x.test/p4").disabled)
    }

    @Test
    fun `错误码唯一且带正确前缀`() {
        val codes = ErrorCode.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size, "错误码必须唯一")

        for (c in ErrorCode.entries) {
            val expectedPrefix = when (c.kind) {
                ErrorCode.Kind.CONFIG -> "E-CFG-"
                ErrorCode.Kind.RUNTIME -> "E-CHALLENGE-"
            }
            assertTrue(c.code.startsWith(expectedPrefix), "${c.code} 前缀不符")
        }
    }

    @Test
    fun `只有运行期中断可重试`() {
        assertFalse(ErrorCode.REQUIRED_METHOD_MISSING.retryable)
        assertFalse(ErrorCode.SCRIPT_TIMEOUT.retryable)
        assertTrue(ErrorCode.CHALLENGE_CANCELLED.retryable)
        assertTrue(ErrorCode.CHALLENGE_TIMEOUT.retryable)
    }

    @Test
    fun `错误码可反查`() {
        assertEquals(ErrorCode.CHALLENGE_REQUIRED, ErrorCode.of("E-CHALLENGE-001"))
        assertNull(ErrorCode.of("E-UNKNOWN-999"))
    }

    @Test
    fun `校验结果只有无错误才 ok`() {
        assertTrue(ValidationResult().ok)
        assertFalse(
            ValidationResult(
                errors = listOf(Issue(ErrorCode.MISSING_REQUIRED_FIELD, "缺少 key", path = "meta.key")),
                warnings = listOf(Issue(ErrorCode.DEPRECATED_API, "旧式写法")),
            ).ok,
        )
        assertTrue(ValidationResult(warnings = listOf(Issue(ErrorCode.DEPRECATED_API, "旧式写法"))).ok)
    }
}
