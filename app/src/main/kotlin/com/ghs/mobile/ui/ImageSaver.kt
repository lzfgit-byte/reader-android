package com.ghs.mobile.ui

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.ghs.mobile.GhsApp
import java.io.File
import java.io.OutputStream
import okhttp3.Request

/**
 * 图片保存到相册。
 *
 * - API 29+：走 MediaStore，**不需要存储权限**，写入 `Pictures/GHS/`
 * - API 26~28：写到应用外部目录（`Android/data/.../files/Pictures/GHS`），不需要权限，
 *   但不会出现在系统相册里——这是旧版的现实限制，避免为了存图去要全盘读写权限
 *
 * 下载复用 [GhsApp.proxiedClient]，与页面请求走同一条代理链路。
 */
object ImageSaver {

    /** 返回保存位置的可读描述；失败抛异常 */
    fun save(context: Context, imageUrl: String, referer: String? = null): String {
        require(imageUrl.isNotBlank()) { "图片地址为空" }
        val name = "ghs_${System.currentTimeMillis()}.${extensionOf(imageUrl)}"
        android.util.Log.i("GHS", "ImageSaver.save $name from $imageUrl")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeOf(imageUrl))
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GHS")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("无法创建媒体条目")
            try {
                resolver.openOutputStream(uri)?.use { out -> download(imageUrl, referer, out) }
                    ?: error("无法打开输出流")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return "Pictures/GHS/$name"
            } catch (error: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw error
            }
        }

        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "GHS")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.outputStream().use { out -> download(imageUrl, referer, out) }
        return file.absolutePath
    }

    private fun download(url: String, referer: String?, out: OutputStream) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
        // 很多图床校验 Referer，缺了会 403
        referer?.takeIf { it.isNotBlank() }?.let { builder.header("Referer", it) }
        GhsApp.instance.proxiedClient().newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val body = response.body ?: error("响应为空")
            body.byteStream().copyTo(out)
        }
    }

    private fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val ext = path.substringAfterLast('.', "").lowercase()
        return if (ext in setOf("jpg", "jpeg", "png", "webp", "gif")) ext else "jpg"
    }

    private fun mimeOf(url: String): String = when (extensionOf(url)) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    private const val USER_AGENT =
        com.ghs.mobile.core.engine.net.UserAgents.DESKTOP_CHROME
}
