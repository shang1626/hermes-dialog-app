package com.hermesapp

/**
 * 「本轮耗时」的起点选择。
 *
 * 为什么要有它（2026-10-10 用户报「切一下后台，耗时就从 0 重算」）：
 * 计时起点的两个本地来源都会丢 ——
 *   ① 进行中的**空气泡**会被落盘时的「空壳过滤」整条丢掉（模型还没吐正文/工具轨迹就切后台）；
 *   ② 会话运行时的 startedAt 只在内存里，App 进程被系统回收后就是 0（真机日志实测一天重启 8 次）。
 * 两者都缺时，旧代码直接拿「现在」当起点，界面上就是从 0 重新计时。
 * 而服务端 run 记录一直带 `created_at`（秒级浮点），App 探活（probeRun）时本来就拿到了这个
 * payload（只用来恢复待办卡片，没接成计时起点）。
 *
 * 纯函数、无 Android 依赖 —— 便于单测把行为钉住（见 TurnAnchorTest）。
 */
object TurnAnchor {

    /**
     * 服务端 run 的 `created_at` → 毫秒。
     *
     * 兼容：秒级浮点（服务端就是这种，实测 1791597459.87）、已经是毫秒的数值、数字字符串。
     * 缺失/非法一律返回 0（表示「拿不到真值」，由调用方决定退回什么）。
     * 分界用 1e11：秒级约 1.7e9、毫秒级约 1.7e12，两边都不会误判。
     */
    fun serverCreatedAtMs(raw: Any?): Long {
        val v: Double = when (raw) {
            null -> return 0L
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull() ?: return 0L
            else -> return 0L
        }
        if (!v.isFinite() || v <= 0.0) return 0L
        return if (v < 1.0e11) (v * 1000.0).toLong() else v.toLong()
    }

    /**
     * 服务端 run 的创建时间之前多久以内的本地起点仍算「本轮」。
     *
     * 本机 send 到服务端建 run 有一段往返，本地气泡起点因此可能略早于 created_at；
     * 5 秒足够吸收这段往返与两端时钟差，又远小于「上一轮的起点」这种真错位（实测差 21 分钟）。
     */
    private const val ANCHOR_SLACK_MS = 5_000L

    /**
     * 选本轮计时起点（毫秒），优先级：
     *   ① 本地气泡已记的起点 —— 同一进程内最准，也是唯一「用户看见的那个起点」；
     *   ② 会话运行时的起点 —— 本进程发起这一轮时记下的；
     *   ③ 服务端 run 的 `created_at` —— 进程重启后唯一还能拿到的真值；
     *   ④ 都没有才退回「现在」。
     * 结果一律不超过 now：服务端/本机时钟跑快时不做「负数计时」。
     */
    fun resolveTurnStartMs(
        bubbleStartedAt: Long,
        runtimeStartedAt: Long,
        serverCreatedAt: Any?,
        nowMs: Long,
    ): Long {
        val server = serverCreatedAtMs(serverCreatedAt)
        val picked = when {
            bubbleStartedAt > 0 -> bubbleStartedAt
            runtimeStartedAt > 0 -> runtimeStartedAt
            else -> server
        }
        // 起点比服务端这条 run 的创建时间还早 —— 它不可能是本轮的起点，换成服务端真值。
        // 实测（2026-10-10 真机日志）：重开 App 恢复时进行中的空气泡会被落盘空壳过滤掉，
        // 于是「最后一条助手气泡」落到了**上一轮**，界面显示「已跑 2398 秒」而真值是
        // 1135 秒，多算了 21 分钟；而正确的 created_at 就在这次探活的 payload 里。
        val start = if (server > 0 && picked > 0 && picked < server - ANCHOR_SLACK_MS) server else picked
        return if (start > 0) minOf(start, nowMs) else nowMs
    }

    /** 起点来自哪里（日志与排查用）。 */
    fun source(bubbleStartedAt: Long, runtimeStartedAt: Long, serverCreatedAt: Any?): String {
        val server = serverCreatedAtMs(serverCreatedAt)
        fun stale(v: Long) = v > 0 && server > 0 && v < server - ANCHOR_SLACK_MS
        return when {
            bubbleStartedAt > 0 && !stale(bubbleStartedAt) -> "本地气泡"
            runtimeStartedAt > 0 && !stale(runtimeStartedAt) -> "运行时"
            server > 0 -> if (stale(bubbleStartedAt) || stale(runtimeStartedAt))
                "服务端created_at(本地起点早于本轮)" else "服务端created_at"
            else -> "现在(无锚点)"
        }
    }
}
