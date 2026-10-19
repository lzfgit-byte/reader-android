package com.ghs.mobile.core.engine.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigSourceTest {

    private val source = """
        const getData = (): WebConfig => /* break */ ({
          key: 'demo',
          getItems($) { return []; },
        });
    """.trimIndent()

    @Test
    fun `按标记切成头与体`() {
        val parts = ConfigSource.split(source)
        assertTrue(parts.hasBreakMarker)
        assertTrue(parts.header.contains("const getData"), "header 应保留元信息包裹：${parts.header}")
        assertTrue(parts.body.startsWith("({"), "body 应以对象字面量开头：${parts.body}")
        assertTrue(parts.body.endsWith("});"))
    }

    @Test
    fun `没有标记时整段当体且显式标注`() {
        val parts = ConfigSource.split("({ key: 'x' })")
        assertFalse(parts.hasBreakMarker)
        assertEquals("", parts.header)
        assertEquals("({ key: 'x' })", parts.body)
    }

    @Test
    fun `标记之后为空则报错`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigSource.bodyOf("const getData = () => /* break */   ")
        }
    }

    @Test
    fun `只取第一个标记`() {
        val parts = ConfigSource.split("a /* break */ b /* break */ c")
        assertEquals("b /* break */ c", parts.body)
    }
}
