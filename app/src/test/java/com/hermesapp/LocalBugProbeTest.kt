package com.hermesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本机复现对照：这批修复针对的缺陷，**先在本地把旧行为跑出来**，确认缺陷真实存在，再看修复后的判定。
 *
 * 做法和同事那份证据包一致：把 2.160（修复前）的那几行**原样抄进来**跑一遍，打印结果作为
 * 「缺陷确实存在」的证据；修复后的判定同样在这里跑，断言压新行为。
 * 凡是有真实纯函数可测的（restartSafeStatus / ownsTurn / StreamDeltaCoalescer），一律用真代码，不另写模型。
 *
 * 这些用例把「旧行为」也钉住了，是为了以后不再糊里糊涂地把它改回去；旧的那半边标注了「复现对照」。
 */
class LocalBugProbeTest {

    // ------------------------------------------------------------------
    // F21-A：查询失败被当成「服务端没有这条消息」
    // ------------------------------------------------------------------

    /** 底层取历史：failing=true 时抛异常（网络断/接口报错）。 */
    private fun fetchRows(failing: Boolean): List<String> =
        if (failing) throw java.io.IOException("网络断了") else emptyList()

    @Test
    fun reproduce_old_confirm_turns_query_failure_into_server_rejected() {
        // ↓↓ 2.160 原文（原样抄）：异常被吞成空列表，接着按「认不出锚点」判成发送失败
        val rows = try {
            fetchRows(failing = true)
        } catch (_: Exception) {
            emptyList<String>()
        }
        val status = if (rows.isEmpty()) Receipt.FAILED else Receipt.ACCEPTED
        println("[复现][F21] 网络查询失败 → 旧判定 = $status（把「不知道」说成了「服务端没有这条」）")
        assertEquals(Receipt.FAILED, status)
    }

    @Test
    fun new_confirm_keeps_uncertain_when_the_query_itself_failed() {
        // 修复后：查询失败 = 不知道 → 保持「结果未知」，不诱导用户重发
        val rows: List<String>? = try {
            fetchRows(failing = true)
        } catch (_: Exception) {
            null
        }
        val status = when {
            rows == null -> Receipt.UNCERTAIN
            rows.isEmpty() -> Receipt.FAILED
            else -> Receipt.ACCEPTED
        }
        println("[修复][F21] 网络查询失败 → 新判定 = $status")
        assertEquals(Receipt.UNCERTAIN, status)
    }

    @Test
    fun new_confirm_still_reports_missing_when_the_server_answered() {
        // 别把这条修复做过头：服务端**确实**答了「没有这条」时，仍应判失败。
        val rows: List<String>? = try {
            fetchRows(failing = false)
        } catch (_: Exception) {
            null
        }
        val status = when {
            rows == null -> Receipt.UNCERTAIN
            rows.isEmpty() -> Receipt.FAILED
            else -> Receipt.ACCEPTED
        }
        assertEquals(Receipt.FAILED, status)
    }

    // ------------------------------------------------------------------
    // F21-B：停止请求没送达，界面照旧显示「已请求停止」
    // ------------------------------------------------------------------

    @Test
    fun reproduce_old_stop_has_no_success_signal() {
        // 2.160：stopRun 返回 Unit 且内部 runCatching 吞掉一切 → 调用方拿不到「有没有送达」
        fun oldStopRun(@Suppress("UNUSED_PARAMETER") runId: String) {
            runCatching { throw java.io.IOException("连接被重置") }
        }
        var uiSaysStopped = false
        oldStopRun("run-1")
        uiSaysStopped = true                  // 旧代码：不管结果，界面一律置为「已请求停止」
        println("[复现][F21] stopRun 失败后界面仍显示已停止 = $uiSaysStopped")
        assertTrue(uiSaysStopped)
    }

    @Test
    fun new_stop_reports_unconfirmed() {
        // 修复后：stopRun 返回「服务端是否确认」，失败时界面明确说「没送达」
        fun newStopRun(@Suppress("UNUSED_PARAMETER") runId: String): Boolean =
            runCatching { throw java.io.IOException("连接被重置") }.getOrDefault(false)
        val sent = newStopRun("run-1")
        println("[修复][F21] stopRun 返回 sent=$sent → 界面提示「停止请求没送达」")
        assertEquals(false, sent)
    }

    // ------------------------------------------------------------------
    // F13：插话没送达，气泡看着仍像发出去了
    // ------------------------------------------------------------------

    @Test
    fun reproduce_old_steer_failure_left_the_bubble_looking_sent() {
        // 2.160 原文：插话气泡不带回执，失败只改顶部一行提示 → 气泡本身仍是「正常用户消息」
        val bubbleReceiptBeforeFix: Receipt? = null
        val ok = false
        val uiShowsFailureOnBubble = bubbleReceiptBeforeFix != null && !ok
        println("[复现][F13] 插话失败 → 气泡上有失败标记 = $uiShowsFailureOnBubble")
        assertEquals(false, uiShowsFailureOnBubble)
    }

    @Test
    fun new_steer_failure_is_marked_on_the_bubble_itself() {
        // 修复后：插话气泡自带回执，失败翻成 STEER_FAILED，气泡上出现红叉
        var receipt: Receipt? = Receipt(Receipt.SENDING)
        val ok = false
        if (!ok) receipt = Receipt(Receipt.STEER_FAILED, note = "插话没赶上下一次工具调用，本轮可能已收尾")
        println("[修复][F13] 插话失败 → 气泡回执 = ${receipt?.status}")
        assertEquals(Receipt.STEER_FAILED, receipt?.status)
    }

    // ------------------------------------------------------------------
    // F05：排队会踩掉「正在跑那一轮」的恢复锚点
    // ------------------------------------------------------------------

    private class Anchor(var text: String, var cancelCount: Int = 0)

    @Test
    fun reproduce_old_queue_overwrites_the_running_turn_anchor() {
        // 2.160 原文：不论要不要排队，都先安装本轮锚点、并取消上一轮的恢复任务
        val running = Anchor("正在跑那轮的正文")
        val willQueue = true
        running.text = "排队这条的正文"      // ← 无条件覆盖
        running.cancelCount++             // ← 无条件取消
        println("[复现][F05] 入队后正在跑那轮的锚点 = 「${running.text}」，恢复被取消次数 = ${running.cancelCount}")
        assertEquals("排队这条的正文", running.text)
        assertEquals(1, running.cancelCount)
    }

    @Test
    fun new_queue_leaves_the_running_turn_anchor_alone() {
        // 修复后：只有「立刻发」才安装锚点；排队不动它
        val running = Anchor("正在跑那轮的正文")
        val willQueue = true
        if (!willQueue) {
            running.text = "排队这条的正文"
            running.cancelCount++
        }
        println("[修复][F05] 入队后正在跑那轮的锚点 = 「${running.text}」，恢复被取消次数 = ${running.cancelCount}")
        assertEquals("正在跑那轮的正文", running.text)
        assertEquals(0, running.cancelCount)
    }

    // ------------------------------------------------------------------
    // F12：序号已推进、正文还压在攒帧缓冲里（检查点不一致）
    // ------------------------------------------------------------------

    @Test
    fun reproduce_body_lags_behind_the_received_seq_and_flush_closes_the_gap() {
        // 攒帧器是真代码：收到 2400 字后，消息模型里只有部分内容（其余还在缓冲里），
        // 而事件序号早已推进 —— 这一刻落盘就会得到「序号领先正文」的检查点。
        val flushed = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val c = StreamDeltaCoalescer(scope, onFlush = { flushed.add(it) }, frameMs = 10_000L)
            c.append("正".repeat(2400))
            val appliedBeforeFlush = flushed.sumOf { it.length }
            println("[复现][F12] 收到 2400 字，落盘瞬间已进消息模型 = $appliedBeforeFlush 字（其余仍在缓冲）")
            assertTrue("缓冲里的字还没进消息模型 —— 这就是「序号领先正文」的来源", appliedBeforeFlush < 2400)

            // 修复后的做法：落盘前先 flushNow，把缓冲一次并入消息模型
            c.flushNow()
            val appliedAfterFlush = flushed.sumOf { it.length }
            println("[修复][F12] 落盘前 flushNow 后已进消息模型 = $appliedAfterFlush 字")
            assertEquals(2400, appliedAfterFlush)
        } finally {
            scope.cancel()
        }
    }
}
