package com.hermesapp

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「统计」页的数据模型与解析/格式化（纯函数，无 UI、无实例依赖，便于单测）。
 *
 * 数据源：服务端只读端点 `GET /api/stats?days=N`（state.db 现成计数聚合）。
 * 口径要点（界面小字也要照这个说，别让用户以为数字错了）：
 *  - 「调用」= 上游 API 调用次数（api_call_count 汇总），一次真实上游请求算一次；
 *  - 「按天」按**会话开始时刻**归日（跨午夜的会话整段记在开始那天）；
 *  - token 含缓存读/推理，合计 = 输入 + 输出 + 缓存读 + 缓存写；
 *  - 不含费用（本机未记录计费，显示 0 会误导，故整页不放费用）。
 */
data class StatsMetric(val label: String, val value: String, val sub: String = "")

/** 分渠道/分模型/分用途的通用两列行：左名、右主值、下面小字副值。 */
data class StatsRow(val name: String, val value: String, val sub: String = "")

data class StatsDaily(val day: String, val label: String, val calls: Long, val input: Long, val output: Long)

data class StatsTool(val name: String, val count: Long)

data class StatsTop(val title: String, val sub: String, val tokens: Long)

data class StatsModel(
    val days: Int,
    val generatedAt: Long,
    val metrics: List<StatsMetric>,
    val sources: List<StatsRow>,
    val models: List<StatsRow>,
    val tasks: List<StatsRow>,
    val daily: List<StatsDaily>,
    val tools: List<StatsTool>,
    val tops: List<StatsTop>,
    val totalCalls: Long,
    val totalTokens: Long,
)

/** token 缩写：与桌面面板同一习惯（1.2K / 240.8K / 926.8M / 2.24B）。 */
internal fun fmtTok(n: Long): String = when {
    n <= 0L -> "0"
    n < 1000L -> n.toString()
    n < 1_000_000L -> String.format("%.1fK", n / 1e3)
    n < 1_000_000_000L -> String.format("%.1fM", n / 1e6)
    else -> String.format("%.2fB", n / 1e9)
}

/** 时长：秒 → 「3 小时 12 分」/「45 分」/「38 秒」。 */
internal fun fmtDur(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val min = (s % 3600) / 60
    return when {
        h > 0 -> h.toString() + " 小时 " + min + " 分"
        min > 0 -> min.toString() + " 分"
        else -> s.toString() + " 秒"
    }
}

/** 会话来源中文名（服务端 source 字段）。 */
internal fun sourceLabel(src: String): String = when (src) {
    "api_server" -> "App"
    "weixin" -> "微信"
    "cron" -> "定时任务"
    "subagent" -> "子任务"
    "cli" -> "命令行"
    "oneshot" -> "一次性"
    "system" -> "系统"
    "webhook" -> "回调"
    else -> src.ifEmpty { "其它" }
}

/** 调用用途中文名（session_model_usage.task；main = 主对话）。 */
internal fun taskLabel(task: String): String = when (task) {
    "main" -> "主对话"
    "compression" -> "压缩摘要"
    "approval" -> "审批判断"
    "vision" -> "视觉识图"
    "title_generation" -> "自动取名"
    "background_review" -> "后台复盘"
    else -> task
}

private fun jsonArr(o: JSONObject, key: String): JSONArray? = o.optJSONArray(key)

/**
 * 解析端点返回体 → 界面模型。字段缺失一律按 0/空处理（老服务端没这个端点时由调用方兜错，
 * 这里只保证「不因为少个字段就崩」）。
 */
internal fun buildStats(raw: JSONObject): StatsModel {
    val days = raw.optInt("days", 7)
    val t = raw.optJSONObject("totals") ?: JSONObject()
    val m = raw.optJSONObject("messages") ?: JSONObject()

    fun l(o: JSONObject, k: String): Long = o.optLong(k, 0L)

    val calls = l(t, "api_calls")
    val sessions = l(t, "sessions")
    val input = l(t, "input_tokens")
    val output = l(t, "output_tokens")
    val cacheRead = l(t, "cache_read_tokens")
    val cacheWrite = l(t, "cache_write_tokens")
    val reasoning = l(t, "reasoning_tokens")
    val total = if (l(t, "total_tokens") > 0) l(t, "total_tokens") else input + output + cacheRead + cacheWrite
    val avgCall = if (l(t, "avg_tokens_per_call") > 0) l(t, "avg_tokens_per_call")
    else if (calls > 0) Math.round(total.toDouble() / calls) else 0L

    val metrics = listOf(
        StatsMetric("调用次数", fmtNum(calls), "上游 API 调用"),
        StatsMetric("会话数", fmtNum(sessions), if (sessions > 0) "平均 " + fmtNum(Math.round(calls.toDouble() / sessions)) + " 次/会话" else ""),
        StatsMetric("我提问", fmtNum(l(m, "user_messages")) + " 条", "助手回复 " + fmtNum(l(m, "assistant_messages")) + " 条"),
        StatsMetric("工具调用", fmtNum(l(t, "tool_calls")) + " 次", "终端/改文件/搜索等"),
        StatsMetric("输入 token", fmtTok(input), "含每轮全部上下文"),
        StatsMetric("输出 token", fmtTok(output), "模型生成的部分"),
        StatsMetric("缓存读 token", fmtTok(cacheRead), if (cacheWrite > 0) "缓存写 " + fmtTok(cacheWrite) else "命中缓存的部分"),
        StatsMetric("推理 token", fmtTok(reasoning), "思考链（若有）"),
        StatsMetric("合计 token", fmtTok(total), "输入+输出+缓存"),
        StatsMetric("平均每调用", fmtTok(avgCall) + " token", "按上游调用数算"),
        StatsMetric("活跃时长", fmtDur(l(t, "active_seconds")), "窗口内会话累计"),
        StatsMetric("消息总数", fmtNum(l(t, "messages")) + " 条", "含工具结果"),
    )

    val sources = mutableListOf<StatsRow>()
    jsonArr(raw, "by_source")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sources.add(
                StatsRow(
                    sourceLabel(o.optString("source", "")),
                    fmtNum(l(o, "api_calls")) + " 次 · " + fmtTok(l(o, "input_tokens") + l(o, "output_tokens")) + " token",
                    "会话 " + fmtNum(l(o, "sessions")) + " · 输入 " + fmtTok(l(o, "input_tokens")) + " · 输出 " + fmtTok(l(o, "output_tokens")),
                )
            )
        }
    }

    val models = mutableListOf<StatsRow>()
    jsonArr(raw, "by_model")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("model", "").ifEmpty { "（未记录）" }
            models.add(
                StatsRow(
                    name,
                    fmtNum(l(o, "api_calls")) + " 次 · " + fmtTok(l(o, "input_tokens") + l(o, "output_tokens")) + " token",
                    "输入 " + fmtTok(l(o, "input_tokens")) + " · 输出 " + fmtTok(l(o, "output_tokens")) + " · 会话 " + fmtNum(l(o, "sessions")),
                )
            )
        }
    }

    val tasks = mutableListOf<StatsRow>()
    jsonArr(raw, "by_task")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            tasks.add(
                StatsRow(
                    taskLabel(o.optString("task", "")),
                    fmtNum(l(o, "api_calls")) + " 次 · " + fmtTok(l(o, "input_tokens") + l(o, "output_tokens")) + " token",
                    "输入 " + fmtTok(l(o, "input_tokens")) + " · 输出 " + fmtTok(l(o, "output_tokens")),
                )
            )
        }
    }

    val daily = mutableListOf<StatsDaily>()
    jsonArr(raw, "daily")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val day = o.optString("day", "")
            daily.add(
                StatsDaily(
                    day, day.takeLast(5).replace('-', '/'),
                    l(o, "api_calls"), l(o, "input_tokens"), l(o, "output_tokens"),
                )
            )
        }
    }

    val tools = mutableListOf<StatsTool>()
    jsonArr(raw, "tools")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            tools.add(StatsTool(o.optString("tool", ""), l(o, "count")))
        }
    }

    val tops = mutableListOf<StatsTop>()
    jsonArr(raw, "top_sessions")?.let { arr ->
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val src = sourceLabel(o.optString("source", ""))
            val model = o.optString("model", "").ifEmpty { "—" }
            tops.add(
                StatsTop(
                    o.optString("title", "").ifEmpty { "(无标题)" },
                    src + " · " + model + " · " + fmtNum(l(o, "api_calls")) + " 次",
                    l(o, "input_tokens") + l(o, "output_tokens"),
                )
            )
        }
    }

    return StatsModel(
        days = days,
        generatedAt = (raw.optDouble("generated_at", 0.0) * 1000).toLong(),
        metrics = metrics,
        sources = sources,
        models = models,
        tasks = tasks,
        daily = daily,
        tools = tools,
        tops = tops,
        totalCalls = calls,
        totalTokens = total,
    )
}

/** 千分位：12345 → 12,345（次数这类整数比缩写更好读）。 */
internal fun fmtNum(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    for ((i, ch) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return sb.toString()
}
