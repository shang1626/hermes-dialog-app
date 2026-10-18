package com.hermesapp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务端历史行 `content` 的**投影**（2.215）。
 *
 * 背景（真事故）：带图的消息，服务端 `/api/sessions/{id}/messages` 把 `content` 返回成
 * 「分块数组」——`[{"type":"text","text":"…"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,…"}}]`。
 * 旧实现一律 `o.optString("content","")`：org.json 对数组会走 `String.valueOf`，
 * 于是整块数组（含 22 万字的 base64 图）被序列化成 JSON 文本，成了**一条 20 万字的气泡**。
 * 后果两条：
 *  1. 首屏渲染这条气泡把主线程卡死（实测 20:09 连杀 3 次 App，日志在渲染那一步断掉）；
 *  2. 合并时正文对不上（本地是原文、服务端是那坨 JSON），于是同一句话出现两个气泡。
 *
 * 这里的规矩：**只取人能看的正文**——文本块原样取、图注（`[Image attached at: …]`）剥掉、
 * 图片块与其它结构化块一律不落成文字。本地存储里已经被写坏的行，靠 [sanitizeBody] /
 * [sanitizeLoadedMessages] 在读取时自愈。
 */

/** 单段正文的渲染上限：超过就截断+注明（防止任何超大正文再把主线程拖死）。 */
internal const val MAX_PARA_CHARS = 20_000

/** 图注 / 图片占位行（服务端注入的说明，不是用户打的字）。 */
private val IMG_HINT_RE = Regex("\\n*\\[Image attached(?: at)?: [^\\]\\n]*\\]")
private val IMG_REF_LINE_RE = Regex("^@image:.*$")
private const val SCREENSHOT_PLACEHOLDER = "[screenshot]"

/** 剥掉服务端往正文里塞的图注与占位行（App 里不显示、也不能参与「同一条消息」比对）。 */
internal fun stripImageHints(s: String): String {
    if (s.isEmpty()) return s
    if (!s.contains("[Image attached") && !s.contains("@image:") && !s.contains(SCREENSHOT_PLACEHOLDER)) return s
    var t = IMG_HINT_RE.replace(s, "")
    val lines = t.split("\n").toMutableList()
    while (lines.isNotEmpty()) {
        val last = lines.last().trim()
        if (last == SCREENSHOT_PLACEHOLDER || IMG_REF_LINE_RE.matches(last)) lines.removeAt(lines.size - 1) else break
    }
    t = lines.joinToString("\n")
    return t.trimEnd()
}

/** 分块数组 → 人能看的正文（文本块按序拼，其余块忽略）。 */
internal fun partsToText(arr: JSONArray): String {
    val sb = StringBuilder()
    for (i in 0 until arr.length()) {
        when (val p = arr.opt(i)) {
            is String -> sb.append(p)
            is JSONObject -> {
                val type = p.optString("type", "")
                val text = p.optString("text", "")
                if (text.isNotEmpty() && (type.isEmpty() || type == "text" || type == "input_text")) sb.append(text)
            }
            else -> Unit
        }
    }
    return stripImageHints(sb.toString())
}

/** 判断一段正文是不是「被序列化过的分块数组」（App 旧版本写坏的行、或服务端原样透传的数组文本）。 */
internal fun looksLikeContentArray(s: String): Boolean {
    val t = s.trim()
    if (t.length < 4 || !t.startsWith("[{") || !t.endsWith("]")) return false
    return t.contains("\"type\"") || t.contains("'type'")
}

/** 把任意来源的正文投影成人能看的文本；不是分块数组就原样返回。 */
internal fun sanitizeBody(raw: String): String {
    if (!looksLikeContentArray(raw)) return raw
    runCatching { return partsToText(JSONArray(raw)) }   // App 自己写下的投影是标准 JSON
    // 兜底：Python repr 形态（单引号）解析不了，只抠 text 字段，抠不到就交给渲染层截断。
    val m = Regex("'text'\\s*:\\s*'((?:[^'\\\\]|\\\\.)*)'").find(raw)
    return if (m != null) stripImageHints(m.groupValues[1].replace("\\n", "\n").replace("\\'", "'")) else raw
}

/** 从服务端历史行里取正文（content 可能是字符串、分块数组或结构化对象）。 */
internal fun contentText(o: JSONObject): String {
    return when (val v = o.opt("content")) {
        null -> ""
        is String -> sanitizeBody(v)
        is JSONArray -> partsToText(v)
        is JSONObject -> {
            val t = v.optString("text", "")
            if (t.isNotEmpty()) stripImageHints(t) else ""
        }
        else -> sanitizeBody(v.toString())
    }
}

/**
 * 读取本地正文时自愈：
 *  ① 被旧版本写成 JSON 的分块数组（那条 20 万字的气泡）→ 还原成正文；
 *  ② 这类「服务端投影行」与它对应的那条本地用户消息是**同一条**（本地才有图片缩略图、
 *     附件清单、回执），近窗口内已有同文用户消息时直接丢掉投影行，别留成重复气泡。
 * 数据安全：只动「本身就是分块数组投影」的行，普通消息一字不改；丢弃也只丢投影行。
 */
internal fun sanitizeLoadedMessages(list: List<Msg>, windowMs: Long = REWRITTEN_ROW_WINDOW_MS): List<Msg> {
    if (list.isEmpty()) return list
    val out = ArrayList<Msg>(list.size)
    var fixed = 0
    var dropped = 0
    for (m in list) {
        val raw = m.text
        val projection = looksLikeContentArray(raw)
        if (!projection) { out.add(m); continue }
        val text = sanitizeBody(raw)
        if (m.role == "user") {
            val n = normText(text)
            val dup = out.any {
                it.role == "user" && it.ts > 0 && m.ts > 0 &&
                    kotlin.math.abs(it.ts - m.ts) <= windowMs && normText(it.text) == n
            }
            if (dup) { dropped++; continue }
        }
        fixed++
        out.add(m.copy(text = text))
    }
    if (fixed > 0 || dropped > 0) {
        AppLog.log("store", "投影行自愈 原=" + list.size + " 还原=" + fixed + " 去重=" + dropped + " 修后=" + out.size)
    }
    return if (fixed > 0 || dropped > 0) out else list
}

/** 单段正文渲染前的上限裁切：超长只显示前面一段 + 注明，绝不让一整屏渲染卡死主线程。 */
internal fun capPara(s: String): String {
    if (s.length <= MAX_PARA_CHARS) return s
    val over = s.length - MAX_PARA_CHARS
    return s.take(MAX_PARA_CHARS) + "\n\n…（本条过长，已省略 " + over + " 字）"
}
