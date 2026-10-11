package com.hermesapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 待办卡片失效判定单测（用户 2026-10-11 实测：审批超时后点确认/拒绝反复 409，提示条下不去）。
 *
 * 关键区分：404/409 是「服务端已经不在等这条」的终局 → 卡片必须收起；
 * 网络异常(-1) 与 5xx 是「没送到」→ 保持可点可重试。
 */
class CardExpiryTest {

    @Test
    fun gone_codes_are_terminal() {
        assertTrue(receiptGone(404))
        assertTrue(receiptGone(409))
    }

    @Test
    fun transient_codes_stay_retryable() {
        assertFalse(receiptGone(-1))
        assertFalse(receiptGone(0))
        assertFalse(receiptGone(400))
        assertFalse(receiptGone(401))
        assertFalse(receiptGone(500))
        assertFalse(receiptGone(503))
    }

    @Test
    fun success_is_not_gone() {
        assertFalse(receiptGone(200))
        assertFalse(receiptGone(204))
    }
}
