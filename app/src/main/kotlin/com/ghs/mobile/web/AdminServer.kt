package com.ghs.mobile.web

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 内置 Web 管理服务（F23）。
 *
 * 只做三件事：
 * 1. `/console` 提供控制台静态资源（SPA，未知路径回落 index.html）
 * 2. `/api` 提供配置 CRUD 与校验（必须带令牌）
 * 3. `/healthz` 存活探测（无敏感信息）
 *
 * 安全边界：端口、绑定地址、令牌**不在本 API 内可改**——它们只在应用设置里配
 * （见 web-admin-api.md §1）。
 */
class AdminServer(
    private val context: Context,
    private val store: ConfigStore,
    private val port: Int,
    private val bindHost: String,
    private val token: String,
    private val appVersion: String,
) : NanoHTTPD(bindHost, port) {

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            path == "/healthz" -> text("ok")
            path.startsWith("/console") -> serveConsole(session)
            path.startsWith("/api/") -> serveApi(session)
            else -> text("not found", Response.Status.NOT_FOUND)
        }
    }

    // ── 静态资源 ────────────────────────────────────────────────────────────

    private fun serveConsole(session: IHTTPSession): Response {
        // /console/?t=xxx 换取 Cookie，之后页面内的 fetch 复用 Cookie
        val incomingToken = session.parameters["t"]?.firstOrNull()
        if (incomingToken != null && incomingToken == token) {
            return staticFile("index.html")?.apply {
                addHeader("Set-Cookie", "ghs_token=$token; Path=/; SameSite=Lax; HttpOnly")
            } ?: text("console 未打包", Response.Status.NOT_FOUND)
        }

        val relative = session.uri.removePrefix("/console").trimStart('/').ifEmpty { "index.html" }
        val file = relative.takeIf { it.isNotBlank() && !it.contains("..") }
        if (file != null) staticFile(file)?.let { return it }
        // SPA 回落：未命中的路径交给前端路由
        return staticFile("index.html") ?: text("console 未打包", Response.Status.NOT_FOUND)
    }

    private fun staticFile(relative: String): Response? {
        val assetPath = "webconsole/${relative.removePrefix("/")}"
        val bytes = runCatching {
            context.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull() ?: return null

        val mime = when (relative.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ttf" -> "font/ttf"
            "woff2" -> "font/woff2"
            "map" -> "application/json"
            else -> "application/octet-stream"
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "$mime; charset=utf-8",
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        )
    }

    // ── API ────────────────────────────────────────────────────────────────

    private fun serveApi(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return json(Response.Status.UNAUTHORIZED, error("E-API-002", "未鉴权或令牌失效"))
        }

        val path = session.uri.removePrefix("/api").trimEnd('/').ifEmpty { "/" }
        val method = session.method

        return runCatching {
            when {
                path == "/meta" && method == Method.GET -> jsonOk(
                    JSONObject()
                        .put("appVersion", appVersion)
                        .put("port", port)
                        .put("bind", bindHost)
                        .put("scriptApiVersion", "1")
                        .put("engine", "quickjs+webview"),
                )

                path == "/configs" && method == Method.GET -> jsonOk(configList())
                path == "/configs" && method == Method.POST -> createConfig(session)
                path == "/configs/order" && method == Method.POST -> reorder(session)
                path == "/validate" && method == Method.POST -> validate(session)

                path.startsWith("/configs/") -> {
                    val rest = path.removePrefix("/configs/").split('/')
                    val key = rest.firstOrNull().orEmpty()
                    when {
                        rest.size == 1 && method == Method.GET -> readConfig(key)
                        rest.size == 1 && method == Method.PUT -> updateConfig(key, session)
                        rest.size == 1 && method == Method.PATCH -> patchConfig(key, session)
                        rest.size == 1 && method == Method.DELETE -> deleteConfig(key)
                        rest.size == 2 && rest[1] == "script" && method == Method.GET -> script(key)
                        else -> json(Response.Status.NOT_FOUND, error("E-API-001", "未知路径：$path"))
                    }
                }

                else -> json(Response.Status.NOT_FOUND, error("E-API-001", "未知路径：$path"))
            }
        }.getOrElse { failure ->
            json(
                Response.Status.INTERNAL_ERROR,
                error("E-API-500", failure.message ?: failure.toString()),
            )
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val auth = session.headers["authorization"].orEmpty()
        if (auth.startsWith("Bearer ") && auth.removePrefix("Bearer ").trim() == token) return true
        val cookie = session.headers["cookie"].orEmpty()
        return cookie.split(';').any { it.trim() == "ghs_token=$token" }
    }

    private fun configList(): JSONObject {
        val array = JSONArray()
        store.list().forEach { config ->
            array.put(
                JSONObject()
                    .put("key", config.key)
                    .put("name", pickName(config.source))
                    .put("enabled", config.enabled)
                    .put("format", if (config.source.contains("/* break */")) "inline" else "manifest")
                    .put("sort", config.sort)
                    .put("updatedAt", config.updatedAt),
            )
        }
        return JSONObject().put("configs", array)
    }

    private fun readConfig(key: String): Response {
        val config = store.get(key) ?: return json(
            Response.Status.NOT_FOUND,
            error("E-CFG-404", "配置不存在：$key"),
        )
        return jsonOk(
            JSONObject()
                .put("key", config.key)
                .put("name", pickName(config.source))
                .put("enabled", config.enabled)
                .put("format", if (config.source.contains("/* break */")) "inline" else "manifest")
                .put("sort", config.sort)
                .put("updatedAt", config.updatedAt)
                .put("config", config.source),
        )
    }

    private fun script(key: String): Response {
        val config = store.get(key) ?: return json(
            Response.Status.NOT_FOUND,
            error("E-CFG-404", "配置不存在：$key"),
        )
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/plain; charset=utf-8",
            config.source,
        )
    }

    private fun createConfig(session: IHTTPSession): Response {
        val body = readBody(session)
        val source = body.optString("config")
        if (source.isBlank()) {
            return json(Response.Status.BAD_REQUEST, error("E-CFG-002", "缺少 config 内容"))
        }
        val key = body.optString("key").ifBlank { pickKey(source) }
        if (!ConfigStore.isValidKey(key)) {
            return json(Response.Status.BAD_REQUEST, error("E-CFG-003", "key 不合法：$key"))
        }
        if (store.get(key) != null) {
            return json(Response.Status.CONFLICT, error("E-CFG-009", "配置已存在：$key"))
        }
        store.save(key, source, enabled = body.optBoolean("enabled", true), sort = body.optInt("sort", 0))
        return jsonOk(JSONObject().put("key", key))
    }

    private fun updateConfig(key: String, session: IHTTPSession): Response {
        if (store.get(key) == null) {
            return json(Response.Status.NOT_FOUND, error("E-CFG-404", "配置不存在：$key"))
        }
        val body = readBody(session)
        val source = body.optString("config")
        if (source.isBlank()) {
            return json(Response.Status.BAD_REQUEST, error("E-CFG-002", "缺少 config 内容"))
        }
        val keyInSource = pickKey(source)
        if (keyInSource.isNotEmpty() && keyInSource != key) {
            return json(
                Response.Status.BAD_REQUEST,
                error("E-CFG-004", "配置内 key（$keyInSource）与路径 key（$key）不一致"),
            )
        }
        val existing = store.get(key)!!
        store.save(key, source, enabled = existing.enabled, sort = existing.sort)
        return jsonOk(JSONObject().put("key", key).put("updatedAt", System.currentTimeMillis()))
    }

    private fun patchConfig(key: String, session: IHTTPSession): Response {
        if (store.get(key) == null) {
            return json(Response.Status.NOT_FOUND, error("E-CFG-404", "配置不存在：$key"))
        }
        val body = readBody(session)
        if (body.has("enabled")) store.setEnabled(key, body.getBoolean("enabled"))
        if (body.has("sort")) store.reorder(store.list().map { it.key }.let { keys ->
            keys.toMutableList().apply { remove(key); add(body.getInt("sort").coerceIn(0, keys.size), key) }
        })
        return jsonOk(JSONObject().put("key", key))
    }

    private fun deleteConfig(key: String): Response {
        val removed = store.delete(key)
        return if (removed) jsonOk(JSONObject().put("key", key))
        else json(Response.Status.NOT_FOUND, error("E-CFG-404", "配置不存在：$key"))
    }

    private fun reorder(session: IHTTPSession): Response {
        val body = readBody(session)
        val keys = body.optJSONArray("keys") ?: return json(
            Response.Status.BAD_REQUEST,
            error("E-API-003", "缺少 keys"),
        )
        val list = (0 until keys.length()).map { keys.getString(it) }
        store.reorder(list)
        return jsonOk(JSONObject().put("count", list.size))
    }

    /** 静态校验：与浏览器端的快速检查同源，避免"网页说没问题、App 里加载失败" */
    private fun validate(session: IHTTPSession): Response {
        val source = readBody(session).optString("config")
        val errors = JSONArray()
        val warnings = JSONArray()

        val declared = Regex("""^[ \t]+(?:async\s+)?([A-Za-z_$][\w$]*)\s*\([^)]*\)\s*\{""", RegexOption.MULTILINE)
            .findAll(source)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filterNot { it in NOT_METHODS }
            .toSet()

        REQUIRED_METHODS.filterNot { it in declared }.forEach { method ->
            errors.put(issue("E-CFG-005", "缺少必需方法 $method", "script.$method"))
        }
        val key = pickKey(source)
        if (key.isEmpty()) errors.put(issue("E-CFG-002", "缺少 key", "meta.key"))
        else if (!ConfigStore.isValidKey(key)) errors.put(issue("E-CFG-003", "key 不合法：$key", "meta.key"))

        val bytes = source.toByteArray(Charsets.UTF_8).size
        if (bytes > 1_000_000) errors.put(issue("E-CFG-010", "配置超过 1MB（$bytes 字节）", "script"))
        if (!source.contains("/* break */")) {
            warnings.put(issue("E-CFG-001", "未找到 /* break */ 分隔符，可能不是内联形态", "script"))
        }

        return jsonOk(
            JSONObject()
                .put("ok", errors.length() == 0)
                .put("errors", errors)
                .put("warnings", warnings),
        )
    }

    /**
     * 读原始 body。
     *
     * 不用 `parseBody`：NanoHTTPD 对 `application/json` 不会把内容放进 `postData`，
     * 直接按 `Content-Length` 从流里读才稳定。
     */
    private fun readBody(session: IHTTPSession): JSONObject {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        val raw = if (length > 0) {
            val buffer = ByteArray(length)
            var read = 0
            val input = session.inputStream
            while (read < length) {
                val n = input.read(buffer, read, length - read)
                if (n <= 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
        return runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
    }

    private fun pickKey(source: String): String =
        Regex("""\bkey\s*:\s*['"]([^'"]+)['"]""").find(source)?.groupValues?.get(1).orEmpty()

    private fun pickName(source: String): String =
        Regex("""\bname\s*:\s*['"]([^'"]+)['"]""").find(source)?.groupValues?.get(1) ?: "未命名配置"

    private fun issue(code: String, message: String, path: String): JSONObject =
        JSONObject().put("code", code).put("message", message).put("path", path)

    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message))

    private fun jsonOk(payload: JSONObject): Response = json(Response.Status.OK, payload)

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())

    private fun text(body: String, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", body)

    companion object {
        val REQUIRED_METHODS = listOf(
            "getItems",
            "getPagination",
            "getTags",
            "getUrlReplace",
            "getDetailInfo",
            "adapterLoadUrl",
            "adapterSearchUrl",
        )

        private val NOT_METHODS = setOf(
            "if", "for", "while", "switch", "catch", "function", "return", "else", "do", "try",
        )
    }
}
