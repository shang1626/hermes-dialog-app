package com.hermesapp

/**
 * 把服务端 `hermes.status` 事件里的记忆召回指示压成一句中文提示。
 *
 * 背景（2026-10-14）：服务端每轮都会发一条 lifecycle 状态 `👁️ Hindsight — recalled 12 memories`
 * （journald 里逐轮可见），但 App 走的 `/v1/runs` 那条 SSE 流原先根本没接 status 回调 ——
 * 服务端补丁 `apply_run_status_stream_patch` 才把它转发进来。用户的要求是
 * 「每轮开始都要有召回多少条记忆的显式」：这里只做**解析与措辞**，落点（挂哪个气泡、
 * 怎么落盘）在 ChatViewModel / SessionStore / Screens。
 *
 * 原文形态（服务端 `agent/memory_manager.py::describe_recall`，多库时用两个空格拼接）：
 *   recalled 1 memory / recalled 12 memories / recalled relevant memory（reflect 无离散计数）
 * 非召回类状态（上游等待、自动恢复倒计时、模型切换）一律返回 null —— 它们不该被当成召回量显示。
 */
object RecallNote {

    private val COUNT = Regex("recalled\\s+(\\d+)\\s+memor", RegexOption.IGNORE_CASE)

    /** 召回指示 → 中文提示；不是召回指示就返回 null。 */
    fun line(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val n = COUNT.find(t)?.groupValues?.get(1)?.toIntOrNull()
        if (n != null && n >= 0) {
            return if (n == 1) "👁️ 本轮召回 1 条记忆" else "👁️ 本轮召回 $n 条记忆"
        }
        if (t.contains("recalled relevant memory", ignoreCase = true)) {
            return "👁️ 本轮召回了相关记忆（条数不详）"
        }
        return null
    }

    /** 这条状态是不是召回指示（语义同 `line(raw) != null`）。 */
    fun isRecall(raw: String): Boolean = line(raw) != null
}
