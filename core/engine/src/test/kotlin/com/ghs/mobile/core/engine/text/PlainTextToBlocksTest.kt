package com.ghs.mobile.core.engine.text

import com.ghs.mobile.core.model.BlockType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlainTextToBlocksTest {

    @Test
    fun `单行文本生成一个段落`() {
        val blocks = PlainTextToBlocks.convert("hello world")
        assertEquals(1, blocks.size)
        assertEquals(BlockType.P, blocks[0].type)
        assertEquals("hello world", blocks[0].text)
    }

    @Test
    fun `多行按行分段并跳过空行`() {
        val blocks = PlainTextToBlocks.convert("a\n\n\nb\n   \nc")
        assertEquals(listOf("a", "b", "c"), blocks.map { it.text })
    }

    @Test
    fun `br 标签等价于换行`() {
        val blocks = PlainTextToBlocks.convert("第一行<br>第二行<BR/>第三行")
        assertEquals(listOf("第一行", "第二行", "第三行"), blocks.map { it.text })
    }

    @Test
    fun `段内图片被摘出并切成前后两段`() {
        val blocks = PlainTextToBlocks.convert("""前文<img src="https://x.test/1.jpg">后文""")
        assertEquals(3, blocks.size)
        assertEquals(BlockType.P, blocks[0].type)
        assertEquals("前文", blocks[0].text)
        assertEquals(BlockType.IMAGE, blocks[1].type)
        assertEquals("https://x.test/1.jpg", blocks[1].src)
        assertEquals(BlockType.P, blocks[2].type)
        assertEquals("后文", blocks[2].text)
    }

    @Test
    fun `src 支持单引号与无引号`() {
        val single = PlainTextToBlocks.convert("<img src='https://x.test/a.png'>")
        assertEquals("https://x.test/a.png", single.single().src)

        val bare = PlainTextToBlocks.convert("<img src=https://x.test/b.png width=10>")
        assertEquals("https://x.test/b.png", bare.single().src)
    }

    @Test
    fun `缺 src 的图片被丢弃且不吞掉正文`() {
        val blocks = PlainTextToBlocks.convert("""<img class="x">可见文本""")
        assertEquals(1, blocks.size)
        assertEquals("可见文本", blocks.single().text)
    }

    @Test
    fun `非 http 协议的图片被丢弃`() {
        val blocks = PlainTextToBlocks.convert("""<img src="javascript:alert(1)">正文""")
        assertEquals(1, blocks.size)
        assertEquals("正文", blocks.single().text)
    }

    @Test
    fun `协议相对与 data 图片可用`() {
        assertEquals("//x.test/a.png", PlainTextToBlocks.convert("<img src='//x.test/a.png'>").single().src)
        assertEquals(
            "data:image/png;base64,AAA",
            PlainTextToBlocks.convert("<img src='data:image/png;base64,AAA'>").single().src,
        )
    }

    @Test
    fun `实体被解码`() {
        val blocks = PlainTextToBlocks.convert("A&amp;B&nbsp;C&#39;D&#x4E2D;")
        assertEquals("A&B C'D中", blocks.single().text)
    }

    @Test
    fun `实体化的 img 仍按纯文本处理`() {
        val blocks = PlainTextToBlocks.convert("""&lt;img src="https://x.test/1.jpg"&gt;""")
        assertEquals(1, blocks.size)
        assertEquals(BlockType.P, blocks.single().type)
        assertEquals("""<img src="https://x.test/1.jpg">""", blocks.single().text)
    }

    @Test
    fun `空白输入返回空列表`() {
        assertTrue(PlainTextToBlocks.convert("").isEmpty())
        assertTrue(PlainTextToBlocks.convert("   \n\t ").isEmpty())
    }

    @Test
    fun `已有 blocks 时优先使用 blocks`() {
        val preset = PlainTextToBlocks.convert("段落")
        assertEquals(preset, PlainTextToBlocks.blocksOf("完全不同的文本", preset))
        assertEquals(1, PlainTextToBlocks.blocksOf("没有预置块", emptyList()).size)
    }

    @Test
    fun `超长文本被截断且不抛异常`() {
        val huge = "字".repeat(PlainTextToBlocks.MAX_CHARS + 1000)
        val blocks = PlainTextToBlocks.convert(huge)
        assertEquals(1, blocks.size)
        assertEquals(PlainTextToBlocks.MAX_CHARS, blocks.single().text.length)
    }
}
