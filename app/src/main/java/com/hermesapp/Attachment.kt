package com.hermesapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 服务端把 `MEDIA:` 标签转成内联 data URL（`data:<mime>;base64,<...>`）随消息送到客户端。
 * 这里负责把它解码成字节、落盘到 App 私有目录，再经 FileProvider 交给系统应用打开。
 *
 * 为什么走 data URL 而不是新增下载端点：Hermes 自带的 `/v1/artifacts/download/{id}` 是
 * 一次性（第二次 404），重开 App 再点就废了；内联 + 本地落盘后永久可看，且不动服务端契约。
 */

data class DecodedData(val mime: String, val bytes: ByteArray)

private val DATA_URL_RE = Regex("^data:([^;,]+)(;base64)?,(.*)$", RegexOption.DOT_MATCHES_ALL)

/** 解析 `data:<mime>[;base64],<payload>`；非 base64 的按 UTF-8 文本处理。 */
fun decodeDataUrl(dataUrl: String): DecodedData? {
    val m = DATA_URL_RE.find(dataUrl) ?: return null
    val mime = m.groupValues[1].ifBlank { "application/octet-stream" }
    val isB64 = m.groupValues[2].isNotEmpty()
    val payload = m.groupValues[3]
    return try {
        // 上限防 OOM：这个函数在 Compose 合成期主线程调用，超大内联体会直接崩。
        if (isB64 && payload.length > 48_000_000) return null
        val bytes = if (isB64) Base64.decode(payload, Base64.DEFAULT) else payload.toByteArray()
        if (bytes.isEmpty()) null else DecodedData(mime, bytes)
    } catch (_: Exception) {
        null
    }
}

fun extFor(mime: String): String = when {
    mime.contains("html") -> ".html"
    mime.contains("svg") -> ".svg"
    mime.contains("png") -> ".png"
    mime.contains("jpeg") || mime.contains("jpg") -> ".jpg"
    mime.contains("gif") -> ".gif"
    mime.contains("webp") -> ".webp"
    mime.contains("pdf") -> ".pdf"
    mime.contains("json") -> ".json"
    mime.contains("csv") -> ".csv"
    mime.contains("zip") -> ".zip"
    mime.contains("markdown") -> ".md"
    mime.contains("plain") -> ".txt"
    mime.contains("mpeg") && mime.contains("audio") -> ".mp3"
    mime.contains("mp4") -> ".mp4"
    else -> ".bin"
}

fun fmtSize(n: Int): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format("%.0f KB", n / 1024.0)
    else -> String.format("%.1f MB", n / 1024.0 / 1024.0)
}

/** 去掉文件名里的路径分隔与非法字符，避免落盘时越界。 */
private fun sanitizeName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    val cleaned = base.replace(Regex("[^\\w\\u4e00-\\u9fa5.\\-]+"), "_").trim('_', '.')
    return if (cleaned.isBlank()) "attachment" else cleaned.take(80)
}

/**
 * 把解码后的字节写进 `filesDir/attachments/`（FileProvider 已声明该子目录），
 * 再用 ACTION_VIEW 拉起系统应用：HTML/SVG 走浏览器，图片走图库，PDF 走阅读器。
 */
fun openAttachment(ctx: Context, name: String, data: DecodedData) {
    // 失败不能静默：原来整段 runCatching 什么都不留，点一下毫无反应，
    // 用户分不清是「没点到」还是「打不开」。
    val ok = runCatching {
        val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
        val safe = sanitizeName(name)
        val withExt = if (safe.contains('.')) safe else safe + extFor(data.mime)
        val f = File(dir, withExt)
        f.writeBytes(data.bytes)
        val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, data.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)
    if (!ok) {
        AppLog.log("attach", "打开附件失败（无可用应用或写入失败）name=" + name)
        Toast.makeText(ctx, "打开失败：手机上没有能打开这个文件的应用", Toast.LENGTH_SHORT).show()
    }
}


/**
 * 把解码后的字节保存到系统相册（Pictures/Hermes）。
 * API 29+ 走 MediaStore（无需存储权限）；API 26-28 写应用外部目录后扫描入册（尽力而为）。
 * 返回保存后的可读文件名（失败返回 null）。
 */
fun saveImageToGallery(ctx: Context, name: String, data: DecodedData): String? = runCatching {
    val safe = sanitizeName(name)
    val withExt = if (safe.contains('.')) safe else safe + extFor(data.mime)
    val display = if (withExt.contains('.')) withExt else withExt + extFor(data.mime)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, display)
            put(MediaStore.Images.Media.MIME_TYPE, data.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Hermes")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { it.write(data.bytes) } ?: return null
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        display
    } else {
        val dir = File(
            ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir,
            "Hermes"
        ).apply { mkdirs() }
        val f = File(dir, display)
        f.writeBytes(data.bytes)
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(data.mime), null)
        display
    }
}.getOrNull()


/**
 * 从 content:// 或 file:// Uri 读字节 + MIME：用户气泡里的本地图片要能放大/存相册。
 * 读不到返回 null。
 */
fun uriToDecoded(ctx: Context, uri: Uri): DecodedData? = runCatching {
    val mime = ctx.contentResolver.getType(uri) ?: guessMime(uri.lastPathSegment ?: "")
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    if (bytes.isEmpty()) null else DecodedData(mime, bytes)
}.getOrNull()


/**
 * 网关托管媒体的下载入口：ChatViewModel 登录后把带鉴权的取文件函数挂到这里，
 * Markdown 附件卡片点开时调用（大文件不再塞进消息体，按需下载）。
 */
object MediaFetch {
    /** 取不到（未登录）时为 null。 */
    @Volatile var handler: ((String) -> ByteArray?)? = null

    /** 最近一次取字节的失败原因（给界面提示用）：空 = 上次成功或还没取过。 */
    @Volatile var lastError: String = ""

    fun download(token: String): ByteArray? {
        return try {
            val r = handler?.invoke(token)
            lastError = if (r == null) "文件取不到（未登录或服务端无留档）" else ""
            r
        } catch (e: Exception) {
            val m = e.message ?: ""
            lastError = if (m.startsWith("TOO_LARGE:"))
                "文件太大，超过 100MB 上限，无法在手机上打开"
            else "下载失败：" + m.take(80)
            AppLog.err("attach", "媒体下载失败", e)
            null
        }
    }
}


/**
 * 附件落盘到 filesDir/attachments/（FileProvider 已声明该目录）并返回 File。
 * 打开、分享、保存三处共用这一份落盘逻辑，避免各写一套。
 */
private fun persistAttachment(ctx: Context, name: String, data: DecodedData): File? = runCatching {
    val dir = File(ctx.filesDir, "attachments").apply { mkdirs() }
    val safe = sanitizeName(name)
    val withExt = if (safe.contains('.')) safe else safe + extFor(data.mime)
    val f = File(dir, withExt)
    f.writeBytes(data.bytes)
    f
}.onFailure {
    AppLog.err("attach", "附件落盘失败 name=" + name, it)
}.getOrNull()

/**
 * 分享附件：落盘 → FileProvider 换成 content:// → ACTION_SEND 弹系统分享面板
 * （微信、QQ、邮件都在里面）。这是「收到的文件转发给别人」的正路。
 */
fun shareAttachment(ctx: Context, name: String, data: DecodedData) {
    // 失败不能静默：原来整段被 runCatching 包住，落盘失败或手机没有可分享的应用时
    // 点一下毫无反应（旁边的「保存」有 Toast，分享没有，不一致）。
    val ok = runCatching {
        val f = persistAttachment(ctx, name, data) ?: return@runCatching false
        val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = data.mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "分享").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        true
    }.getOrDefault(false)
    if (!ok) {
        AppLog.log("attach", "分享附件失败 name=" + name)
        Toast.makeText(ctx, "分享失败：没有可用的分享应用，或文件写入失败", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 保存附件到系统「下载」目录，返回保存后的文件名（失败返回 null）。
 * API 29+ 走 MediaStore（无需存储权限）；API 26-28 写应用外部目录后扫描入册。
 */
fun saveAttachmentToDownloads(ctx: Context, name: String, data: DecodedData): String? = runCatching {
    val safe = sanitizeName(name)
    val display = if (safe.contains('.')) safe else safe + extFor(data.mime)
    val mime = data.mime.ifBlank { "application/octet-stream" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, display)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Hermes")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(data.bytes) } ?: return null
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        display
    } else {
        val dir = File(
            ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir,
            "Hermes"
        ).apply { mkdirs() }
        val f = File(dir, display)
        f.writeBytes(data.bytes)
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf(mime), null)
        display
    }
}.onFailure {
    AppLog.err("attach", "保存附件到下载失败 name=" + name, it)
}.getOrNull()
