package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态轮询兜底那几句提示。
 *
 * 2026-10-14 现场（真机日志）：服务端 00:49:38 这一轮已完成，00:50:57 用户切回 App 拿到
 * `/events` 404（服务端清扫器回收的是**已结束**那条 run 的传输层），界面却报
 * 「这一轮还在服务器上跑（已 26 分钟），跑完自动取回」，还要等 20 秒的下一次探测才把结果取回。
 *
 * 三条形状各钉一条判据：
 * ① 探测结果出来之前，那句提示不含任何结论；
 * ② 确认「在跑」之后，提示带上服务端最后一次动静（答「在算还是卡住了」）；
 * ③ 这几句都归「流一恢复就自动清掉」的家族（否则回前台归零 autoContinue 后永久残留）。
 */
class LiveRunNoteTest {

    @Test
    fun confirming_note_claims_nothing() {
        // 它出现在「404 之后、状态还没问到」的那一瞬间：既不能说还在跑（可能刚跑完），
        // 也不能说结束了，只能描述「正在确认」。旧代码正是在这里喊「还在服务器上跑」。
        val n = ReconnectPolicy.LIVE_RUN_CONFIRMING_NOTE
        assertFalse(n, n.contains("还在服务器上跑"))
        assertFalse(n, n.contains("中断"))
        assertTrue(n, ReconnectPolicy.isReconnectingNote(n))
    }

    @Test
    fun running_note_reports_elapsed_and_last_server_step() {
        assertEquals(
            "这一轮还在服务器上跑（已 26 分钟），最后一个工具步骤在 3 分钟前，跑完自动取回",
            ReconnectPolicy.liveRunNote(26 * 60_000L, "tool.completed", 3 * 60_000L),
        )
    }

    @Test
    fun running_note_omits_minutes_when_the_start_is_unknown() {
        // 起点锚点丢了（重开 App 且服务端负载没带时间戳）时只报「还在跑」，不编一个数字
        assertEquals(
            "这一轮还在服务器上跑，跑完自动取回",
            ReconnectPolicy.liveRunNote(0L, "", -1L),
        )
    }

    @Test
    fun a_fresh_tool_step_reads_as_active() {
        assertEquals("，刚刚还有工具步骤在跑", ReconnectPolicy.liveRunDetail("tool.started", 5_000L))
        // 边界：59 秒算「刚刚」，满 1 分钟才报分钟
        assertEquals("，刚刚还有工具步骤在跑", ReconnectPolicy.liveRunDetail("tool.completed", 59_000L))
        assertEquals("，最后一个工具步骤在 1 分钟前", ReconnectPolicy.liveRunDetail("tool.completed", 60_000L))
    }

    @Test
    fun a_tool_step_without_a_timestamp_says_nothing_extra() {
        assertEquals("", ReconnectPolicy.liveRunDetail("tool.completed", -1L))
    }

    @Test
    fun waiting_states_are_named_in_chinese() {
        assertEquals("，服务端在等你确认一条审批", ReconnectPolicy.liveRunDetail("approval.request", 1_000L))
        assertEquals("，服务端在等你回答一个问题", ReconnectPolicy.liveRunDetail("clarify.request", 1_000L))
        assertEquals("，正在停止这一轮", ReconnectPolicy.liveRunDetail("run.stopping", 1_000L))
    }

    @Test
    fun text_streaming_and_unknown_events_add_nothing() {
        // message.delta（流式正文）不刷新 run 状态的 updated_at，事件名也不在表里：长回答期间的
        // 静默是正常的，绝不能写成「最后一个工具步骤在 N 分钟前」——那会把「在算」说成「卡住」。
        assertEquals("", ReconnectPolicy.liveRunDetail("message.delta", 5 * 60_000L))
        assertEquals("", ReconnectPolicy.liveRunDetail("", 3_000L))
        assertEquals("", ReconnectPolicy.liveRunDetail("run.created", 3_000L))
    }

    @Test
    fun the_unreachable_note_says_unknown_not_finished() {
        val n = ReconnectPolicy.liveRunUnreachableNote(2, 5)
        assertTrue(n, n.contains("第 2/5 次"))
        assertFalse(n, n.contains("还在服务器上跑"))
        assertFalse(n, n.contains("结束"))
    }

    @Test
    fun every_live_run_note_is_cleared_once_the_stream_comes_back() {
        // 回前台会把 autoContinue 归零（见 onAppForeground），此后清理只看「提示是不是这一类」。
        // 漏登记任何一句 = 它永久钉在会话上方（本仓复发过两次的缺陷形状）。
        listOf(
            ReconnectPolicy.LIVE_RUN_CONFIRMING_NOTE,
            ReconnectPolicy.liveRunNote(26 * 60_000L, "tool.completed", 3 * 60_000L),
            ReconnectPolicy.liveRunNote(0L, "", -1L),
            ReconnectPolicy.liveRunUnreachableNote(1, 5),
        ).forEach {
            assertTrue(it, ReconnectPolicy.isReconnectingNote(it))
            assertTrue(it, ReconnectPolicy.shouldClearNoteOnEvent(it, "tool.completed", 0))
        }
    }

    @Test
    fun live_run_notes_survive_a_truncated_replay() {
        // replay.truncated 不是「流已恢复」，是服务端在说「断线期间的事件补不回来了」，别顺手清
        assertFalse(
            ReconnectPolicy.shouldClearNoteOnEvent(
                ReconnectPolicy.liveRunNote(60_000L, "tool.completed", 10_000L), "replay.truncated", 1,
            )
        )
    }
}
