package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重启后投递状态归位（F03 回归）。
 *
 * 旧实现把 sending / queued 的回执直接丢掉，重启后那条消息既认不出「发没发出去」，
 * 也没有幂等键可用来安全重发。现在改为落盘 + 读回时归位：转圈的和「排队中」的两个
 * 当次运行才成立的状态，落到重启后仍然成立的档位。
 */
class ReceiptRestartTest {

    @Test
    fun sending_becomes_uncertain_after_restart() {
        // 重启后那个 POST 已经没了：收没收不知道，必须报「结果未知」而不是一直转圈。
        assertEquals(Receipt.UNCERTAIN, restartSafeStatus(Receipt.SENDING))
    }

    @Test
    fun queued_becomes_not_sent_after_restart() {
        // 内存里的待发队列重启即丢，这条不会再自动发——不能继续显示「排队中」。
        assertEquals(Receipt.NOT_SENT, restartSafeStatus(Receipt.QUEUED))
    }

    @Test
    fun terminal_states_are_unchanged() {
        // 已落定的状态读回后原样保留：重启不该改变用户已经做过的判断。
        assertEquals(Receipt.ACCEPTED, restartSafeStatus(Receipt.ACCEPTED))
        assertEquals(Receipt.UNCERTAIN, restartSafeStatus(Receipt.UNCERTAIN))
        assertEquals(Receipt.NOT_SENT, restartSafeStatus(Receipt.NOT_SENT))
        assertEquals(Receipt.FAILED, restartSafeStatus(Receipt.FAILED))
        assertEquals(Receipt.ACKED, restartSafeStatus(Receipt.ACKED))
    }

    @Test
    fun unknown_status_passes_through() {
        // 未来版本写入的新状态：本版读不出来也不能擅自改写（保持原样，界面回落不显示角标）。
        assertEquals("future_status", restartSafeStatus("future_status"))
    }
}
