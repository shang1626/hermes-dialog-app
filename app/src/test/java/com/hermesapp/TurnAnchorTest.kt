package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 本轮耗时起点（2026-10-10「切一下后台耗时从 0 重算」）的回归用例。
 *
 * [复现] 先把修复前的旧规则跑出来：本地气泡与运行时起点都为 0 时，旧代码拿「现在」当起点
 *        ——这正是用户看到的「从 0 开始」。
 * [修复] 同一组输入，新规则应当取服务端 run 的 created_at（秒级浮点 → 毫秒）。
 *
 * println 的 [复现]/[修复] 两行做前后对照（Gradle 默认不回显，从
 * app/build/test-results/**/*.xml 里 grep 得到）。
 */
class TurnAnchorTest {

    /** 修复前的旧规则（原样抄自 ChatViewModel: turnStart + 各恢复分支的回退）。 */
    private fun oldRule(bubbleStartedAt: Long, runtimeStartedAt: Long, nowMs: Long): Long =
        if (bubbleStartedAt > 0) bubbleStartedAt
        else if (runtimeStartedAt > 0) runtimeStartedAt
        else nowMs

    private val serverCreatedAt = 1791597459.87     // 实测：服务端 runs_idempotency.db 里 run 的 created_at
    private val now = 1791600000000L                // 真实起点之后约 42 分钟的「现在」

    @Test
    fun reproduce_old_rule_restarts_the_timer_from_zero() {
        // 进程被系统回收后重启：本地气泡已被空壳过滤丢掉、运行时是新的 → 两个起点都是 0。
        val bubble = 0L
        val runtime = 0L
        val oldStart = oldRule(bubble, runtime, now)
        println("[复现] 旧规则起点=${oldStart}（=现在）→ 已跑 0 秒，界面上计时从 0 重算")
        assertEquals(now, oldStart)

        val fixedStart = TurnAnchor.resolveTurnStartMs(bubble, runtime, serverCreatedAt, now)
        println("[修复] 新规则起点=${fixedStart}（服务端 created_at）→ 已跑 ${(now - fixedStart) / 1000} 秒")
        assertEquals(1791597459870L, fixedStart)
    }

    @Test
    fun local_bubble_wins_over_server() {
        val bubble = now - 90_000L
        assertEquals(bubble, TurnAnchor.resolveTurnStartMs(bubble, 0L, serverCreatedAt, now))
        assertEquals("本地气泡", TurnAnchor.source(bubble, 0L, serverCreatedAt))
    }

    @Test
    fun runtime_wins_when_bubble_is_gone() {
        val runtime = now - 45_000L
        assertEquals(runtime, TurnAnchor.resolveTurnStartMs(0L, runtime, serverCreatedAt, now))
        assertEquals("运行时", TurnAnchor.source(0L, runtime, serverCreatedAt))
    }

    @Test
    fun falls_back_to_now_when_nothing_is_known() {
        assertEquals(now, TurnAnchor.resolveTurnStartMs(0L, 0L, null, now))
        assertEquals(now, TurnAnchor.resolveTurnStartMs(0L, 0L, "", now))
        assertEquals(now, TurnAnchor.resolveTurnStartMs(0L, 0L, "not-a-number", now))
        assertEquals("现在(无锚点)", TurnAnchor.source(0L, 0L, null))
    }

    @Test
    fun server_value_already_in_millis_is_kept() {
        val ms = 1791597459870.0
        assertEquals(1791597459870L, TurnAnchor.serverCreatedAtMs(ms))
        assertEquals(1791597459870L, TurnAnchor.resolveTurnStartMs(0L, 0L, ms, now))
    }

    @Test
    fun server_value_as_string_is_parsed() {
        assertEquals(1791597459870L, TurnAnchor.serverCreatedAtMs("1791597459.87"))
        assertEquals(0L, TurnAnchor.serverCreatedAtMs(" "))
    }

    @Test
    fun a_future_server_clock_never_produces_negative_elapsed() {
        // 服务端时钟比本机快：起点不许超过 now，否则会显示成负数/巨大值。
        val future = (now + 600_000L) / 1000.0
        assertEquals(now, TurnAnchor.resolveTurnStartMs(0L, 0L, future, now))
    }

    @Test
    fun bogus_server_values_are_ignored() {
        assertEquals(0L, TurnAnchor.serverCreatedAtMs(null))
        assertEquals(0L, TurnAnchor.serverCreatedAtMs(0))
        assertEquals(0L, TurnAnchor.serverCreatedAtMs(-5.0))
        assertEquals(0L, TurnAnchor.serverCreatedAtMs(Double.NaN))
        assertEquals(0L, TurnAnchor.serverCreatedAtMs(true))
    }
}
