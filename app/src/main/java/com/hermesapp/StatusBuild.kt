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
/** 把 /health/sysinfo 的扁平字段整理成「分组 → 行」结构，供状态页排版渲染。 */
internal fun buildStatus(h: JSONObject): List<StatusSection> {
    val out = mutableListOf<StatusSection>()

    fun sec(title: String, items: List<StatusItem>) {
        val keep = items.filter { it.value.isNotEmpty() }
        if (keep.isNotEmpty()) out.add(StatusSection(title, keep))
    }
    fun it(label: String, v: String?): StatusItem =
        StatusItem(label, v.orEmpty())

    val gw = mutableListOf<StatusItem>()
    gw.add(it("运行状态", if (h.optString("status") == "ok") "正常" else h.optString("status", "?")))
    val pid = h.optInt("pid", 0)
    if (pid > 0) gw.add(it("进程 PID", pid.toString()))
    gw.add(it("操作系统", h.optString("platform", "")))
    gw.add(it("Python", h.optString("python", "")))
    sec("网关", gw)

    val cpu = mutableListOf<StatusItem>()
    cpu.add(it("型号", h.optString("cpu_model", "")))
    val cpuPct = h.optDouble("cpu_percent", -1.0)
    if (cpuPct >= 0) cpu.add(it("使用率", String.format("%.1f%%", cpuPct)))
    val cores = h.optInt("cpu_count", 0)
    if (cores > 0) cpu.add(it("核心数", cores.toString() + " 核"))
    val freq = h.optInt("cpu_freq_mhz", 0)
    if (freq > 0) cpu.add(it("主频", freq.toString() + " MHz"))
    sec("CPU", cpu)

    val mem = mutableListOf<StatusItem>()
    val memPct = h.optDouble("memory_percent", -1.0)
    if (memPct >= 0) mem.add(it("使用率", String.format("%.1f%%", memPct)))
    val mUsed = h.optInt("memory_used_mb", 0)
    val mTotal = h.optInt("memory_total_mb", 0)
    if (mTotal > 0) mem.add(it("已用/总量", mUsed.toString() + " MB / " + mTotal.toString() + " MB"))
    val pmem = h.optInt("proc_memory_mb", 0)
    if (pmem > 0) mem.add(it("网关进程", pmem.toString() + " MB"))
    // 交换分区：内存满了靠 swap 顶，swap 也快满才是真要 OOM，故与内存同区显示。
    // 服务端未打 sysinfo swap 补丁时不回这三个字段，optDouble 取到 -1 / optInt 取到 0，整行自动不显示。
    val swPct = h.optDouble("swap_percent", -1.0)
    if (swPct >= 0) mem.add(it("Swap 使用率", String.format("%.1f%%", swPct)))
    val swUsed = h.optInt("swap_used_mb", 0)
    val swTotal = h.optInt("swap_total_mb", 0)
    if (swTotal > 0) mem.add(it("Swap 已用/总量", swUsed.toString() + " MB / " + swTotal.toString() + " MB"))
    sec("内存", mem)

    val disk = mutableListOf<StatusItem>()
    val dTotal = h.optDouble("disk_total_gb", -1.0)
    if (dTotal >= 0) disk.add(it("总大小", String.format("%.1f GB", dTotal)))
    val dUsed = h.optDouble("disk_used_gb", -1.0)
    if (dUsed >= 0) disk.add(it("已用", String.format("%.1f GB", dUsed)))
    val dFree = h.optDouble("disk_free_gb", -1.0)
    if (dFree >= 0) disk.add(it("可用", String.format("%.1f GB", dFree)))
    val dPct = h.optDouble("disk_percent", -1.0)
    if (dPct >= 0) disk.add(it("使用率", String.format("%.1f%%", dPct)))
    sec("磁盘", disk)

    val run = mutableListOf<StatusItem>()
    val la = h.optJSONArray("load_avg")
    if (la != null && la.length() >= 3) {
        run.add(it("负载 1/5/15", String.format("%.2f / %.2f / %.2f",
            la.optDouble(0, 0.0), la.optDouble(1, 0.0), la.optDouble(2, 0.0))))
    }
    // 「已运行」= 网关自身进程时长（proc_uptime_seconds），回落主机开机时长。
    var up = h.optLong("proc_uptime_seconds", -1)
    if (up < 0) up = h.optLong("uptime_seconds", -1)
    if (up >= 0) run.add(it("已运行", (up / 86400).toString() + " 天 " + ((up % 86400) / 3600).toString() + " 小时 " + ((up % 3600) / 60).toString() + " 分"))
    sec("运行", run)

    // 「API 与任务」卡片已移除（用户 2026-10-09）：里面几项要么在顶部概览卡已有（活跃任务/
    // 子任务），要么没人看（今日请求/队列深度/最后心跳）。顶部概览卡保留「活跃任务 / 子任务」。

    return out
}
