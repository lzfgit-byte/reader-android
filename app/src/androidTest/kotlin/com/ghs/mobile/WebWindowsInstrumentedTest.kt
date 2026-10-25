package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ghs.mobile.core.script.window.WebWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 窗口注册表 / 对象池的真机验证（不访问外网，用 data URL 加载）。
 *
 * 断言的是三条窗口语义：
 * - [WebWindows.FETCH_SPEC] 单例：两次取到同一个实例，能加载并求值
 * - [WebWindows.IMAGE_SPEC] 池化：归还后再次取用会复用同一个实例
 * - `destroy()` 之后同名窗口会被重建，不会拿到已销毁的实例
 */
@RunWith(AndroidJUnit4::class)
class WebWindowsInstrumentedTest {

    private val page = "data:text/html,<html><body><h1 id=t>hi</h1></body></html>"

    @Before
    fun setUp() {
        WebWindows.initialize(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @Test
    fun fetchWindowIsSingletonAndEvaluates() {
        val first = WebWindows.acquire(WebWindows.FETCH_SPEC)
        assertFalse("窗口应可用", first.isClosed)

        assertTrue("data URL 应能加载完成", first.load(page, timeoutMs = 15_000))
        // evaluateJavascript 回传的是 JSON：字符串带引号
        assertEquals("\"hi\"", first.eval("document.getElementById('t').textContent"))

        val second = WebWindows.acquire(WebWindows.FETCH_SPEC)
        assertSame("单例窗口应被复用", first, second)
    }

    @Test
    fun imagePoolReusesReleasedWindow() {
        val first = WebWindows.acquire(WebWindows.IMAGE_SPEC)
        WebWindows.release(first)
        val second = WebWindows.acquire(WebWindows.IMAGE_SPEC)
        assertSame("池化窗口归还后应被复用", first, second)
        WebWindows.release(second)
    }

    @Test
    fun destroyedWindowIsNotHandedOut() {
        val first = WebWindows.acquire(WebWindows.BROWSER_SPEC)
        first.destroy()
        val second = WebWindows.acquire(WebWindows.BROWSER_SPEC)
        assertNotSame("销毁后应重建", first, second)
        assertFalse(second.isClosed)
        second.destroy()
    }
}
