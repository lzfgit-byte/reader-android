package com.ghs.mobile

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghs.mobile.core.script.QuickJsScriptEngine
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 能力探测：QuickJS 封装没有暴露 `JS_ExecutePendingJob`，所以"`await` 能不能推进"
 * 只能实测。本测试把结论打到 logcat（tag=GhsAsync），据此决定异步桥的实现方式。
 */
@RunWith(AndroidJUnit4::class)
class AsyncCapabilityProbeTest {

    @Test
    fun probe_microtask_progression() {
        QuickJsScriptEngine().use { engine ->
            // 1) 两次 executeScript 之间，微任务有没有被推动
            engine.eval(
                "globalThis.__micro = 'pending';" +
                    "Promise.resolve().then(function () { globalThis.__micro = 'done'; }); 0",
            )
            Log.i(TAG, "microtask_after_eval=" + engine.eval("globalThis.__micro"))

            // 2) async 函数里 await 一个已 resolve 的 Promise
            engine.eval(
                "globalThis.__state = 'running';" +
                    "(async function () { await Promise.resolve(); globalThis.__state = 'awaited'; })(); 0",
            )
            Log.i(TAG, "async_after_eval=" + engine.eval("globalThis.__state"))

            // 3) then 链在多次 eval 后是否继续推进
            engine.eval(
                "globalThis.__chain = 0;" +
                    "Promise.resolve(1).then(function () { globalThis.__chain = 1; })" +
                    ".then(function () { globalThis.__chain = 2; }); 0",
            )
            Log.i(TAG, "chain_after_1st=" + engine.eval("globalThis.__chain"))
            Log.i(TAG, "chain_after_2nd=" + engine.eval("globalThis.__chain"))
            Log.i(TAG, "chain_after_3rd=" + engine.eval("globalThis.__chain"))

            // 4) 定时器是否可用（决定"延迟 resolve"能否被测）
            Log.i(TAG, "typeof_setTimeout=" + engine.eval("typeof setTimeout"))
            Log.i(TAG, "typeof_queueMicrotask=" + engine.eval("typeof queueMicrotask"))
        }
    }

    private companion object {
        const val TAG = "GhsAsync"
    }
}
