package com.ghs.mobile.core.engine.script

import com.ghs.mobile.core.engine.select.GhsSelect
import com.ghs.mobile.core.engine.select.Selection
import java.security.MessageDigest

/**
 * GHS 脚本桥：把 [GhsSelect] 与运行时工具注入 JS，并管理选择器句柄。
 *
 * 设计要点（与 GraalJS spike 一致，真机 QuickJS 复用同一套协议）：
 * - **句柄 + 派发**：JS 侧拿到的只是一个整数句柄，所有方法都是 `__xxx(handle, ...)` 的薄包装
 * - 只传基本类型（String / Int / Boolean）跨越 JS↔Kotlin 边界
 * - 取值一律返回 `''` 而不是 null（配置里的 `a || b` 兜底才成立）
 *
 * 本类不含任何 JS 引擎依赖，可在 JVM 上单测（见 `GhsScriptBridgeTest`）。
 */
class GhsScriptBridge(private val engine: ScriptEngine) {

    private val handles = HashMap<Int, Selection>()
    private var sequence = 0
    private var document: GhsSelect = GhsSelect.empty()

    /** 供测试断言用的日志收集 */
    val logs = mutableListOf<String>()

    /** 切换当前文档（配置里的 `$` 作用在它上面） */
    fun loadDocument(html: String) {
        document = if (html.isEmpty()) GhsSelect.empty() else GhsSelect.parse(html)
    }

    /** 注入宿主函数与 JS 侧 prelude；返回自身便于链式调用 */
    fun install(transport: ScriptHostTransport = ScriptHostTransport.None): GhsScriptBridge {
        // 先等引擎就绪（WebView 需要页面加载完成，否则注入会被上下文重置丢弃）
        engine.eval("1", "ghs-ready")
        bindHostFunctions(transport)
        // prelude 是**语句块**，而各引擎的 eval 会把脚本包进 `return (...)`；
        // 套一层 IIFE 让它成为单个表达式（否则语法错误且会被静默吞掉）。
        engine.eval("(function(){ $PRELUDE })()", "ghs-prelude")
        engine.eval("(function(){ $GLOBALS })()", "ghs-globals")
        return this
    }

    /**
     * 求值一份内联配置，**配置对象留在 JS 侧**（绑定为全局 `config`）。
     *
     * 刻意不把配置对象回传给 Kotlin：它的成员是函数，跨语言序列化会丢掉方法，
     * 只保留能被 JSON 表达的数据字段。Kotlin 侧需要的是"能调用它"，而不是"持有它"。
     *
     * @return 求值后的脚本正文（便于日志与断言），失败时抛异常
     */
    fun loadConfig(source: String): String {
        val body = ConfigSource.bodyOf(source)
        // 必须是**单个表达式**：各引擎的 eval 都会把它包进 `return (...)`。
        // 配置正文通常是 `({ ... });`，去掉结尾分号才是表达式。
        val expression = body.trimEnd().removeSuffix(";").trimEnd()
        engine.eval("globalThis.config = $expression", "config")
        return body
    }

    /** 写入一个 JS 全局字符串（图片字节等大文本走这里，避免拼进表达式） */
    fun setGlobalValue(name: String, value: String) {
        engine.setGlobal(name, value)
    }

    fun hasMethod(method: String): Boolean =
        engine.eval("typeof config.$method === 'function'", "probe") == true

    /** 调用配置方法（`this` 绑定到配置对象） */
    fun invoke(method: String, argumentExpression: String = "$"): Any? =
        engine.eval("config.$method($argumentExpression)", "invoke:$method")

    /**
     * 调用**异步**配置方法并等待它 resolve。
     *
     * 只有带事件循环的引擎（WebView）能真正等到；QuickJS 封装没有
     * `JS_ExecutePendingJob`，微任务永不前进，此处会退化为 [invoke]。
     */
    /** 直接执行一段表达式并等待 Promise（用于非常规签名的方法，如 adapterImageCode） */
    fun invokeAwaitRaw(expression: String): Any? = engine.evalAwait(expression, "invokeAwaitRaw")

    fun invokeAwait(method: String, argumentExpression: String = "$"): Any? =
        engine.evalAwait("config.$method($argumentExpression)", "invokeAwait:$method")

    private fun register(selection: Selection): Int {
        handles[++sequence] = selection
        return sequence
    }

    private fun selection(handle: Any?): Selection =
        handles[(handle as? Number)?.toInt() ?: -1]
            ?: throw IllegalArgumentException(
                "未知选择器句柄：$handle（已注册 ${handles.keys.sorted()}，共 ${handles.size} 个）",
            )

    private fun intOf(value: Any?): Int = (value as? Number)?.toInt() ?: 0

    private fun booleanOf(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value?.toString()?.equals("true", ignoreCase = true) == true
    }

    private fun textOf(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }

    private fun bindHostFunctions(transport: ScriptHostTransport) {
        engine.setFunction("__select") { args -> register(select(textOf(args.getOrNull(0)))) }
        engine.setFunction("__len") { args -> selection(args.getOrNull(0)).length }
        engine.setFunction("__md5") { args ->
            val digest = java.security.MessageDigest.getInstance("MD5")
            digest.digest(textOf(args.getOrNull(0)).toByteArray()).joinToString("") { "%02x".format(it) }
        }
        engine.setFunction("__at") { args -> register(selection(args.getOrNull(0)).eq(intOf(args.getOrNull(1)))) }
        engine.setFunction("__eq") { args -> register(selection(args.getOrNull(0)).eq(intOf(args.getOrNull(1)))) }
        engine.setFunction("__find") { args ->
            register(selection(args.getOrNull(0)).find(textOf(args.getOrNull(1))))
        }
        engine.setFunction("__attr") { args ->
            selection(args.getOrNull(0)).attr(textOf(args.getOrNull(1)))
        }
        engine.setFunction("__text") { args -> selection(args.getOrNull(0)).text() }
        engine.setFunction("__html") { args -> selection(args.getOrNull(0)).html() }
        engine.setFunction("__outer") { args -> selection(args.getOrNull(0)).toString() }
        engine.setFunction("__hasClass") { args ->
            selection(args.getOrNull(0)).hasClass(textOf(args.getOrNull(1)))
        }
        engine.setFunction("__data") { args ->
            selection(args.getOrNull(0)).data(textOf(args.getOrNull(1)))
        }
        engine.setFunction("__parent") { args -> register(selection(args.getOrNull(0)).parent()) }
        engine.setFunction("__children") { args -> register(selection(args.getOrNull(0)).children()) }
        engine.setFunction("__next") { args -> register(selection(args.getOrNull(0)).next()) }
        engine.setFunction("__prev") { args -> register(selection(args.getOrNull(0)).prev()) }
        engine.setFunction("__closest") { args ->
            register(selection(args.getOrNull(0)).closest(textOf(args.getOrNull(1))))
        }
        engine.setFunction("__filter") { args ->
            register(selection(args.getOrNull(0)).filter(textOf(args.getOrNull(1))))
        }
        engine.setFunction("__hash") { args -> sha1(textOf(args.getOrNull(0))) }
        engine.setFunction("__log") { args ->
            val line = args.joinToString(" ") { textOf(it) }
            logs += line
            // 同时推给界面：配置里 LogMsgUtil 的输出会显示在应用内「日志云」里
            LogHub.push(line)
            null
        }

        // ── 宿主能力（同步返回；脚本里的 await 会立即拿到值） ──
        engine.setFunction("__fetchHtml") { args ->
            transport.fetchHtml(textOf(args.getOrNull(0)))
        }
        // 需要 JS 渲染的取页：交给 WebView 打开并取渲染后的 HTML
        engine.setFunction("__fetchHtmlProcessed") { args ->
            transport.fetchHtmlProcessed(textOf(args.getOrNull(0)))
        }
        engine.setFunction("__httpRequest") { args ->
            val url = textOf(args.getOrNull(0))
            val method = textOf(args.getOrNull(1)).ifEmpty { "GET" }
            val headerNames = textOf(args.getOrNull(2)).split(SEP).filter { it.isNotEmpty() }
            val headerValues = textOf(args.getOrNull(3)).split(SEP)
            val body = textOf(args.getOrNull(4)).ifEmpty { null }
            val headers = headerNames.mapIndexedNotNull { index, name ->
                headerValues.getOrNull(index)?.let { name to it }
            }.toMap()

            val response = transport.request(url, method, headers, body)
            // 用 \u0001 分隔，避免依赖 JSON 库
            buildString {
                append(response.status).append(SEP)
                append(response.headers.entries.joinToString(";") { "${it.key}:${it.value}" }).append(SEP)
                append(response.body)
            }
        }
        engine.setFunction("__executePageEx") { args ->
            // show=true：把取页用的 WebView 显示出来（配置里让用户完成验证/登录）
            transport.executePage(
                textOf(args.getOrNull(0)),
                textOf(args.getOrNull(1)),
                booleanOf(args.getOrNull(2)),
            )
        }
        engine.setFunction("__executePage") { args ->
            transport.executePage(textOf(args.getOrNull(0)), textOf(args.getOrNull(1)))
        }
        // cheerio.load(html)：把当前文档换成这段 HTML，并返回 $
        engine.setFunction("__loadDocument") { args ->
            loadDocument(textOf(args.getOrNull(0)))
            0
        }
    }

    private fun select(selector: String): Selection =
        if (selector.isEmpty()) Selection(emptyList()) else document(selector)

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {

        /** JS 侧最小 `$`：包一层句柄，暴露配置脚本用到的全部方法 */
        val PRELUDE = """
            globalThis.__wrap = function (h) {
              return {
                __h: h,
                get length() { return __len(h); },
                each: function (fn) { for (var i = 0; i < __len(h); i++) fn(i, __wrap(__at(h, i))); },
                find: function (s) { return __wrap(__find(h, s)); },
                attr: function (n) { return __attr(h, n); },
                text: function () { return __text(h); },
                html: function () { return __html(h); },
                data: function (n) { return __data(h, n); },
                hasClass: function (n) { return __hasClass(h, n); },
                toString: function () { return __outer(h); },
                eq: function (i) { return __wrap(__eq(h, i)); },
                first: function () { return __wrap(__eq(h, 0)); },
                last: function () { return __wrap(__eq(h, -1)); },
                parent: function () { return __wrap(__parent(h)); },
                children: function () { return __wrap(__children(h)); },
                next: function () { return __wrap(__next(h)); },
                prev: function () { return __wrap(__prev(h)); },
                closest: function (s) { return __wrap(__closest(h, s)); },
                filter: function (s) { return __wrap(__filter(h, s)); }
              };
            };

            globalThis.${'$'} = function (arg) {
              if (typeof arg === 'string') return __wrap(__select(arg));
              if (arg && typeof arg.__h === 'number') return __wrap(arg.__h);
              return __wrap(__select(String(arg == null ? '' : arg)));
            };

            globalThis.cheerio = {
              load: function (html) {
                __loadDocument(String(html == null ? '' : html));
                return globalThis.${'$'};
              }
            };
        """.trimIndent()

        /** 配置脚本可用的全局符号（值语义与 config-spec §8 一致） */
        val GLOBALS = """
            globalThis.ElementAttr = {
              src: 'src', href: 'href', title: 'title', alt: 'alt', class: 'class', poster: 'poster',
              dataSrc: 'data-src', dataOriginal: 'data-original', dataWebp: 'data-webp',
              dataType: 'data-type', dataEcho: 'data-echo', dataSource: 'data-source', dataError: 'data-error'
            };
            globalThis.ElementTypes = {
              a: 'a', img: 'img', h1: 'h1', h4: 'h4', p: 'p', span: 'span', div: 'div', video: 'video', source: 'source'
            };
            globalThis.helpElAttr = function (el, name) { return el && el.__h ? __attr(el.__h, name) : ''; };
            globalThis.helpElText = function (el) { return el && el.__h ? __text(el.__h) : ''; };
            globalThis.hashString = function (s) { return __hash(String(s)); };
            globalThis.Md5 = function (s) { return __md5(String(s)); };
            // 解图函数约定的 Md5 是对象形态：Md5.hashStr(text)
            globalThis.__ghsMd5HashStr = function (s) { return __md5(String(s)); };
            globalThis.LogMsgUtil = { sendLogMsg: function () { __log.apply(null, arguments); } };
            globalThis.ConsoleLogUtil = { log: function () { __log.apply(null, arguments); } };
            globalThis.MessageUtil = {
              info: function (m) { __log(String(m)); }, error: function (m) { __log(String(m)); }, success: function (m) { __log(String(m)); }, warning: function (m) { __log(String(m)); }
            };
            globalThis.NotifyMsgUtil = { sendNotifyMsg: function () {}, close: function () {} };
            // 进度消息要进调试日志/顶部灵动岛，不能是空实现
            // sendProgress(已处理, 总数, 标题)：第三个参数才是要展示的文案
            // 事件总线：配置里常用 eventEmitter.emit/on 做进度通知，缺失会直接 ReferenceError
            globalThis.eventEmitter = {
              on: function () { return function () { }; },
              addListener: function () { },
              once: function () { },
              off: function () { },
              removeListener: function () { },
              removeAllListeners: function () { },
              emit: function () { },
              listenerCount: function () { return 0; }
            };
            globalThis.ProgressMsgUtil = {
              sendProgress: function (done, total, title) {
                var text = (typeof title === 'string' && title)
                  ? title
                  : (typeof total === 'string' && total ? total : String(done == null ? '' : done));
                __log(String(text));
              },
              sendProgressMsg: function (done, total, title) {
                var text = (typeof title === 'string' && title)
                  ? title
                  : (typeof total === 'string' && total ? total : String(done == null ? '' : done));
                __log(String(text));
              },
              info: function (m) { __log(String(m)); },
              update: function (m) { __log(String(m)); },
              finish: function (m) { __log(String(m)); },
              close: function () { }
            };
            globalThis.getCurrentItems = function () { return []; };

            /**
             * 宿主能力：同步返回。
             * `await getHtml(...)` 依然成立——await 一个非 Promise 值会立即得到它。
             */
            globalThis.getHtml = function (url, forceWebView) {
              return __fetchHtml(String(url));
            };
            /**
             * 同步 GET 取文本（配置里常用于拉 m3u8 / JSON 等小文件）。
             * 返回字符串而不是 Promise：`await request_string_get(...)` 依然成立，
             * 同步取页也能直接 `.split()` 处理。
             */
            globalThis.request_string_get = function (url) {
              return __fetchHtml(String(url == null ? "" : url));
            };
            /**
             * 需要「跑过页面脚本」的取页：用无头 WebView 打开页面后取渲染结果。
             * 配置里靠它拿 JS 渲染/登录后的内容。
             */
            globalThis.getHtmlWithProcess = function (url) {
              return __fetchHtmlProcessed(String(url));
            };
            globalThis.executeJs = function (url, code, show) {
              // show=true：把取页窗口显示出来，让用户完成验证/登录
              return __executePageEx(String(url), String(code), !!show);
            };
            /**
             * 配置里的日志入口：全部汇到调试日志（应用内「日志云」+ logcat GHS-JS）。
             * 站点脚本常用 sendMessage / MessageUtil / LogMsgUtil 打进度，
             * 不接的话排查时什么也看不到。
             */
            globalThis.sendMessage = function (msg) { __log(String(msg)); };
            globalThis.MessageUtil = {
              info: function (msg) { __log(String(msg)); },
              warning: function (msg) { __log(String(msg)); },
              warn: function (msg) { __log(String(msg)); },
              error: function (msg) { __log('ERROR: ' + String(msg)); },
              success: function (msg) { __log(String(msg)); },
              sendMessage: function (msg) { __log(String(msg)); }
            };
            globalThis.LogMsgUtil = {
              sendLogMsg: function (msg) { __log(String(msg)); },
              info: function (msg) { __log(String(msg)); },
              error: function (msg) { __log('ERROR: ' + String(msg)); }
            };
            globalThis.NotifyMsgUtil = {
              sendNotifyMsg: function () { },
              close: function () { }
            };
            /** 进度类工具的调用也进调试日志 */
            globalThis.ProgressUtil = {
              start: function (msg) { __log(String(msg || 'progress start')); },
              update: function (msg) { __log(String(msg)); },
              finish: function (msg) { __log(String(msg || 'progress finish')); },
              error: function (msg) { __log('ERROR: ' + String(msg)); },
              sendProgress: function (msg) { __log(String(msg)); }
            };
            globalThis.LogUtil = {
              log: function (msg) { __log(String(msg)); },
              info: function (msg) { __log(String(msg)); },
              warn: function (msg) { __log(String(msg)); },
              error: function (msg) { __log('ERROR: ' + String(msg)); }
            };
            globalThis.console = {
              log: function () { __log(Array.prototype.join.call(arguments, ' ')); },
              info: function () { __log(Array.prototype.join.call(arguments, ' ')); },
              warn: function () { __log(Array.prototype.join.call(arguments, ' ')); },
              error: function () { __log(Array.prototype.join.call(arguments, ' ')); }
            };
            globalThis.net = {
              request: function (options) {
                var opts = options || {};
                var names = [];
                var values = [];
                var headers = opts.headers || {};
                for (var key in headers) {
                  if (Object.prototype.hasOwnProperty.call(headers, key)) {
                    names.push(key);
                    values.push(String(headers[key]));
                  }
                }
                var sep = '\u0001';
                var raw = __httpRequest(
                  String(opts.url || ''),
                  String(opts.method || 'GET'),
                  names.join(sep),
                  values.join(sep),
                  opts.body == null ? '' : String(opts.body)
                );
                var parts = String(raw).split(sep);
                var headerMap = {};
                String(parts[1] || '').split(';').forEach(function (pair) {
                  if (!pair) return;
                  var index = pair.indexOf(':');
                  if (index > 0) headerMap[pair.slice(0, index)] = pair.slice(index + 1);
                });
                return { status: Number(parts[0]), headers: headerMap, body: parts[2] == null ? '' : parts[2] };
              }
            };
        """.trimIndent()

        /** 宿主函数参数里的字段分隔符（避免依赖 JSON 库） */
        const val SEP = "\u0001"
    }
}
