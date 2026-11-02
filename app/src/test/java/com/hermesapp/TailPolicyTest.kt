package com.hermesapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退出界面时「本轮该不该继续跑」的判据回归（修复三的开关）。
 *
 * 左边一列是真实故障：`keepTail=true`（Activity 销毁）却把还在跑的轮次掐掉，
 * 服务端跑完的正文就没人接（实测 868 字回复消失、重开四次都补不回来）。
 */
class TailPolicyTest {

    @Test
    fun `Activity 销毁且本轮在跑 - 必须留`() {
        assertTrue(TailPolicy.keepOnDetach(keepTail = true, dead = false, runId = "run-abc", finished = false))
    }

    @Test
    fun `登出或切换身份 - 一律不留`() {
        // 留了就会让旧身份的流往新身份的会话里写正文，这是数据错乱，比丢一条回复严重得多。
        assertFalse(TailPolicy.keepOnDetach(keepTail = false, dead = false, runId = "run-abc", finished = false))
    }

    @Test
    fun `会话已删除 - 不留`() {
        assertFalse(TailPolicy.keepOnDetach(keepTail = true, dead = true, runId = "run-abc", finished = false))
    }

    @Test
    fun `没有 run - 不留`() {
        assertFalse(TailPolicy.keepOnDetach(keepTail = true, dead = false, runId = "", finished = false))
    }

    @Test
    fun `本轮已收尾 - 不留`() {
        assertFalse(TailPolicy.keepOnDetach(keepTail = true, dead = false, runId = "run-abc", finished = true))
    }

    @Test
    fun `守护判据 - 只要还有一个活的轮次就得继续守`() {
        val running = SessionRuntime("a").apply { runId = "run-1" }
        val idle = SessionRuntime("b")
        val done = SessionRuntime("c").apply { runId = "run-2"; finished = true }
        val deleted = SessionRuntime("d").apply { runId = "run-3"; dead = true }
        assertTrue(TailPolicy.hasLiveTail(listOf(running, idle, done, deleted)))
        assertFalse(TailPolicy.hasLiveTail(listOf(idle, done, deleted)))
        assertFalse(TailPolicy.hasLiveTail(emptyList()))
    }
}
