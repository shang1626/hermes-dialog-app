package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归用例：2026-10-13 上报里的两个语音缺陷（纯 JVM，不依赖设备）。
 *
 * 1) 「重播少一截」：本机 mp3 是边收边写的，被跳过/让位/手动重播打断的那条只落了前 176 块，
 *    而服务端留档是完整的 187 块 —— 判据 = 必须「收完了」（标记字节数 == 文件长度）才敢用本机文件。
 *    实测数字：留档 1209456 字节 / 201.6 秒 vs 本机 1140480 字节（少 11 块 ≈ 12 秒）。
 * 2) 「任务完成却没自动播报」：其实是排队在等前一条长语音 —— 等超过阈值才给提示。
 */
class VoiceQueueTipTest {

    @Test
    fun cache_mark_requires_complete_file() {
        // 完整收完：标记字节数与文件长度一致 -> 可用
        assertTrue(VoiceCacheMark.usable(1209456L, 1209456L))
        // 打断的半截文件（实测那一例）：长度对不上 -> 不可用，必须回服务端取留档
        assertFalse(VoiceCacheMark.usable(1209456L, 1140480L))
        // 没有标记（流没正常收尾）-> 不可用
        assertFalse(VoiceCacheMark.usable(0L, 1140480L))
        // 空文件 -> 不可用
        assertFalse(VoiceCacheMark.usable(0L, 0L))
    }

    @Test
    fun waiting_tip_only_after_threshold() {
        val t0 = 1_000_000L
        // 没在等 -> 不显示
        assertFalse(VoiceQueueTip.shouldShow(0L, t0))
        // 刚等 1 秒 / 29 秒 -> 不显示（正常一条语音播完就轮到了，别打扰）
        assertFalse(VoiceQueueTip.shouldShow(t0, t0 + 1_000L))
        assertFalse(VoiceQueueTip.shouldShow(t0, t0 + 29_999L))
        // 满 30 秒 -> 显示
        assertTrue(VoiceQueueTip.shouldShow(t0, t0 + VoiceQueueTip.WAIT_NOTICE_MS))
        // 实测那一例：07:26:37 入队，等到 07:27:10 时已经等了 33 秒
        assertTrue(VoiceQueueTip.shouldShow(t0, t0 + 33_000L))
    }

    @Test
    fun waiting_tip_text_carries_seconds() {
        assertEquals("上一条语音还在播，这条排在它后面（已等 201 秒）", VoiceQueueTip.text(201L))
    }
}
