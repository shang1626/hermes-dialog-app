package com.hermesapp

import org.junit.Assert.assertEquals
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
}
