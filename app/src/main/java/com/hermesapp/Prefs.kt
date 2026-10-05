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
}
