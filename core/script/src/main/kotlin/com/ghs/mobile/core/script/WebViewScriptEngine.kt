package com.ghs.mobile.core.script

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.ghs.mobile.core.engine.script.ScriptEngine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * WebView 实现的 [ScriptEngine]：**真事件循环**，`Promise` / `await` 原生可用。
 *
 * 为什么需要它：QuickJS 封装（`io.github.taoweiji:quickjs-android`）没有暴露
 * `JS_ExecutePendingJob`，实测微任务永不前进——`await getHtml(...)` 会永远挂住。
 * 而真实配置里 `await` 与 `new Promise` 都在引擎侧被使用，所以异步必须由带事件循环的宿主承担。
 *
 * 设计要点：
 * - **宿主调用是同步的**：JS → Java 通过 `@JavascriptInterface`，Java 侧阻塞返回，JS 侧立即拿到值；
 *   `await` 一个非 Promise 值会立即得到它，所以配置里的 `await getHtml(...)` 语义不变
 * - 求值走 `evaluateJavascript`，结果经 `__ghs.onResult` 回传，Kotlin 侧用 latch 等待
 * - 所有 WebView 操作都在主线程
 */
class WebViewScriptEngine(context: Context) : ScriptEngine {

    private val main = Handler(Looper.getMainLooper())
    private val functions = HashMap<String, (List<Any?>) -> Any?>()

    @Volatile
    private var webView: WebView? = null

    private val ready = CountDownLatch(1)

    private val pendingLock = Object()
    private var pendingLatch: CountDownLatch? = null
    private var pendingPayload: String? = null

    init {
        onMain {
            val view = WebView(context.applicationContext)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            view.addJavascriptInterface(HostApi(), BRIDGE)
            // 必须等页面加载完成：early evaluateJavascript 落在临时上下文里，页面一加载就被丢弃
            view.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    ready.countDown()
                }
            }
            view.loadDataWithBaseURL(BASE_URL, "<html><body></body></html>", "text/html", "utf-8", null)
            webView = view
        }
    }

    /**
     * 同步求值：用 `evaluateJavascript` 自带的回调拿 JSON 结果。
     *
     * 刻意不走 `__ghs.onResult`：那条路是给 `evalAwait` 用的，
     * 混用会让前一次异步回调把结果写进下一次的等待槽。
     */
    override fun eval(script: String, name: String): Any? {
        if (!ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("WebView 未就绪（$name）")
        }
        val latch = CountDownLatch(1)
        var raw: String? = null

        onMain {
            webView?.evaluateJavascript("(function(){ return ($script); })()") { value ->
                raw = value
                latch.countDown()
            }
        }

        if (!latch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("脚本执行超时（$name）")
        }
        val text = raw ?: return null
        return if (text == "null") null else MiniJson.parse(text)
    }

    override fun evalAwait(script: String, name: String, timeoutMs: Long): Any? =
        evaluateAndAwait(script, name, timeoutMs)

    override fun setGlobal(name: String, value: Any?) {
        val literal = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            is String -> quote(value)
            else -> quote(value.toString())
        }
        onMain { webView?.evaluateJavascript("window[${quote(name)}] = $literal; 0", null) }
    }

    override fun setFunction(name: String, fn: (args: List<Any?>) -> Any?) {
        functions[name] = fn
        // JS 侧包装：参数按类型编码成 \u0001 分隔的串交给 Java；返回值按类型还原。
        // 不能无脑 JSON.parse：@JavascriptInterface 传回的字符串可能已被去引号。
        val shim = """
            window[${quote(name)}] = function () {
              var args = Array.prototype.slice.call(arguments);
              var encoded = args.map(function (v) {
                if (v === null || v === undefined) return '\u0002';
                if (typeof v === 'number') return 'n' + v;
                if (typeof v === 'boolean') return 'b' + (v ? 1 : 0);
                if (typeof v === 'object') {
                  if (typeof v.__h === 'number') return 'h' + v.__h;
                  return 'j' + JSON.stringify(v);
                }
                return 's' + String(v);
              }).join('\u0001');
              var raw = __ghs.call(${quote(name)}, encoded);
              if (raw === 'null' || raw === null) return null;
              if (raw === 'true') return true;
              if (raw === 'false') return false;
              if (/^-?\d+(\.\d+)?$/.test(raw)) return Number(raw);
              try { return JSON.parse(raw); } catch (e) { return raw; }
            }; 0
        """.trimIndent()
        onMain { webView?.evaluateJavascript(shim, null) }
        // 同步屏障：确保 shim 已经装进 JS 上下文，再返回给调用方。
        // 否则紧随其后的 prelude/配置求值可能跑在 shim 之前，得到 "xxx is not defined"。
        if (ready.count == 0L) {
            runCatching { eval("1", "sync:$name") }
        }
    }

    override fun close() {
        onMain {
            webView?.let {
                it.removeJavascriptInterface(BRIDGE)
                it.destroy()
            }
            webView = null
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    private fun evaluateAndAwait(expression: String, name: String, timeoutMs: Long): Any? {
        if (!ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("WebView 未就绪（$name）")
        }
        val latch = CountDownLatch(1)
        synchronized(pendingLock) {
            pendingPayload = null
            pendingLatch = latch
        }

        val script = """
            (async function () {
              try {
                var value = await ($expression);
                __ghs.onResult(JSON.stringify({ ok: true, value: value === undefined ? null : value }));
              } catch (error) {
                __ghs.onResult(JSON.stringify({ ok: false, error: String((error && error.stack) || error) }));
              }
            })(); 0
        """.trimIndent()

        onMain { webView?.evaluateJavascript(script, null) }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("脚本执行超时（${timeoutMs}ms，$name）")
        }
        return decodeResult(synchronized(pendingLock) { pendingPayload })
    }

    /** 解析 `{ok, value}` / `{ok:false, error}` */
    private fun decodeResult(payload: String?): Any? {
        if (payload == null) throw IllegalStateException("脚本执行没有返回结果")
        val parsed = MiniJson.parse(payload) as? Map<*, *>
            ?: throw IllegalStateException("脚本返回格式异常：$payload")
        if (parsed["ok"] != true) {
            throw IllegalStateException("脚本错误：${parsed["error"] ?: payload}")
        }
        if (!parsed.containsKey("value")) {
            throw IllegalStateException("脚本返回缺少 value：$payload")
        }
        return parsed["value"]
    }

    private inner class HostApi {

        @JavascriptInterface
        fun call(name: String, encodedArgs: String): String {
            val fn = functions[name] ?: return "null"
            val args = decodeArgs(encodedArgs)
            val result = runCatching { fn(args) }
                .getOrElse { error ->
                    return "\"" + quote(MiniJson.stringify(error.message ?: error.toString())) + "\""
                }
            return MiniJson.stringify(result)
        }

        @JavascriptInterface
        fun onResult(json: String) {
            synchronized(pendingLock) {
                pendingPayload = json
                pendingLatch?.countDown()
            }
        }
    }

    private fun decodeArgs(encoded: String): List<Any?> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split(SEP).map { token ->
            when {
                token == "\u0002" -> null
                token.startsWith("n") -> token.drop(1).toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toInt() else it }
                token.startsWith("b") -> token.drop(1) == "1"
                token.startsWith("h") -> token.drop(1).toIntOrNull() ?: 0
                token.startsWith("j") -> MiniJson.parse(token.drop(1))
                else -> token.drop(1)
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            val latch = CountDownLatch(1)
            main.post {
                try {
                    block()
                } finally {
                    latch.countDown()
                }
            }
            latch.await(10, TimeUnit.SECONDS)
        }
    }

    private fun quote(text: String): String = MiniJson.stringify(text)

    private companion object {
        const val BRIDGE = "__ghs"
        const val BASE_URL = "https://ghs.local/"
        const val SEP = "\u0001"
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val READY_TIMEOUT_MS = 15_000L
    }
}

/**
 * 极简 JSON 编解码：只处理脚本边界需要的类型（null / 布尔 / 数字 / 字符串 / 数组 / 对象）。
 * 之所以不引依赖：`:core:engine` 要在纯 JVM 与 Android 上都能跑，且 Android 自带 org.json
 * 与 JVM 侧并不通用。
 */
internal object MiniJson {

    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Int, is Long -> value.toString()
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        is Float -> stringify(value.toDouble())
        is String -> quote(value)
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) -> quote(k.toString()) + ":" + stringify(v) }
        is Iterable<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        is Array<*> -> value.joinToString(",", "[", "]") { stringify(it) }
        else -> quote(value.toString())
    }

    private fun quote(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (ch in text) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        return out.append('"').toString()
    }

    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val text: String) {
        private var index = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) return null
            return when (val ch = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consume("true").let { true }
                'f' -> consume("false").let { false }
                'n' -> consume("null").let { null }
                else -> if (ch == '-' || ch.isDigit()) parseNumber() else skipToken()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            index++ // {
            while (true) {
                skipWhitespace()
                if (index >= text.length) return map
                if (text[index] == '}') { index++; return map }
                val key = parseString()
                skipWhitespace()
                if (index < text.length && text[index] == ':') index++
                map[key] = parseValue()
                skipWhitespace()
                if (index < text.length && text[index] == ',') index++
            }
        }

        private fun parseArray(): List<Any?> {
            val list = ArrayList<Any?>()
            index++ // [
            while (true) {
                skipWhitespace()
                if (index >= text.length) return list
                if (text[index] == ']') { index++; return list }
                list += parseValue()
                skipWhitespace()
                if (index < text.length && text[index] == ',') index++
            }
        }

        private fun parseString(): String {
            if (index >= text.length || text[index] != '"') return ""
            index++
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when {
                    ch == '"' -> return out.toString()
                    ch == '\\' && index < text.length -> {
                        when (val esc = text[index++]) {
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'u' -> {
                                val hex = text.substring(index, minOf(index + 4, text.length))
                                index += hex.length
                                out.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                            }
                            else -> out.append(esc)
                        }
                    }
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }

        private fun parseNumber(): Any {
            val start = index
            if (index < text.length && (text[index] == '-' || text[index] == '+')) index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            val raw = text.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: 0
        }

        private fun consume(token: String): Unit {
            if (text.startsWith(token, index)) index += token.length
        }

        private fun skipToken(): Any? {
            while (index < text.length && text[index] !in ",}]") index++
            return null
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
