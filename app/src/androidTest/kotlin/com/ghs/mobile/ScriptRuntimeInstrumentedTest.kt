package com.ghs.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ghs.mobile.core.script.ScriptProbe
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机（或模拟器）上的脚本运行时验证。
 *
 * 这是"QuickJS 能不能跑我们的配置"的**可断言证据**：
 * 跑 `ScriptProbe`，要求里面每一项自检都通过。
 *
 * 运行：`gradle :app:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class ScriptRuntimeInstrumentedTest {

    @Test
    fun quickjs_runtime_probe_all_ok() {
        val lines = ScriptProbe.run()

        // 打印到 logcat/instrumentation 输出，失败时便于定位
        lines.forEach { println("PROBE ${it.ok} ${it.label} = ${it.value}") }

        val failed = lines.filter { it.ok == false }
        assertTrue("自检失败项：${failed.map { "${it.label}=${it.value}" }}", failed.isEmpty())

        // 至少要有一批"被判定为通过"的检查项，避免空跑也算过
        val passed = lines.count { it.ok == true }
        assertTrue("通过项过少（$passed），疑似环境异常", passed >= 7)
    }
}
