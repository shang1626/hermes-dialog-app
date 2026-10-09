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
    /** 手动排序键：越小越靠前。0 是「未排过」的哨兵；首次进入手动模式时按当前顺序钉成 1..N。 */
    var order: Long = 0L,
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

    /** 索引文件格式版本：改了字段结构就 +1，读到旧值按旧格式解析。 */
    private val SCHEMA_INDEX = 2

    /**
     * 本次实际读到的索引格式版本（缺省按 1 = 老版裸数组）。
     *
     * 用途只有一个：**别把「不认识的（更新版本的）」索引降级覆盖掉**（F24）。
     * 旧实现无论读到什么都按当前格式重写一遍，未来版本新加的字段会被静默抹掉 ——
     * 用户看到的就是「装了旧包之后历史结构被改坏」。
     */
    private var loadedIndexSchema: Int = SCHEMA_INDEX

    private fun indexFile() = File(dir, "sessions_$profile.json")

    /** 原子写/回退：逻辑抽到 AtomicStore（纯 java.io，便于单测），此处仅委托。 */
    private fun writeAtomic(f: File, text: String) = AtomicStore.writeAtomic(f, text)

    private fun readTextOrBackup(f: File): String = try {
        AtomicStore.readTextOrBackup(f)
    } catch (e: Exception) {
        AppLog.err("store", "读 " + f.name + " 失败，回退备份 字节=" + f.length(), e)
        throw e
    }

    /**
     * 读主文件，**缺失时也回退 .bak**；两者都没有返回 null（R13）。
     * 供「先判 exists 再读」的加载路径使用，别再用 `if (!exists) return` 把备份绕过。
     */
    private fun readMaybeMissing(f: File): String? {
        val t = AtomicStore.readTextOrBackupOrNull(f)
        if (t == null) return null
        if (!f.exists()) {
            AppLog.log("store", "主文件缺失，已用备份恢复 " + f.name + " 共 " + t.length + " 字节")
        }
        return t
    }
    private fun msgFile(id: String) = File(dir, "chat_${profile}_$id.json")
    private fun legacyFile() = File(dir, "chat_$profile.json")

    /**
     * 本地存储体检：一行交代这台手机上到底有哪些会话文件。
     *
     * 用户报历史对话没了时，服务端数据 / 本地索引 / 消息文件三层都可能。
     * 旧日志只有网络层，看不出本地到底存了什么——启动时打这一行，立刻能分清
     * 是索引文件丢了（不存在 / 只有几十字节），还是索引在而消息文件没了。
     */
    fun diagSummary(): String {
        val idx = indexFile()
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        // 排除原子写留下的 .bak/.tmp：它们不是会话本身，算进去会让「消息文件 N 个 / X 字节」
        // 虚高近一倍，而这条诊断正是为排查「历史对话没了」准备的，数字不能失真。
        val idxFiles = files.filter { it.name.startsWith("sessions_") && !it.name.endsWith(".bak") && !it.name.endsWith(".tmp") }
        val chatFiles = files.filter {
            it.name.startsWith("chat_") && !it.name.endsWith(".bak") &&
                !it.name.endsWith(".tmp") && !it.name.endsWith(".archive.json")
        }
        val names = if (idxFiles.size <= 4) idxFiles.joinToString(",") { it.name }
                    else "共" + idxFiles.size + "个"
        return "索引[" + (if (idx.exists()) idx.name + "=" + idx.length() + "B" else idx.name + "=无") + "]" +
            " 索引文件=" + names +
            " 消息文件=" + chatFiles.size + "个/" + chatFiles.sumOf { it.length() } + "B" +
            " 目录共" + files.size + "个文件"
    }

    fun loadIndex(): MutableList<SessionMeta> {
        val out = mutableListOf<SessionMeta>()
        val f = indexFile()
        // 主文件缺失也要看备份（R13）：旧代码在这里直接 return 空列表，把同目录里
        // 那份完好的 .bak 白白绕过 —— 用户看到「历史对话全没了」，内容其实可恢复。
        val raw = readMaybeMissing(f) ?: return out
        try {
            // 兼容两种外形：老版是裸数组 `[...]`，新版是 `{"schema":N,"sessions":[...]}`。
            // 解析辅助同时接受两者，老文件照常能读。
            fun parseArr(text: String): JSONArray {
                val t = text.trim()
                return if (t.startsWith("{")) {
                    val o = JSONObject(t)
                    // 记下这份索引自称的格式版本，供 saveIndex 判断能不能安全写回（F24）。
                    loadedIndexSchema = o.optInt("schema", 1)
                    o.optJSONArray("sessions") ?: JSONArray()
                } else {
                    loadedIndexSchema = 1
                    JSONArray(t)
                }
            }
            val arr = try {
                parseArr(raw)
            } catch (e: Exception) {
                // 主文件是半截 JSON（写盘被中断）：回退上一次的备份。
                val bak = File(dir, f.name + ".bak")
                if (bak.exists()) {
                    AppLog.err("store", "索引解析失败，回退备份 字节=" + f.length(), e)
                    parseArr(bak.readText())
                } else throw e
            }
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
                        order = o.optLong("order", 0L),
                    )
                )
            }
        } catch (e: Exception) {
            // 原来整段 runCatching 把解析异常静默吞掉、当成空索引返回——
            // 界面表现正是历史对话全部没了，而日志里一行线索都没有。
            // 必须留痕并带上文件大小：文件在但读不出 = 写盘被中断 / 被杀留下的半截 JSON。
            AppLog.err("store", "读索引失败（按空列表处理）profile=" + profile +
                " 文件=" + f.name + " 字节=" + f.length(), e)
        }
        return out
    }

    fun saveIndex(list: List<SessionMeta>) {
        // 列表变更的唯一落盘点：每次写盘记写了几条、几条归档。
        // 用户报历史对话没了时，靠这串记录能看出是哪一次操作把列表写空的。
        val archivedCount = list.count { it.archived }
        // 读到的是「更新版本写的」索引：说明写它的 App 比这台设备上的新（装过新包又降级）。
        // 此时按当前格式重写会把不认识的字段静默抹掉（F24），所以拒绝写回、保留原件 ——
        // 只读仍然能用；改标题/归档这类写操作在装上能理解该版本的 App 之前不会落盘。
        if (loadedIndexSchema > SCHEMA_INDEX) {
            AppLog.err("store", "索引版本 $loadedIndexSchema 高于本版 $SCHEMA_INDEX，拒绝写回以保住原件", null)
            return
        }
        try {
            val arr = JSONArray()
            for (s in list) {
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("updatedAt", s.updatedAt)
                        .put("archived", s.archived)
                        .put("order", s.order)
                )
            }
            // 加 schema 版本号：将来改字段格式时能判断来源版本，避免旧版读到新结构
            // 后把不认识的字段当默认值写回、造成静默丢字段。旧文件没这个键，读到 0/缺失
            // 一律按当前版本处理，向后兼容。
            val wrapper = JSONObject().put("schema", SCHEMA_INDEX).put("sessions", arr)
            writeAtomic(indexFile(), wrapper.toString())
            AppLog.log("store", "存索引 profile=" + profile + " 条数=" + list.size +
                " 已归档=" + archivedCount + " schema=" + SCHEMA_INDEX)
        } catch (e: Exception) {
            AppLog.err("store", "存索引失败 条数=" + list.size + " 已归档=" + archivedCount, e)
        }
    }

    fun loadMessages(id: String): List<Msg> {
        val out = mutableListOf<Msg>()
        runCatching {
            val f = msgFile(id)
            // 主文件缺失也要看备份（R13），理由同 loadIndex。
            val raw = readMaybeMissing(f) ?: return@runCatching
            val arr = try {
                JSONArray(raw)
            } catch (e: Exception) {
                val bak = File(dir, f.name + ".bak")
                if (bak.exists()) JSONArray(bak.readText()) else throw e
            }
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
                // 原始附件清单（有序，按发送顺序）：重发时用它拼出与首次一致的请求体。
                val attachments = mutableListOf<String>()
                o.optJSONArray("attachments")?.let { aa ->
                    for (k in 0 until aa.length()) aa.optString(k)?.takeIf { it.isNotEmpty() }?.let { attachments.add(it) }
                }
                // 只要还有正文 / 工具轨迹 / 图片 / 附件，这条就得留住。
                // 「只发了图、没打字」的消息正文是空的，按老条件（只查正文与轨迹）会被整条丢掉，
                // 重开 App 后那条图就凭空消失——附件消息必须按附件是否为空一起判。
                val approval = o.optJSONObject("approval")?.let { ao ->
                    val ch = mutableListOf<String>()
                    ao.optJSONArray("choices")?.let { ca ->
                        for (k in 0 until ca.length()) ca.optString(k)?.takeIf { it.isNotEmpty() }?.let { ch.add(it) }
                    }
                    ApprovalCard(ao.optString("requestId", ""), ao.optString("command", ""),
                        ao.optString("description", ""), ch, ao.optString("resolved", ""))
                }
                val clarify = o.optJSONObject("clarify")?.let { co ->
                    val ch = mutableListOf<String>()
                    co.optJSONArray("choices")?.let { ca ->
                        for (k in 0 until ca.length()) ca.optString(k)?.takeIf { it.isNotEmpty() }?.let { ch.add(it) }
                    }
                    ClarifyCard(co.optString("clarifyId", ""), co.optString("question", ""), ch,
                        co.optBoolean("multiSelect", false), co.optString("resolved", ""))
                }
                // token 用量 / 耗时：不落盘的话重开 App、切会话回来这三项就没了。
                val usage = o.optJSONObject("usage")?.let { uo ->
                    Usage(
                        input = uo.optInt("input", 0),
                        output = uo.optInt("output", 0),
                        total = uo.optInt("total", 0),
                        cacheRead = uo.optInt("cacheRead", 0),
                        cacheWrite = uo.optInt("cacheWrite", 0),
                        durationMs = uo.optLong("durationMs", 0L),
                    )
                }
                // 子任务进度也要读回：长任务派了子代理，跑一半重开 App，进度行不能丢。
                val subs = mutableListOf<SubagentLine>()
                o.optJSONArray("subagents")?.let { sa ->
                    for (k in 0 until sa.length()) {
                        val so = sa.optJSONObject(k) ?: continue
                        subs.add(SubagentLine(
                            id = so.optString("id", ""), goal = so.optString("goal", ""),
                            status = so.optString("status", ""), summary = so.optString("summary", ""),
                            childSessionId = so.optString("childSessionId", ""),
                            steps = so.optInt("steps", 0),
                            startedAt = so.optLong("startedAt", 0L),
                            seenAt = so.optLong("seenAt", 0L),
                            endedAt = so.optLong("endedAt", 0L),
                            tokens = so.optInt("tokens", 0),
                        ))
                    }
                }
                // 只要还有正文 / 工具轨迹 / 图片 / 附件 / 待办卡片 / 子任务进度，这条就得留住。
                // 待办卡片气泡的正文可能是空的（服务端还没产出内容），只查正文与轨迹的
                // 老条件会把整条丢掉——重开 App 那张等你点的卡片就没了。
                if (text.isEmpty() && trace.isEmpty() && imgs.isEmpty() && files.isEmpty() &&
                    approval == null && clarify == null && usage == null && subs.isEmpty()) continue
                // 投递状态：老消息没有这个键 → 保持 null（界面不显示角标，不报错）。
                val rc = o.optJSONObject("receipt")?.let { ro ->
                    val arts = mutableListOf<String>()
                    ro.optJSONArray("artifactIds")?.let { aa ->
                        for (k in 0 until aa.length()) aa.optString(k)?.takeIf { it.isNotEmpty() }?.let { arts.add(it) }
                    }
                    Receipt(
                        // 读回时把「只在当次运行里有意义」的两个状态归位，否则重启后会一直
                        // 转圈 / 一直显示「排队中」（F03）：
                        //   sending → uncertain：那个 POST 早就没了，收没收不知道 → 结果未知
                        //   queued  → not_sent ：内存里的待发队列重启即丢，它不会再自动发
                        status = restartSafeStatus(ro.optString("status", Receipt.ACCEPTED)),
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
                        attachments = attachments,
                        trace = trace,
                        receipt = rc,
                        quote = o.optString("quote", ""),
                        steer = o.optBoolean("steer", false),
                        approval = approval,
                        clarify = clarify,
                        usage = usage,
                        subagents = subs,
                        // 进行中气泡的计时起点：不读回来的话，重开 App 后实时耗时会从 0 重新算。
                        startedAt = o.optLong("startedAt", 0L),
                        runId = o.optString("runId", ""),
                    )
                )
            }
        }
        if (out.isEmpty()) {
            // 空结果只有两种：文件不在，或文件在但读不出（解析异常被吞了）。
            // 这两者修法完全不同，必须记清。
            val mf = msgFile(id)
            AppLog.log("store", "读消息为空 id=" + id.take(8) +
                " 文件存在=" + mf.exists() + " 字节=" + (if (mf.exists()) mf.length() else 0L))
        }
        return out
    }

    fun saveMessages(id: String, list: List<Msg>, max: Int = 300): Boolean {
        return runCatching {
            // pending 且正文空、轨迹空、又没有待办卡片的才是空壳可丢；
            // 带审批/澄清卡片的必须留下，否则重开 App 卡片就没了。
            val clean = list.filter {
                !(it.pending && it.text.isEmpty() && it.trace.isEmpty() &&
                    it.approval == null && it.clarify == null && it.subagents.isEmpty())
            }
            // 超上限直接裁到最近 max 条：本地只保留最近窗口（服务端存全量历史）。
            // 原来会把被裁掉的头部另存成归档文件，但归档查看入口已移除、无人再读，
            // 遂不再写这份死重（见 2.151：移除「更早 N 条已归档」功能）。
            val tail = if (clean.size > max) clean.takeLast(max) else clean
            val arr = JSONArray()
            for (m in tail) {
                val o = JSONObject().put("role", m.role).put("text", m.text).put("ts", m.ts)
                if (m.trace.isNotEmpty()) o.put("trace", m.trace)
                if (m.runId.isNotEmpty()) o.put("runId", m.runId)
                if (m.quote.isNotEmpty()) o.put("quote", m.quote)
                if (m.steer) o.put("steer", true)
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
                // 原始附件清单（有序）：重发时靠它拼出与首次一致的请求体（F07）。
                if (m.attachments.isNotEmpty()) {
                    val aa = JSONArray()
                    for (n in m.attachments) aa.put(n)
                    o.put("attachments", aa)
                }
                // 待办卡片（审批/澄清）落盘：重启后即使还没联网也能先把卡片挂回来，
                // 联网探测到 run 已结束再清掉（见 ChatViewModel.clearStaleCards）。
                m.approval?.let { c ->
                    val cj = JSONObject()
                        .put("requestId", c.requestId).put("command", c.command)
                        .put("description", c.description).put("resolved", c.resolved)
                    val ca = JSONArray(); for (s in c.choices) ca.put(s); cj.put("choices", ca)
                    o.put("approval", cj)
                }
                m.clarify?.let { c ->
                    val cj = JSONObject()
                        .put("clarifyId", c.clarifyId).put("question", c.question)
                        .put("multiSelect", c.multiSelect).put("resolved", c.resolved)
                    val ca = JSONArray(); for (s in c.choices) ca.put(s); cj.put("choices", ca)
                    o.put("clarify", cj)
                }
                // token 用量 / 耗时 / 速度也要落盘，否则重开 App 或切走再回来这三项就没了。
                m.usage?.let { u ->
                    val uj = JSONObject()
                        .put("input", u.input).put("output", u.output).put("total", u.total)
                        .put("cacheRead", u.cacheRead).put("cacheWrite", u.cacheWrite)
                        .put("durationMs", u.durationMs)
                    o.put("usage", uj)
                }
                // 子任务进度落盘：长任务跑一半重开 App，进度行要能读回来。
                if (m.subagents.isNotEmpty()) {
                    val sa = JSONArray()
                    for (s in m.subagents) {
                        sa.put(JSONObject().put("id", s.id).put("goal", s.goal)
                            .put("status", s.status).put("summary", s.summary)
                            .put("childSessionId", s.childSessionId).put("steps", s.steps)
                            .put("startedAt", s.startedAt).put("seenAt", s.seenAt)
                            .put("endedAt", s.endedAt).put("tokens", s.tokens))
                    }
                    o.put("subagents", sa)
                }
                // 进行中气泡的计时起点也要落盘，否则 App 退出重进后计时从 0 重新开始。
                if (m.startedAt > 0) o.put("startedAt", m.startedAt)
                // 投递状态**一律**落盘，含 sending / queued（F03）。
                // 旧实现故意过滤掉这两个状态，理由是「重启后那个 POST 已经没了，留着会一直转圈」；
                // 代价是重启后那条消息的回执、幂等键、附件清单全丢——用户再也认不出
                // 「这条到底发出去没有」，也没法安全重发。现在改为**落盘 + 读回时归位**
                // （restartSafeStatus：sending→uncertain、queued→not_sent），两者都不转圈、都带处置按钮。
                m.receipt?.let { rc ->
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
            writeAtomic(msgFile(id), arr.toString())
            if (clean.size > max) {
                AppLog.log("store", "会话消息超上限裁剪 id=" + id.take(8) +
                    " 原=" + clean.size + " 保留=" + tail.size)
            }
            true
        }.getOrElse {
            // 以前整段被 runCatching 静默吞掉：写盘失败一行日志都没有，
            // 表现是「消息看着发了、重开就没了」，却查不到任何线索。
            // 现在返回 Boolean：调用方据此决定「续接序号能不能推进」——
            // 写盘失败仍推进序号的话，重启会按它做 Last-Event-ID，把这段永远跳过。
            AppLog.err("store", "落盘会话消息失败 id=" + id.take(8) +
                " 条数=" + list.size + " 目标=" + msgFile(id).absolutePath, it)
            false
        }
    }

    /** 本地是否已有该会话的消息（非空）。清理「从服务端补进来、用户从没打开过」的空壳行要用。 */
    fun hasMessages(id: String): Boolean {
        val f = msgFile(id)
        if (!f.exists() || f.length() < 3L) return false
        return try {
            JSONArray(f.readText()).length() > 0
        } catch (e: Exception) {
            AppLog.err("store", "判断会话有无消息失败 id=" + id.take(8) + " 字节=" + f.length(), e)
            false
        }
    }

    fun deleteMessages(id: String) {
        val f = msgFile(id)
        val ok = runCatching { f.delete() }.getOrDefault(false)
        // 只有主文件真的删掉了才清备份。删失败（文件被占用 / 权限）时把 .bak 留着，
        // 下次还能靠它把内容捞回来——先删备份等于把唯一退路也断了。
        if (ok) {
            synchronized(AtomicStore.lock) {
                runCatching { File(f.parentFile, f.name + ".bak").delete() }
                runCatching { File(f.parentFile, f.name + ".tmp").delete() }
            }
        } else {
            AppLog.err("store", "删消息文件失败，保留备份 id=" + id.take(8), null)
        }
        // 清理旧版遗留的归档文件（v2.151 起不再产生归档，但老版本可能已经写过，
        // 删除会话时顺手把 `chat_<profile>_<id>.archive.json` 一并清掉，避免留孤儿文件）。
        // R19：主文件清了、它的 .bak/.tmp 也要一起清，否则「删掉的会话」在目录里
        // 仍留着可被读回的副本（探针实测 deleted-legacy-archive-backup=true）。
        runCatching {
            val arc = File(dir, "chat_${profile}_$id.archive.json")
            arc.delete()
            File(dir, arc.name + ".bak").delete()
            File(dir, arc.name + ".tmp").delete()
        }
        AppLog.log("store", "删消息文件 id=" + id.take(8) + " 删除=" + ok)
    }

    /**
     * 把一个会话导出成 Markdown 文本（只读本地记录，不碰网络、不改任何数据）。
     *
     * 工具轨迹默认用 <details> 折叠——手机上看着乱，但导出给同事时想留着还能展开。
     * 正文里的 data URL 图片换成一行占位说明（base64 塞进 md 没有意义、还容易撑爆文件）。
     */
    fun exportMarkdown(id: String, title: String): String {
        val msgs = loadMessages(id)
        val sb = StringBuilder()
        sb.append("# ").append(title.ifBlank { "对话" }).append("\n\n")
        sb.append("- 会话 ID：`").append(id).append("`\n")
        sb.append("- 导出时间：").append(TimeFmt.mdhm(System.currentTimeMillis())).append("\n")
        sb.append("- 消息条数：").append(msgs.size).append("\n\n---\n\n")
        for (m in msgs) {
            val who = if (m.role == "user") "我" else "助手"
            sb.append("**").append(who).append("**")
            if (m.ts > 0) sb.append(" · ").append(TimeFmt.mdhm(m.ts))
            sb.append("\n\n")
            val body = stripDataUrls(m.text)
            if (body.isNotBlank()) sb.append(body.trim()).append("\n\n")
            if (m.images.isNotEmpty() && body.isBlank()) sb.append("（图片 ×").append(m.images.size).append("）\n\n")
            if (m.files.isNotEmpty()) {
                sb.append("附件：")
                sb.append(m.files.joinToString("、"))
                sb.append("\n\n")
            }
            if (m.trace.isNotBlank()) {
                sb.append("<details><summary>工具轨迹</summary>\n\n```\n")
                sb.append(m.trace.trim()).append("\n```\n\n</details>\n\n")
            }
            sb.append("---\n\n")
        }
        return sb.toString()
    }

    /** 去掉正文里的 data URL（图片/附件内联体），换成人能读的占位。 */
    private fun stripDataUrls(s: String): String {
        if (s.isEmpty() || !s.contains("data:")) return s
        return s.replace(Regex("!\\[[^\\]]*\\]\\(data:image/[^)]+\\)"), "（图片）")
            // 音频附件在 App 里是播放按钮，导出的 md 里标成「（语音）」而不是笼统的「（附件）」。
            .replace(
                Regex("\\[[^\\]]*\\.(?:mp3|m4a|aac|wav|ogg|opus)\\]\\(data:[^)]+\\)", RegexOption.IGNORE_CASE),
                "（语音）")
            .replace(Regex("\\[[^\\]]*\\]\\(data:[^)]+\\)"), "（附件）")
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
            val meta = SessionMeta(id, "历史对话", System.currentTimeMillis(), false)
            saveIndex(listOf(meta))
            // 只有确认索引真的写盘了，才删老格式文件。
            // 以前是先删后写索引：saveIndex 静默失败时，老文件已经没了、索引也没建，
            // 这段历史就永久丢失。
            if (indexFile().exists()) {
                f.delete()
            } else {
                AppLog.err("store", "迁移：索引未落盘，保留老文件", null)
            }
            return meta
        }
        return null
    }
}

/**
 * 读盘时把「只在当次运行里有意义」的投递状态归位（F03）——纯函数，便于单测。
 *   sending → uncertain：那个 POST 早已结束，服务端收没收不知道 → 结果未知，交用户处置
 *   queued  → not_sent ：内存里的待发队列重启即丢，这条不会再自动发出
 * 其余状态原样返回。这样重启后既不会一直转圈，也不会显示一句做不到的「排队中」。
 */
internal fun restartSafeStatus(s: String): String = when (s) {
    Receipt.SENDING -> Receipt.UNCERTAIN
    Receipt.QUEUED -> Receipt.NOT_SENT
    else -> s
}