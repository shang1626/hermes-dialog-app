package com.hermesapp

/**
 * 工具名中文化（过程轨迹里显示用）。
 *
 * 服务端下发的 `tool` 字段是内部标识（terminal / patch / read_file …），
 * 直接铺给用户看是英文标识符，读不出「这一步在干什么」。这里只做**显示层**映射：
 * 回执、日志、落盘的 trace 一律保留原始标识，别反过来改数据。
 *
 * 未收录的工具名原样返回（插件/自定义工具随时可能新增，不能空、不能崩）；
 * `mcp__<server>__<tool>` 这类外部工具取末段展示，前面加「外部·」。
 */
private val TOOL_ZH: Map<String, String> = mapOf(
    // 执行与文件
    "terminal" to "执行命令",
    "process_manage" to "管理进程",
    "execute_code" to "运行代码",
    "patch" to "改代码",
    "write_file" to "写文件",
    "read_file" to "读文件",
    "search_files" to "搜文件",
    // 网络与浏览器
    "web_search" to "搜网页",
    "web_extract" to "读网页",
    "browser_exec" to "操作浏览器",
    "browser_vault" to "取账号密码",
    "blocked_page_recovery" to "绕反爬",
    // 协作与子任务
    "delegate_task" to "派子任务",
    "todo_list" to "看待办",
    // 技能与记忆
    "skill_view" to "看技能",
    "skill_manage" to "改技能",
    "skills_list" to "列技能",
    "memory" to "写记忆",
    "hindsight_recall" to "查记忆",
    "hindsight_retain" to "存记忆",
    "hindsight_reflect" to "梳理记忆",
    "session_search" to "查旧会话",
    // 媒体与视觉
    "vision_analyze" to "看图片",
    "image_generate" to "生成图片",
    // 平台与工具自身
    "cronjob_manage" to "管定时任务",
    "manage_connections" to "管应用连接",
    "tool_search" to "找工具",
    "tool_describe" to "看工具说明",
    "tool_call" to "调工具",
)

/** 工具标识 → 中文显示名（未收录的保持原样）。 */
internal fun toolNameZh(name: String): String {
    val n = name.trim()
    if (n.isEmpty()) return n
    TOOL_ZH[n]?.let { return it }
    if (n.startsWith("mcp__")) {
        val last = n.split("__").lastOrNull().orEmpty().ifEmpty { n }
        return "外部·" + last
    }
    return n
}

/**
 * 过程行耗时格式化。
 *
 * - `sec < 0`（服务端没给、本地也没计到）→ 空串，**绝不显示 0.0s**；
 * - 极快（<0.1s）显示 `<0.1s`，别把 0.04s 写成 0.0s 让人以为计时坏了；
 * - 1 秒以下保留两位小数（0.35s），10 秒以内一位，再长取整（65s）。
 */
internal fun fmtToolDuration(sec: Double): String {
    if (sec.isNaN() || sec < 0.0) return ""
    return when {
        sec < 0.1 -> "<0.1s"
        sec < 1.0 -> String.format("%.2f", sec) + "s"
        sec < 10.0 -> String.format("%.1f", sec) + "s"
        else -> String.format("%.0f", sec) + "s"
    }
}
