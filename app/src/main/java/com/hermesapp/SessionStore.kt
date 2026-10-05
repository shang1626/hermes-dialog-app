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
                if (text.isEmpty() && trace.isEmpty()) continue
                val imgs = mutableListOf<String>()
                o.optJSONArray("images")?.let { ia ->
                    for (k in 0 until ia.length()) ia.optString(k)?.takeIf { it.isNotEmpty() }?.let { imgs.add(it) }
                }
                out.add(
                    Msg(
                        role = o.optString("role", "assistant"),
                        text = text,
                        pending = false,
                        ts = o.optLong("ts", 0L),
                        images = imgs,
                        trace = trace,
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
                if (m.images.isNotEmpty()) {
                    val ia = JSONArray()
                    for (u in m.images) ia.put(u)
                    o.put("images", ia)
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