package com.ghs.mobile.ui

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 应用内缓存（F05 / F02）。
 *
 * 三类内容，全部按「URL 的 MD5」作 key，永不过期，只靠 LRU 淘汰：
 * - 解码前图片字节：避免同一张图反复下载
 * - 解码后位图：滚动回看时直接出图，不下载也不解码
 * - HTML 文本：避免同一页反复取
 *
 * 每类都要能报出占用字节数，供「清除缓存」界面展示。
 */
object ImageCache {

    private const val RAW_MAX_BYTES = 24 * 1024 * 1024
    private const val BITMAP_MAX_BYTES = 64 * 1024 * 1024
    private const val HTML_MAX_BYTES = 8 * 1024 * 1024

    /** 解码前的原始图片字节 */
    private val raw = object : LruCache<String, ByteArray>(RAW_MAX_BYTES) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    /** 解码后的位图（字节数按 Bitmap.byteCount 计） */
    private val bitmaps = object : LruCache<String, Bitmap>(BITMAP_MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 页面 HTML 文本（按 UTF-8 字节数计） */
    private val html = object : LruCache<String, String>(HTML_MAX_BYTES) {
        override fun sizeOf(key: String, value: String): Int = value.toByteArray().size
    }

    fun hashOf(url: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ── 图片字节 ────────────────────────────────────────────────
    fun rawBytes(url: String): ByteArray? = raw.get(hashOf(url))
    fun putRawBytes(url: String, bytes: ByteArray) {
        raw.put(hashOf(url), bytes)
    }

    // ── 位图 ───────────────────────────────────────────────────
    fun bitmap(url: String): Bitmap? = bitmaps.get(hashOf(url))
    fun putBitmap(url: String, bitmap: Bitmap) {
        bitmaps.put(hashOf(url), bitmap)
    }

    // ── HTML ───────────────────────────────────────────────────
    fun html(url: String): String? = html.get(hashOf(url))
    fun putHtml(url: String, text: String) {
        if (text.isBlank()) return
        html.put(hashOf(url), text)
    }

    /** 手动刷新 / 切换站点时调用：只清 HTML，图片缓存保留 */
    fun clearHtml() {
        html.evictAll()
    }

    /** 当前占用（字节）：总 / 图片字节 / 位图 / HTML */
    fun sizeBytes(): Long = raw.size().toLong() + bitmaps.size().toLong() + html.size().toLong()
    fun rawSizeBytes(): Long = raw.size().toLong()
    fun bitmapSizeBytes(): Long = bitmaps.size().toLong()
    fun htmlSizeBytes(): Long = html.size().toLong()

    /** 清空全部内存缓存 */
    fun clearAll() {
        raw.evictAll()
        bitmaps.evictAll()
        html.evictAll()
    }

    /** 人类可读的大小 */
    fun format(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
