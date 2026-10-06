package com.hermesapp

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("hermes_app", Context.MODE_PRIVATE)

    var loggedIn: Boolean
        get() = sp.getBoolean("logged_in", false)
        set(v) { sp.edit().putBoolean("logged_in", v).apply() }

    var profile: String
        get() = sp.getString("profile", "friend") ?: "friend"
        set(v) { sp.edit().putString("profile", v).apply() }

    var serverUrl: String
        get() = sp.getString("server_url", "") ?: ""
        set(v) { sp.edit().putString("server_url", v).apply() }

    var sessionId: String?
        get() = sp.getString("session_id", null)
        set(v) { sp.edit().putString("session_id", v).apply() }

    /** 主题：system / day / night，默认跟随系统 */
    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) { sp.edit().putString("theme_mode", v).apply() }

    /** 对话输入框草稿：进程被系统杀掉后重进也能恢复已输入内容。 */
    var draftInput: String
        get() = sp.getString("draft_input", "") ?: ""
        set(v) { sp.edit().putString("draft_input", v).apply() }

    /** 通知栏直接回复暂存："sessionId\u0000文本"；App 起来后由 ChatViewModel 取走发送。 */
    var pendingReply: String
        get() = sp.getString("pending_reply", "") ?: ""
        set(v) { sp.edit().putString("pending_reply", v).apply() }

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

    fun removeActiveRun(sessionId: String) {
        if (sessionId.isEmpty()) return
        sp.edit().remove("run:" + sessionId).apply()
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
        get() = sp.getBoolean("vision_auto_text", true)
        set(v) { sp.edit().putBoolean("vision_auto_text", v).apply() }

    /**
     * 后台运行（任务期间起前台服务保持 SSE 连接）。
     * 开启时必有一条最小化常驻通知（Android 强制）；关闭则不起服务、彻底无通知，
     * 任务仍在服务端跑，重开 App 会重新拉取结果。
     */
    var keepAlive: Boolean
        get() = sp.getBoolean("keep_alive", true)
        set(v) { sp.edit().putBoolean("keep_alive", v).apply() }
}
