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
 * 安全边界：端口、绑定地址、令牌不在本 API 内可改——它们只在应用设置里配
 * （见 web-admin-api.md §1）。
 */
class AdminServer(
    private val context: Context,
    private val store: ConfigStore,
    private val port: Int,
    private val bindHost: String,
    private val appVersion: String,
    /** 配置被改动后的通知：让 App 立刻重载，不必手动重启 */
    private val onConfigsChanged: () -> Unit = {},
) : NanoHTTPD(bindHost, port) {

    override fun serve(session: IHTTPSession): Response {
        // 必须先把 body 读完：keep-alive 下未消费的字节会被当成下一个请求行，
        // NanoHTTPD 会报 "BAD REQUEST: Syntax error. HTTP verb ..."（曾导致保存/新建无效）
        val rawBody = readRawBody(session)
        val path = session.uri
        return when {
            path == "/healthz" -> text("ok")
            path.startsWith("/console") -> serveConsole(session)
            path.startsWith("/api/") -> serveApi(session, rawBody)
            else -> text("not found", Response.Status.NOT_FOUND)
        }
    }

    // ── 静态资源 ────────────────────────────────────────────────────────────

    private fun serveConsole(session: IHTTPSession): Response {
        // 令牌已取消：局域网内直接访问即可
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
            "json", "map" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ttf" -> "font/ttf"
            "woff2" -> "font/woff2"
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

    private fun serveApi(session: IHTTPSession, rawBody: String): Response {
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
                path == "/configs" && method == Method.POST -> createConfig(rawBody)
                path == "/configs/order" && method == Method.POST -> reorder(rawBody)
                path == "/configs/export" && method == Method.GET -> exportConfigs()
                path == "/configs/import" && method == Method.POST -> importConfigs(rawBody)
                path == "/validate" && method == Method.POST -> validate(rawBody)

                path.startsWith("/configs/") -> {
                    val rest = path.removePrefix("/configs/").split('/')
                    val key = rest.firstOrNull().orEmpty()
                    when {
                        rest.size == 1 && method == Method.GET -> readConfig(key)
                        rest.size == 1 && method == Method.PUT -> updateConfig(key, rawBody)
                        rest.size == 1 && method == Method.PATCH -> patchConfig(key, rawBody)
                        rest.size == 1 && method == Method.DELETE -> deleteConfig(key)
                        rest.size == 2 && rest[1] == "script" && method == Method.GET -> script(key)
                        rest.size == 2 && rest[1] == "raw" && method == Method.GET -> rawConfig(key)
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

    /** 令牌已取消：Web 管理仅在本机/局域网内网使用，不再校验凭证 */
    private fun isAuthorized(session: IHTTPSession): Boolean = true

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

    /**
     * 导出全部配置：`{ configs: [{key,name,enabled,sort,source}] }`。
     *
     * 这个结构可直接被 [importConfigs] 读回，用于整机备份/迁移。
     */
    private fun exportConfigs(): Response {
        val array = JSONArray()
        store.list().forEach { config ->
            array.put(
                JSONObject()
                    .put("key", config.key)
                    .put("name", pickName(config.source))
                    .put("enabled", config.enabled)
                    .put("sort", config.sort)
                    .put("source", config.source),
            )
        }
        return jsonOk(
            JSONObject()
                .put("version", 1)
                .put("exportedAt", System.currentTimeMillis())
                .put("configs", array),
        )
    }

    /** 单份配置原文：给浏览器下载用（不带 JSON 包装） */
    private fun rawConfig(key: String): Response {
        val config = store.get(key)
            ?: return json(Response.Status.NOT_FOUND, error("E-CFG-404", "配置不存在：$key"))
        val response = text(config.source)
        response.addHeader("Content-Disposition", "attachment; filename=\"$key.js\"")
        return response
    }

    /**
     * 导入配置。
     *
     * 两种输入：
     * - `{ config: "<脚本原文>" }`：单份导入，key 取自脚本内 `key:`，已存在则覆盖
     * - `{ configs: [ ... ] }`：整包导入（[exportConfigs] 的产物）
     *
     * 返回 `{ imported: n, keys: [...] }`，前端据此提示。
     */
    private fun importConfigs(rawBody: String): Response {
        val body = parseJson(rawBody)
        val keys = mutableListOf<String>()

        val single = body.optString("config")
        if (single.isNotBlank()) {
            val key = body.optString("key").ifBlank { pickKey(single) }
            if (!ConfigStore.isValidKey(key)) {
                return json(Response.Status.BAD_REQUEST, error("E-CFG-003", "key 不合法：$key"))
            }
            val existing = store.get(key)
            store.save(
                key = key,
                source = single,
                enabled = body.optBoolean("enabled", existing?.enabled ?: true),
                sort = existing?.sort ?: body.optInt("sort", 0),
            )
            keys += key
        }

        val many = body.optJSONArray("configs")
        if (many != null) {
            for (i in 0 until many.length()) {
                val item = many.optJSONObject(i) ?: continue
                val source = item.optString("source")
                if (source.isBlank()) continue
                val key = item.optString("key").ifBlank { pickKey(source) }
                if (!ConfigStore.isValidKey(key)) continue
                val existing = store.get(key)
                store.save(
                    key = key,
                    source = source,
                    enabled = item.optBoolean("enabled", existing?.enabled ?: true),
                    sort = item.optInt("sort", existing?.sort ?: 0),
                )
                keys += key
            }
        }

        if (keys.isEmpty()) {
            return json(
                Response.Status.BAD_REQUEST,
                error("E-CFG-002", "没有可导入的内容（缺少 config 或 configs）"),
            )
        }

        notifyChanged()
        return jsonOk(JSONObject().put("imported", keys.size).put("keys", JSONArray(keys)))
    }

    private fun createConfig(rawBody: String): Response {
        val body = parseJson(rawBody)
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
        notifyChanged()
        return jsonOk(JSONObject().put("key", key))
    }

    private fun updateConfig(key: String, rawBody: String): Response {
        if (store.get(key) == null) {
            return json(Response.Status.NOT_FOUND, error("E-CFG-404", "配置不存在：$key"))
        }
        val body = parseJson(rawBody)
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
        notifyChanged()
        return jsonOk(JSONObject().put("key", key).put("updatedAt", System.currentTimeMillis()))
    }

    private fun patchConfig(key: String, rawBody: String): Response {
        if (store.get(key) == null) {
            return json(Response.Status.NOT_FOUND, error("E-CFG-404", "配置不存在：$key"))
        }
        val body = parseJson(rawBody)
        if (body.has("enabled")) store.setEnabled(key, body.getBoolean("enabled"))
        notifyChanged()
        return jsonOk(JSONObject().put("key", key))
    }

    private fun deleteConfig(key: String): Response {
        val removed = store.delete(key)
        if (removed) notifyChanged()
        return if (removed) jsonOk(JSONObject().put("key", key))
        else json(Response.Status.NOT_FOUND, error("E-CFG-404", "配置不存在：$key"))
    }

    private fun reorder(rawBody: String): Response {
        val body = parseJson(rawBody)
        val keys = body.optJSONArray("keys") ?: return json(
            Response.Status.BAD_REQUEST,
            error("E-API-003", "缺少 keys"),
        )
        val list = (0 until keys.length()).map { keys.getString(it) }
        store.reorder(list)
        notifyChanged()
        return jsonOk(JSONObject().put("count", list.size))
    }

    /** 静态校验：与浏览器端的快速检查同源，避免"网页说没问题、App 里加载失败" */
    private fun validate(rawBody: String): Response {
        val source = parseJson(rawBody).optString("config")
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

        return jsonOk(
            JSONObject()
                .put("ok", errors.length() == 0)
                .put("errors", errors)
                .put("warnings", warnings),
        )
    }

    /**
     * 按 `Content-Length` 读完请求体。
     *
     * 两个理由：NanoHTTPD 对 JSON 不填 `postData`；更重要的是必须消费掉，
     * 否则 keep-alive 连接上残留的字节会让下一次请求解析失败。
     */
    private fun readRawBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        val input = session.inputStream
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    /** 变更通知：失败不影响本次写入的结果 */    private fun notifyChanged() {
        runCatching { onConfigsChanged() }
    }

    private fun parseJson(raw: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

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
