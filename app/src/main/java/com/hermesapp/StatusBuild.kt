package com.hermesapp

import org.json.JSONObject

/**
 * 从 ChatViewModel 抽出的状态页数据构建（/health/sysinfo 扁平字段 → 界面结构）。
 * 纯函数，无实例依赖，便于单测。
 */
/** 顶部概览：网关活着没、跑什么模型、跑了多久、在干几件事。 */
internal fun buildHero(h: JSONObject): StatusHero {
    // 「已运行」用网关**自身进程**的时长（proc_uptime_seconds），不是主机开机时长
    // （uptime_seconds）——后者说的是机器开了多久，跟 hermes 跑了多久是两回事。
    // 老网关没这个字段时回落到 uptime_seconds，至少有个数。
    var up = h.optLong("proc_uptime_seconds", -1)
    if (up < 0) up = h.optLong("uptime_seconds", -1)
    val uptime = if (up < 0) "" else buildString {
        val d = up / 86400
        val hh = (up % 86400) / 3600
        val mm = (up % 3600) / 60
        if (d > 0) append(d).append(" 天 ")
        append(hh).append(" 时 ").append(mm).append(" 分")
    }
    return StatusHero(
        ok = h.optString("status") == "ok",
        statusText = if (h.optString("status") == "ok") "运行正常" else h.optString("status", "?"),
        // 显示 hermes 版本号（原来显示的是模型名，本机解析出来是 "hermes-agent"，没信息量）。
        version = h.optString("version", "").ifEmpty { "未知" },
        uptimeText = uptime,
        pid = h.optInt("pid", 0),
        activeRuns = h.optInt("active_runs", 0),
        delegations = h.optInt("active_delegations", 0),
    )
}
/**
 * 进度条数据。只在服务端真的回了该字段时才出条（optDouble 取不到给 -1），
 * 免得老网关（没打 sysinfo swap 补丁）显示一排 0% 的假条。
 */
internal fun buildMetrics(h: JSONObject): List<StatusMetric> {
    val out = mutableListOf<StatusMetric>()
    fun bar(key: String, label: String, pctKey: String, sub: String = "") {
        val p = h.optDouble(pctKey, -1.0)
        if (p >= 0) out.add(StatusMetric(key, label, p.coerceIn(0.0, 100.0), String.format("%.1f%%", p), sub))
    }
    val cores = h.optInt("cpu_count", 0)
    val freq = h.optInt("cpu_freq_mhz", 0)
    bar("cpu", "CPU 使用率", "cpu_percent",
        listOfNotNull(
            cores.takeIf { it > 0 }?.let { it.toString() + " 核" },
            freq.takeIf { it > 0 }?.let { it.toString() + " MHz" },
            h.optString("cpu_model", "").takeIf { it.isNotEmpty() },
        ).joinToString(" · "))
    val mUsed = h.optInt("memory_used_mb", 0)
    val mTotal = h.optInt("memory_total_mb", 0)
    bar("mem", "内存", "memory_percent",
        if (mTotal > 0) fmtMb(mUsed) + " / " + fmtMb(mTotal) else "")
    val swTotal = h.optInt("swap_total_mb", 0)
    bar("swap", "Swap", "swap_percent",
        if (swTotal > 0) fmtMb(h.optInt("swap_used_mb", 0)) + " / " + fmtMb(swTotal) else "未启用" )
    bar("disk", "磁盘", "disk_percent",
        h.optDouble("disk_total_gb", -1.0).takeIf { it >= 0 }?.let {
            String.format("已用 %.1f / %.1f GB", h.optDouble("disk_used_gb", 0.0), it)
        } ?: "")
    // 负载：百分比按「核数 = 100%」折算，超核即满条并标红。
    val la = h.optJSONArray("load_avg")
    if (la != null && la.length() >= 1 && cores > 0) {
        val l1 = la.optDouble(0, 0.0)
        val p = (l1 / cores * 100.0).coerceIn(0.0, 100.0)
        val txt = String.format("%.2f / %.2f / %.2f", l1,
            la.optDouble(1, 0.0), la.optDouble(2, 0.0))
        out.add(StatusMetric("load", "系统负载", p, txt, cores.toString() + " 核基准"))
    }
    return out
}
/**
 * 状态页底部明细（2.228 重构）。
 *
 * 只保留**别处没出现过**的静态信息：使用率/已用总量/PID/运行时长/负载这些已经出现在
 * 顶部概览卡、指标行与折线图里，这里再列一遍就是重复内容（用户要求「去掉重复内容」）。
 * 所以：网关只留操作系统与 Python 版本，CPU 只留型号/核数/主频，内存只留网关进程占用，
 * 磁盘只留可用空间。
 */
internal fun buildStatus(h: JSONObject): List<StatusSection> {
    val out = mutableListOf<StatusSection>()

    fun sec(title: String, items: List<StatusItem>) {
        val keep = items.filter { it.value.isNotEmpty() }
        if (keep.isNotEmpty()) out.add(StatusSection(title, keep))
    }
    fun it(label: String, v: String?): StatusItem =
        StatusItem(label, v.orEmpty())

    sec("网关", listOf(
        it("操作系统", h.optString("platform", "")),
        it("Python", h.optString("python", "")),
    ))

    sec("CPU", listOf(
        it("型号", h.optString("cpu_model", "")),
        it("核心数", h.optInt("cpu_count", 0).takeIf { it > 0 }?.let { "$it 核" } ?: ""),
        it("主频", h.optInt("cpu_freq_mhz", 0).takeIf { it > 0 }?.let { "$it MHz" } ?: ""),
    ))

    sec("内存", listOf(
        it("网关进程", h.optInt("proc_memory_mb", 0).takeIf { it > 0 }?.let { "$it MB" } ?: ""),
    ))

    sec("磁盘", listOf(
        it("可用空间", h.optDouble("disk_free_gb", -1.0).takeIf { it >= 0 }
            ?.let { String.format("%.1f GB", it) } ?: ""),
    ))

    return out
}
