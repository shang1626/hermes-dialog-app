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
        val picked = when {
            bubbleStartedAt > 0 -> bubbleStartedAt
            runtimeStartedAt > 0 -> runtimeStartedAt
            else -> serverCreatedAtMs(serverCreatedAt)
        }
        return if (picked > 0) minOf(picked, nowMs) else nowMs
    }

    /** 起点来自哪里（日志与排查用）。 */
    fun source(bubbleStartedAt: Long, runtimeStartedAt: Long, serverCreatedAt: Any?): String = when {
        bubbleStartedAt > 0 -> "本地气泡"
        runtimeStartedAt > 0 -> "运行时"
        serverCreatedAtMs(serverCreatedAt) > 0 -> "服务端created_at"
        else -> "现在(无锚点)"
    }
}
