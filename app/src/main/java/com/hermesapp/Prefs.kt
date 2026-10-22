package com.hermesapp

import android.content.Context

class Prefs private constructor(private val ctx: Context, private val boundIdentity: IdentityDomain?) {
    constructor(ctx: Context) : this(ctx, null)
    private val global = ctx.getSharedPreferences("hermes_app", Context.MODE_PRIVATE)
    private val sp get() = ctx.getSharedPreferences("identity_" + identity.key, Context.MODE_PRIVATE)
    internal val identity: IdentityDomain get() = boundIdentity ?: IdentityDomain(
        serverUrl, profile, credential(profile).substringBefore(':'))
    internal val legacyServer: String get() {
        val owner = global.getString("legacy_account:" + identity.profile, "").orEmpty()
        return if (owner.isNotEmpty() && owner == identity.account)
            global.getString("legacy_server", "").orEmpty() else ""
    }
    internal fun bound(): Prefs = Prefs(ctx, identity)

    init {
        synchronized(Prefs::class.java) {
            if (!global.contains("legacy_server")) {
                val original = global.getString("server_url", "").orEmpty()

                // Legacy credentials and conversation state belong only to the original server.
                for (p in listOf("default", "friend")) {
                    val credential = global.getString("credential:" + p, "").orEmpty()
                    if (original.isNotBlank() && credential.isNotBlank()) {
                        global.edit().putString(credentialKey(original, p), credential)
                            .putString("legacy_account:" + p, credential.substringBefore(':')).commit()
                        val id = IdentityDomain(original, p, credential.substringBefore(':'))
                        val dest = ctx.getSharedPreferences("identity_" + id.key, Context.MODE_PRIVATE)
                        val editor = dest.edit()
                        for ((k, v) in global.all) if (p == global.getString("profile", "friend") && (isConversationContentPrefKey(k) || k in listOf(
                            "session_id", "shell_cleanup_done", "notified_reports", "pending_open_session"))) {
                            when (v) {
                                is String -> editor.putString(k, v)
                                is Int -> editor.putInt(k, v)
                                is Boolean -> editor.putBoolean(k, v)
                            }
                        }
                        editor.commit()
                    }
                }
                global.edit().remove("credential:default").remove("credential:friend")
                    .putString("legacy_server", original).commit()
            }
        }
    }

    private fun credentialKey(server: String, p: String) =
        "credential:v2:" + domainHash(canonicalServer(server)) + ":" + p

    var loggedIn: Boolean
        get() = global.getBoolean("logged_in", false)
        set(v) { global.edit().putBoolean("logged_in", v).apply() }

    var profile: String
        get() = boundIdentity?.profile ?: global.getString("profile", "friend") ?: "friend"
        set(v) { global.edit().putString("profile", v).apply() }

    var serverUrl: String
        get() = boundIdentity?.server ?: global.getString("server_url", "") ?: ""
        set(v) { global.edit().putString("server_url", v).apply() }

    var sessionId: String?
        get() = sp.getString("session_id", null)
        set(v) { sp.edit().putString("session_id", v).apply() }

    /**
     * 某个身份的登录凭据（"账号:密码"），空串 = 还没登录过这个身份（R20）。
     *
     * 为什么要按身份分开存：账号决定身份（ghzy→本人、jiacvn→朋友），两个身份各有各的
     * 凭据；已登录过的身份切过去就免输。凭据只存在手机本地的 SharedPreferences 里，
     * APK 包里不再编入任何密钥。
     */
    fun credential(profile: String): String {
        if (profile.isEmpty()) return ""
        return global.getString(credentialKey(boundIdentity?.server ?: serverUrl, profile), "") ?: ""
    }

    fun setCredential(profile: String, value: String) {
        if (profile.isEmpty()) return
        global.edit().putString(credentialKey(serverUrl, profile), value).commit()
    }

    /** 退出登录：清掉所有身份的凭据（下次要用得重新输账号密码）。 */
    fun clearCredentials() {
        val prefix = "credential:v2:" + domainHash(canonicalServer(serverUrl)) + ":"
        global.edit().apply {
            for (key in global.all.keys) if (key.startsWith(prefix)) remove(key)
        }.commit()
        loggedIn = false
    }

    /** 主题：system / day / night，默认跟随系统 */
    var themeMode: String
        get() = global.getString("theme_mode", "system") ?: "system"
        set(v) { global.edit().putString("theme_mode", v).apply() }

    /**
     * 对话输入框草稿：按会话隔离存（键 draft_input:<sessionId>）。
     * 以前是全局一个键，导致「在 A 会话输入没发出去、切到 B 会话内容还在」——
     * 草稿必须跟着会话走，切会话各显示各的。
     */
    fun draftFor(sessionId: String): String {
        if (sessionId.isEmpty()) return ""
        return sp.getString("draft_input:" + sessionId, "") ?: ""
    }

    fun setDraft(sessionId: String, v: String) {
        if (sessionId.isEmpty()) return
        if (v.isEmpty()) sp.edit().remove("draft_input:" + sessionId).apply()
        else sp.edit().putString("draft_input:" + sessionId, v).apply()
    }

    /** 删除会话时一并清掉它的草稿。 */
    fun clearDraft(sessionId: String) {
        if (sessionId.isEmpty()) return
        sp.edit().remove("draft_input:" + sessionId).apply()
    }

    /** 通知栏直接回复暂存："sessionId\u0000文本"；App 起来后由 ChatViewModel 取走发送。 */
    var pendingReply: String
        get() = sp.getString("pending_reply", "") ?: ""
        set(v) { sp.edit().putString("pending_reply", v).apply() }

    /**
     * 上面那条回复是**哪个身份**写的（F18）。
     *
     * 暂存本身只有会话 id 与文本，不带身份；切到另一个身份后旧回复仍会被当作当前身份的
     * 消息发出去。存下写入时的身份，取出时对不上就丢弃。空串 = 升级前写的旧数据。
     */
    var pendingReplyProfile: String
        get() = sp.getString("pending_reply_profile", "") ?: ""
        set(v) { sp.edit().putString("pending_reply_profile", v).apply() }

    /**
     * 正在跑的 run_id：按 sessionId 存（多会话可同时跑）。
     * 进程被杀后重开，用它逐个确认任务是否还在执行（决定按钮显示发送还是停止）。
     * 键形如 "run:<sessionId>"。
     */
    fun activeRunsMap(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in sp.all) {
            if (k.startsWith("run:") && v is String && v.isNotEmpty()) {
                out[k.removePrefix("run:")] = v
            }
        }
        return out
    }

    fun putActiveRun(sessionId: String, runId: String) {
        if (sessionId.isEmpty() || runId.isEmpty()) return
        sp.edit().putString("run:" + sessionId, runId).apply()
    }

    /**
     * 待补推的改名：本地已经改好，但服务端还没有这个会话的行 —— 手机上新建、还没发过
     * 消息的会话，在服务端要等第一轮 `/v1/runs` 才建行，直接 PATCH 会 404。
     * 键形如 "ptitle:<sessionId>"；会话在服务端出现后由 syncFromServer 补推并清掉。
     * 语义是「这次的改名一定会到达服务端」，不是「尽力而为后忘掉」。
     */
    fun pendingTitles(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in sp.all) {
            if (k.startsWith("ptitle:") && v is String && v.isNotEmpty()) {
                out[k.removePrefix("ptitle:")] = v
            }
        }
        return out
    }

    fun putPendingTitle(sessionId: String, title: String) {
        if (sessionId.isEmpty()) return
        if (title.isEmpty()) sp.edit().remove("ptitle:" + sessionId).apply()
        else sp.edit().putString("ptitle:" + sessionId, title).apply()
    }

    fun removePendingTitle(sessionId: String) {
        if (sessionId.isEmpty()) return
        sp.edit().remove("ptitle:" + sessionId).apply()
    }

    /**
     * 已收到的最后一个事件序号（SSE 的 id / seq），与消息一起落盘。
     * 重开 App 恢复任务时用它做 Last-Event-ID，只补断线之后的事件；不存的话续接
     * 只能从 0 全量重放，工具轨迹（trace）会被二次追加——表现就是「重开后过程重复显示」。
     */
    fun putLastSeq(sessionId: String, seq: Int) {
        if (sessionId.isEmpty()) return
        sp.edit().putInt("seq:" + sessionId, seq).apply()
    }

    fun lastSeq(sessionId: String): Int {
        if (sessionId.isEmpty()) return -1
        return sp.getInt("seq:" + sessionId, -1)
    }

    /** 清活跃标记时连续接序号一起清——run 结束后留着会污染下一轮。 */
    fun removeActiveRun(sessionId: String) {
        if (sessionId.isEmpty()) return
        sp.edit().remove("run:" + sessionId).remove("seq:" + sessionId).apply()
    }

    /**
     * 一键清空全部对话时，把「属于对话内容/运行时状态」的 prefs 键扫干净：
     * 草稿 draft_input: 、活跃 run: 、续接序号 seq: 、待补推标题 ptitle: ，以及
     * 通知栏直接回复的暂存 pending_reply（里面是用户打的一段话，还指着某个会话 id）。
     *
     * 按 key 前缀整表扫，而不是按传进来的 id 列表删：索引里已经丢行的会话（崩溃、降级、
     * 旧版写坏留下的）它那几条键还在 prefs 里，只按 ids 删会留下这批无人回收的垃圾，
     * 而这些键的内容正是「对话内容/草稿」——用户要求的是清空，不是留着。
     * 凭据 credential: 、主题、服务器地址等身份级配置不在此列，不能误伤（见 isConversationContentPrefKey）。
     */
    fun clearAllSessionState(): Int {
        var n = 0
        sp.edit().apply {
            for (k in sp.all.keys) {
                if (isConversationContentPrefKey(k)) {
                    remove(k); n++
                }
            }
        }.apply()
        return n
    }

    /**
     * 待跳转的会话：审批/澄清通知被点开时写入，App 起来后切到该会话并清空。
     * 走落盘而不是 Intent extra——App 可能已被系统杀掉，单靠 extra 会在冷启动时丢。
     */
    var pendingOpenSession: String
        get() = sp.getString("pending_open_session", "") ?: ""
        set(v) { sp.edit().putString("pending_open_session", v).apply() }

    /** 模型不支持视觉时：true=自动转文字，false=每次都问用户。 */
    var visionAutoText: Boolean
        get() = global.getBoolean("vision_auto_text", true)
        set(v) { global.edit().putBoolean("vision_auto_text", v).apply() }

    /**
     * 后台运行（任务期间起前台服务保持 SSE 连接）。
     * 开启时必有一条最小化常驻通知（Android 强制）；关闭则不起服务、彻底无通知，
     * 任务仍在服务端跑，重开 App 会重新拉取结果。
     */
    var keepAlive: Boolean
        get() = global.getBoolean("keep_alive", true)
        set(v) { global.edit().putBoolean("keep_alive", v).apply() }

    /**
     * 「其它会话完成也提醒」：App 在前台、但你正看着别的会话时，那边跑完也弹系统通知。
     * 要靠一条活连接才能观察到别的会话收尾，所以打开它意味着同时启用后台运行。
     */
    var notifySessionCompletions: Boolean
        get() = global.getBoolean("notify_session_completions", false)
        set(v) { global.edit().putBoolean("notify_session_completions", v).apply() }

    /**
     * 完成语音播报：任务跑完时把服务端合成的整段语音自动播一遍。
     * 默认开（装完即响，不用去设置页点开关）；开关只影响「要不要播」，语音文件本来就随回复下发。
     */
    var playCompletionVoice: Boolean
        get() = global.getBoolean("play_completion_voice", true)
        set(v) { global.edit().putBoolean("play_completion_voice", v).apply() }

    /**
     * 完成语音播报的语速（1.0 = 正常）。设置页给几档平铺按钮调；
     * 播放时由 MediaPlayer 的 PlaybackParams.setSpeed 应用，不影响语音文件本身。
     */
    var voiceRate: Float
        get() = global.getFloat("voice_rate", 1.0f)
        set(v) { global.edit().putFloat("voice_rate", v).apply() }

    /** 空壳会话一次性清理是否已执行（2026-10-07 下线「拉取」时加）。 */
    var shellCleanupDone: Boolean
        get() = sp.getBoolean("shell_cleanup_done", false)
        set(v) { sp.edit().putBoolean("shell_cleanup_done", v).apply() }

    /**
     * 已经弹过系统通知的收件箱条目 id（逗号分隔，只留最近 100 个）。
     *
     * 为什么要落盘：这个去重集合原来只在内存里，App 一重启就空了——用户不进定时任务页
     * 把条目点成已读的话，每次重启都会把同一条未读再响一遍。通知的语义是「提醒一次」，
     * 不是「每次开机提醒」，所以去重必须跨重启。
     * 注意：弹过通知 ≠ 已读，未读标记仍留着，进定时任务页照样能看见红点。
     */
    fun notifiedReportIds(): Set<String> {
        val raw = sp.getString("notified_reports", "") ?: ""
        if (raw.isEmpty()) return emptySet()
        return raw.split(',').filter { it.isNotEmpty() }.toSet()
    }

    fun markReportNotified(id: String) {
        if (id.isEmpty()) return
        val list = (sp.getString("notified_reports", "") ?: "")
            .split(',').filter { it.isNotEmpty() }.toMutableList()
        if (list.contains(id)) return
        list.add(id)
        while (list.size > 100) list.removeAt(0)
        sp.edit().putString("notified_reports", list.joinToString(",")).apply()
    }
}

/**
 * 「清空全部对话」该扫掉哪些 prefs 键——纯函数，便于单测钉住规则（不依赖设备）。
 *
 * 命中「对话内容/运行时状态」：
 *   draft_input:<sid> 输入框草稿（可能就是用户没发出去的一段话）
 *   run:<sid>         活跃运行标记
 *   seq:<sid>         续接序号
 *   ptitle:<sid>      服务端还没回报时的待补推标题
 *   pending_reply / pending_reply_profile 通知栏直接回复的暂存（sessionId\0文本 + 写入身份）：
 *                     里面是一段用户打的字，且指着某个会话 id；清空后留着，App 下次起来
 *                     会拿它往一个本地已不存在的会话发消息
 *
 * 不命中：credential:<profile>（登录凭据）、logged_in、theme_mode/server_url/keep_alive/
 * 语音/通知等设置项、profile/system 之类的身份指针。
 * 清对话连坐凭据 = 把用户踢下线，属于事故，所以这里用白名单而不是「见会话 id 就删」。
 */
internal fun isConversationContentPrefKey(k: String): Boolean =
    k.startsWith("draft_input:") || k.startsWith("run:") ||
        k.startsWith("seq:") || k.startsWith("ptitle:") ||
        k == "pending_reply" || k == "pending_reply_profile"
