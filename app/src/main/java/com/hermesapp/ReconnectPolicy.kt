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
}
