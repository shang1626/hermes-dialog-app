package com.hermesapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 从 ChatViewModel 抽出的无状态工具函数（纯格式化 / 解析）。
 * 无实例依赖、无 UI 依赖，便于单测与复用。
 */
/**
 * 异常摘要：优先「类名: message」。
 *
 * 为什么要带类名：NetworkOnMainThreadException 这类异常的 message 是 null，
 * 老写法 `e.message ?: "?"` 会把真实原因吞成「?」，用户只看到「收件箱获取失败：?」，
 * 排查时等于没有信息。
 */
internal fun diagText(e: Throwable): String {
    val m = e.message
    val n = e.javaClass.simpleName
    return if (m.isNullOrBlank()) n else n + ": " + m
}
/**
 * 时间戳解析。两处来源格式不同，都得认：
 *  - 服务端 `/api/sessions/{id}/messages` 的 `timestamp` 是**浮点秒**
 *    （如 `1791557363.3028529`）——旧实现只 `OffsetDateTime.parse`，解析失败一律回落 0，
 *    结果**所有服务端来源的消息都没有时间**（用户 2026-10-10 报「这些信息偶尔不出现」里
 *    就含时间行）。
 *  - 其他接口（会话列表等）给的是 ISO 字符串。
 * 纯数字按秒解释（> 1e11 视为已经是毫秒），否则当 ISO。
 */
internal fun parseTs(s: String): Long {
    val t = s.trim()
    if (t.isEmpty()) return 0L
    t.toDoubleOrNull()?.let { v ->
        if (v <= 0.0) return 0L
        return if (v > 1e11) v.toLong() else (v * 1000.0).toLong()
    }
    return runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        .getOrDefault(0L)
}
internal fun fmtMb(mb: Int): String =
    if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else mb.toString() + " MB"
internal fun fmtSize(b: Long): String = when {
    b >= 1024L * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
    b >= 1024L -> String.format("%.0f KB", b / 1024.0)
    else -> b.toString() + " B"
}
/**
 * 只发附件、没打字时，发给服务端的占位正文（服务端不允许空 input）。
 * 纯占位，气泡里不显示；图片本身走原生多模态附件，模型照样看得到图。
 */
internal fun attachmentLabel(imgs: List<PendingImage>): String {
    val hasImg = imgs.any { it.isImage }
    val hasFile = imgs.any { !it.isImage }
    return when {
        hasImg && hasFile -> "（图片和文件）"
        hasImg -> "（图片）"
        else -> "（文件）"
    }
}
// 按真实扩展名给 MIME：图片走 image 类，其余按常见类型给，未知一律 octet-stream。
internal fun mimeOf(file: File): String = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    "html", "htm" -> "text/html"
    "txt", "log" -> "text/plain"
    "md" -> "text/markdown"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "xml" -> "application/xml"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    "apk" -> "application/vnd.android.package-archive"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}
/**
 * 把子代理会话末尾的消息拆成步骤流水：一次工具调用 = 一步。
 * 参数从它前面那条 assistant 消息的 tool_calls 取（结果行里没有参数），
 * 对不上就退化成只有工具名——不编造。
 */
internal fun parseSubagentSteps(arr: JSONArray?): List<SubagentStep> {
    if (arr == null) return emptyList()
    val args = HashMap<String, Pair<String, String>>()
    val out = mutableListOf<SubagentStep>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        when (o.optString("role", "")) {
            "assistant" -> {
                val tcs = o.optJSONArray("tool_calls") ?: continue
                for (j in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(j) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    val nm = fn.optString("name", "")
                    val tcid = tc.optString("id", "").ifEmpty { tc.optString("call_id", "") }
                    if (tcid.isNotEmpty()) args[tcid] = nm to toolArgBrief(fn.optString("arguments", ""))
                }
            }
            "tool" -> {
                val pair = args[o.optString("tool_call_id", "")]
                val nm = pair?.first?.takeIf { it.isNotEmpty() } ?: o.optString("tool_name", "")
                out.add(
                    SubagentStep(
                        out.size + 1, nm, pair?.second ?: "",
                        toolResultBrief(o.optString("content", "")),
                    )
                )
            }
        }
    }
    return out
}
/** 工具参数一句话：从参数 JSON 里挑最能说明意图的字段（路径/命令/搜索词）。 */
internal fun toolArgBrief(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    val obj = runCatching { JSONObject(s) }.getOrNull() ?: return oneLine(s, 90)
    for (k in listOf("path", "command", "pattern", "query", "url", "file", "name", "goal", "prompt", "text")) {
        val v = obj.optString(k, "")
        if (v.isNotEmpty()) return oneLine(v, 110)
    }
    return oneLine(s, 90)
}
/** 工具结果一句话：是 JSON 就挑 output/content/success 之类，纯文本直接截断。 */
internal fun toolResultBrief(raw: String): String {
    val s = raw.trim()
    if (s.isEmpty()) return ""
    runCatching { JSONObject(s) }.getOrNull()?.let { o ->
        for (k in listOf("output", "content", "error", "message", "text")) {
            val v = o.optString(k, "")
            if (v.isNotEmpty()) return oneLine(v, 110)
        }
        if (o.has("total_count")) return "命中 " + o.optInt("total_count") + " 处"
        if (o.has("success")) return if (o.optBoolean("success", false)) "成功" else "失败"
        if (o.has("exit_code")) return "exit " + o.optInt("exit_code")
    }
    return oneLine(s, 110)
}
internal fun oneLine(s: String, n: Int): String {
    val t = s.replace(Regex("\\s+"), " ").trim()
    return if (t.length > n) t.take(n) + "…" else t
}
