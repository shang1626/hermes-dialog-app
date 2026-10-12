package com.hermesapp

/**
 * 重连退避与「服务端还在跑」的状态集合。
 *
 * 抽成无 Android 依赖的纯对象，便于单元测试（app/src/test/.../ReconnectPolicyTest.kt）。
 * 这是阶段2「关键路径单测」的第一步：把纯逻辑从 ChatViewModel 里挪出来，测得到，才谈得上后续重构。
 */
object ReconnectPolicy {

    /** SSE 断流后的最大自动重连次数。 */
    const val MAX_RECONNECT_ATTEMPTS = 8

    /**
     * 「服务端还在跑」的状态集合：探测到这些状态就续接事件流，不判结束。
     * 必须与 api_server 的 run 状态机逐字对齐（含 stopping——已请求停止但还没收尾）。
     */
    val RUNNING_STATES = setOf(
        "started", "running", "waiting_for_approval", "waiting_for_clarify", "queued", "stopping",
    )

    /** 退避等待：第 n 次重试 2^(n-1) 秒（首次 1 秒），封顶 30 秒。 */
    fun backoffDelayMs(attempt: Int): Long {
        val base = 1000L shl (attempt - 1).coerceIn(0, 5)   // 1,2,4,8,16,32...
        return base.coerceAtMost(30_000L)
    }

    /**
     * 一条流多久没有任何一行（含服务端每 10 秒的心跳帧）就算死了。
     * 45 秒 = 连丢四拍心跳，正常的长工具执行（期间只有心跳）绝不会被误判。
     */
    const val STREAM_DEAD_AFTER_MS = 45_000L

    /**
     * 会话列表（`GET /api/sessions`）的轮询节拍，单位是 2 秒心跳的 tick 数。
     * 有任务在跑时 30 秒一轮（在跑的状态不能滞后），空闲时 120 秒一轮。
     * 2026-10-13 省电：这一项原来固定 30 秒一轮、响应 167 KB，实测 11.5 MB/小时
     * （手机上第二大的一笔）；响应侧另有 compact=1 补丁把它压到约 17 KB。
     */
    const val SESSIONS_SYNC_TICKS_ACTIVE = 15
    const val SESSIONS_SYNC_TICKS_IDLE = 60

    fun sessionsSyncTicks(hasActiveRuns: Boolean): Int =
        if (hasActiveRuns) SESSIONS_SYNC_TICKS_ACTIVE else SESSIONS_SYNC_TICKS_IDLE

    /**
     * 看门狗重起流的节流间隔：同一会话最多这么久重起一次。
     * 重起本身是幂等的（新流会掐掉旧流并按代际号作废旧回调），节流只为别刷日志、别白打请求。
     */
    const val STREAM_RESTART_THROTTLE_MS = 15_000L

    /**
     * 看门狗判据：这一轮**还在跑**，但手上没有一条活着的流 → 必须重起流。
     *
     * 为什么要有这条（2026-10-13 真机现场）：断流后的重连链有三个已知的静默出口 ——
     * ① 流代际号把这条流的回调作废；② 同会话探测的单飞位没释放，之后每次重连都被挡回；
     * ③ 退避 8 次用尽转「翻历史」后，那条分支只留一行提示、不起任何任务。
     * 任何一条命中，界面上「正在执行」就永久停住（实测：01:33:37 起流的任务，流 01:45:59
     * 断掉，之后十分钟 App 一次都没再连）。
     *
     * 判据只看**事实**（有没有流、流多久没动静），不看是哪条链挂了 —— 这样以后新增的
     * 静默出口也自动被兜住。纯函数，便于单测钉住。
     */
    fun streamNeedsRestart(
        busy: Boolean,
        finished: Boolean,
        hasRunId: Boolean,
        hasCall: Boolean,
        callCanceled: Boolean,
        msSinceLastEvent: Long,
    ): Boolean {
        if (!busy || finished || !hasRunId) return false
        if (!hasCall || callCanceled) return true
        return msSinceLastEvent > STREAM_DEAD_AFTER_MS
    }
}
