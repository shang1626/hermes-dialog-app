package com.hermesapp

import org.json.JSONObject

/**
 * 定时任务的中文翻译表 + 纯格式化工具（阶段4 从 ChatViewModel 拆出）。
 * 无状态、只依赖参数，同包调用点零改动。
 */

internal fun jobZhName(name: String): String {
    when (name) {
        "nightly-memory-refactor" -> return "夜间记忆整理"
        "browser-idle-reaper" -> return "浏览器空闲回收"
        "mem0-watchdog" -> return "记忆库看门狗"
        "boot-verify-report" -> return "开机自检报告"
        "fix-dup-unit-report" -> return "重复服务修复报告"
        "boot-verify2-report" -> return "开机自检报告（二）"
        "mem0-upgrade-postcheck" -> return "记忆库升级检查"
        "gradle-idle-reaper" -> return "编译进程空闲回收"
        "apk-keep-30" -> return "安装包只留 30 个"
        "friend-nightly-memory-refactor" -> return "夜间记忆整理"
        "ds-upstream-watch" -> return "上游巡检（DeepSeek）"
    }
    // 精确表认不出时按关键词兜底：别的档案（friend）和以后新加的任务都能自动出中文，
    // 不用每加一个任务改一次代码。顺序有讲究：watchdog 必须排在 watch 前面。
    val n = name.lowercase()
    return when {
        n.contains("memory-refactor") || n.contains("memory_refactor") -> "记忆整理"
        n.contains("watchdog") -> "看门狗"
        n.contains("upstream") -> "上游巡检"
        n.contains("watch") -> "上游巡检"
        n.contains("reaper") -> "空闲回收"
        n.contains("apk") || n.contains("keep") -> "安装包清理"
        n.contains("postcheck") -> "升级检查"
        n.contains("backup") -> "备份"
        n.contains("report") -> "结果报告"
        n.contains("verify") || n.contains("check") -> "自检"
        else -> ""
    }
}

/** 这个任务是干什么的；不认识的返回空串。 */
internal fun jobZhNote(name: String): String {
    when (name) {
        "nightly-memory-refactor" ->
            return "每天凌晨自动整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
        "browser-idle-reaper" ->
            return "每 15 分钟收掉闲置的浏览器进程，回收内存；没有闲置进程时静默。"
        "mem0-watchdog" ->
            return "每 15 分钟检查记忆库：服务掉了就拉起，网关记忆后端初始化失败就重启网关。健康时静默。"
        "boot-verify-report" ->
            return "一次性任务：容器重启后把开机自检结果发给你，跑完自动删。"
        "fix-dup-unit-report" ->
            return "一次性任务：修复重复网关服务后把结果发给你，跑完自动删。"
        "boot-verify2-report" ->
            return "一次性任务：容器重启后的自检（含重启次数与重复服务检查），跑完自动删。"
        "mem0-upgrade-postcheck" ->
            return "一次性任务：记忆库升级后的检查报告，跑完自动删。"
        "gradle-idle-reaper" ->
            return "每 15 分钟收掉空闲的编译进程，回收内存；正在编译时不动。"
        "apk-keep-30" ->
            return "每 15 分钟清一次安装包：只保留最近 30 个，防止旧版本堆满磁盘。"
        "friend-nightly-memory-refactor" ->
            return "每天凌晨自动整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
        "ds-upstream-watch" ->
            return "每天巡检 DeepSeek 上游的状态变化，有变化才出报告；无变化时静默。"
    }
    val n = name.lowercase()
    return when {
        n.contains("memory-refactor") || n.contains("memory_refactor") ->
            "定时整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
        n.contains("watchdog") ->
            "定期检查记忆库：服务掉了就拉起，网关记忆后端初始化失败就重启网关。健康时静默。"
        n.contains("watch") ->
            "定期巡检上游页面的变化，有变化才出报告；无变化时静默。"
        n.contains("reaper") ->
            "定期收掉闲置的进程，回收内存；没有闲置时静默。"
        n.contains("apk") || n.contains("keep") ->
            "定期清理安装包，只保留最近 30 个，防止旧版本堆满磁盘。"
        n.contains("postcheck") ->
            "一次性任务：升级后的检查报告，跑完自动删。"
        n.contains("backup") ->
            "定时备份数据。"
        n.contains("report") ->
            "一次性任务：把结果发给你，跑完自动删。"
        n.contains("verify") || n.contains("check") ->
            "定时自检，结果有异常才提醒。"
        else -> ""
    }
}

/** 运行状态翻译。 */
internal fun jobZhState(s: String): String = when (s) {
    "scheduled" -> "已排期"
    "running" -> "执行中"
    "completed" -> "已完成"
    "paused" -> "已暂停"
    "failed" -> "失败"
    else -> s
}

/** 上次运行结果翻译。 */
internal fun jobZhStatus(s: String): String = when (s) {
    "ok" -> "正常"
    "failed", "error" -> "失败"
    else -> s
}

/** 执行记录状态翻译（latest_execution.status）。 */
internal fun jobZhExecStatus(s: String): String = when (s) {
    "claimed" -> "已排入队列"
    "running" -> "执行中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "unknown" -> "状态未知"
    else -> s
}

/**
 * 执行耗时文案：优先 finished-claimed 的墙钟差，跑着就 started-claimed。
 * 时间戳是带时区的 ISO 串，用 OffsetDateTime 解析再相减；解析失败返回空串。
 */
internal fun execDurationText(ex: JSONObject?): String {
    val e = ex ?: return ""
    fun ts(k: String): java.time.OffsetDateTime? = runCatching {
        val v = e.optString(k, "")
        if (v.isEmpty()) null else java.time.OffsetDateTime.parse(v)
    }.getOrNull()
    val claimed = ts("claimed_at") ?: return ""
    val end = ts("finished_at") ?: ts("started_at") ?: return ""
    val ms = java.time.Duration.between(claimed, end).toMillis()
    if (ms < 0) return ""
    return when {
        ms < 1000 -> "不到 1 秒"
        ms < 60_000 -> String.format("%.1f 秒", ms / 1000.0)
        else -> String.format("%.1f 分", ms / 60_000.0)
    }
}

/** 排期翻译：interval / cron / once 三类分别转中文。 */
internal fun jobZhSchedule(sch: JSONObject?): String {
    if (sch == null) return ""
    return when (sch.optString("kind", "")) {
        "interval" -> {
            val m = sch.optInt("minutes", 0)
            when {
                m <= 0 -> "定时"
                m % 60 == 0 -> "每 " + (m / 60) + " 小时"
                else -> "每 " + m + " 分钟"
            }
        }
        // 优先按 cron 表达式翻（服务端 display 常是「every day at 9am」这种英文，
        // 靠它翻不出来）；表达式翻不出再回落 display。
        "cron" -> cronZh(sch.optString("expr", "")).ifEmpty { sch.optString("display", "") }
        "once" -> "一次性"
        else -> sch.optString("display", "")
    }
}

/** 5 段 cron 转中文；认不出就原样返回。 */
internal fun cronZh(expr: String): String {
    val p = expr.trim().split(Regex("\\s+"))
    if (p.size != 5) return expr
    val mi = p[0]
    val ho = p[1]
    val dom = p[2]
    val mo = p[3]
    val dow = p[4]
    val everyMin = Regex("^\\*/(\\d+)$").find(mi)
    if (everyMin != null && ho == "*" && dom == "*" && mo == "*" && dow == "*") {
        return "每 " + everyMin.groupValues[1] + " 分钟"
    }
    if (mi == "*" && ho == "*") return "每分钟"
    val m = mi.toIntOrNull()
    val h = ho.toIntOrNull()
    if (m != null && h != null) {
        val hm = String.format("%02d:%02d", h, m)
        return when {
            dom == "*" && mo == "*" && dow == "*" -> "每天 " + hm
            dom == "*" && mo == "*" && dow == "1-5" -> "工作日 " + hm
            dom == "*" && mo == "*" -> "每周 " + hm
            else -> "每月 " + dom + " 日 " + hm
        }
    }
    return expr
}

/**
 * 取字符串字段，把「JSON 空值」统一转成空串。
 *
 * 为什么必须单列：org.json 的 optString(key, "") 只对**缺失**的键返回默认值；
 * 键存在但值是 JSON null 时，它返回字面的四个字母 "null"。定时任务卡片上的
 * 「原因 null」就是这么来的——服务端的 latest_execution.error 本来是 null
 * （= 没出错），却被当成有错误原因印了出来。
 */
internal fun jsonStr(o: org.json.JSONObject?, key: String): String {
    val v = o?.opt(key) ?: return ""
    if (v === org.json.JSONObject.NULL) return ""
    val s = v.toString()
    return if (s == "null") "" else s
}
