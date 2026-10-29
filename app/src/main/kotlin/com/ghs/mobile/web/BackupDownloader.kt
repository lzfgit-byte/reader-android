package com.ghs.mobile.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 从链接下载备份文件（F20 的第二种恢复入口）。
 *
 * 只做三件事：校验协议与体积、把内容读成文本、把失败统一成
 * [BackupService.BackupException]——错误码与"选文件"那条路径共用同一套（`E-BAK-*`），
 * 界面文案不用分两份。
 *
 * 客户端由调用方传入（App 侧传 [com.ghs.mobile.GhsApp.proxiedClient]），所以设备代理开关、
 * 来源级代理策略对它同样生效：备份放在网盘、内网 NAS 上也能取。
 */
object BackupDownloader {

    /**
     * 取回并解成文本。
     *
     * @throws BackupService.BackupException 链接不合法、HTTP 非 2xx、超时、内容超过 16MB
     */
    fun download(client: OkHttpClient, url: String): String {
        val target = url.trim()
        val uri = runCatching { java.net.URI(target) }.getOrNull()
        if (uri == null || uri.host.isNullOrBlank() ||
            (uri.scheme != "http" && uri.scheme != "https")
        ) {
            throw BackupService.BackupException("E-BAK-008", "链接要以 http:// 或 https:// 开头")
        }

        val tuned = client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(target)
            .header("Accept", "application/json, text/plain, */*")
            .build()

        try {
            tuned.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw BackupService.BackupException("E-BAK-008", "链接返回 HTTP ${response.code}")
                }
                if ((response.header("Content-Length")?.toLongOrNull() ?: -1L) > BackupService.MAX_BYTES) {
                    throw tooLarge()
                }
                val body = response.body
                    ?: throw BackupService.BackupException("E-BAK-008", "链接返回了空内容")
                val bytes = body.byteStream().use { readCapped(it) }
                // 备份文件是 UTF-8 JSON；非 UTF-8 会在解析阶段被判成"不是合法的 JSON"
                return bytes.toString(Charsets.UTF_8)
            }
        } catch (failure: IOException) {
            throw BackupService.BackupException(
                "E-BAK-008",
                "取不到链接：${failure.message ?: failure}",
            )
        }
    }

    /** 读满 16MB 就停：不信任 Content-Length，防止对端不给长度或谎报 */
    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > BackupService.MAX_BYTES) throw tooLarge()
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun tooLarge() = BackupService.BackupException(
        "E-BAK-005",
        "备份文件超过 ${BackupService.MAX_BYTES / 1024 / 1024}MB",
    )
}
