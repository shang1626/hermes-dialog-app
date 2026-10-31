package com.hermesapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.hermesapp.net.HermesApi
import org.json.JSONObject

/**
 * 纯读函数：只依赖入参（HermesApi / SessionRuntime / Context+Uri），不碰 ViewModel 实例状态。
 * 从 ChatViewModel 抽出以便复用与（后续）单测。
 */

internal suspend fun fetchRunSummary(a: HermesApi, jobId: String): String {
    return try {
        val resp = a.listSessions(limit = 50)
        val arr = resp.optJSONArray("data") ?: return ""
        val prefix = "cron_" + jobId + "_"
        var best: JSONObject? = null
        var bestTs = Double.NEGATIVE_INFINITY
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            val sid = s.optString("id", "")
            if (!sid.startsWith(prefix)) continue
            val ts = s.optDouble("started_at", 0.0)
            if (ts > bestTs) { bestTs = ts; best = s }
        }
        val sid = best?.optString("id", "").orEmpty()
        if (sid.isEmpty()) return ""
        val m = a.sessionMessages(sid)
        val msgs = m.optJSONArray("data") ?: return ""
        var text = ""
        for (i in 0 until msgs.length()) {
            val o = msgs.optJSONObject(i) ?: continue
            if (o.optString("role", "") != "assistant") continue
            val c = contentText(o)
            if (c.isNotBlank()) text = c
        }
        val one = text.replace(Regex("\\s+"), " ").trim()
        when {
            one.isEmpty() -> ""
            one.length > 160 -> one.take(160) + "…"
            else -> one
        }
    } catch (_: Exception) {
        ""
    }
}

internal fun historyRows(a: HermesApi, sid: String): List<HistRow> {
    val resp = a.sessionMessages(sid)
    val arr = resp.optJSONArray("data") ?: return emptyList()
    val out = ArrayList<HistRow>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val role = o.optString("role", "")
        if (role != "user" && role != "assistant") continue
        // content 可能是字符串，也可能是「分块数组」（带图的消息）——一律走 contentText 投影，
        // 绝不能 optString：数组会被序列化成含 base64 的 JSON 文本（20 万字气泡，卡死首屏）。
        out.add(HistRow(role, contentText(o), o.optString("id", i.toString())))
    }
    return coalesceCutRows(out)
}

/**
 * 服务端把「断流后自动续跑」写成一条 role=user 的 `[System: …]` 注记：它不是用户说的话，
 * 不能当用户气泡、也不能当合并锚点；紧邻的两段助手按顺序并回一条（中间留一行说明）。
 */
internal fun coalesceCutRows(rows: List<HistRow>): List<HistRow> {
    if (rows.isEmpty() || rows.none { isCutNote(it.text) }) return rows
    val out = ArrayList<HistRow>(rows.size)
    var cut = false
    for (m in rows) {
        if (isCutNote(m.text)) { cut = true; continue }
        if (cut) {
            cut = false
            val last = out.lastOrNull()
            if (m.role == "assistant" && last != null && last.role == "assistant") {
                out[out.size - 1] = last.copy(text = (last.text + CUT_MARK + m.text).trim())
                continue
            }
        }
        out.add(m)
    }
    return out
}

internal fun runningChildren(r: SessionRuntime): List<SubagentLine> =
    r.messages.value.flatMap { it.subagents }
        .filter { it.status == "running" && it.childSessionId.isNotEmpty() && it.endedAt == 0L }

internal fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long> {
    var name = "image.jpg"
    var size = 0L
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (ni >= 0) c.getString(ni)?.let { name = it }
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
    }
    return name to size
}
