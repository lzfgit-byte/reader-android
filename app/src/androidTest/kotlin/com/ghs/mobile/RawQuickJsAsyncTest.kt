package com.ghs.mobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.quickjs.JSContext
import com.quickjs.QuickJS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 原始 QuickJS 实验：微任务是否只在"事件队列被驱动"时才前进。
 *
 * 如果 `postEventQueue` 一次就能让 `Promise.then` 落地，那异步桥可以靠"主动泵"实现；
 * 如果不行，则说明该封装在 Java 层完全没有 `JS_ExecutePendingJob`，`await` 不可用。
 */
@RunWith(AndroidJUnit4::class)
class RawQuickJsAsyncTest {

    @Test
    fun promise_then_advances_only_when_pumped() {
        val runtime = QuickJS.createRuntimeWithEventQueue()
        val context: JSContext = runtime.createContext()
        try {
            context.executeScript(
                "globalThis.__m = 'pending';" +
                    "Promise.resolve().then(function () { globalThis.__m = 'done'; }); 0",
                "t",
            )
            Log.i(TAG, "before_post=" + context.executeScript("globalThis.__m", "t"))

            // 驱动一次事件队列（等价于"泵一次"）
            val latch = CountDownLatch(1)
            runtime.postEventQueue { latch.countDown() }
            latch.await(3, TimeUnit.SECONDS)
            Log.i(TAG, "after_post=" + context.executeScript("globalThis.__m", "t"))

            // 再连泵多次
            repeat(3) {
                val l = CountDownLatch(1)
                runtime.postEventQueue { l.countDown() }
                l.await(2, TimeUnit.SECONDS)
            }
            Log.i(TAG, "after_3_more=" + context.executeScript("globalThis.__m", "t"))

            // async/await 是否推进
            context.executeScript(
                "globalThis.__a = 'running';" +
                    "(async function () { await Promise.resolve(); globalThis.__a = 'awaited'; })(); 0",
                "t",
            )
            val l2 = CountDownLatch(1)
            runtime.postEventQueue { l2.countDown() }
            l2.await(2, TimeUnit.SECONDS)
            Log.i(TAG, "async_after_pump=" + context.executeScript("globalThis.__a", "t"))
        } finally {
            runCatching { context.close() }
            runCatching { runtime.close() }
        }
    }

    private companion object {
        const val TAG = "GhsRaw"
    }
}
