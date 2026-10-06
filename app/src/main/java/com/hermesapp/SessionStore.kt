package com.hermesapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一个历史会话的元信息。id 同时是网关的 session_id。 */
data class SessionMeta(
    val id: String,
    var title: String,
    var updatedAt: Long,
    var archived: Boolean = false,
)

/**
 * 跨会话搜索的一条命中。
 * text 存整条正文：跳到那个会话后靠它把这条从本地消息里认出来（消息 id 不落盘，不能靠 id 认）。
 */
data class GlobalHit(
    val sessionId: String,
    val sessionTitle: String,
    val role: String,
    val text: String,
    val snippet: String,
    val ts: Long,
)

/**
 * 会话索引 + 逐会话消息的本地存储。
 * 全部落在 App 私有目录，不动服务器/引擎任何数据。
 *
 *   sessions_<profile>.json          会话索引
 *   chat_<profile>_<sessionId>.json  单个会话的消息
 */
class SessionStore(ctx: Context, private val profile: String) {

    private val dir: File = ctx.filesDir

    private fun indexFile() = File(dir, "sessions_$profile.json")
    private fun msgFile(id: String) = File(dir, "chat_${profile}_$id.json")
    private fun legacyFile() = File(dir, "chat_$profile.json")

    fun loadIndex(): MutableList<SessionMeta> {
        val out = mutableListOf<SessionMeta>()
        runCatching {
            val f = indexFile()
            if (!f.exists()) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                if (id.isEmpty()) continue
                out.add(
                    SessionMeta(
                        id = id,
                        title = o.optString("title", "新对话"),
                        updatedAt = o.optLong("updatedAt", 0L),
                        archived = o.optBoolean("archived", false),
                    )
                )
            }
        }
        return out
    }

    fun saveIndex(list: List<SessionMeta>) {
        runCatching {
            val arr = JSONArray()
            for (s in list) {
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updatedAt", s.updatedAt)
                        .put("archived", s.archived)
                )
            }
            indexFile().writeText(arr.toString())
        }
    }

    fun loadMessages(id: String): List<Msg> {
        val out = mutableListOf<Msg>()
        runCatching {
            val f = msgFile(id)
            if (!f.exists()) return@runCatching
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val text = o.optString("text", "")
                val trace = o.optString("trace", "")
                val imgs = mutableListOf<String>()
                o.optJSONArray("images")?.let { ia ->
                    for (k in 0 until ia.length()) ia.optString(k)?.takeIf { it.isNotEmpty() }?.let { imgs.add(it) }
                }
                val files = mutableListOf<String>()
                o.optJSONArray("files")?.let { fa ->
                    for (k in 0 until fa.length()) fa.optString(k)?.takeIf { it.isNotEmpty() }?.let { files.add(it) }
                }
                // 只要还有正文 / 工具轨迹 / 图片 / 附件，这条就得留住。
                // 「只发了图、没打字」的消息正文是空的，按老条件（只查正文与轨迹）会被整条丢掉，
                // 重开 App 后那条图就凭空消失——附件消息必须按附件是否为空一起判。
                if (text.isEmpty() && trace.isEmpty() && imgs.isEmpty() && files.isEmpty()) continue
                // 投递状态：老消息没有这个键 → 保持 null（界面不显示角标，不报错）。
                val rc = o.optJSONObject("receipt")?.let { ro ->
                    val arts = mutableListOf<String>()
                    ro.optJSONArray("artifactIds")?.let { aa ->
                        for (k in 0 until aa.length()) aa.optString(k)?.takeIf { it.isNotEmpty() }?.let { arts.add(it) }
                    }
                    Receipt(
                        status = ro.optString("status", Receipt.ACCEPTED),
                        runId = ro.optString("runId", ""),
                        note = ro.optString("note", ""),
                        rawText = ro.optString("rawText", text.trim()),
                        priorUserCount = ro.optInt("priorUserCount", -1),
                        idemKey = ro.optString("idemKey", ""),
                        artifactIds = arts,
                    )
                }
                out.add(
                    Msg(
                        role = o.optString("role", "assistant"),
                        text = text,
                        pending = false,
                        ts = o.optLong("ts", 0L),
                        images = imgs,
                        files = files,
                        trace = trace,
                        receipt = rc,
                        quote = o.optString("quote", ""),
                    )
                )
            }
        }
        return out
    }

    fun saveMessages(id: String, list: List<Msg>, max: Int = 300) {
        runCatching {
            val clean = list.filter { !(it.pending && it.text.isEmpty() && it.trace.isEmpty()) }
            val tail = if (clean.size > max) clean.takeLast(max) else clean
            val arr = JSONArray()
            for (m in tail) {
                val o = JSONObject().put("role", m.role).put("text", m.text).put("ts", m.ts)
                if (m.trace.isNotEmpty()) o.put("trace", m.trace)
                if (m.quote.isNotEmpty()) o.put("quote", m.quote)
                if (m.images.isNotEmpty()) {
                    val ia = JSONArray()
                    for (u in m.images) ia.put(u)
                    o.put("images", ia)
                }
                // 非图片附件的文件名也要落盘：不落的话重开 App 附件卡片就没了。
                if (m.files.isNotEmpty()) {
                    val fa = JSONArray()
                    for (n in m.files) fa.put(n)
                    o.put("files", fa)
                }
                // 投递状态要落盘：重开 App 后「不确定/失败」的消息还得能处置。
                // sending 不落盘——重启后那个 POST 已经没了，留着会一直转圈；
                // queued 同理——内存里的排队队列重启即丢，落盘会永远停在「排队中」。
                m.receipt?.takeIf { it.status != Receipt.SENDING && it.status != Receipt.QUEUED }?.let { rc ->
                    val rj = JSONObject()
                        .put("status", rc.status)
                        .put("runId", rc.runId)
                        .put("note", rc.note)
                        .put("rawText", rc.rawText)
                        .put("priorUserCount", rc.priorUserCount)
                        .put("idemKey", rc.idemKey)
                    // 附件 id 也要落盘：重开 App 后重发同样要复用它们，否则指纹不符被拒。
                    if (rc.artifactIds.isNotEmpty()) {
                        val aa = JSONArray()
                        for (s in rc.artifactIds) aa.put(s)
                        rj.put("artifactIds", aa)
                    }
                    o.put("receipt", rj)
                }
                arr.put(o)
            }
            msgFile(id).writeText(arr.toString())
        }
    }

    fun deleteMessages(id: String) {
        runCatching { msgFile(id).delete() }
    }

    /**
     * 跨会话搜索：扫全部会话的本地消息（正文与工具轨迹），按词命中返回结果。
     *
     * 会话按更新时间从新到旧扫，单条只取第一处命中；上限 limit 条（默认 60），
     * 结果再按消息时间从新到旧排。纯本地文件读，不碰网络。
     */
    fun searchAll(query: String, limit: Int = 60): List<GlobalHit> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<GlobalHit>()
        val metas = loadIndex().sortedByDescending { it.updatedAt }
        for (meta in metas) {
            if (out.size >= limit) break
            val f = msgFile(meta.id)
            if (!f.exists()) continue
            runCatching {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    if (out.size >= limit) break
                    val o = arr.optJSONObject(i) ?: continue
                    val text = o.optString("text", "")
                    val trace = o.optString("trace", "")
                    val hitText = when {
                        text.lowercase().contains(needle) -> text
                        trace.isNotEmpty() && trace.lowercase().contains(needle) -> trace
                        else -> null
                    } ?: continue
                    out.add(
                        GlobalHit(
                            sessionId = meta.id,
                            sessionTitle = meta.title,
                            role = o.optString("role", "assistant"),
                            text = text,
                            snippet = hitSnippet(hitText, needle),
                            ts = o.optLong("ts", 0L),
                        )
                    )
                }
            }
        }
        return out.sortedByDescending { it.ts }
    }

    /** 命中处的上下文片段：截命中词前后一小段，两头加省略号。 */
    private fun hitSnippet(text: String, needle: String): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        if (one.isEmpty()) return ""
        val k = one.lowercase().indexOf(needle)
        if (k < 0) return if (one.length > 80) one.take(80) + "…" else one
        val start = (k - 20).coerceAtLeast(0)
        val end = (k + needle.length + 40).coerceAtMost(one.length)
        return (if (start > 0) "…" else "") + one.substring(start, end) +
            (if (end < one.length) "…" else "")
    }

    /**
     * 1.4 的老格式（chat_<profile>.json，没有会话索引）迁移成列表里的第一个会话。
     * 已有索引则直接丢弃老文件（说明已经迁移过）。
     */
    fun migrateLegacy(legacySessionId: String): SessionMeta? {
        runCatching {
            val f = legacyFile()
            if (!f.exists()) return null
            if (indexFile().exists()) {
                f.delete()
                return null
            }
            val id = legacySessionId.ifEmpty { UUID.randomUUID().toString() }
            f.copyTo(msgFile(id), overwrite = true)
            f.delete()
            val meta = SessionMeta(id, "历史对话", System.currentTimeMillis(), false)
            saveIndex(listOf(meta))
            return meta
        }
        return null
    }
}