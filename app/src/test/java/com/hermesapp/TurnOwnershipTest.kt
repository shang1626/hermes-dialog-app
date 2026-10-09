package com.hermesapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮次所有权（F04 回归）：迟到的旧轮不得接管会话。
 *
 * 场景：A 的同步 startRun 还没返回 → 用户点停止 → 立刻发 B → A 的响应才回来。
 * 此时 B 已把 finished/stopRequested 重置为 false，A 必须靠轮次号认出自己已过时。
 */
class TurnOwnershipTest {

    @Test
    fun current_turn_with_no_stop_owns_the_session() {
        // 正在进行的那一轮：号对得上、没人叫停 → 允许写状态、允许接流。
        assertTrue(ownsTurn(myGen = 2, curGen = 2, finished = false, stopRequested = false))
    }

    @Test
    fun late_turn_superseded_by_next_send_is_rejected() {
        // A 的号是 1，B 发起后 curGen 变成 2 —— A 回来时对不上号，必须自我作废。
        assertFalse(ownsTurn(myGen = 1, curGen = 2, finished = false, stopRequested = false))
    }

    @Test
    fun late_turn_superseded_by_stop_is_rejected() {
        // 停止会把 curGen 推进一格：A 回来对不上号（即使 finished 被谁重置了）。
        assertFalse(ownsTurn(myGen = 1, curGen = 2, finished = false, stopRequested = true))
    }

    @Test
    fun finished_turn_never_writes_state() {
        assertFalse(ownsTurn(myGen = 3, curGen = 3, finished = true, stopRequested = false))
    }

    @Test
    fun stop_requested_turn_never_writes_state() {
        assertFalse(ownsTurn(myGen = 3, curGen = 3, finished = false, stopRequested = true))
    }

    @Test
    fun only_the_exact_current_generation_is_accepted() {
        // 号必须严格相等：既不能是更老的（被取代），也不能凭空超前（不属于本会话当前轮）。
        assertFalse(ownsTurn(myGen = 0, curGen = 1, finished = false, stopRequested = false))
        assertFalse(ownsTurn(myGen = 5, curGen = 4, finished = false, stopRequested = false))
    }
}
