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
 *
 * 图标（2026-10-15，随 2.210 改）：前缀原本照抄服务端原文的 👁️。用户嫌它难看，而且它是**彩色
 * emoji**：自带配色，不跟主题的 accent 色走，三套配色（夜间／浅色／护眼）里都不受控。
 * 换成 [ICON] = `⧫`（U+29EB，小黑菱）—— 走正文字体、跟随 accent 色，形状比 ◆ 轻、不掉视觉重量。
 * 以后换图标：改 [ICON] 一行 + 单测期望值即可（文案散落处已全部改用它）。
 */
object RecallNote {

    /**
     * 召回提示行的前缀图标。
     *
     * 必须挑**正文字体**里的几何符号，别用彩色 emoji（✦ / ⚑ / ⚙ 这类在安卓上落到 emoji 字体，
     * 变成彩色字形、不吃主题色）。U+29EB 在 Noto Sans Symbols 里，三套配色下都是单色。
     */
    const val ICON = "⧫"

    private val COUNT = Regex("recalled\\s+(\\d+)\\s+memor", RegexOption.IGNORE_CASE)

    /** 召回指示 → 中文提示；不是召回指示就返回 null。 */
    fun line(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val n = COUNT.find(t)?.groupValues?.get(1)?.toIntOrNull()
        if (n != null && n >= 0) {
            return if (n == 1) "$ICON 本轮召回 1 条记忆" else "$ICON 本轮召回 $n 条记忆"
        }
        if (t.contains("recalled relevant memory", ignoreCase = true)) {
            return "$ICON 本轮召回了相关记忆（条数不详）"
        }
        return null
    }

    /** 这条状态是不是召回指示（语义同 `line(raw) != null`）。 */
    fun isRecall(raw: String): Boolean = line(raw) != null
}
