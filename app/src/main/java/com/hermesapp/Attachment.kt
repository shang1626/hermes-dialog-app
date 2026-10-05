package com.hermesapp

import android.content.Context
import android.content.Intent
import android.util.Base64
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
    runCatching {
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
    }
}


/**
 * 网关托管媒体的下载入口：ChatViewModel 登录后把带鉴权的取文件函数挂到这里，
 * Markdown 附件卡片点开时调用（大文件不再塞进消息体，按需下载）。
 */
object MediaFetch {
    /** 取不到（未登录）时为 null。 */
    @Volatile var handler: ((String) -> ByteArray?)? = null

    fun download(token: String): ByteArray? = try {
        handler?.invoke(token)
    } catch (_: Exception) {
        null
    }
}
