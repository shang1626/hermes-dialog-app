package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段2 单测第 1 块：重连退避链与运行态集合。
 * 纯 JVM，不依赖设备/模拟器。
 */
class ReconnectPolicyTest {

    @Test
    fun backoff_grows_then_caps_at_30s() {
        assertEquals(1000L, ReconnectPolicy.backoffDelayMs(1))
        assertEquals(2000L, ReconnectPolicy.backoffDelayMs(2))
        assertEquals(4000L, ReconnectPolicy.backoffDelayMs(3))
        assertEquals(8000L, ReconnectPolicy.backoffDelayMs(4))
        assertEquals(16000L, ReconnectPolicy.backoffDelayMs(5))
        // 2^5=32s 超封顶 -> 30s
        assertEquals(30000L, ReconnectPolicy.backoffDelayMs(6))
        assertEquals(30000L, ReconnectPolicy.backoffDelayMs(8))
    }

    @Test
    fun backoff_clamps_out_of_range_input() {
        // attempt<=1 时指数钳到 0 -> 1s（不会出现 0 或负等待）
        assertEquals(1000L, ReconnectPolicy.backoffDelayMs(0))
        assertEquals(1000L, ReconnectPolicy.backoffDelayMs(-3))
        assertEquals(30000L, ReconnectPolicy.backoffDelayMs(99))
    }

    @Test
    fun running_states_aligns_with_server_state_machine() {
        assertEquals(
            setOf("started", "running", "waiting_for_approval", "waiting_for_clarify", "queued", "stopping"),
            ReconnectPolicy.RUNNING_STATES,
        )
    }

    @Test
    fun max_attempts_is_8() {
        assertEquals(8, ReconnectPolicy.MAX_RECONNECT_ATTEMPTS)
    }

    // ---- 断流自愈看门狗（2026-10-13 现场：流断了之后十分钟 App 一次都没再连）----------------

    private fun need(
        busy: Boolean = true, finished: Boolean = false, hasRunId: Boolean = true,
        hasCall: Boolean = true, callCanceled: Boolean = false, idleMs: Long = 0L,
    ) = ReconnectPolicy.streamNeedsRestart(busy, finished, hasRunId, hasCall, callCanceled, idleMs)

    @Test
    fun watchdog_restarts_when_no_stream_at_all() {
        // 退避用尽转「翻历史」的死胡同、或流从未起来 → 手上没有流，必须重起
        assertTrue(need(hasCall = false))
        assertTrue(need(hasCall = false, idleMs = Long.MAX_VALUE))
    }

    @Test
    fun watchdog_restarts_when_stream_was_canceled() {
        assertTrue(need(callCanceled = true))
    }

    @Test
    fun watchdog_restarts_only_after_stream_goes_quiet() {
        // 服务端每 10 秒必发心跳帧（任何一行都刷 lastEventAt）→ 静默超过 45 秒才算死
        assertFalse(need(idleMs = 10_000L))                       // 正常心跳
        assertFalse(need(idleMs = 30_000L))                       // 长工具执行期间只有心跳
        assertFalse(need(idleMs = ReconnectPolicy.STREAM_DEAD_AFTER_MS))  // 边界：等于阈值不算死
        assertTrue(need(idleMs = ReconnectPolicy.STREAM_DEAD_AFTER_MS + 1))
    }

    @Test
    fun watchdog_never_touches_idle_or_finished_turns() {
        assertFalse(need(busy = false))                // 本轮不在跑：不许凭空起流
        assertFalse(need(finished = true))             // 已收尾（等收尾补拉那一段）
        assertFalse(need(hasRunId = false))            // 没有 runId：起流也没目标
        assertFalse(need(busy = false, hasCall = false, idleMs = Long.MAX_VALUE))
    }

    // ---- 会话列表轮询节拍（2026-10-13 省电：167KB × 120 次/小时 → 精简 + 降频）-----------

    @Test
    fun sessions_sync_slows_down_when_nothing_is_running() {
        assertEquals(15, ReconnectPolicy.sessionsSyncTicks(true))    // 有任务在跑：30 秒一轮
        assertEquals(60, ReconnectPolicy.sessionsSyncTicks(false))   // 空闲：120 秒一轮
        // 两个节拍各自 × 2 秒 = 期望的真实间隔
        assertEquals(30_000L, ReconnectPolicy.sessionsSyncTicks(true) * 2_000L)
        assertEquals(120_000L, ReconnectPolicy.sessionsSyncTicks(false) * 2_000L)
    }

    // ---- 增量窗口（铁律：本地绝不能比服务端少）-----------------------------------------

    @Test
    fun sync_window_covers_exactly_what_local_is_missing() {
        // 本地完整（不落后）→ 只拉常规 40 行
        assertEquals(40, ReconnectPolicy.syncFetchRows(serverRows = 400, localRows = 400, localEmpty = false))
        // 本地落后 30 行 → 窗口放大到 70，缺的那 30 行落在窗口里
        assertEquals(70, ReconnectPolicy.syncFetchRows(serverRows = 430, localRows = 400, localEmpty = false))
        // 本地落后 200 行 → 窗口 240（补上落后的部分）
        assertEquals(240, ReconnectPolicy.syncFetchRows(serverRows = 600, localRows = 400, localEmpty = false))
        // 服务端比本地少（本地有服务端没有的：本地独有的助手行/轨迹）→ 仍只拉 40 行，不整份
        assertEquals(40, ReconnectPolicy.syncFetchRows(serverRows = 300, localRows = 400, localEmpty = false))
    }

    @Test
    fun sync_window_falls_back_to_full_pull_when_unknown_or_far_behind() {
        // 本地空 / 服务端条数未知 → 整份拉（宁可多花一次，也不能少内容）
        assertEquals(0, ReconnectPolicy.syncFetchRows(serverRows = 400, localRows = 0, localEmpty = true))
        assertEquals(0, ReconnectPolicy.syncFetchRows(serverRows = -1, localRows = 400, localEmpty = false))
        // 落后到 400 行仍拼窗口（400+40），401 行就整份拉
        assertEquals(440, ReconnectPolicy.syncFetchRows(serverRows = 800, localRows = 400, localEmpty = false))
        assertEquals(0, ReconnectPolicy.syncFetchRows(serverRows = 801, localRows = 400, localEmpty = false))
    }

    @Test
    fun local_raw_rows_counts_trace_lines() {
        assertEquals(12, ReconnectPolicy.localRawRows(10, 2))     // 消息 + 轨迹行
        assertEquals(10, ReconnectPolicy.localRawRows(10, 0))
        assertEquals(10, ReconnectPolicy.localRawRows(10, -5))    // 负数兜底
    }
}
