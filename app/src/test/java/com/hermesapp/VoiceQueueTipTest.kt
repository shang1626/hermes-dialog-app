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

    // ------------------------------------------------------------------
    // 2026-10-13 第二轮上报：「提示条永久停在顶部，已等 251 秒，但已经没有可播的语音了」。
    // 事件序列取自真机上报日志（2.204 / versionCode 215）：
    //   17:16:22.111 语音入队 队列=1
    //   17:16:23.850 首个音频块 6480 字节
    //   17:16:23.886 流式语音已起播 队列剩=1
    //   17:18:01.266 流式语音播完 队列剩=0
    // 全程队列里只有这一条（队列=1），没有任何语音排在它后面，可提示条却亮着不退。
    // ------------------------------------------------------------------

    /** 旧实现（2.203）：写 = 「队列里有带数据的条目」，清 = 只在起播那一处与 stop()。 */
    private class OldWaitingModel {
        var active = ""
        val queue = mutableListOf<String>()
        val bytes = mutableMapOf<String, Long>()
        var waitingSince = 0L

        fun pump(now: Long) {
            if (active.isNotEmpty()) { note(now); return }   // 有人在响：记等待
            val k = queue.firstOrNull() ?: return             // 队列空：直接 return，不碰等待状态（缺陷所在）
            if ((bytes[k] ?: 0L) <= 0L) return                // 还没数据
            active = k
            waitingSince = 0L                                 // 轮到自己：清
        }

        private fun note(now: Long) {
            if (queue.any { (bytes[it] ?: 0L) > 0L }) {
                if (waitingSince == 0L) waitingSince = now
            } else {
                waitingSince = 0L
            }
        }

        fun begin(k: String, now: Long) { queue.add(k); bytes[k] = 0L; pump(now) }

        fun append(k: String, len: Long, now: Long) { bytes[k] = len; pump(now) }

        fun end(k: String, now: Long) { queue.remove(k); if (active == k) active = ""; pump(now) }
    }

    @Test
    fun repro_waiting_tip_sticks_after_queue_empties_on_old_logic() {
        val t0 = 1_000_000L                       // 对齐 17:16:22.111
        val m = OldWaitingModel()
        val key = "stream:run_0c64628a"
        m.begin(key, t0)
        m.append(key, 6480L, t0 + 1_739L)         // 17:16:23.850 首块
        m.append(key, 6480L, t0 + 2_000L)         // 后续块：此时 active=自己 -> note()
        val waitFrom = m.waitingSince
        assertTrue("旧实现：单条语音在播时也把提示点亮", waitFrom > 0L)
        // 30 秒后提示出现（此时队列里只有它自己，没有谁在排队）
        assertTrue(VoiceQueueTip.shouldShow(waitFrom, waitFrom + 30_000L))
        // 播完：队列空
        val tEnd = t0 + 99_155L                   // 17:18:01.266
        m.end(key, tEnd)
        assertEquals("播完了但一条也没在响", "", m.active)
        assertTrue("旧实现：队列空了也不清，提示条永久残留", m.waitingSince > 0L)
        // 用户看到的那一刻：07:20:35 -> 已等 251 秒，而当时已经没有任何可播的语音
        assertTrue(
            "复现：播完 2 分半后顶部还在显示「已等 251 秒」",
            VoiceQueueTip.shouldShow(m.waitingSince, m.waitingSince + 251_000L)
        )
    }

    /** 新实现：状态只由 VoiceQueueTip 的三个纯函数决定，与生产代码同一份判据。 */
    private class NewWaitingModel {
        var active = ""
        var externalPlaying = false
        val queue = mutableListOf<String>()
        val bytes = mutableMapOf<String, Long>()
        var waitingSince = 0L

        fun refresh(now: Long) {
            val playing = active.isNotEmpty() || externalPlaying
            val w = VoiceQueueTip.waitingCount(queue.toList(), active) { bytes[it] ?: 0L }
            waitingSince = VoiceQueueTip.nextWaitingSince(
                waitingSince, VoiceQueueTip.shouldTrackWaiting(playing, w), now
            )
        }

        fun begin(k: String, now: Long) { queue.add(k); bytes[k] = 0L; refresh(now) }

        fun append(k: String, len: Long, now: Long) {
            bytes[k] = len
            if (active.isEmpty() && (bytes[k] ?: 0L) > 0L) active = k   // 队首有数据就起播
            refresh(now)
        }

        fun end(k: String, now: Long) { queue.remove(k); if (active == k) active = ""; refresh(now) }

        fun drop(k: String, now: Long) { queue.remove(k); if (active == k) active = ""; refresh(now) }
    }

    @Test
    fun fix_waiting_tip_clears_when_queue_empties() {
        val t0 = 1_000_000L
        val m = NewWaitingModel()
        val key = "stream:run_0c64628a"
        m.begin(key, t0)
        m.append(key, 6480L, t0 + 1_739L)
        m.append(key, 6480L, t0 + 2_000L)
        // 单条语音：队里只有它自己，没有谁在后面等 -> 不点亮（旧实现在这里就亮了）
        assertEquals(0L, m.waitingSince)
        m.end(key, t0 + 99_155L)
        assertEquals(0L, m.waitingSince)
        assertFalse(VoiceQueueTip.shouldShow(m.waitingSince, t0 + 200_000L))
    }

    @Test
    fun waiting_tip_only_when_someone_is_playing_and_someone_waits() {
        // 该亮：有人在响 + 后面确实有人等着（前一条长语音 / 手动重播时新任务排后面）
        assertTrue(VoiceQueueTip.shouldTrackWaiting(true, 1))
        // 不该亮：没人出声（队列空转）
        assertFalse(VoiceQueueTip.shouldTrackWaiting(false, 1))
        // 不该亮：没人在后面等 —— 这是本轮缺陷的正例（正在响的那条不算「在等」）
        assertFalse(VoiceQueueTip.shouldTrackWaiting(true, 0))

        // waitingCount：正在响的那条不计数，还没收到数据的（0 字节）也不算在等
        val keys = listOf("A", "B", "C")
        val bytes = mapOf("A" to 100L, "B" to 0L, "C" to 50L)
        assertEquals(1, VoiceQueueTip.waitingCount(keys, "A") { bytes[it] ?: 0L })
        assertEquals(2, VoiceQueueTip.waitingCount(keys, "") { bytes[it] ?: 0L })
        assertEquals(1, VoiceQueueTip.waitingCount(keys, "C") { bytes[it] ?: 0L })   // 只剩 A 有数据且在等
    }

    @Test
    fun waiting_since_is_written_and_cleared_by_one_rule() {
        val t = 5_000L
        // 该等且还没记 -> 记现在
        assertEquals(t, VoiceQueueTip.nextWaitingSince(0L, true, t))
        // 本来就该等 -> 保持原值（队列前移时不重置「已等 N 秒」）
        assertEquals(1_000L, VoiceQueueTip.nextWaitingSince(1_000L, true, t))
        // 不该等 -> 归零（队列空了 / 轮到自己 / 谁都不在响，都走这一条）
        assertEquals(0L, VoiceQueueTip.nextWaitingSince(1_000L, false, t))
        assertEquals(0L, VoiceQueueTip.nextWaitingSince(0L, false, t))
    }
}
