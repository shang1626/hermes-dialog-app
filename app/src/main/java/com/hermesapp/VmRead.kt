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
            val c = o.optString("content", "")
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
        out.add(HistRow(role, o.optString("content", ""), o.optString("id", i.toString())))
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
