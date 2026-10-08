package com.hermesapp

import android.content.Context
import java.io.File

/**
 * 缓存统计与清理。
 *
 * 只清「临时文件」三类：待发图片沙盒、下载的安装包、图片磁盘缓存。
 * 明确不动：会话记录（sessions_ 与 chat_ 前缀文件）、输入草稿、登录状态与设置——清缓存不该让人丢聊天记录。
 */
object CacheUtil {

    private fun outbox(ctx: Context) = File(ctx.filesDir, "outbox")
    private fun apkDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "apk")
    /** Coil 默认磁盘缓存目录；按目录清，避免依赖其 API 版本差异。 */
    private fun imgCache(ctx: Context) = File(ctx.cacheDir, "image_cache")
    /** 流式语音落盘目录：每条约 10~100KB，只增不减会慢慢涨。 */
    private fun voiceDir(ctx: Context) = File(ctx.cacheDir, "voice_replay")
    /** 导出目录（日志/记录导出）：分享完就没用了，可清。 */
    private fun exportsDir(ctx: Context) = File(ctx.filesDir, "exports")

    private fun dirSize(f: File?): Long {
        if (f == null || !f.exists()) return 0L
        if (f.isFile) return f.length()
        return f.listFiles()?.sumOf { dirSize(it) } ?: 0L
    }

    /** (待发图片, 安装包, 图片缓存) 三项字节数。 */
    fun sizes(ctx: Context): Triple<Long, Long, Long> =
        Triple(dirSize(outbox(ctx)), dirSize(apkDir(ctx)), dirSize(imgCache(ctx)))

    fun total(ctx: Context): Long = sizes(ctx).let { it.first + it.second + it.third } +
        dirSize(voiceDir(ctx)) + dirSize(exportsDir(ctx))

    /** 清空三项临时缓存；会话记录/草稿/登录状态一律不动。 */
    fun clear(ctx: Context) {
        runCatching { outbox(ctx).listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { apkDir(ctx).listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { imgCache(ctx).listFiles()?.forEach { it.deleteRecursively() } }
        // 附件落盘目录：打开/分享/保存每次都写一份，原来只增不减。
        runCatching { File(ctx.filesDir, "attachments").listFiles()?.forEach { it.deleteRecursively() } }
        // 流式语音/导出目录：同样只增不减，纳入清理。
        runCatching { voiceDir(ctx).listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { exportsDir(ctx).listFiles()?.forEach { it.deleteRecursively() } }
    }
}